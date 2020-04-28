package se.lu.nateko.cp.meta.services.sparql

import scala.language.implicitConversions
import org.eclipse.rdf4j.model.IRI

package object index{

	import HierarchicalBitmap.FilterRequest

	case class DataObjectFetch(filter: Filter, sort: Option[SortBy], offset: Int)

	sealed trait Filter
	implicit def dofFilterOps(filter: Filter) = new FilterOps(filter)

	final case class And(filters: Seq[Filter]) extends Filter
	final case class Or(filters: Seq[Filter]) extends Filter
	final case object All extends Filter
	final case object Nothing extends Filter
	final case object FilterDeprecated extends Filter
	final case class RequiredProps(props: Seq[ContProp]) extends Filter
	//TODO Wrap values in Option to indicate no known values, i.e. allow-all filter, in contrast to empty values, which should become "no solutions"
	final case class CategFilter[T <: AnyRef](category: TypedCategProp[T], values: Seq[T]) extends Filter
	final case class ContFilter[T](property: ContProp{type ValueType = T}, condition: FilterRequest[T]) extends Filter

	case class SortBy(property: ContProp, descending: Boolean)

	sealed trait Property{type ValueType}

	type TypedCategProp[T <: AnyRef] = CategProp{type ValueType = T}

	sealed trait UriProperty extends CategProp{type ValueType = IRI}
	sealed trait OptUriProperty extends CategProp{ type ValueType = Option[IRI]}

	final case object DobjUri extends UriProperty

	sealed trait ContProp extends Property

	sealed trait LongProperty extends ContProp{type ValueType = Long}
	sealed trait DateProperty extends LongProperty

	final case object FileName extends ContProp{type ValueType = String}
	final case object FileSize extends LongProperty
	final case object SamplingHeight extends ContProp{type ValueType = Float}
	final case object SubmissionStart extends DateProperty
	final case object SubmissionEnd extends DateProperty
	final case object DataStart extends DateProperty
	final case object DataEnd extends DateProperty

	sealed trait CategProp extends Property{type ValueType <: AnyRef}

	final case object Spec extends UriProperty
	final case object Station extends OptUriProperty
	final case object Site extends OptUriProperty
	final case object Submitter extends UriProperty
}
