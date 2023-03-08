package se.lu.nateko.cp.meta.services.upload

import scala.util.matching.Regex
import java.net.URI
import se.lu.nateko.cp.meta.core.data.ValueType
import se.lu.nateko.cp.meta.core.data.VarMeta
import se.lu.nateko.cp.meta.core.data.UriResource
import se.lu.nateko.cp.meta.core.data.InstrumentDeployment

class DatasetVariable(
	val self: UriResource,
	val title: String,
	val valueType: ValueType,
	val valueFormat: Option[URI],
	val isRegex: Boolean,
	val isOptional: Boolean
):
	def plain: Option[VarMeta] =
		if !isRegex
		then Some(VarMeta(self, title, valueType, valueFormat, None, None))
		else None

class VarMetaLookup(varDefs: Seq[DatasetVariable]):

	val plainMandatory = varDefs.filterNot(_.isOptional).flatMap(_.plain)

	private val plainLookup: Map[String, VarMeta] = varDefs.flatMap(_.plain).map(vm => vm.label -> vm).toMap

	private val regexes = varDefs.filter(_.isRegex).sortBy(_.isOptional).map{
		dv => new Regex(dv.title) -> dv
	}

	def lookup(varName: String): Option[VarMeta] = plainLookup.get(varName).orElse(
		regexes.collectFirst{
			case (reg, dv) if reg.matches(varName) => VarMeta(dv.self, varName, dv.valueType, dv.valueFormat, None, None)
		}
	)
