package se.lu.nateko.cp.meta.services.citation

import akka.Done
import akka.actor.ActorSystem
import akka.event.LoggingAdapter
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.HttpRequest
import akka.http.scaladsl.settings.ConnectionPoolSettings
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.Materializer
import se.lu.nateko.cp.doi.Doi
import se.lu.nateko.cp.doi.DoiMeta
import se.lu.nateko.cp.meta.CitationConfig
import se.lu.nateko.cp.meta.CpmetaConfig
import se.lu.nateko.cp.meta.core.data.Envri
import se.lu.nateko.cp.meta.services.upload.DoiClientFactory
import se.lu.nateko.cp.meta.utils.async.errorLite
import se.lu.nateko.cp.meta.utils.async.timeLimit
import spray.json.RootJsonFormat
import se.lu.nateko.cp.doi.core.JsonSupport.{given RootJsonFormat[DoiMeta]}
import se.lu.nateko.cp.doi.core.JsonSupport.{given RootJsonFormat[Doi]}

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.StandardOpenOption
import java.util.concurrent.TimeoutException
import scala.collection.concurrent.TrieMap
import scala.concurrent.Future
import scala.concurrent._
import scala.concurrent.duration.DurationInt
import scala.util.Failure
import scala.util.Success
import scala.util.Try

import CitationClient.{*, given}
import scala.util.control.NoStackTrace
import se.lu.nateko.cp.meta.utils.Validated
import se.lu.nateko.cp.meta.utils.Mergeable


enum CitationStyle:
	case HTML, bibtex, ris, TEXT

trait PlainDoiCiter:
	def getCitationEager(doi: Doi, style: CitationStyle): Option[Try[String]]
	def getDoiEager(doi: Doi)(using Envri): Option[Try[DoiMeta]]

trait CitationClient extends PlainDoiCiter:
	protected def citCache: CitationCache = TrieMap.empty
	protected def doiCache: DoiCache = TrieMap.empty

	def getCitation(doi: Doi, citationStyle: CitationStyle): Future[String]
	def getDoiMeta(doi: Doi)(using Envri): Future[DoiMeta]

	//not waiting for HTTP; only returns string if the result previously citCached
	def getCitationEager(doi: Doi, citationStyle: CitationStyle): Option[Try[String]] = getCitation(doi, citationStyle).value
	def getDoiEager(doi: Doi)(using Envri): Option[Try[DoiMeta]] = getDoiMeta(doi).value

	def dropCache(doi: Doi): Unit =
		CitationStyle.values.foreach(style => citCache.remove(doi -> style))
		doiCache.remove(doi)


class CitationClientImpl (
	knownDois: List[Doi], config: CitationConfig, initCitCache: CitationCache, initDoiCache: DoiCache
)(using system: ActorSystem, mat: Materializer) extends CitationClient:
	import system.{dispatcher, scheduler, log}

	override protected val citCache = initCitCache
	override protected val doiCache = initDoiCache

	if(config.eagerWarmUp) scheduler.scheduleOnce(35.seconds)(warmUpCache())

	private val http = Http()
	private val doiClientFactory = DoiClientFactory(config.doi)

	def getCitation(doi: Doi, citationStyle: CitationStyle): Future[String] =
		val key = doi -> citationStyle
		withTimeout(fetchIfNeeded(key, citCache, fetchCitation), "Citation formatting")

	def getDoiMeta(doi: Doi)(using Envri): Future[DoiMeta] =
		withTimeout(fetchIfNeeded(doi, doiCache, fetchDoiMeta), "DOI metadata")

	private def withTimeout[T](fut: Future[T], serviceName: String): Future[T] =
		timeLimit(fut, config.timeoutSec.seconds, scheduler).recoverWith{
			case _: TimeoutException => Future.failed(
				new Exception(serviceName + " service timed out") with NoStackTrace
			)
		}

	private def fetchIfNeeded[K, V](key: K, cache: TrieMap[K, Future[V]], fetchValue: K => Future[V]): Future[V] =

		def recache(): Future[V] = {
			val res = fetchValue(key)
			cache += key -> res
			res
		}

		cache.get(key).fold(recache()){fut =>
			fut.value match
				case Some(Failure(_)) =>
					//if this citation is a completed failure at the moment
					recache()
					fut
				case _ => fut
		}

	private def warmUpCache(): Unit =
		val MaxErrors = 5
		def warmupOne(doi: Doi): Future[Validated[Done]] =
			log.info(s"Warming up citation cache for DOI $doi")

			val allFuts = CitationStyle.values.map{ citStyle =>
				fetchIfNeeded(doi -> citStyle, citCache, fetchCitation).transform{
					case Success(_) => Success(Validated.ok(Done))
					case Failure(err) => Success(Validated.error(err.getMessage))
				}
			}
			Future.reduceLeft(allFuts.toIndexedSeq)(Validated.merge)

		def warmUp(dois: List[Doi], soFar: Validated[Done]): Future[Validated[Done]] =
			if soFar.errors.length > MaxErrors then
				val msg = s"Got more than $MaxErrors errors while warming up DOI citation cache, cancelling for now"
				Future.successful(soFar.withExtraError(msg))
			else dois match
				case Nil => Future.successful(soFar)
				case head :: tail =>
					warmupOne(head).flatMap{ first =>
						warmUp(tail, Validated.merge(soFar, first))
					}

		warmUp(knownDois, Validated.ok(Done)).onComplete{
			case Success(v) if v.errors.nonEmpty =>
				log.warning("DOI citation cache warmup encountered the following errors (will retry later):\n" +
					v.errors.mkString("\n"))
				scheduler.scheduleOnce(1.hours)(warmUpCache())
			case Success(v) =>
					log.info(s"DOI citation cache warmup success but encountered the following errors ${v.errors.mkString(", ")}")
			case Failure(exception) => //this has to be success, so doing nothing
		}

	private def fetchCitation(key: Key): Future[String] =
		val (doi, style) = key
		http.singleRequest(
			request = HttpRequest(
				uri = style match {
					case CitationStyle.bibtex => s"https://api.datacite.org/dois/application/x-bibtex/${doi.prefix}/${doi.suffix}"
					case CitationStyle.ris    => s"https://api.datacite.org/dois/application/x-research-info-systems/${doi.prefix}/${doi.suffix}"
					case CitationStyle.HTML   => s"https://api.datacite.org/dois/text/x-bibliography/${doi.prefix}/${doi.suffix}?style=${config.style}"
					case CitationStyle.TEXT   => s"https://citation.crosscite.org/format?doi=${doi.prefix}%2F${doi.suffix}&style=${config.style}&lang=en-US"
				}
			),
			settings = ConnectionPoolSettings(system).withMaxConnections(6).withMaxOpenRequests(10000)
		).flatMap{resp =>
			Unmarshal(resp).to[String].flatMap{payload =>
				if(resp.status.isSuccess) Future.successful(payload)
				//the payload is the error message/page from the citation service
				else errorLite(resp.status.defaultMessage + " " + payload)
			}
		}
		.flatMap{citation =>
			if(citation.trim.isEmpty)
				errorLite("got empty citation text")
			else
				Future.successful(citation.trim)
		}
		.recoverWith{
			case err => errorLite(s"Error fetching citation string for ${key._1} from DataCite: ${err.getMessage}")
		}
		.andThen{
			case Failure(err) => log.warning("Citation fetching error: " + err.getMessage)
			case Success(cit) => log.debug(s"Fetched $cit")
		}

	private def fetchDoiMeta(doi: Doi)(using Envri): Future[DoiMeta] =
		doiClientFactory.getClient.getMetadata(doi).flatMap{
			case None => Future.failed(new Exception(s"No metadata found for DOI $doi") with NoStackTrace)
			case Some(value) => Future.successful(value)
		}

