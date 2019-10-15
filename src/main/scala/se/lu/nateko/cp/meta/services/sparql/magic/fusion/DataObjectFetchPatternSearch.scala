package se.lu.nateko.cp.meta.services.sparql.magic.fusion

import org.eclipse.rdf4j.query.algebra._

import se.lu.nateko.cp.meta.services.CpmetaVocab
import org.eclipse.rdf4j.model.IRI
import DataObjectFetchPattern._
import PatternFinder._
import se.lu.nateko.cp.meta.services.sparql.index.DataObjectFetch.{Filter => _, _}

class DataObjectFetchPatternSearch(meta: CpmetaVocab){
	import StatementPatternSearch._
	import DataObjectFetchPatternSearch._

	// FILTER NOT EXISTS {[] [] []}
	val notExistsSingleStatementPattern: NodeSearch[Filter, StatementPattern] = takeNode[Filter]
		.thenGet(_.getCondition)
		.ifIs[Not]
		.thenGet(_.getArg)
		.ifIs[Exists]
		.thenGet(_.getSubQuery)
		.ifIs[StatementPattern]

	// [] cpmeta:isNextVersionOf ?dobj
	val deprecatedDobjVarName: NodeSearch[StatementPattern, String] = sp => {
		val (s, p, o) = splitTriple(sp)
		if(meta.isNextVersionOf == p.getValue && s.isAnonymous && !s.hasValue && !o.isAnonymous)
			Some(o.getName)
		else
			None
	}

	// FILTER NOT EXISTS {[] cpmeta:isNextVersionOf ?dobj}
	val isLatestDobjVersionFilter: TopNodeSearch[ExcludeDeprecatedPattern] = takeNode
		.ifIs[Filter]
		.thenAlsoSearch(
			notExistsSingleStatementPattern.thenSearch(deprecatedDobjVarName)
		)
		.thenGet{
			case (filter, varName) => new ExcludeDeprecatedPattern(filter, varName)
		}
		.recursive

	// ?dobj cpmeta:hasObjectSpec ?spec . VALUES ?spec {<iri1> ... <irin>}
	val dataObjSpecPatternSearch: CategPatternSearch = StatementPatternSearch
		.byPredicate(meta.hasObjectSpec).thenSearch(nonAnonymous).recursive
		.thenFlatMap(nvp => BindingSetAssignmentSearch.byVarName(nvp.objVar).recursive)
		.thenGet{
			case (nvp, bsas) =>
				val exprs = Seq(nvp.sp, bsas.expr)
				nvp.subjVar -> categPattern(exprs, Spec, nvp.objVar, bsas.values)
		}

	// ?dobj cpmeta:wasAcquiredBy/prov:wasAssociatedWith ?station . VALUES ?station {<iri1> ... <irin>}
	val stationPatternSearch: CategPatternSearch = twoStepPropPath(meta.wasAcquiredBy, meta.prov.wasAssociatedWith)
		.thenFlatMap{tspp =>
			BindingSetAssignmentSearch.byVarName(tspp.objVariable).recursive
		}
		.thenGet{
			case (tspp, bsas) =>
				val exprs = Seq(tspp.step1, tspp.step2, bsas.expr)
				tspp.subjVariable -> categPattern(exprs, Station, tspp.objVariable, bsas.values.map(Some(_)))
		}

	// ?dobj cpmeta:wasSubmittedBy/prov:wasAssociatedWith ?submitter . VALUES ?submitter {<iri1> ... <irin>}
	val submitterPatternSearch: CategPatternSearch = twoStepPropPath(meta.wasSubmittedBy, meta.prov.wasAssociatedWith)
		.thenFlatMap{tspp =>
			BindingSetAssignmentSearch.byVarName(tspp.objVariable).recursive
		}
		.thenGet{
			case (tspp, bsas) =>
				val exprs = Seq(tspp.step1, tspp.step2, bsas.expr)
				tspp.subjVariable -> categPattern(exprs, Submitter, tspp.objVariable, bsas.values)
		}

	//	?dobj cpmeta:wasSubmittedBy/prov:startedAtTime ?submTime .
	val submStartPatternSearch: ContPatternSearch = twoStepPropPath(meta.wasSubmittedBy, meta.prov.startedAtTime)
		.thenGet{tspp =>
			val exprs = Seq(tspp.step1, tspp.step2)
			tspp.subjVariable -> new ContPropPattern(exprs, SubmissionStart, tspp.objVariable)
		}

