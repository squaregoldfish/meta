package se.lu.nateko.cp.meta.services.sparql.magic.fusion

import org.eclipse.rdf4j.query.algebra._
import org.eclipse.rdf4j.model.IRI

object StatementPatternSearch{
	import PatternFinder._

	def byPredicate(predValue: IRI): TopNodeSearch[StatementPattern] = takeNode
		.ifIs[StatementPattern]
		.filter(sp => predValue == sp.getPredicateVar.getValue)
		.recursive

	def twoStepPropPath(pred1: IRI, pred2: IRI): TopNodeSearch[TwoStepPropPath] =  node => {
		def isPropPath(sp1: StatementPattern, sp2: StatementPattern): Boolean = {
			val (s1, _, o1) = splitTriple(sp1)
			val (s2, _, o2) = splitTriple(sp2)
			!s1.isAnonymous && o1.isAnonymous && s2.isAnonymous && o1.getName == s2.getName && !o2.isAnonymous &&
			areWithinCommonJoin(Seq(sp1, sp2)) && (
				areSiblings(sp1, sp2) || sp1.isUncleOf(sp2) || sp2.isUncleOf(sp1)
			)
		}
		for(
			sp1 <- byPredicate(pred1)(node);
			sp2 <- byPredicate(pred2)(node)
			if isPropPath(sp1, sp2)
		) yield new TwoStepPropPath(sp1, sp2)
	}

	class TwoStepPropPath(val step1: StatementPattern, val step2: StatementPattern){
		def subjVariable: String = step1.getSubjectVar.getName
		def objVariable: String = step2.getObjectVar.getName
	}
}

