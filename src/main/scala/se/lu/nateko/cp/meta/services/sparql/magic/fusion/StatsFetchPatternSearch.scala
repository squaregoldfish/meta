package se.lu.nateko.cp.meta.services.sparql.magic.fusion

import scala.jdk.CollectionConverters._
import org.eclipse.rdf4j.query.algebra.Group
import org.eclipse.rdf4j.query.algebra.Count
import org.eclipse.rdf4j.query.algebra.Var
import org.eclipse.rdf4j.query.algebra.Extension
import org.eclipse.rdf4j.query.algebra.ValueExpr
import se.lu.nateko.cp.meta.services.sparql.index.Filter


object StatsFetchPatternSearch{

	def singleVarCountGroup(g: Group): Option[String] = g.getGroupElements().asScala.toSeq match{
		case Seq(elem) => singleVarCount(elem.getOperator)
		case _         => None
	}

	def singleCountExtension(ext: Extension): Option[(String, String)] = ext.getElements().asScala.toSeq
		.flatMap{
			elem => singleVarCount(elem.getExpr).map(elem.getName -> _)
		} match{
			case Seq(singleCountVarNames) => Some(singleCountVarNames)
			case _ => None
		}

	def singleVarCount(expr: ValueExpr): Option[String] = expr match{
		case cnt: Count =>
			cnt.getArg match {
				case v: Var if !v.isAnonymous => Some(v.getName)
				case _ => None
			}
		case _ => None
	}

	case class GroupPattern(filter: Filter, submitterVar: String, stationVar: String, specVar: String, siteVar: Option[String])

}
