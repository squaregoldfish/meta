package se.lu.nateko.cp.meta.core.data

import java.net.URI
import java.util.Date

case class UriResource(uri: URI, label: Option[String])

case class DataPackageSpec(format: URI, encoding: URI, dataLevel: Int)

case class PackageSubmission(submittingOrg: UriResource, start: Date, stop: Option[Date])
case class PackageProduction(producer: UriResource)

case class DataPackage(
	spec: DataPackageSpec,
	submission: PackageSubmission,
	production: PackageProduction
)
