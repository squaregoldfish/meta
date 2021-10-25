package se.lu.nateko.cp.meta.services.sparql.magic.fusion

import se.lu.nateko.cp.meta.services.sparql.index.DobjUri
import org.eclipse.rdf4j.query.algebra.SingletonSet
import org.eclipse.rdf4j.query.algebra.BinaryTupleOperator
import org.eclipse.rdf4j.query.algebra.TupleExpr
import org.eclipse.rdf4j.query.algebra.Slice
import org.eclipse.rdf4j.query.algebra.Order
import org.eclipse.rdf4j.query.algebra.Group
import org.eclipse.rdf4j.query.algebra.Filter
import org.eclipse.rdf4j.query.algebra.UnaryTupleOperator
import se.lu.nateko.cp.meta.services.sparql.index.FileName
import org.eclipse.rdf4j.query.algebra.BindingSetAssignment

object DofPatternRewrite{

	def rewrite(queryTop: TupleExpr, fusions: Seq[FusionPattern]): Unit = fusions.foreach{
		case dlf: DobjListFusion => rewriteForDobjListFetches(queryTop, dlf)
		case DobjStatFusion(expr, statsNode) =>
			expr.getArg.replaceWith(statsNode)
			expr.getElements.removeIf(elem => StatsFetchPatternSearch.singleVarCount(elem.getExpr).isDefined)
	}

	def rewriteForDobjListFetches(queryTop: TupleExpr, fusion: DobjListFusion): Unit = if(!fusion.exprsToFuse.isEmpty){
		import fusion.{exprsToFuse => exprs}

		val subsumingParents = exprs.collect{case bto: BinaryTupleOperator => bto}

		val independentChildren = exprs.filter{expr =>
			!subsumingParents.exists(parent => parent != expr && parent.isAncestorOf(expr)) &&
			expr.getParentNode != null
		}

		val deepest = independentChildren.toSeq.sortBy(weightedNodeDepth).last

		exprs.filter(_ ne deepest).foreach(replaceNode)

		//excluding FileName property, as it is supposed to be bound by vanilla SPARQL execution
		val propVars = fusion.propVars.collect{case (qvar, prop) if prop != FileName => (prop, qvar.name)}
		val fetchExpr = new DataObjectFetchNode(fusion.fetch, propVars)

		safelyReplace(deepest, fetchExpr)

		DanglingCleanup.clean(queryTop)

	}

	def replaceNode(node: TupleExpr): Unit = node match{
		case slice: Slice => slice.setOffset(0)
		case o: Order => replaceUnaryOp(o)
		case g: Group => replaceUnaryOp(g)
		case f: Filter => replaceUnaryOp(f)

		case _: UnaryTupleOperator =>

		case _ =>
			safelyReplace(node, new SingletonSet)
	}

	private def replaceUnaryOp(op: UnaryTupleOperator): Unit = safelyReplace(op, op.getArg)

	def safelyReplace(expr: TupleExpr, replacement: TupleExpr): Unit = {
		val parent = expr.getParentNode
		if(parent != null){
			parent.replaceChildNode(expr, replacement)
			expr.setParentNode(null)
		}
	}
}
