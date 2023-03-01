package se.lu.nateko.cp.meta.routes

import akka.http.scaladsl.model.*
import akka.http.scaladsl.model.headers.*
import akka.http.scaladsl.server.Directives.*
import akka.http.scaladsl.server.Route
import se.lu.nateko.cp.meta.api.SparqlRunner
import se.lu.nateko.cp.meta.api.SparqlQuery
import se.lu.nateko.cp.meta.core.data.EnvriConfigs
import se.lu.nateko.cp.meta.services.metaexport.SchemaOrg
import se.lu.nateko.cp.meta.core.data.EnvriConfig
import se.lu.nateko.cp.meta.core.data.envriConf

object SitemapRoute {
	def apply(sparqler: SparqlRunner)(using EnvriConfigs): Route = {
		val extractEnvri = AuthenticationRouting.extractEnvriDirective

		(get & path("sitemap.xml") & extractEnvri){ implicit envri =>
			given EnvriConfig = envriConf
			complete(
				HttpEntity(
					ContentType.WithCharset(MediaTypes.`text/xml`, HttpCharsets.`UTF-8`),
					views.xml.Sitemap(SchemaOrg.dataObjs(sparqler) ++ SchemaOrg.collObjs(sparqler) ++ SchemaOrg.docObjs(sparqler)).body
				)
			)
		}
	}
}