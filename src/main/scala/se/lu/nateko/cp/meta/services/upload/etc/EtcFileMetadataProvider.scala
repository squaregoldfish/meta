package se.lu.nateko.cp.meta.services.upload.etc

import scala.concurrent.duration._
import scala.util.Failure
import scala.util.Success

import akka.actor.ActorSystem
import akka.stream.Materializer
import se.lu.nateko.cp.meta.core.etcupload.DataType
import se.lu.nateko.cp.meta.core.etcupload.StationId
import se.lu.nateko.cp.meta.ingestion.badm.EtcEntriesFetcher
import se.lu.nateko.cp.meta.ingestion.badm.Parser
import akka.http.scaladsl.Http
import akka.http.scaladsl.model.HttpRequest
import se.lu.nateko.cp.meta.EtcConfig
import se.lu.nateko.cp.meta.icos.EtcMetaSource

class EtcFileMetadataProvider(conf: EtcConfig)(implicit system: ActorSystem) extends EtcFileMetadataStore{

	import system.dispatcher

	private[this] val metaSrc = new EtcMetaSource(conf)
	private[this] var inner: Option[EtcFileMetadataStore] = None
	private[this] var retryCount: Int = 0

	def lookupFile(key: EtcFileMetaKey) = inner.flatMap(_.lookupFile(key))

	def getUtcOffset(station: StationId) =
		inner.flatMap(_.getUtcOffset(station))

	private val fetchInterval = 5.hours
	private val initDelay = if(conf.ingestFileMetaAtStart) Duration.Zero else fetchInterval
	system.scheduler.scheduleWithFixedDelay(initDelay, fetchInterval)(() => fetchFromEtc())

	private def fetchFromEtc(): Unit = metaSrc.getFileMeta.onComplete{

			case Success(storeV) =>
				storeV.errors.foreach{err =>
					system.log.warning("ETC logger/file metadata problem: " + err)
				}
				storeV.result.fold{
					system.log.error("ETC logger/file metadata was not (re-)initialized")
				}{store =>
					inner = Some(store)
					system.log.info(s"Fetched ETC logger/file metadata from ${conf.metaService}")
					retryCount = 0
				}

			case Failure(err) =>
				system.log.error(err, "Problem fetching/parsing ETC logger/file metadata")
				if(retryCount < 3){
					system.scheduler.scheduleOnce(10.minutes){
						retryCount += 1
						fetchFromEtc()
					}
				}
		}
}

class TsvBasedEtcFileMetadataStore(
	utcOffsets: Map[StationId, Int], fileInfo: Map[EtcFileMetaKey, EtcFileMeta]
) extends EtcFileMetadataStore{

	override def lookupFile(key: EtcFileMetaKey): Option[EtcFileMeta] = fileInfo.get(key)

	override def getUtcOffset(station: StationId): Option[Int] = utcOffsets.get(station)
		.orElse(EtcFileMetadataStore.fallbackUtcOffset(station))

}
