package se.lu.nateko.cp.meta.core.data

import java.net.URI

case class EnvriConfig(
	authHost: String,
	dataHost: String,
	metaHost: String,
	dataItemPrefix: URI,
	metaItemPrefix: URI,
	defaultTimezoneId: String
){
	def matchesHost(host: String): Boolean =
		host == dataHost || host == metaHost ||
		host == dataItemPrefix.getHost || host == metaItemPrefix.getHost
}

object Envri extends Enumeration{

	type Envri = Value
	type EnvriConfigs = Map[Envri, EnvriConfig]

	val ICOS, SITES = Value

	def infer(uri: URI)(using EnvriConfigs): Option[Envri] = infer(uri.getHost)

	def infer(hostname: String)(using configs: EnvriConfigs): Option[Envri] = configs.collectFirst{
		case (envri, conf) if conf.matchesHost(hostname) => envri
	}

}
