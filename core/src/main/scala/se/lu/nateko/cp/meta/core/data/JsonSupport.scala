package se.lu.nateko.cp.meta.core.data

import se.lu.nateko.cp.meta.core.CommonJsonSupport
import se.lu.nateko.cp.meta.core.crypto.JsonSupport.sha256sumFormat
import spray.json._

object JsonSupport extends CommonJsonSupport{

	implicit val uriResourceFormat = jsonFormat3(UriResource)
	implicit val projectFormat = jsonFormat2(Project)
	implicit val dataThemeFormat = jsonFormat3(DataTheme)
	implicit val plainStaticObjectFormat = jsonFormat3(PlainStaticObject)
	implicit val dataObjectSpecFormat = jsonFormat9(DataObjectSpec)

	implicit val positionFormat = jsonFormat3(Position.apply)
	implicit val spatialCoverageFormat = jsonFormat4(LatLonBox)
	implicit val geoTrackFormat = jsonFormat1(GeoTrack)
	implicit val geoPolygonFormat = jsonFormat1(Polygon)
	implicit val genericGeoFeatureFormat = jsonFormat1(GenericGeoFeature)

	implicit object geoFeatureFormat extends RootJsonFormat[GeoFeature]{

		def write(geo: GeoFeature): JsValue = {
			val baseJson = geo match {
				case llb: LatLonBox => llb.toJson
				case gt: GeoTrack => gt.toJson
				case pos: Position => pos.toJson
				case ggf: GenericGeoFeature => ggf.toJson
				case gpoly: Polygon => gpoly.toJson
			}
			JsObject(baseJson.asJsObject.fields + ("geo" -> geo.geoJson.parseJson))
		}

		def read(value: JsValue): GeoFeature = value match {
			case JsObject(fields) =>
				if(fields.contains("points"))
					value.convertTo[GeoTrack]
				else if(fields.contains("vertices"))
					value.convertTo[Polygon]
				else if(fields.contains("min") && fields.contains("max"))
					value.convertTo[LatLonBox]
				else if(fields.contains("lat") && fields.contains("lon"))
					value.convertTo[Position]
				else
					value.convertTo[GenericGeoFeature]
			case _ =>
				deserializationError("Expected a JsObject representing a GeoFeature")
		}
	}

	implicit object orcidFormat extends JsonFormat[Orcid]{
		def write(id: Orcid): JsValue = JsString(id.shortId)

		def read(value: JsValue) = value match{
			case JsString(s) => Orcid.unapply(s) match{
				case Some(o) => o
				case None => deserializationError(s"Could not parse Orcid id from $s")
			}
			case x => deserializationError("Orcid must be represented with a JSON string, got " + x.getClass.getCanonicalName)
		}
	}

	implicit val orgFormat = jsonFormat3(Organization)
	implicit val personFormat = jsonFormat4(Person)
	implicit val locationFormat = jsonFormat2(Location)
	implicit val siteFormat = jsonFormat3(Site)
	implicit val stationFormat = jsonFormat12(Station)

	implicit object agentFormat extends JsonFormat[Agent]{

		def write(agent: Agent): JsValue = agent match{
			case person: Person => person.toJson
			case org: Organization => org.toJson
		}

		def read(value: JsValue): Agent = value match{
			case JsObject(fields) =>
				if(fields.contains("firstName"))
					value.convertTo[Person]
				else
					value.convertTo[Organization]
			case _ =>
				deserializationError("Expected JS object representing eigher a person or an organization")
		}
	}

	implicit val dataProductionFormat = jsonFormat6(DataProduction)
	implicit val dataAcquisitionFormat = jsonFormat6(DataAcquisition)
	implicit val dataSubmissionFormat = jsonFormat3(DataSubmission)

	implicit val temporalCoverageFormat = jsonFormat2(TemporalCoverage)

	implicit val valueTypeFormat = jsonFormat3(ValueType)
	implicit val columnInfoFormat = jsonFormat2(ColumnInfo)
	implicit val l3VarInfoFormat = jsonFormat3(L3VarInfo)
	implicit val l2SpecificMetaFormat = jsonFormat5(L2OrLessSpecificMeta)
	implicit val l3SpecificMetaFormat = jsonFormat6(L3SpecificMeta)

	implicit val tabularIngestionFormat = jsonFormat2(TabularIngestionExtract)
	implicit val wdcggExtractFormat = jsonFormat3(WdcggExtract)
	implicit val varInfoFormat = jsonFormat3(VarInfo)
	implicit val netCdfExtractFormat = jsonFormat1(NetCdfExtract)
	implicit val ecocsvExtractFormat = jsonFormat2(TimeSeriesExtract)
	implicit val socatExtractFormat = jsonFormat2(SpatialTimeSeriesExtract)

	implicit object ingestionMetadataExtractFormat extends JsonFormat[IngestionMetadataExtract]{

		def write(uploadInfo: IngestionMetadataExtract): JsValue = uploadInfo match{
			case wdcgg: WdcggExtract => wdcgg.toJson
			case ts: TimeSeriesExtract => ts.toJson
			case sts: SpatialTimeSeriesExtract => sts.toJson
			case ncdf: NetCdfExtract => ncdf.toJson
		}

		def read(value: JsValue): IngestionMetadataExtract =  value match {
			case JsObject(fields)  =>
				if(fields.contains("customMetadata"))
					value.convertTo[WdcggExtract]
				else if(fields.contains("coverage"))
					value.convertTo[SpatialTimeSeriesExtract]
				else if(fields.contains("varInfo"))
					value.convertTo[NetCdfExtract]
				else
					value.convertTo[TimeSeriesExtract]
			case _ =>
				deserializationError("Expected JS object representing upload completion info")
		}
	}

	implicit val uploadCompletionFormat = jsonFormat2(UploadCompletionInfo)
	implicit val docObjectFormat = jsonFormat10(DocObject)
	implicit val referencesFormat = jsonFormat2(References)

	implicit object dataObjectFormat extends RootJsonFormat[DataObject] {
		private val defFormat = jsonFormat13(DataObject)

		def read(value: JsValue): DataObject = value.convertTo[DataObject](defFormat)

		def write(dobj: DataObject): JsValue = {
			val plain = dobj.toJson(defFormat).asJsObject
			dobj.coverage.fold(plain) { geo =>
				JsObject(plain.fields + ("coverageGeoJson" -> JsString(geo.geoJson)))
			}
		}
	}

	implicit object staticObjectFormat extends RootJsonFormat[StaticObject]{
		override def read(value: JsValue): StaticObject = value match {
			case JsObject(fields)  =>
				if(fields.contains("specification"))
					value.convertTo[DataObject]
				else
					value.convertTo[DocObject]
			case _ =>
				deserializationError("Expected JS object representing a data/doc object")
		}
		override def write(so: StaticObject): JsValue = so match{
			case dobj: DataObject => dobj.toJson
			case doc: DocObject => doc.toJson
		}
	}


	implicit object staticDataItemFormat extends JsonFormat[StaticDataItem]{
		implicit val statCollFormat = jsonFormat8(StaticCollection)

		def write(sdi: StaticDataItem): JsValue = sdi match{
			case pdo: PlainStaticObject => pdo.toJson
			case sc: StaticCollection => sc.toJson
		}

		def read(value: JsValue): StaticDataItem = value match {
			case JsObject(fields) =>
				if(fields.contains("title"))
					value.convertTo[StaticCollection]
				else
					value.convertTo[PlainStaticObject]
			case _ =>
				deserializationError("Expected JS object representing static collection or a plain data object")
		}
	}

	implicit val staticCollFormat = staticDataItemFormat.statCollFormat

}