	//	?dobj cpmeta:wasSubmittedBy/prov:endedAtTime ?submTime .
	val submEndPatternSearch: ContPatternSearch = twoStepPropPath(meta.wasSubmittedBy, meta.prov.endedAtTime)
		.thenGet{tspp =>
			val exprs = Seq(tspp.step1, tspp.step2)
			tspp.subjVariable -> new ContPropPattern(exprs, SubmissionEnd, tspp.objVariable)
		}

	val tempCoverage = new TempCoveragePatternSearch(meta)

	// ?dobj cpmeta:wasSubmittedBy/prov:startedAtTime ?submStartTime .
	val dataStartSearch: ContPatternSearch = tempCoverage.startTimePattern
		.thenGet{tcp =>
			tcp.dobjVar -> new ContPropPattern(Seq(tcp.expr), DataStart, tcp.timeVar)
		}

	// ?dobj cpmeta:wasSubmittedBy/prov:endedAtTime ?submEndTime .
	val dataEndSearch: ContPatternSearch = tempCoverage.endTimePattern
		.thenGet{tcp =>
			tcp.dobjVar -> new ContPropPattern(Seq(tcp.expr), DataEnd, tcp.timeVar)
		}

	private def simpleContPattSearch(pred: IRI, prop: ContProp): ContPatternSearch =
		StatementPatternSearch.byPredicate(pred)
			.thenSearch(nonAnonymous)
			.thenGet(sp => sp.subjVar -> new ContPropPattern(Seq(sp.sp), prop, sp.objVar))
			.recursive

	val fileNameSearch = simpleContPattSearch(meta.hasName, FileName)
	val fileSizeSearch = simpleContPattSearch(meta.hasSizeInBytes, FileSize)

	val offsetSearch: TopNodeSearch[OffsetPattern] = takeNode
		.ifIs[Slice]
		.thenGet(new OffsetPattern(_))
		.filter(_.offset > 0)

	val search: TopNodeSearch[DataObjectFetchPattern] = node => {

		val noDeprecatedOpt = isLatestDobjVersionFilter(node)

		val categPatts = dataObjSpecPatternSearch(node).toSeq ++ stationPatternSearch(node) ++ submitterPatternSearch(node)

		val contPatts = submStartPatternSearch(node).toSeq ++ submEndPatternSearch(node) ++
			dataStartSearch(node) ++ dataEndSearch(node) ++
			fileNameSearch(node) ++ fileSizeSearch(node)

		val filterSearcher = {
			val contPropLookup: Map[String, ContProp] = contPatts.map(_._2).map(cpp => cpp.propVarName -> cpp.property).toMap
			new FilterPatternSearch(contPropLookup.get)
		}

		val dobjVarNames = categPatts.map(_._1) ++ contPatts.map(_._1) ++ noDeprecatedOpt.map(_.dobjVar)
		//detecting the most common variable name for data obj uri (for example, could be '?dobj')
		val dobjVarNameOpt = dobjVarNames.groupBy(identity).mapValues(_.size).toSeq.sortBy(_._2).lastOption.map(_._1)

		dobjVarNameOpt
			.map{dobjVar =>
				new DataObjectFetchPattern(
					dobjVar,
					categPatts.collect{ case (`dobjVar`, cp) => cp },
					contPatts.collect { case (`dobjVar`, cp) => cp },
					filterSearcher.search(node),
					noDeprecatedOpt,
					OrderPatternSearch.search.recursive(node),
					offsetSearch(node)
				)
			}
			.filter{res =>
				val patternsToBeInCommonJoin = res.allPatterns.filter{
					case _: OrderPattern | _: OffsetPattern | _: FilterPattern => false
					case _ => true
				}
				patternsToBeInCommonJoin.length > 1 && areWithinCommonJoin(
					patternsToBeInCommonJoin.flatMap(_.expressions)
				)
			}
	}
}

object DataObjectFetchPatternSearch{
	type CategPatternSearch = TopNodeSearch[(String, CategPropPattern)]
	type ContPatternSearch = TopNodeSearch[(String, ContPropPattern)]
}
