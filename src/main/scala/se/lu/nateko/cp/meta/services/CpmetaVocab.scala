package se.lu.nateko.cp.meta.services

import org.eclipse.rdf4j.model.ValueFactory
import se.lu.nateko.cp.meta.api.CustomVocab

class CpmetaVocab (val factory: ValueFactory) extends CustomVocab { top =>

	implicit val bup = makeUriProvider(CpmetaVocab.MetaPrefix)

//	val stationClass = getRelative("Station")
	val ingosStationClass = getRelative("IngosStation")
	val wdcggStationClass = getRelative("WdcggStation")
	val atmoStationClass = getRelative("AS")
	val ecoStationClass = getRelative("ES")
	val oceStationClass = getRelative("OS")
//	val tcClass = getRelative("ThematicCenter")
//	val cfClass = getRelative("CentralFacility")
	val orgClass = getRelative("Organization")
	val membershipClass = getRelative("Membership")

	val aquisitionClass = getRelative("DataAcquisition")
	val productionClass = getRelative("DataProduction")
	val submissionClass = getRelative("DataSubmission")
	val dataObjectClass = getRelative("DataObject")
	val dataObjectSpecClass = getRelative("DataObjectSpec")
	val collectionClass = getRelative("Collection")
	val spatialCoverageClass = getRelative("SpatialCoverage")
	val latLonBoxClass = getRelative("LatLonBox")

	val hasElevation = getRelative("hasElevation")
	val hasLatitude = getRelative("hasLatitude")
	val hasNothernBound = getRelative("hasNothernBound")
	val hasSouthernBound = getRelative("hasSouthernBound")
	val hasLongitude = getRelative("hasLongitude")
	val hasEasternBound = getRelative("hasEasternBound")
	val hasWesternBound = getRelative("hasWesternBound")
	val hasSamplingHeight = getRelative("hasSamplingHeight")
	val hasName = getRelative("hasName")
	val hasStationId = getRelative("hasStationId")
	val countryCode = getRelative("countryCode")
	val country = getRelative("country")

	val hasSha256sum = getRelative("hasSha256sum")
	val hasDoi = getRelative("hasDoi")
	val isNextVersionOf = getRelative("isNextVersionOf")
	val wasAcquiredBy = getRelative("wasAcquiredBy")
	val wasSubmittedBy = getRelative("wasSubmittedBy")
	val wasProducedBy = getRelative("wasProducedBy")
	val wasPerformedBy = getRelative("wasPerformedBy")
	val wasPerformedWith = getRelative("wasPerformedWith")
	val wasParticipatedInBy = getRelative("wasParticipatedInBy")
	val wasHostedBy = getRelative("wasHostedBy")
	val hasDataLevel = getRelative("hasDataLevel")
	val hasDataTheme = getRelative("hasDataTheme")
	val hasAssociatedProject = getRelative("hasAssociatedProject")
	val containsDataset = getRelative("containsDataset")
	val hasObjectSpec = getRelative("hasObjectSpec")
	val hasFormat = getRelative("hasFormat")
	val hasEncoding = getRelative("hasEncoding")
	val hasFormatSpecificMeta = getRelative("hasFormatSpecificMetadata")
	val hasNumberOfRows = getRelative("hasNumberOfRows")
	val hasSizeInBytes = getRelative("hasSizeInBytes")
	val hasTemporalResolution = getRelative("hasTemporalResolution")
	val hasSpatialCoverage = getRelative("hasSpatialCoverage")
	val asGeoJSON = getRelative("asGeoJSON")

	val personClass = getRelative("Person")
	val roleClass = getRelative("Role")

	val hasFirstName = getRelative("hasFirstName")
	val hasLastName = getRelative("hasLastName")
	val hasEmail = getRelative("hasEmail")
	val hasRole = getRelative("hasRole")
	val hasMembership = getRelative("hasMembership")
	val atOrganization = getRelative("atOrganization")
	val hasStartTime = getRelative("hasStartTime")
	val hasEndTime = getRelative("hasEndTime")
	val hasIcon = getRelative("hasIcon")
	val hasMarkerIcon = getRelative("hasMarkerIcon")

	val hasAtcId = getRelative("hasAtcId")
	val hasEtcId = getRelative("hasEtcId")
	val hasOtcId = getRelative("hasOtcId")

	val ancillaryValueClass = getRelative("AncillaryValue")
	val ancillaryEntryClass = getRelative("AncillaryEntry")

	val hasAncillaryDataValue = getRelative("hasAncillaryDataValue")
	val hasAncillaryObjectValue = getRelative("hasAncillaryObjectValue")
	val hasAncillaryEntry = getRelative("hasAncillaryEntry")

	val wdcggFormat = getRelative("asciiWdcggTimeSer")
//	val etcFormat = getRelative("asciiEtcTimeSer")
	val atcFormat = getRelative("asciiAtcTimeSer")
	val atcProductFormat = getRelative("asciiAtcProductTimeSer")
//	val socatFormat = getRelative("asciiOtcSocatTimeSer")

	object prov extends CustomVocab {
		val factory = top.factory
		implicit val bup = makeUriProvider(CpmetaVocab.ProvPrefix)

		val wasAssociatedWith = getRelative("wasAssociatedWith")
		val startedAtTime = getRelative("startedAtTime")
		val endedAtTime = getRelative("endedAtTime")
	}

	object dcterms extends CustomVocab {
		val factory = top.factory
		implicit val bup = makeUriProvider(CpmetaVocab.DctermsPrefix)

		val date = getRelative("date")
		val title = getRelative("title")
		val description = getRelative("description")
		val creator = getRelative("creator")
		val hasPart = getRelative("hasPart")
		val dateSubmitted = getRelative("dateSubmitted")
	}

//	object sites extends CustomVocab {
//		val factory = top.factory
//		implicit val bup = makeUriProvider("https://meta.fieldsites.se/ontologies/sites/")
//		val simpleSitesCsv = getRelative("simpleSitesCsv")
//	}
}

object CpmetaVocab{
	val MetaPrefix = "http://meta.icos-cp.eu/ontologies/cpmeta/"
	val ProvPrefix = "http://www.w3.org/ns/prov#"
	val DctermsPrefix = "http://purl.org/dc/terms/"
}
