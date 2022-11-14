package se.lu.nateko.cp.meta.upload

import java.net.URI

import se.lu.nateko.cp.meta.core.data.Envri
import se.lu.nateko.cp.meta.core.data.Envri

object SparqlQueries {

	type Binding = Map[String, String]

	private def sitesStations(orgFilter: String) = s"""PREFIX cpmeta: <http://meta.icos-cp.eu/ontologies/cpmeta/>
		|PREFIX sitesmeta: <https://meta.fieldsites.se/ontologies/sites/>
		|SELECT *
		|FROM <https://meta.fieldsites.se/resources/sites/>
		|WHERE {
		|	$orgFilter
		|	?station a sitesmeta:Station ; cpmeta:hasName ?name; cpmeta:hasStationId ?id .
		|}
		|order by ?name""".stripMargin

	private def icosStations(orgFilter: String) = s"""PREFIX cpmeta: <http://meta.icos-cp.eu/ontologies/cpmeta/>
		|SELECT *
		|FROM <http://meta.icos-cp.eu/resources/icos/>
		|FROM <http://meta.icos-cp.eu/resources/cpmeta/>
		|FROM <http://meta.icos-cp.eu/ontologies/cpmeta/>
		|FROM <http://meta.icos-cp.eu/resources/extrastations/>
		|WHERE {
		|	$orgFilter
		|	?station cpmeta:hasName ?name; cpmeta:hasStationId ?id .
		|}
		|order by ?name""".stripMargin

	def stations(orgClass: Option[URI], producingOrg: Option[URI])(implicit envri: Envri): String = {
		val orgClassFilter = orgClass.map(org => s"?station a/rdfs:subClassOf* <$org> .")
		val producingOrgFilter: Option[String] = producingOrg.map(org => s"BIND(<$org> AS ?station) .")
		val orgFilter = Iterable(producingOrgFilter, orgClassFilter).flatten.mkString("\n")
		envri match {
			case Envri.SITES => sitesStations(orgFilter)
			case Envri.ICOS => icosStations(orgFilter)
		}
	}

	def toStation(b: Binding) = Station(new NamedUri(new URI(b("station")), b("name")), b("id"))

	def sites(station: URI): String = s"""PREFIX cpmeta: <http://meta.icos-cp.eu/ontologies/cpmeta/>
		|SELECT ?site ?name
		|WHERE {
		|	<$station> cpmeta:operatesOn ?site .
		|	?site rdfs:label ?name }
		|order by ?name""".stripMargin

	def toSite(b: Binding) = NamedUri(new URI(b("site")), b("name"))

	def samplingpoints(site: URI): String = s"""PREFIX cpmeta: <http://meta.icos-cp.eu/ontologies/cpmeta/>
		|SELECT *
		|WHERE {
		|	<$site> cpmeta:hasSamplingPoint ?point .
		|	?point rdfs:label ?name .
		|	?point cpmeta:hasLatitude ?latitude .
		|	?point cpmeta:hasLongitude ?longitude .
		|}
		|order by ?name""".stripMargin

	def toSamplingPoint(b: Binding) = SamplingPoint(new URI(b("point")), b("latitude").toDouble, b("longitude").toDouble, b("name"))

	private def objSpecsTempl(from: String) = s"""PREFIX cpmeta: <http://meta.icos-cp.eu/ontologies/cpmeta/>
		|SELECT *
		|FROM <${from}>
		|WHERE {
		|	?spec cpmeta:hasDataLevel ?dataLevel ; rdfs:label ?name ; cpmeta:hasFormat ?format ;
		|		cpmeta:hasDataTheme ?theme ; cpmeta:hasAssociatedProject ?project .
		|	OPTIONAL{?spec cpmeta:hasKeywords ?keywords}
		|	OPTIONAL{?project cpmeta:hasKeywords ?projKeywords}
		|	OPTIONAL{
		|		?spec cpmeta:containsDataset ?dataset .
		|		BIND(EXISTS{?dataset a cpmeta:DatasetSpec} as ?isSpatioTemp)
		|	}
		|} order by ?name""".stripMargin

	def objSpecs(implicit envri: Envri): String = envri match {
		case Envri.SITES => objSpecsTempl("https://meta.fieldsites.se/resources/sites/")
		case Envri.ICOS => objSpecsTempl("http://meta.icos-cp.eu/resources/cpmeta/")
	}

