package se.lu.nateko.cp.meta.core.data

import java.net.URI
import se.lu.nateko.cp.meta.core.crypto.Sha256Sum
import java.time.Instant

import DataTheme.DataTheme
import spray.json.JsValue

object DataTheme extends Enumeration{
	val Atmosphere, Ecosystem, Ocean, CP, CAL, NonICOS = Value
	type DataTheme = Value
}

case class UriResource(uri: URI, label: Option[String])

case class DataProducer(
	uri: URI,
	label: String,
	theme: DataTheme,
	pos: Option[Position],
	coverage: Option[String]
)

case class Position(lat: Double, lon: Double)

case class DataObjectSpec(
	format: UriResource,
	encoding: UriResource,
	dataLevel: Int,
	datasetSpec: Option[JsValue]
)

case class DataSubmission(submitter: UriResource, start: Instant, stop: Option[Instant])

case class TimeInterval(start: Instant, stop: Instant)

case class DataProduction(producer: DataProducer, timeInterval: Option[TimeInterval])

case class DataObject(
	hash: Sha256Sum,
	accessUrl: Option[URI],
	pid: Option[String],
	fileName: Option[String],
	production: DataProduction,
	submission: DataSubmission,
	specification: DataObjectSpec
)