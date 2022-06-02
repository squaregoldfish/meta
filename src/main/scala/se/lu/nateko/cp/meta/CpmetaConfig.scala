package se.lu.nateko.cp.meta

import java.net.URI
import com.typesafe.config.ConfigRenderOptions
import com.typesafe.config.ConfigFactory
import com.typesafe.config.Config
import spray.json.*
import se.lu.nateko.cp.meta.persistence.postgres.DbServer
import se.lu.nateko.cp.meta.persistence.postgres.DbCredentials
import se.lu.nateko.cp.cpauth.core.PublicAuthConfig
import se.lu.nateko.cp.meta.core.MetaCoreConfig
import se.lu.nateko.cp.meta.core.data.Envri

case class RdflogConfig(server: DbServer, credentials: DbCredentials)

case class IngestionConfig(
	ingesterId: String,
	waitFor: Option[Seq[String]],
	ingestAtStartup: Option[Boolean]
)

case class InstanceServerConfig(
	writeContexts: Seq[URI],
	logName: Option[String],
	skipLogIngestionAtStart: Option[Boolean],
	readContexts: Option[Seq[URI]],
	ingestion: Option[IngestionConfig]
)

case class DataObjectInstServerDefinition(label: String, format: URI)

case class DataObjectInstServersConfig(
	commonReadContexts: Seq[URI],
	uriPrefix: URI,
	definitions: Seq[DataObjectInstServerDefinition]
)

case class InstanceServersConfig(
	specific: Map[String, InstanceServerConfig],
	forDataObjects: Map[Envri, DataObjectInstServersConfig],
	cpMetaInstanceServerId: String,
	icosMetaInstanceServerId: String,
	otcMetaInstanceServerId: String
)

case class SchemaOntologyConfig(ontoId: Option[String], owlResource: String)

case class InstOntoServerConfig(
	serviceTitle: String,
	ontoId: String,
	instanceServerId: String,
	authorizedUserIds: Seq[String]
)

case class OntoConfig(
	ontologies: Seq[SchemaOntologyConfig],
	instOntoServers: Map[String, InstOntoServerConfig]
)

case class DataSubmitterConfig(
	authorizedUserIds: Seq[String],
	producingOrganizationClass: Option[URI],
	producingOrganization: Option[URI],
	submittingOrganization: URI,
	authorizedThemes: Option[Seq[URI]],
	authorizedProjects: Option[Seq[URI]]
)

case class SubmittersConfig(submitters: Map[Envri, Map[String, DataSubmitterConfig]])

case class EtcConfig(
	eddyCovarObjSpecId: String,
	storageObjSpecId: String,
	bioMeteoObjSpecId: String,
	saheatObjSpecId: String,
	metaService: URI,
	ingestFileMetaAtStart: Boolean
)

case class UploadServiceConfig(
	metaServers: Map[Envri, String],
	collectionServers: Map[Envri, String],
	documentServers: Map[Envri, String],
	handle: HandleNetClientConfig,
	etc: EtcConfig
)

case class EmailConfig(
	mailSendingActive: Boolean,
	smtpServer: String,
	username: String,
	password: String,
	fromAddress: String,
	logBccAddress: Option[String]
)

case class LabelingServiceConfig(
	instanceServerId: String,
	provisionalInfoInstanceServerId: String,
	icosMetaInstanceServerId: String,
	tcUserIds: Map[URI, Seq[String]],
	dgUserId: String,
	riComEmail: String,
	calLabEmails: Seq[String],
	mailing: EmailConfig,
	ontoId: String
)

case class HandleNetClientConfig(
	prefix: Map[Envri, String],
	baseUrl: String,
	serverCertPemFilePath: Option[String],
	clientCertPemFilePath: String,
	clientPrivKeyPKCS8FilePath: String,
	dryRun: Boolean
)

case class SparqlServerConfig(
	maxQueryRuntimeSec: Int,
	quotaPerMinute: Int,//in seconds
	quotaPerHour: Int,  //in seconds
	maxParallelQueries: Int,
	maxQueryQueue: Int,
	banLength: Int, //in minutes
	maxCacheableQuerySize: Int, //in bytes
	adminUsers: Seq[String]
)

case class RdfStorageConfig(path: String, recreateAtStartup: Boolean, indices: String, disableCpIndex: Boolean)

case class CitationConfig(style: String, eagerWarmUp: Boolean, timeoutSec: Int)

case class RestheartConfig(baseUri: String, dbNames: Map[Envri, String]) {
	def dbName(implicit envri: Envri): String = dbNames(envri)
}

case class StatsClientConfig(downloadsUri: String, previews: RestheartConfig)

