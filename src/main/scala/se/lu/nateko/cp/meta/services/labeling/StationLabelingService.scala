package se.lu.nateko.cp.meta.services.labeling

import akka.event.LoggingAdapter
import org.eclipse.rdf4j.model.IRI
import org.eclipse.rdf4j.model.ValueFactory
import se.lu.nateko.cp.cpauth.core.UserId
import se.lu.nateko.cp.meta.LabelingServiceConfig
import se.lu.nateko.cp.meta.instanceserver.InstanceServer
import se.lu.nateko.cp.meta.instanceserver.LoggingInstanceServer
import se.lu.nateko.cp.meta.instanceserver.TriplestoreConnection
import se.lu.nateko.cp.meta.onto.InstOnto
import se.lu.nateko.cp.meta.onto.Onto
import se.lu.nateko.cp.meta.services.CpmetaVocab
import se.lu.nateko.cp.meta.services.FileStorageService
import se.lu.nateko.cp.meta.services.UnauthorizedStationUpdateException
import se.lu.nateko.cp.meta.services.MetadataException


class StationLabelingService(
	instanceServers: Map[String, InstanceServer],
	protected val onto: Onto,
	protected val fileStorage: FileStorageService,
	protected val metaVocab: CpmetaVocab,
	protected val config: LabelingServiceConfig,
	protected val log: LoggingAdapter
) extends UserInfoService with StationInfoService with FileService with LifecycleService:
	import LabelingDb.{LblAppConn, ProvConn}
	import TriplestoreConnection.{getStringValues, getUriValues, getOptionalString, getSingleString}

	protected val db = LabelingDb(
		provServer = instanceServers(config.provisionalInfoInstanceServerId),
		lblServer = instanceServers(config.instanceServerId),
		icosServer = instanceServers(config.icosMetaInstanceServerId)
	)
	protected given factory: ValueFactory = metaVocab.factory
	protected val vocab = new StationsVocab(factory)
	protected val protectedPredicates = Set(vocab.hasAssociatedFile, vocab.hasApplicationStatus)

	protected def assertThatWriteIsAuthorized(stationUri: IRI, uploader: UserId)(using ProvConn): Unit =
		if(!userIsPiOrDeputy(uploader, stationUri)) throw new UnauthorizedStationUpdateException(
			"Only PIs are authorized to update station's info"
		)

	protected def userIsPiOrDeputy(user: UserId, station: IRI)(using ProvConn): Boolean = {
		getStationPiOrDeputyEmails(station).contains(user.email.toLowerCase)
	}

	protected def getPiEmails(piUri: IRI)(using ProvConn): Seq[String] =
		getStringValues(piUri, vocab.hasEmail).map(_.toLowerCase)

	protected def lookupStationClass(stationUri: IRI)(using ProvConn): Option[IRI] =
		InstOnto.getSingleTypeIfAny(stationUri)

	protected def lookupLblStationId(stationUri: IRI)(using LblAppConn): Option[String] =
		getOptionalString(stationUri, vocab.hasShortName).getOrThrow(new MetadataException(_))

	protected def lookupProvStationId(stationUri: IRI)(using ProvConn): String =
		getSingleString(stationUri, vocab.hasShortName).getOrThrow(new MetadataException(_))

	protected def getStationPiOrDeputyEmails(stationUri: IRI)(using ProvConn): Seq[String] = (
		getUriValues(stationUri, vocab.hasPi) ++
		getUriValues(stationUri, vocab.hasDeputyPi)
	).flatMap(getPiEmails)

end StationLabelingService
