package se.lu.nateko.cp.meta.services.sparql.magic.fusion

import org.eclipse.rdf4j.model.IRI
import org.eclipse.rdf4j.model.Literal
import org.eclipse.rdf4j.model.Value
import org.eclipse.rdf4j.query.algebra.BindingSetAssignment
import org.eclipse.rdf4j.query.algebra.Exists
import org.eclipse.rdf4j.query.algebra.Extension
import org.eclipse.rdf4j.query.algebra.Not
import org.eclipse.rdf4j.query.algebra.QueryModelNode
import org.eclipse.rdf4j.query.algebra.SingletonSet
import org.eclipse.rdf4j.query.algebra.StatementPattern
import org.eclipse.rdf4j.query.algebra.TupleExpr
import org.eclipse.rdf4j.query.algebra.Union
import org.eclipse.rdf4j.query.algebra.ValueExpr
import se.lu.nateko.cp.meta.services.CpmetaVocab
import se.lu.nateko.cp.meta.services.sparql.index
import se.lu.nateko.cp.meta.services.sparql.index.{Exists => _, _}
import se.lu.nateko.cp.meta.services.sparql.magic.fusion.StatsFetchPatternSearch.GroupPattern
import se.lu.nateko.cp.meta.utils.rdf4j._

import DofPatternFusion._

sealed trait FusionPattern
case class DobjStatFusion(exprToFuse: Extension, node: StatsFetchNode) extends FusionPattern

case class DobjListFusion(
	fetch: DataObjectFetch,
	exprsToFuse: Seq[TupleExpr],
	propVars: Map[NamedVar, Property],
	nonMagicQMNodes: Seq[QueryModelNode]
) extends FusionPattern{
	def essentiallyEqual(other: DobjListFusion): Boolean =
		this.fetch == other.fetch &&
		this.propVars == other.propVars &&
		this.nonMagicNodeIds == other.nonMagicNodeIds

	def isPureCpIndexQuery: Boolean = nonMagicQMNodes.isEmpty
	def nonMagicNodeIds = nonMagicQMNodes.map(System.identityHashCode).toSet
}

class DofPatternFusion(meta: CpmetaVocab){

	def findFusions(patt: DofPattern): Seq[FusionPattern] = patt match{
		case DofPattern.Empty => Nil

		case pdp @ ProjectionDofPattern(_, _, _, _, Some(outer)) =>
			findFusions(pdp.copy(outer = None)) ++ findFusions(outer)

		case pdp @ ProjectionDofPattern(lj: LeftJoinDofPattern, _, Some(groupBy), _, _) =>
			findStatsFusion(groupBy, lj).fold(findFusions(pdp.copy(groupBy = None)))(Seq(_))

		case pdp: ProjectionDofPattern => findFusions(pdp.inner) match{
			case Seq(singleResult: DobjListFusion) => Seq(addOrderByAndOffset(pdp, singleResult))
			case any => any
		}

		case lj: LeftJoinDofPattern => findFusions(lj.left) ++ lj.optionals.flatMap(findFusions)

		case union: DofPatternUnion =>
			val subSeqs = union.subs.map(findFusions)
			val subs = subSeqs.flatten.collect{case dlf: DobjListFusion => dlf}

			def allMergable: Boolean = subs.distinctBy{sub =>
				val nonMagicNodeIds = sub.nonMagicQMNodes.map(System.identityHashCode).toSet
				(sub.fetch.sort, sub.fetch.offset, nonMagicNodeIds)
			}.size == 1

			val oneListFusionPerSubPatt: Boolean = subSeqs.forall(_.size == 1) && subs.size == subSeqs.size

			if(oneListFusionPerSubPatt && allMergable){
				val newExprsToFuse = subs.flatMap(_.exprsToFuse).distinctBy(System.identityHashCode) :+ union.union
				val allSame = subs.sliding(2,1).forall(s => s(0) essentiallyEqual s(1))
				if(allSame)
					Seq(subs.head.copy(exprsToFuse = newExprsToFuse))
				else {
					unionVarProps(subs.map(_.propVars)).fold(Seq.empty[FusionPattern]){propVars =>
						val sampleFetch = subs.head.fetch
						Seq(DobjListFusion(
							fetch = DataObjectFetch(Or(subs.map(_.fetch.filter)), sampleFetch.sort, sampleFetch.offset),
							exprsToFuse = newExprsToFuse,
							propVars = propVars,
							nonMagicQMNodes = subs.head.nonMagicQMNodes
						))
					}
				}
			}
			else subSeqs.flatten

		case plain: PlainDofPattern => findPlainFusion(plain).toSeq

	}