	def toObjSpec(b: Binding) = {
		def keywords(varname: String) = b.get(varname).map(_.split(",").toSeq).getOrElse(Seq.empty)

		ObjSpec(
			new URI(b("spec")),
			b("name"),
			b("dataLevel").toInt,
			if(b.contains("dataset")) Some(
				DsSpec(
					uri = new URI(b("dataset")),
					dsClass = (if(b("isSpatioTemp").toBoolean) DsSpec.SpatioTemp else DsSpec.StationTimeSer)
				)
			) else None,
			new URI(b("theme")),
			new URI(b("project")),
			new URI(b("format")),
			keywords("keywords").concat(keywords("projKeywords")).distinct
		)
	}

	//Only for ICOS for now
	def l3spatialCoverages = """|prefix cpmeta: <http://meta.icos-cp.eu/ontologies/cpmeta/>
		|select *
		|from <http://meta.icos-cp.eu/resources/cpmeta/>
		|where{
		|	{{?cov a cpmeta:SpatialCoverage } union {?cov a cpmeta:LatLonBox}}
		|	?cov rdfs:label ?label
		|}
		|""".stripMargin

	def toSpatialCoverage(b: Binding) = new SpatialCoverage(new URI(b("cov")), b("label"))

	private def peopleQuery(from: Seq[String]) = s"""prefix cpmeta: <http://meta.icos-cp.eu/ontologies/cpmeta/>
		|select *
		|${from.map(graph => s"""FROM <$graph>""").mkString("\n")}
		|where{
		|	?pers a cpmeta:Person ;
		|	cpmeta:hasFirstName ?fname ;
		|	cpmeta:hasLastName ?lname .
		|	optional {?pers cpmeta:hasEmail ?email}
		|}""".stripMargin

	def people(implicit envri: Envri): String = envri match {
		case Envri.SITES => peopleQuery(Seq("https://meta.fieldsites.se/resources/sites/"))
		case Envri.ICOS => peopleQuery(Seq("http://meta.icos-cp.eu/resources/cpmeta/", "http://meta.icos-cp.eu/resources/icos/"))
	}

	def toPerson(b: Binding) = NamedUri(new URI(b("pers")), s"""${b("fname")} ${b("lname")}""")

	def organizationsQuery(from: Seq[String]) = s"""prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#>
		|prefix cpmeta: <http://meta.icos-cp.eu/ontologies/cpmeta/>
		|select ?org ?orgName ?label
		|${from.map(graph => s"""FROM <$graph>""").mkString("\n")}
		|where{
		|	values ?orgClass {cpmeta:Organization cpmeta:CentralFacility cpmeta:ThematicCenter}
		|	?org a ?orgClass ; cpmeta:hasName ?orgName .
		|	optional {?org rdfs:label ?label }
		|}""".stripMargin

	def organizations(implicit envri: Envri): String = envri match {
		case Envri.SITES => organizationsQuery(Seq("https://meta.fieldsites.se/resources/sites/"))
		case Envri.ICOS => organizationsQuery(Seq(
			"http://meta.icos-cp.eu/ontologies/cpmeta/", "http://meta.icos-cp.eu/resources/cpmeta/", "http://meta.icos-cp.eu/resources/icos/"
		))
	}

	def toOrganization(b: Binding) = NamedUri(new URI(b("org")), b("orgName"))

	def datasetColumnQuery(dataset: URI) = datasetVarOrColQuery(dataset, "Column")
	def datasetVariableQuery(dataset: URI) = datasetVarOrColQuery(dataset, "Variable")

	private def datasetVarOrColQuery(dataset: URI, typ: String) = s"""prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#>
		|prefix cpmeta: <http://meta.icos-cp.eu/ontologies/cpmeta/>
		|select ?label ?title ?valueType ?unit ?optional ?regex
		|where{
		|	<$dataset> cpmeta:has${typ} ?var .
		|	?var rdfs:label ?label; cpmeta:has${typ}Title ?title ; cpmeta:hasValueType ?valueTypeRes .
		|	?valueTypeRes rdfs:label ?valueType .
		|	optional { ?var cpmeta:isOptional${typ} ?optional }
		|	optional { ?var cpmeta:isRegex${typ} ?regex }
		|	optional { ?valueTypeRes cpmeta:hasUnit ?unit }
		|}""".stripMargin

	def toDatasetVar(b: Binding) = DatasetVar(
		b("label"),
		b("title"),
		b("valueType"),
		b.getOrElse("unit", ""),
		if(b.contains("optional")) b("optional").toBoolean else false,
		if(b.contains("regex")) b("regex").toBoolean else false
	)
}
