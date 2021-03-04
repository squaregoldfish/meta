package se.lu.nateko.cp.meta.core.data

import java.net.URI
import java.time.LocalDate
import scala.util.Try
import spray.json._
import se.lu.nateko.cp.meta.core.CommonJsonSupport

case class Station(
	org: Organization,
	id: String,
	coverage: Option[GeoFeature],
	responsibleOrganization: Option[Organization],
	pictures: Seq[URI],
	specificInfo: StationSpecifics
)

sealed trait StationSpecifics

case object NoStationSpecifics extends StationSpecifics

sealed trait EcoStationSpecifics extends StationSpecifics{
	def climateZone: Option[UriResource]
	def ecosystems: Seq[UriResource]
	def meanAnnualTemp: Option[Float]
}

case class SitesStationSpecifics(
	sites: Seq[Site],
	ecosystems: Seq[UriResource],
	climateZone: Option[UriResource],
	meanAnnualTemp: Option[Float],
	operationalPeriod: Option[String],
	documentation: Seq[PlainStaticObject]
) extends EcoStationSpecifics

sealed trait IcosStationSpecifics extends StationSpecifics{
	def stationClass: Option[IcosStationClass.Value]
	def labelingDate: Option[LocalDate]
	def countryCode: Option[CountryCode]
	def timeZoneOffset: Option[Int]
	def documentation: Seq[PlainStaticObject]
}

case class PlainIcosSpecifics(
	stationClass: Option[IcosStationClass.Value],
	labelingDate: Option[LocalDate],
	countryCode: Option[CountryCode],
	timeZoneOffset: Option[Int],
	documentation: Seq[PlainStaticObject]
) extends IcosStationSpecifics

case class EtcStationSpecifics(
	stationClass: Option[IcosStationClass.Value],
	labelingDate: Option[LocalDate],
	countryCode: Option[CountryCode],
	climateZone: Option[UriResource],
	ecosystemType: Option[UriResource],
	meanAnnualTemp: Option[Float],
	meanAnnualPrecip: Option[Float],
	meanAnnualRad: Option[Float],
	stationDocs: Seq[URI],
	stationPubs: Seq[URI],
	timeZoneOffset: Option[Int],
	documentation: Seq[PlainStaticObject]
) extends IcosStationSpecifics with EcoStationSpecifics{
	override def ecosystems = ecosystemType.toSeq
}

object IcosStationClass extends Enumeration{
	val One = Value("1")
	val Two = Value("2")
	val Associated = Value("Associated")
	def parse(s: String): Try[Value] = Try(withName(s))
}

object StationSpecifics extends CommonJsonSupport{
	import JsonSupport.{uriResourceFormat, plainStaticObjectFormat, siteFormat, countryCodeFormat}
	import CommonJsonSupport._
	implicit val stationClassFormat = enumFormat(IcosStationClass)
	implicit val etcStationSpecificsFormat = jsonFormat12(EtcStationSpecifics)
	implicit val sitesStationSpecificsFormat = jsonFormat6(SitesStationSpecifics)
	implicit val plainIcosSpecificsFormat = jsonFormat5(PlainIcosSpecifics)

	private val EtcSpec = "etc"
	private val SitesSpec = "sites"
	private val PlainIcosSpec = "plainicos"
	implicit object stationSpecificsFormat extends RootJsonFormat[StationSpecifics]{
		def write(ss: StationSpecifics): JsValue = ss match{
			case NoStationSpecifics => JsObject.empty
			case etc: EtcStationSpecifics => etc.toTypedJson(EtcSpec)
			case sites: SitesStationSpecifics => sites.toTypedJson(SitesSpec)
			case icos: PlainIcosSpecifics => icos.toTypedJson(PlainIcosSpec)
		}

		def read(value: JsValue) =
			value.asJsObject("StationSpecifics must be a JSON object").fields.get(TypeField) match{
				case Some(JsString(EtcSpec)) => value.convertTo[EtcStationSpecifics]
				case Some(JsString(SitesSpec)) => value.convertTo[SitesStationSpecifics]
				case Some(JsString(PlainIcosSpec)) => value.convertTo[PlainIcosSpecifics]
				case None => NoStationSpecifics
				case Some(unknType) => deserializationError(s"Unknown StationSpecifics type $unknType")
			}
	}
}