end CitationClientImpl

object CitationClient:
	import spray.json.*
	import scala.concurrent.ExecutionContext.Implicits.global
	type Key = (Doi, CitationStyle)
	type CitationCache = TrieMap[Key, Future[String]]
	type DoiCache = TrieMap[Doi, Future[DoiMeta]]

	val citCacheDumpFile = Paths.get("./citationsCacheDump.json")
	val doiCacheDumpFile = Paths.get("./doiMetaCacheDump.json")

	def readCitCache(log: LoggingAdapter): Future[CitationCache] =
		readCache(log, citCacheDumpFile){cells =>
			val toParse = cells.collect{case JsString(s) => s}
			assert(toParse.length == 3, "Citation dump had an entry with a wrong number of values")
			val doi = Doi.parse(toParse(0)).get
			val style = CitationStyle.valueOf(toParse(1))
			val cit = toParse(2)
			doi -> style -> cit
		}

	def readDoiCache(log: LoggingAdapter): Future[DoiCache] =
		readCache(log, doiCacheDumpFile){cells =>
			assert(cells.length == 2, "Doi dump had an entry with a wrong number of values")
			val doi = cells(0).convertTo[Doi]
			val doiMeta = cells(1).convertTo[DoiMeta]
			doi -> doiMeta
		}

	def writeCitCache(client: CitationClient): Future[Done] =
		writeCache(client.citCache, citCacheDumpFile){case ((doi, style), cit) =>
			JsArray(doi.toJson, JsString(style.toString), JsString(cit))
		}

	def writeDoiCache(client: CitationClient): Future[Done] =
		writeCache(client.doiCache, doiCacheDumpFile)(
			(doi, doiMeta) => JsArray(doi.toJson, doiMeta.toJson)
		)

	private def readCache[K, V](
		log: LoggingAdapter, file: Path
	)(parser: Vector[JsValue] => (K, V)): Future[TrieMap[K, Future[V]]] =
		Future{
			val dump = Files.readString(file).parseJson
			val tuples = dump match
				case JsArray(arrs) => arrs.collect{
					case JsArray(cells) =>
						val (k, v) = parser(cells)
						k -> Future.successful(v)
				}
				case _ => throw Exception("Citation/DOI dump was not a JSON array")
			TrieMap.apply(tuples*)
		}.recover{
			case err: Throwable =>
				log.error(err, "Could not read cache dump")
				TrieMap.empty
		}

	private def writeCache[K, V](
		cache: TrieMap[K, Future[V]], toFile: Path
	)(serializer: (K, V) => JsArray): Future[Done] = Future{
		val arrays = cache.iterator.flatMap{
			case (key, fut) =>
				fut.value.flatMap(_.toOption).map(serializer(key, _))
		}.toVector
		val js = JsArray(arrays).prettyPrint
		import StandardOpenOption.*
		Files.writeString(toFile, js, WRITE, CREATE, TRUNCATE_EXISTING)
		Done
	}

	given Mergeable[Done] with
		def merge(d1: Done, d2: Done) = Done

end CitationClient