	def addOrderByAndOffset(pdp: ProjectionDofPattern, inner: DobjListFusion): DobjListFusion = {
		val sortBy = pdp.orderBy.map(op => op -> inner.propVars.get(op.sortVar)).collect{
			case (op, Some(cp: ContProp)) => SortBy(cp, op.descending)
		}

		val offset = pdp.offset.filter(_ => inner.isPureCpIndexQuery)

		val exprs = inner.exprsToFuse ++ sortBy.flatMap(_ => pdp.orderBy.map(_.expr)) ++ offset.map(_.slice)
		inner.copy(
			fetch = inner.fetch.copy(sort = sortBy, offset = offset.fold(0)(_.offset)),
			exprsToFuse = exprs
		)
	}

	def findPlainFusion(patt: PlainDofPattern): Option[DobjListFusion] = patt.dobjVar.collect{
		//if dobj is pre-specified, then there is no need for SPARQL magic
		case dobjVar if(patt.varValues.get(dobjVar).flatMap(_.vals).isEmpty) =>

			val varProps = getVarPropLookup(patt)

			val andOrFilterParser = new FilterPatternSearch(varProps, meta)

			val filtsAndExprs = patt.filters.flatMap{fexp =>
				andOrFilterParser.parseFilterExpr(fexp).map(_ -> fexp.getParentNode)
			}
			val filts = filtsAndExprs.map(_._1)
			val filtExprs = filtsAndExprs.collect{case (_, te: TupleExpr) => te}

			val categFiltsAndExprs = varProps.toSeq.flatMap{
				case (v, prop) => getCategFilter(v, prop, patt.varValues)
			}

			val categFilts: Seq[Filter] = categFiltsAndExprs.map(_._1)
			val categExprs = categFiltsAndExprs.flatMap(_._2)
			val reqProps = varProps.valuesIterator.collect{
					case cp: ContProp => cp
					case optp: OptUriProperty => optp
				}.distinct.toSeq
			val allFilts = And(categFilts ++ filts ++ reqProps.map(index.Exists(_)))

			val namedVarProps = varProps.collect{
				case (nv: NamedVar, prop) => nv -> prop
			}

			val engagedVars = namedVarProps.keySet.toSet[QVar]

			val statPattExprs = patt.propPaths.values.flatten.collect{
				//filenames are not in the index, need to leave this pattern in the query
				case sp2 @ StatementPattern2(pred, sp) if pred != meta.hasName && engagedVars.contains(sp2.targetVar) => sp
			}
			val assignmentExprs = patt.varValues.collect{
				case (v, vif) if varProps.contains(v) => vif.providers
			}.flatten

			val allExprs = filtExprs ++ categExprs ++ statPattExprs ++ assignmentExprs

			val nonMagicFilterExprs = patt.filters.map(_.getParentNode).filter(f => !filtExprs.contains(f))
			val nonMagicStatPatts = patt.propPaths.flatMap(_._2).filterNot{sp2 =>
				val objVar = sp2.sp.getObjectVar
				varProps.contains(sp2.targetVar) || (objVar.isAnonymous && !objVar.hasValue)
			}.map(_.sp)
			val nonMagicQMNodes = nonMagicFilterExprs ++ nonMagicStatPatts

			DobjListFusion(DataObjectFetch(allFilts.flatten, None, 0), allExprs, namedVarProps, nonMagicQMNodes)
	}

