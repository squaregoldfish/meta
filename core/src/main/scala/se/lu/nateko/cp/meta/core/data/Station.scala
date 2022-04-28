package se.lu.nateko.cp.meta.core.data

import java.net.URI
import java.time.LocalDate
import scala.util.Try
import spray.json.*
import se.lu.nateko.cp.meta.core.CommonJsonSupport

case class Station(
	org: Organization,
	id: String,
	location: Option[Position],
	coverage: Option[GeoFeature],
	responsibleOrganization: Option[Organization],
	pictures: Seq[URI],
	specificInfo: StationSpecifics,
	funding: Option[Seq[Funding]]
){
	def fullCoverage: Option[GeoFeature] = List(location, coverage).flatten match{
		case Nil => None
		case single :: Nil => Some(single)
		case multiple => Some(FeatureCollection(multiple, Some(org.name)).flatten)
	}
}

case class Funding(
	self: UriResource,
	funder: Funder,
	awardTitle: Option[String],
	awardNumber: Option[String],
	awardUrl: Option[URI],
	start: Option[LocalDate],
	stop: Option[LocalDate],
)

object FunderIdType extends Enumeration{
	type FunderIdType = Value
	val Crossref = Value("Crossref Funder ID")
	val GRID, ISNI, ROR, Other = Value
}

case class Funder(org: Organization, id: Option[(String,FunderIdType.Value)])

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
	def theme: Option[DataTheme]
	def stationClass: Option[IcosStationClass.Value]
	def labelingDate: Option[LocalDate]
	def discontinued: Boolean
	def countryCode: Option[CountryCode]
	def timeZoneOffset: Option[Int]
	def documentation: Seq[PlainStaticObject]
}

case class PlainIcosSpecifics(
	theme: Option[DataTheme],
	stationClass: Option[IcosStationClass.Value],
	labelingDate: Option[LocalDate],
	discontinued: Boolean,
	countryCode: Option[CountryCode],
	timeZoneOffset: Option[Int],
	documentation: Seq[PlainStaticObject]
) extends IcosStationSpecifics

case class EtcStationSpecifics(
	theme: Option[DataTheme],
	stationClass: Option[IcosStationClass.Value],
	labelingDate: Option[LocalDate],
	discontinued: Boolean,
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
	type IcosStationClass = Value
	val One = Value("1")
	val Two = Value("2")
	val Associated = Value("Associated")
	def parse(s: String): Try[Value] = Try(withName(s))
}
