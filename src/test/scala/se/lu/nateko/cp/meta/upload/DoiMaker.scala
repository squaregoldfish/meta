package se.lu.nateko.cp.meta.upload

import se.lu.nateko.cp.doi.core.DoiClient
import se.lu.nateko.cp.doi.core.DoiClientConfig
import java.net.URL
import se.lu.nateko.cp.doi.core.PlainJavaDoiHttp
import se.lu.nateko.cp.doi._
import se.lu.nateko.cp.doi.meta._
import se.lu.nateko.cp.meta.core.crypto.Sha256Sum
import akka.actor.ActorSystem
import java.net.URI
import se.lu.nateko.cp.meta.utils.async.executeSequentially
import scala.concurrent.Future
import akka.Done
import se.lu.nateko.cp.meta.services.upload.UploadService
import scala.util.Try

class DoiMaker(password: String)(implicit val system: ActorSystem){

	import system.dispatcher

	val client: DoiClient = {
		val conf = DoiClientConfig("SND.ICOS", password, new URL("https://api.datacite.org/"), "10.18160")
		val http = new PlainJavaDoiHttp(conf.symbol, password)
		new DoiClient(conf, http)
	}

	val sparqlHelper = new SparqlHelper(new URI("https://meta.icos-cp.eu/sparql"))

	def saveDoi(meta: DoiMeta): Future[Done] = {
		client.putMetadata(meta).map(_ => Done)
	}

	def saveDois(infos: Seq[DoiMeta]): Future[Done] = executeSequentially(infos)(saveDoi).map(_ => Done)

}

object DoiMaker{

	val ccby4 = Rights("CC BY 4.0", Some("https://creativecommons.org/licenses/by/4.0"))
	val etc = GenericName("ICOS Ecosystem Thematic Centre")
	val atc = GenericName("ICOS Atmosphere Thematic Centre")

	def coolDoi(hash: Sha256Sum): String = {
		val id: Long = hash.getBytes.take(8).foldLeft(0L){(acc, b) => (acc << 8) + b}
		CoolDoi.makeRandom(id)
	}
}