	def getVarPropLookup(patt: PlainDofPattern): VarPropLookup = {

		def endVar(steps: IRI*): Iterable[QVar] = steps.reverse.toList match{
			case Nil => patt.dobjVar
			case head :: tail => for(
				prev <- endVar(tail:_*);
				statPatts <- patt.propPaths.get(prev).toIterable;
				statPat <- statPatts.filter(_.pred === head)
			) yield statPat.targetVar
		}

		def propVar(prop: Property, steps: IRI*) = endVar(steps:_*).map(_ -> prop)
		//TODO This approach disregards the possibility of duplicate entries (all but one get discarded)
		Seq(
			propVar(DobjUri),
			propVar(Spec           , meta.hasObjectSpec ),
			propVar(VariableName   , meta.hasVariableName),
			propVar(Keyword        , meta.hasKeyword    ),
			propVar(FileName       , meta.hasName       ),
			propVar(FileSize       , meta.hasSizeInBytes),
			propVar(Submitter      , meta.wasSubmittedBy , meta.prov.wasAssociatedWith),
			propVar(SubmissionStart, meta.wasSubmittedBy , meta.prov.startedAtTime    ),
			propVar(SubmissionEnd  , meta.wasSubmittedBy , meta.prov.endedAtTime      ),
			propVar(Station        , meta.wasAcquiredBy  , meta.prov.wasAssociatedWith),
			propVar(Site           , meta.wasAcquiredBy  , meta.wasPerformedAt        ),
			propVar(DataStart      , meta.hasStartTime  ),
			propVar(DataStart      , meta.wasAcquiredBy  , meta.prov.startedAtTime    ),
			propVar(DataEnd        , meta.hasEndTime    ),
			propVar(DataEnd        , meta.wasAcquiredBy  , meta.prov.endedAtTime      ),
			propVar(SamplingHeight , meta.wasAcquiredBy  , meta.hasSamplingHeight     ),
		).flatten.toMap
	}

	def findStatsFusion(groupBy: StatGroupByPattern, inner: LeftJoinDofPattern): Option[DobjStatFusion] = findFusions(inner.left) match{
		case Seq(DobjListFusion(DataObjectFetch(filter, None, 0), _, propVars, nonMagics))
			if nonMagics.isEmpty && propVars.get(NamedVar(groupBy.dobjVar)).contains(DobjUri) =>

			val optionals = inner.optionals.collect{
				case pdp @ PlainDofPattern(None, _, _, Nil) =>
					findPlainFusion(pdp.copy(dobjVar = Some(NamedVar(groupBy.dobjVar))))
			}.flatten

			if(optionals.size != inner.optionals.size || optionals.isEmpty) None else {

				val lookup = (propVars ++ optionals.flatMap(_.propVars)).map(_.swap)

				for(
					specVar <- lookup.get(Spec);
					submVar <- lookup.get(Submitter);
					stationVar <- lookup.get(Station);
					siteVarOpt = lookup.get(Site);
					if (Seq(specVar, submVar, stationVar) ++ siteVarOpt).map(_.name).toSet == groupBy.groupVars
				) yield{
					val gp = GroupPattern(filter, submVar.name, stationVar.name, specVar.name, siteVarOpt.map(_.name))
					val node = new StatsFetchNode(groupBy.countVar, gp)
					DobjStatFusion(groupBy.expr, node)
				}
			}
		case _ => None
	}

}

object DofPatternFusion{
	type PlainFusionRes = (Filter, Set[TupleExpr])
	type VarPropLookup = Map[QVar, Property]
	type NamedVarPropLookup = Map[NamedVar, Property]

	def unionVarProps(varProps: Seq[NamedVarPropLookup]): Option[NamedVarPropLookup] = varProps match{
		case Nil => Some(Map.empty[NamedVar, Property])
		case Seq(single) => Some(single)
		case Seq(vp1, rest @ _*) => unionVarProps(rest).flatMap{vp2 =>
			val keys = vp1.keySet.intersect(vp2.keySet)
			if(keys.forall(v => vp1(v) eq vp2(v))) Some(
				vp1.filter{
					case (v, _) => keys.contains(v)
				}
			) else None
		}
	}

	def getCategFilter(v: QVar, prop: Property, vvals: Map[QVar, ValueInfoPattern]): Option[(Filter, Set[TupleExpr])] = prop match{
		case cp: CategProp =>

			val valsExprsOpt: Option[(Seq[Value], Set[TupleExpr])] = vvals.get(v).flatMap{vip =>

				val irisOpt = vip.vals.map(_.toSeq).filter(!_.isEmpty)
				irisOpt.map(_ -> vip.providers.toSet)
			}

			valsExprsOpt.map{
				case (vals, exprs) =>
					val iris = vals.collect{case iri: IRI => iri}
					val filter: Filter = cp match{
						case uriProp: UriProperty => CategFilter(uriProp, iris)
						case optUri: OptUriProperty => CategFilter(optUri, iris.map(Some(_)))
						case strProp: StringCategProp => CategFilter(
							strProp,
							vals.collect{case lit: Literal => asString(lit)}.flatten
						)
					}
					filter -> exprs
			}

		case _ => None
	}
}
