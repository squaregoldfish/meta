package se.lu.nateko.cp.meta.routes

import scala.language.postfixOps
import akka.http.scaladsl.marshallers.sprayjson.SprayJsonSupport.*
import akka.http.scaladsl.model.*
import akka.http.scaladsl.model.headers.*
import akka.http.scaladsl.server.Directives.*
import akka.http.scaladsl.server.Directive1
import akka.http.scaladsl.server.Route
import se.lu.nateko.cp.meta.InstanceServersConfig
import se.lu.nateko.cp.meta.MetaDb
import se.lu.nateko.cp.meta.core.data.DataObject
import se.lu.nateko.cp.meta.core.data.Envri
import se.lu.nateko.cp.meta.core.data.EnvriConfigs
import se.lu.nateko.cp.meta.instanceserver.InstanceServer
import se.lu.nateko.cp.meta.routes.FilesRoute.Sha256Segment
import se.lu.nateko.cp.meta.services.CpVocab
import se.lu.nateko.cp.meta.services.linkeddata.InstanceServerSerializer
import se.lu.nateko.cp.meta.services.linkeddata.UriSerializer
import se.lu.nateko.cp.meta.services.linkeddata.UriSerializer.Hash
import se.lu.nateko.cp.meta.services.metaexport.Inspire
import spray.json.DefaultJsonProtocol.*
import akka.http.scaladsl.marshalling.ToResponseMarshaller

object LinkedDataRoute {
	private given ToResponseMarshaller[InstanceServer] = InstanceServerSerializer.marshaller

	def apply(
		config: InstanceServersConfig,
		uriSerializer: UriSerializer,
		instanceServers: Map[String, InstanceServer],
		vocab: CpVocab
	)(using envriConfs: EnvriConfigs): Route = {

		val instServerConfs = MetaDb.getAllInstanceServerConfigs(config)
		given ToResponseMarshaller[Uri] = uriSerializer.marshaller
		val extractEnvri = AuthenticationRouting.extractEnvriDirective

		def canonicalize(uri: Uri, envri: Envri): Uri = {
			val envriConf = envriConfs(envri)//will not fail, as envri extraction is based on EnvriConfigs
			val itemPrefix = uri.path match{
				case Hash.Object(_) | Hash.Collection(_) => envriConf.dataItemPrefix
				case _ => envriConf.metaItemPrefix
			}
			uri.withHost(itemPrefix.getHost).withScheme(itemPrefix.getScheme)
		}

		val genericRdfUriResourcePage: Route = (extractUri & extractEnvri){(uri, envri) =>
			val canonicalUri = canonicalize(uri, envri)
			respondWithHeaders(`Access-Control-Allow-Origin`.*) {
				complete(canonicalUri)
			}
		}

		get{
			path(("ontologies" | "resources") / Segment /){_ =>
				extractUri{uri =>
					val path = uri.path.toString

					val serverOpt: Option[(String, InstanceServer)] = instServerConfs.collectFirst{
						case (id, instServConf)
							if instServConf.writeContexts.exists(_.toString.endsWith(path)) =>
								instanceServers.get(id).map((id, _))
					}.flatten

					serverOpt match{
						case None =>
							complete(StatusCodes.NotFound)
						case Some((id, instServer)) =>
							respondWithHeader(attachmentHeader(id + ".rdf")){
								complete(instServer)
							}
					}
				}
			} ~
			pathPrefix(("objects" | "collections") / Sha256Segment){hash =>
				pathEnd{
					genericRdfUriResourcePage
				} ~
				path(Segment){
					case fileName if InspireXmlFilename.matches(fileName) =>
						(extractUri & extractEnvri){(uri, envri) =>
							val canonUri = canonicalize(objMetaFormatUriToObjUri(uri), envri)
							uriSerializer.fetchStaticObject(canonUri) match{
								case Some(dobj: DataObject) =>
									respondWithHeader(attachmentHeader(fileName)){
										complete(
											HttpEntity(
												ContentType.WithCharset(MediaTypes.`application/xml`, HttpCharsets.`UTF-8`),
												views.xml.InspireDobjMeta(Inspire(dobj, vocab), envri, envriConfs(envri)).body
											)
										)
									}
								case _ =>
									complete(StatusCodes.NotFound -> s"No data object with SHA-256 hashsum of ${hash.base64Url}")
							}
						}

					case fileName @ FileNameWithExtension(_, ext) =>
						extToMime.get(ext).fold[Route](reject){mime =>
							mapRequest(rewriteObjRequest(mime)){
								respondWithHeader(attachmentHeader(fileName)){
									genericRdfUriResourcePage
								}
							}
						}
					case _ =>
						reject
				}
			} ~
			pathPrefix("ontologies" | "resources" | "files"){
				genericRdfUriResourcePage
			}
		} ~
		options{
			pathPrefix("objects" | "collections" | "resources") {
				respondWithHeaders(
					`Access-Control-Allow-Origin`.*,
					`Access-Control-Allow-Methods`(HttpMethods.GET),
					`Access-Control-Allow-Headers`(`Content-Type`.name, `Cache-Control`.name)
				) {
					complete(StatusCodes.OK)
				}
			}
		}
	}

	private val FileNameWithExtension = "^(.+)(\\.[a-z]+)$".r
	private val InspireXmlFilename = "\\.iso\\.xml$".r.unanchored

	private val extToMime: Map[String, MediaType] = Map(
		".json" -> MediaTypes.`application/json`,
		".ttl" -> MediaTypes.`text/plain`,
		".xml" -> MediaTypes.`application/xml`
	)

	private def rewriteObjRequest(mime: MediaType)(req: HttpRequest): HttpRequest = {
		val newUri = objMetaFormatUriToObjUri(req.uri)
		val accept = Accept(mime)
		req.removeHeader(accept.name).addHeader(accept).withUri(newUri)
	}

	private def objMetaFormatUriToObjUri(uri: Uri): Uri =
		uri.withPath(uri.path.reverse.tail.tail.reverse)

	def attachmentHeader(fileName: String) = {
		import ContentDispositionTypes.attachment
		`Content-Disposition`(attachment, Map("filename" -> fileName))
	}
}