case class DoiClientConfig(
	symbol: String,
	password: String,
	restEndpoint: URI,
	prefix: String
)

case class CpmetaConfig(
	port: Int,
	dataUploadService: UploadServiceConfig,
	stationLabelingService: LabelingServiceConfig,
	instanceServers: InstanceServersConfig,
	rdfLog: RdflogConfig,
	fileStoragePath: String,
	rdfStorage: RdfStorageConfig,
	onto: OntoConfig,
	auth: Map[Envri, PublicAuthConfig],
	core: MetaCoreConfig,
	sparql: SparqlServerConfig,
	citations: CitationConfig,
	statsClient: StatsClientConfig,
	doi: Map[Envri, DoiClientConfig]
)

object ConfigLoader extends CpmetaJsonProtocol{

	import MetaCoreConfig.given
	import DefaultJsonProtocol.*

	given RootJsonFormat[IngestionConfig] = jsonFormat3(IngestionConfig.apply)
	given RootJsonFormat[InstanceServerConfig] = jsonFormat5(InstanceServerConfig.apply)
	given RootJsonFormat[DataObjectInstServerDefinition] = jsonFormat2(DataObjectInstServerDefinition.apply)
	given RootJsonFormat[DataObjectInstServersConfig] = jsonFormat3(DataObjectInstServersConfig.apply)
	given RootJsonFormat[InstanceServersConfig] = jsonFormat5(InstanceServersConfig.apply)
	given RootJsonFormat[DbServer] = jsonFormat2(DbServer.apply)
	given RootJsonFormat[DbCredentials] = jsonFormat3(DbCredentials.apply)
	given RootJsonFormat[RdflogConfig] = jsonFormat2(RdflogConfig.apply)
	given RootJsonFormat[PublicAuthConfig] = jsonFormat4(PublicAuthConfig.apply)
	given RootJsonFormat[SchemaOntologyConfig] = jsonFormat2(SchemaOntologyConfig.apply)
	given RootJsonFormat[InstOntoServerConfig] = jsonFormat4(InstOntoServerConfig.apply)
	given RootJsonFormat[OntoConfig] = jsonFormat2(OntoConfig.apply)
	given RootJsonFormat[DataSubmitterConfig] = jsonFormat6(DataSubmitterConfig.apply)
	given RootJsonFormat[SubmittersConfig] = jsonFormat1(SubmittersConfig.apply)
	given RootJsonFormat[EtcConfig] = jsonFormat6(EtcConfig.apply)
	given RootJsonFormat[HandleNetClientConfig] = jsonFormat6(HandleNetClientConfig.apply)

	given RootJsonFormat[UploadServiceConfig] = jsonFormat5(UploadServiceConfig.apply)
	given RootJsonFormat[EmailConfig] = jsonFormat6(EmailConfig.apply)
	given RootJsonFormat[LabelingServiceConfig] = jsonFormat9(LabelingServiceConfig.apply)
	given RootJsonFormat[SparqlServerConfig] = jsonFormat8(SparqlServerConfig.apply)
	given RootJsonFormat[RdfStorageConfig] = jsonFormat4(RdfStorageConfig.apply)
	given RootJsonFormat[CitationConfig] = jsonFormat3(CitationConfig.apply)
	given RootJsonFormat[RestheartConfig] = jsonFormat2(RestheartConfig.apply)
	given RootJsonFormat[StatsClientConfig] = jsonFormat2(StatsClientConfig.apply)
	given RootJsonFormat[DoiClientConfig] = jsonFormat4(DoiClientConfig.apply)

	given RootJsonFormat[CpmetaConfig] = jsonFormat14(CpmetaConfig.apply)

	val appConfig: Config = {
		val confFile = new java.io.File("application.conf").getAbsoluteFile
		val default = ConfigFactory.load
		if(confFile.exists)
			ConfigFactory.parseFile(confFile).withFallback(default)
		else default
	}

	private val renderOpts = ConfigRenderOptions.concise.setJson(true)

	val default: CpmetaConfig = {
		val confJson: String = appConfig.getValue("cpmeta").render(renderOpts)

		confJson.parseJson.convertTo[CpmetaConfig]
	}

	def submittersConfig: SubmittersConfig = {
		val confFile = new java.io.File("submitters.conf").getAbsoluteFile

		if(confFile.exists) {
			val confJson: String = ConfigFactory.parseFile(confFile).root.render(renderOpts)
			confJson.parseJson.convertTo[SubmittersConfig]
		} else {
			SubmittersConfig(Envri.values.iterator.map(_ -> Map.empty[String, DataSubmitterConfig]).toMap)
		}
	}

}
