package se.lu.nateko.cp.meta.core.data

import se.lu.nateko.cp.meta.core.CommonJsonSupport
import spray.json._
import se.lu.nateko.cp.meta.core.crypto.Sha256Sum

object JsonSupport extends CommonJsonSupport{

	import Sha256Sum.sha256sumFormat

	implicit val uriResourceFormat = jsonFormat2(UriResource)
	implicit val dataObjectSpecFormat = jsonFormat4(DataObjectSpec)

	implicit val objectSubmissionFormat = jsonFormat3(DataSubmission)

	implicit val dataThemeFormat = enumFormat(DataTheme)

	implicit val positionFormat = jsonFormat2(Position)
	implicit val objectProducerFormat = jsonFormat5(DataProducer)
	implicit val objectProductionFormat = jsonFormat2(DataProduction)
	implicit val dataObjectFormat = jsonFormat7(DataObject)

}