package se.lu.nateko.cp.meta.metaflow.icos

import scala.util.Success
import scala.util.Try

import akka.actor.ActorSystem
import akka.stream.Materializer
import akka.stream.scaladsl.Sink
import se.lu.nateko.cp.meta.EtcConfig
import se.lu.nateko.cp.meta.IcosMetaFlowConfig
import se.lu.nateko.cp.meta.MetaDb
import se.lu.nateko.cp.meta.instanceserver.WriteNotifyingInstanceServer
import se.lu.nateko.cp.meta.metaflow.*
import se.lu.nateko.cp.meta.services.CpVocab
import se.lu.nateko.cp.meta.services.CpmetaVocab
import se.lu.nateko.cp.meta.services.Rdf4jSparqlRunner
import se.lu.nateko.cp.cpauth.core.UserId
import eu.icoscp.envri.Envri
import se.lu.nateko.cp.meta.core.data.EnvriConfigs


object IcosMetaFlow:

	def init(
		db: MetaDb, etcConf: EtcConfig, flowConf: IcosMetaFlowConfig
	)(using Materializer, ActorSystem, EnvriConfigs): MetaFlow =

		given Envri = Envri.ICOS
		val log = summon[ActorSystem].log

		val otcServer = db.instanceServers(flowConf.otcMetaInstanceServerId) match
			case wnis: WriteNotifyingInstanceServer => wnis
			case _ => throw Exception(
				"Configuration problem! OTC metadata-entry instance server is supposed to be a notifying one."
			)

		val sparql = Rdf4jSparqlRunner(db.repo)

		val diff = StateDiffApplier(db, flowConf, log)

		def startFlow[TC <: IcosTC: TcConf](src: TcMetaSource[TC]): () => Unit =
			src.state.to(Sink.foreach(diff.apply[TC])).run()

		val atcSource = AtcMetaSource(flowConf.atcUpload)

		val cancellers = Seq(
			startFlow(atcSource),
			startFlow(OtcMetaSource(otcServer, sparql, log)),
			startFlow(EtcMetaSource(etcConf, db.vocab))
		)

		MetaFlow(Seq(atcSource), () => cancellers.foreach(_.apply()))
	end init
end IcosMetaFlow
