package se.lu.nateko.cp.meta.services.sparql.magic.fusion

import org.eclipse.rdf4j.query.algebra._
import org.eclipse.rdf4j.query.algebra.helpers.AbstractQueryModelVisitor

import scala.reflect.ClassTag

object PatternFinder{

	type QMN = QueryModelNode
	type NodeSearch[-I <: QMN, +O] = I => Option[O]
	type TopNodeSearch[+O] = NodeSearch[QMN, O]

	def takeNode[T <: QMN]: NodeSearch[T, T] = Some.apply

	implicit class TopNodeSearchOps[O](val test: TopNodeSearch[O]) extends AnyVal{
		def recursive: TopNodeSearch[O] = node => {
			val finder = new Visitor(test)
			node.visit(finder)
			finder.result
		}
	}

	implicit class NodeSearchOps[I <: QMN, O](val test: NodeSearch[I, O]) extends AnyVal{

		def thenSearch[O2](other: O => Option[O2]): NodeSearch[I, O2] = node => test(node).flatMap(other)

		def thenAlsoSearch[O2](other: O => Option[O2]): NodeSearch[I, (O, O2)] = node => {
			for(o <- test(node); o2 <- other(o)) yield o -> o2
		}

		def thenGet[O2](f: O => O2): NodeSearch[I, O2] = test.andThen(_.map(f).filter(_ != null))

		def ifIs[O2 : ClassTag]: NodeSearch[I, O2] = thenSearch{
			case t: O2 => Some(t)
			case _ => None
		}

		def ifFound[O2](other: O => Option[O2]): NodeSearch[I, O] = node => test(node).filter(t => other(t).isDefined)
	}

	private class Visitor[T](test: TopNodeSearch[T]) extends AbstractQueryModelVisitor{

		var result: Option[T] = None

		override def meetNode(node: QueryModelNode): Unit = {
			val res = test(node)
			if(res.isDefined) result = res
			else node.visitChildren(this)
		}
	}
}
