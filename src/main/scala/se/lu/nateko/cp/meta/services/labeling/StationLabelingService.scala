package se.lu.nateko.cp.meta.services.labeling

import org.openrdf.model.Literal
import org.openrdf.model.URI
import org.openrdf.model.vocabulary.RDF

import se.lu.nateko.cp.cpauth.core.UserInfo
import se.lu.nateko.cp.meta.ingestion.StationStructuringVocab
import se.lu.nateko.cp.meta.instanceserver.InstanceServer
import se.lu.nateko.cp.meta.LabelingServiceConfig
import se.lu.nateko.cp.meta.onto.Onto
import se.lu.nateko.cp.meta.services.FileStorageService
import se.lu.nateko.cp.meta.services.UnauthorizedStationUpdateException


class StationLabelingService(
	protected val server: InstanceServer,
	protected val provisionalInfoServer: InstanceServer,
	protected val onto: Onto,
	protected val fileService: FileStorageService,
	protected val config: LabelingServiceConfig
) extends UserInfoService with StationInfoService with FileService with LifecycleService {

	private val (_, vocab) = getFactoryAndVocab(provisionalInfoServer)

	protected def getFactoryAndVocab(instServer: InstanceServer) = {
		val factory = instServer.factory
		val vocab = new StationStructuringVocab(factory)
		(factory, vocab)
	}

	protected def assertThatWriteIsAuthorized(stationUri: URI, uploader: UserInfo): Unit = {
		val piEmails = getPis(stationUri).flatMap(getPiEmails).toIndexedSeq

		if(!piEmails.contains(uploader.mail.toLowerCase)) throw new UnauthorizedStationUpdateException(
			"Only the following user(s) is(are) authorized to update this station's info: " +
				piEmails.mkString(" and ")
		)
	}

	protected def getPiEmails(piUri: URI) = provisionalInfoServer.getValues(piUri, vocab.hasEmail)
		.collect{ case mail: Literal => mail.getLabel.toLowerCase }

	protected def lookupStationClass(stationUri: URI): Option[URI] = provisionalInfoServer
		.getValues(stationUri, RDF.TYPE)
		.headOption
		.collect{case uri: URI => uri}

	private def getPis(stationUri: URI) = provisionalInfoServer.getValues(stationUri, vocab.hasPi)
		.collect{ case pi: URI => pi }
}
