package se.lu.nateko.cp.meta

import java.net.URI

case class ResourceDto(displayName: String, uri: URI, comment: Option[String])

sealed trait ValueDto
case class LiteralValueDto(value: String, property: ResourceDto) extends ValueDto
case class ObjectValueDto(value: ResourceDto, property: ResourceDto) extends ValueDto

sealed trait DataRestrictionDto
case class MinRestrictionDto(minValue: Double) extends DataRestrictionDto
case class MaxRestrictionDto(maxValue: Double) extends DataRestrictionDto
case class RegexpRestrictionDto(regexp: String) extends DataRestrictionDto
case class OneOfRestrictionDto(values: Seq[String]) extends DataRestrictionDto

case class DataRangeDto(dataType: URI, restrictions: Seq[DataRestrictionDto])
case class CardinalityDto(min: Option[Int], max: Option[Int])

sealed trait PropertyDto
case class DataPropertyDto(resource: ResourceDto, cardinality: CardinalityDto, range: DataRangeDto) extends PropertyDto
case class ObjectPropertyDto(resource: ResourceDto, cardinality: CardinalityDto, range: ResourceDto) extends PropertyDto

case class ClassDto(resource: ResourceDto, properties: Seq[PropertyDto])
case class IndividualDto(resource: ResourceDto, owlClass: ClassDto, values: Seq[ValueDto])

case class UpdateDto(isAssertion: Boolean, subject: URI, predicate: URI, obj: String)
case class ReplaceDto(subject: URI, predicate: URI, oldObject: String, newObject: String){
	def assertion = UpdateDto(true, subject, predicate, newObject)
	def retraction = UpdateDto(false, subject, predicate, oldObject)
}

case class UploadMetadataDto(
	submitter: URI,
	station: URI,
	dataStructure: URI,
	hashSum: String,
	acquisitionStart: String,
	acquisitionEnd: String
)

case class StationLabelingDto(
	stationUri: URI,
	shortName: Option[String],
	longName: Option[String],
	address: Option[String],
	website: Option[String],
	stationClass: Option[String],
	lat: Option[Double],
	lon: Option[Double],
	aboveGround: Option[String],
	aboveSea: Option[Float],
	accessibility: Option[String],
	vegetation: Option[String],
	anthropogenics: Option[String],
	constructionStartDate: Option[String],
	constructionEndDate: Option[String],
	plannedDateOperational: Option[String],
	telecom: Option[String],
	infrastructure: Option[String],
	anemometerDir: Option[Int]
)

case class FileDeletionDto(stationUri: URI, file: URI)
case class LabelingUserDto(
	uri: Option[URI],
	mail: String,
	isPi: Boolean,
	firstName: Option[String],
	lastName: Option[String],
	affiliation: Option[String] = None,
	phone: Option[String] = None
)
