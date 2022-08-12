package se.lu.nateko.cp.meta.services.citation

import java.util.concurrent.TimeoutException

import scala.collection.concurrent.TrieMap
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.util.Failure
import scala.util.Success
import scala.util.Try

import akka.Done
import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.HttpRequest
import akka.http.scaladsl.unmarshalling.Unmarshal
import akka.stream.Materializer
import se.lu.nateko.cp.meta.utils.async.{timeLimit, errorLite}
import se.lu.nateko.cp.meta.CitationConfig
import java.nio.file.Paths
import java.nio.file.Files
import java.nio.file.StandardOpenOption
import akka.event.LoggingAdapter
import akka.http.scaladsl.settings.ConnectionPoolSettings
import se.lu.nateko.cp.doi.Doi

enum CitationStyle:
	case HTML, bibtex, ris, TEXT

trait PlainDoiCiter{
	import CitationStyle.*
	def getCitationEager(doi: Doi, style: CitationStyle): Option[Try[String]]
}

trait CitationClient extends PlainDoiCiter{
	protected def cache: CitationClient.CitationCache = TrieMap.empty
	def getCitation(doi: Doi, citationStyle: CitationStyle): Future[String]
	//not waiting for HTTP; only returns string if the result previously cached
	def getCitationEager(doi: Doi, citationStyle: CitationStyle): Option[Try[String]] = getCitation(doi, citationStyle).value

	def dropCache(doi: Doi): Unit = CitationStyle.values.foreach(style => cache.remove(doi -> style))

}

class CitationClientImpl (
	knownDois: List[Doi], config: CitationConfig, initCache: CitationClient.CitationCache
)(using system: ActorSystem, mat: Materializer) extends CitationClient{
	import CitationStyle.*
	import CitationClient.Key

	override protected val cache = initCache

	private val http = Http()
	import system.{dispatcher, scheduler, log}
	import CitationStyle.*

	if(config.eagerWarmUp) scheduler.scheduleOnce(35.seconds)(warmUpCache())

	def getCitation(doi: Doi, citationStyle: CitationStyle): Future[String] = {
		val key = doi -> citationStyle
		timeLimit(fetchIfNeeded(key), config.timeoutSec.seconds, scheduler).recoverWith{
			case _: TimeoutException => Future.failed(
				new Exception("Citation formatting service timed out")
			)
		}
	}


	private def fetchIfNeeded(key: Key): Future[String] = {

		def recache(): Future[String] = {
			val res = fetchCitation(key)
			cache += key -> res
			res
		}

		cache.get(key).fold(recache()){fut =>
			fut.value match{
				case Some(Failure(_)) =>
					//if this citation is a completed failure at the moment
					recache()
				case _ => fut
			}
		}
	}

	private def warmUpCache(): Unit = {

		def warmUp(dois: List[Doi]): Future[Done] = dois match {
			case Nil => Future.successful(Done)
			case head :: tail =>
				Future.sequence(
					CitationStyle.values.toSeq.map{citStyle => fetchIfNeeded(head -> citStyle)}
				).flatMap(_ => warmUp(tail))
		}

		warmUp(knownDois).failed.foreach{_ =>
			scheduler.scheduleOnce(1.hours)(warmUpCache())
		}
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

}

object CitationClient{
	import spray.json.*
	import scala.concurrent.ExecutionContext.Implicits.global
	type Key = (Doi, CitationStyle)
	type CitationCache = TrieMap[Key, Future[String]]
	opaque type CitationDump = JsValue
	val cacheDumpFile = Paths.get("./citationsCacheDump.json")

	def dumpCache(client: CitationClient): CitationDump = {
		val arrays = client.cache.iterator.flatMap{case ((doi, style), fut) =>
			fut.value.flatMap(_.toOption).map{cit =>
				val strs = Vector(doi.toString, style.toString, cit)
				JsArray(strs.map(JsString.apply))
			}
		}.toVector
		JsArray(arrays)
	}

	def reconstructCache(dump: CitationDump): CitationCache = {
		val tuples = dump match{
			case JsArray(arrs) => arrs.collect{
				case JsArray(cells) =>
					val toParse = cells.collect{case JsString(s) => s}
					assert(toParse.length == 3, "Citation dump had en entry with a wrong number of calues")
					val doi = Doi.parse(toParse(0)).get
					val style = CitationStyle.valueOf(toParse(1))
					val fut = Future.successful(toParse(2))
					doi -> style -> fut
			}
			case _ => throw Exception("Citation dump was not a JSON array")
		}
		TrieMap.apply(tuples*)
	}

	def saveCache(client: CitationClient): Future[Done] = Future{
		import StandardOpenOption.*
		Files.writeString(cacheDumpFile, dumpCache(client).prettyPrint, WRITE, CREATE, TRUNCATE_EXISTING)
		Done
	}

	def readCache(log: LoggingAdapter): Future[CitationCache] = Future{
		val dump: CitationDump = Files.readString(cacheDumpFile).parseJson
		reconstructCache(dump)
	}.recover{
		case err: Throwable =>
			log.error(err, "Could not read citation string cache dump")
			TrieMap.empty
	}
}
