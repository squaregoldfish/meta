package se.lu.nateko.cp.meta.services.sparql.magic.fusion

import se.lu.nateko.cp.meta.services.CpmetaVocab
import se.lu.nateko.cp.meta.services.sparql.index._
import se.lu.nateko.cp.meta.utils.rdf4j._

import DofPatternFusion._

import org.eclipse.rdf4j.model.IRI
import org.eclipse.rdf4j.query.algebra.Exists
import org.eclipse.rdf4j.query.algebra.Not
import org.eclipse.rdf4j.query.algebra.StatementPattern
import org.eclipse.rdf4j.query.algebra.TupleExpr
import org.eclipse.rdf4j.query.algebra.ValueExpr
import org.eclipse.rdf4j.query.algebra.Union
import org.eclipse.rdf4j.query.algebra.SingletonSet
import org.eclipse.rdf4j.query.algebra.BindingSetAssignment

case class FusionResult(
	fetch: DataObjectFetch,
	exprsToFuse: Seq[TupleExpr],
	propVars: Map[NamedVar, Property],
	isPureCpIndexQuery: Boolean
){
	def essentials = copy(exprsToFuse = Nil)
	def essentiallyEqual(other: FusionResult): Boolean = this.essentials == other.essentials
}

class DofPatternFusion(meta: CpmetaVocab){

	def findFusions(patt: DofPattern): Seq[FusionResult] = patt match{
		case DofPattern.Empty => Nil

		case pdp @ ProjectionDofPattern(_, _, _, Some(outer)) =>
			findFusions(pdp.copy(outer = None)) ++ findFusions(outer)

		case pdp: ProjectionDofPattern => findFusions(pdp.inner) match{
			case Seq(singleResult) => Seq(addOrderByAndOffset(pdp, singleResult))
			case any => any
		}

		case list: DofPatternList => list.subs.flatMap(findFusions)

		case union: DofPatternUnion =>
			val subSeqs = union.subs.map(findFusions)
			val subs = subSeqs.flatten

			val isValid = !subs.isEmpty && subSeqs.forall(_.size == 1) && subs.forall(
				dof => dof.fetch.sort.isEmpty && dof.fetch.offset == 0
			)

			if(!isValid) Nil else{
				val newExprsToFuse = subs.flatMap(_.exprsToFuse) :+ union.union
				val allSame = if(subs.length < 2) true else subs.sliding(2,1).forall(s => s(0) essentiallyEqual s(1))

				if(allSame){
					Seq(subs.head.copy(exprsToFuse = newExprsToFuse))
				} else unionVarProps(subs.map(_.propVars)).map{propVars =>
					FusionResult(
						fetch = DataObjectFetch(Or(subs.map(_.fetch.filter)), None, 0),
						exprsToFuse = newExprsToFuse,
						propVars = propVars,
						isPureCpIndexQuery = subs.forall(_.isPureCpIndexQuery)
					)
				}.toSeq
			}

		case plain: PlainDofPattern => findPlainFusion(plain).toSeq

	}

	def addOrderByAndOffset(pdp: ProjectionDofPattern, inner: FusionResult): FusionResult = if(!inner.isPureCpIndexQuery) inner else{
		val sortBy = pdp.orderBy.map(op => op -> inner.propVars.get(op.sortVar)).collect{
			case (op, Some(cp: ContProp)) => SortBy(cp, op.descending)
		}
		val exprs = inner.exprsToFuse ++ sortBy.flatMap(_ => pdp.orderBy.map(_.expr)) ++ pdp.offset.map(_.slice)
		inner.copy(
			fetch = inner.fetch.copy(sort = sortBy, offset = pdp.offset.fold(0)(_.offset)),
			exprsToFuse = exprs
		)
	}

	def findPlainFusion(patt: PlainDofPattern): Option[FusionResult] = patt.dobjVar.collect{
		//if dobj is pre-specified, then there is no need for SPARQL magic
		case dobjVar if(patt.varValues.get(dobjVar).flatMap(_.vals).isEmpty) =>

			val varProps = getVarPropLookup(patt)

			val andOrFilterParser = new FilterPatternSearch(v => varProps.get(NamedVar(v)).collect{case cp: ContProp => cp})

			val filtsAndExprs = patt.filters.flatMap{fexp =>
				parseFilter(fexp, varProps)
					.orElse(andOrFilterParser.parseFilterExpr(fexp))
					.map(_ -> fexp.getParentNode)
			}
			val filts = filtsAndExprs.map(_._1)
			val filtExprs = filtsAndExprs.collect{case (_, te: TupleExpr) => te}

			val categFiltsAndExprs = varProps.toSeq.flatMap{
				case (v, prop) => getCategFilter(v, prop, patt.varValues)
			}

			val categFilts: Seq[Filter] = categFiltsAndExprs.map(_._1)
			val categExprs = categFiltsAndExprs.flatMap(_._2)
			val allFilts = And(categFilts ++ filts)

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

			val allRecognized = patt.propPaths.flatMap(_._2).forall{sp2 =>
				val objVar = sp2.sp.getObjectVar
				varProps.contains(sp2.targetVar) || (objVar.isAnonymous && !objVar.hasValue)
			}

			FusionResult(DataObjectFetch(allFilts.flatten, None, 0), allExprs, namedVarProps, allRecognized)
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

	def parseFilter(expr: ValueExpr, parProps: VarPropLookup): Option[Filter] = expr match{
		case not: Not => not.getArg match{
			case exists: Exists => exists.getSubQuery match{
				case sp: StatementPattern =>
					val (s, p, o) = splitTriple(sp)
					if(meta.isNextVersionOf == p.getValue && s.isAnonymous && !s.hasValue && !o.isAnonymous && parProps.get(QVar(o)) == Some(DobjUri))
						Some(FilterDeprecated)
					else
						None
				case _ => None
			}
			case _ => None
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

			val valsExprsOpt: Option[(Seq[IRI], Set[TupleExpr])] = vvals.get(v).map{vip =>
				//TODO Change to explicit difference between unspecified values and an empty set of allowed values
				val iris = vip.vals.map(_.toSeq.collect{case iri: IRI => iri}).getOrElse(Nil)
				iris -> vip.providers.toSet
			}.orElse(v match{
				case _: NamedVar => Some(Nil -> Set.empty)
				case _ => None
			})

			valsExprsOpt.map{
				case (vals, exprs) =>
					val filter: Filter = cp match{
						case uriProp: UriProperty => CategFilter(uriProp, vals)
						case optUri: OptUriProperty => CategFilter(optUri, vals.map(Some(_)))
					}
					filter -> exprs
			}

		case _ => None
	}

}
