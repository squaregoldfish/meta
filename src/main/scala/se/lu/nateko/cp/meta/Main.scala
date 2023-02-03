package se.lu.nateko.cp.meta

import scala.concurrent.Await
import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt

import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.stream.Materializer
import se.lu.nateko.cp.meta.icos.MetaFlow
import se.lu.nateko.cp.meta.routes.MainRoute
import se.lu.nateko.cp.meta.services.sparql.magic.IndexHandler
import se.lu.nateko.cp.meta.services.sparql.magic.CpIndex.IndexData
import se.lu.nateko.cp.meta.services.citation.CitationClient
import se.lu.nateko.cp.meta.core.data.Envri
import se.lu.nateko.cp.meta.icos.EtcMetaSource.given_Envri

object Main extends App with CpmetaJsonProtocol{

	given system: ActorSystem = ActorSystem("cpmeta", config = ConfigLoader.appConfig)
	import system.log //force log initialization to avoid deadlocks at startup
	import system.dispatcher

	val config: CpmetaConfig = ConfigLoader.default
	val metaFactory = new MetaDbFactory()

	val optIndexDataFut: Future[Option[IndexData]] =
		import config.{rdfStorage => conf}
		val recreateIndex: Boolean = conf.recreateAtStartup || conf.recreateCpIndexAtStartup
		if(recreateIndex) IndexHandler.dropStorage()
		if(recreateIndex || conf.disableCpIndex) Future.successful(None)
		else {
			log.info("Trying to restore SPARQL magic index...")
			val indexDataFut = IndexHandler.restore()
			indexDataFut.foreach{idx =>
				log.info(s"SPARQL magic index restored successfully (${idx.objs.length} objects)")
				IndexHandler.dropStorage()
			}
			indexDataFut.map(Option(_)).recover{
				case err =>
					log.warning(s"Failed to restore SPARQL index (${err.getMessage})")
					None
			}
		}

	val startup = for(
		citCache <- CitationClient.readCitCache(log);
		doiCache <- CitationClient.readDoiCache(log);
		db <- metaFactory(citCache, doiCache, config);
		metaflow <- Future.fromTry(MetaFlow.initiate(db, config));
		idxOpt <- optIndexDataFut;
		_ = db.store.initSparqlMagicIndex(idxOpt);
		route = MainRoute(db, metaflow, config);
		binding <- Http().newServerAt(config.httpBindInterface, config.port).bind(route)
	) yield {
		sys.addShutdownHook{
			metaflow.cancel()
			try
				Await.result(binding.unbind(), 10.seconds)
			finally
				db.close()
				println("Metadata db has been shut down")

			println("meta service shutdown successful")
		}
		system.log.info(binding.toString)
	}

	startup.failed.foreach{err =>
		system.log.error(err, "Could not start meta service")
		system.terminate()
	}
}
