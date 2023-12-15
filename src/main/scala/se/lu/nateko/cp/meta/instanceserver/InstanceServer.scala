package se.lu.nateko.cp.meta.instanceserver

import org.eclipse.rdf4j.model.*
import org.eclipse.rdf4j.model.vocabulary.RDF
import org.eclipse.rdf4j.model.vocabulary.RDFS
import org.eclipse.rdf4j.model.vocabulary.XSD
import se.lu.nateko.cp.meta.api.CloseableIterator
import se.lu.nateko.cp.meta.api.CloseableIterator.empty
import se.lu.nateko.cp.meta.api.SparqlRunner
import se.lu.nateko.cp.meta.core.crypto.Sha256Sum
import se.lu.nateko.cp.meta.core.data.UriResource
import se.lu.nateko.cp.meta.utils.Validated
import se.lu.nateko.cp.meta.utils.Validated.CardinalityExpectation
import se.lu.nateko.cp.meta.utils.Validated.validateSize
import se.lu.nateko.cp.meta.utils.parseInstant
import se.lu.nateko.cp.meta.utils.rdf4j.===
import se.lu.nateko.cp.meta.utils.rdf4j.toJava

import java.net.{URI => JavaUri}
import java.time.Instant
import java.time.LocalDate
import scala.util.Try

trait InstanceServer extends AutoCloseable{
	import InstanceServer.*

	/**
	 * Makes a new IRI for the new instance, but does not add any triples to the repository.
	 * @param prefix The prefix to start the new IRI with
	 */
	def makeNewInstance(prefix: IRI): IRI
	def readContexts: Seq[IRI]
	def writeContext: IRI
	def factory: ValueFactory

	def getStatements(subject: Option[IRI], predicate: Option[IRI], obj: Option[Value]): CloseableIterator[Statement]
	def hasStatement(subject: Option[IRI], predicate: Option[IRI], obj: Option[Value]): Boolean
	def applyAll(updates: Seq[RdfUpdate])(cotransact: => Unit = ()): Try[Unit]
	def withContexts(read: Seq[IRI], write: IRI): InstanceServer
	def getConnection(): TriplestoreConnection
	final def access[T](read: TriplestoreConnection ?=> T): T =
		given conn: TriplestoreConnection = getConnection()
		try read finally conn.close()

	def shutDown(): Unit

	final def filterNotContainedStatements(statements: IterableOnce[Statement]): IndexedSeq[Statement] = access: conn ?=>
		statements.iterator.filterNot(conn.hasStatement).toIndexedSeq

	final def writeContextsView: InstanceServer = withContexts(Seq(writeContext), writeContext)

	final def addAll(statements: Seq[Statement]): Try[Unit] = applyAll(statements.map(RdfUpdate(_, true)))()
	final def removeAll(statements: Seq[Statement]): Try[Unit] = applyAll(statements.map(RdfUpdate(_, false)))()

	final override def close(): Unit = shutDown()

	final def getInstances(classUri: IRI): IndexedSeq[IRI] =
		getStatements(None, Some(RDF.TYPE), Some(classUri))
			.map(_.getSubject)
			.collect{case uri: IRI => uri}
			.toIndexedSeq

	final def getStatements(instUri: IRI): IndexedSeq[Statement] = getStatements(Some(instUri), None, None).toIndexedSeq

	final def getValues(instUri: IRI, propUri: IRI): IndexedSeq[Value] =
		getStatements(Some(instUri), Some(propUri), None)
			.map(_.getObject)
			.toIndexedSeq

	final def add(statements: Statement*): Try[Unit] = addAll(statements)
	final def remove(statements: Statement*): Try[Unit] = removeAll(statements)

	final def addInstance(instUri: IRI, classUri: IRI): Try[Unit] =
		add(factory.createStatement(instUri, RDF.TYPE, classUri))

	final def removeInstance(instUri: IRI): Unit = removeAll(getStatements(instUri))

	final def addPropertyValue(instUri: IRI, propUri: IRI, value: Value): Try[Unit] =
		add(factory.createStatement(instUri, propUri, value))

	final def removePropertyValue(instUri: IRI, propUri: IRI, value: Value): Try[Unit] =
		remove(factory.createStatement(instUri, propUri, value))

	final def applyDiff(from: Seq[Statement], to: Seq[Statement]): Unit = {
		val toRemove = from.diff(to)
		val toAdd = to.diff(from)

		applyAll(toRemove.map(RdfUpdate(_, false)) ++ toAdd.map(RdfUpdate(_, true)))()
	}

	final def getUriValues(subj: IRI, pred: IRI, exp: CardinalityExpectation = Default): IndexedSeq[IRI] = {
		val values = getValues(subj, pred).collect{case uri: IRI => uri}.distinct
		assertCardinality(values.size, exp, s"IRI value(s) of $pred for $subj")
		values
	}

	final def getTypes(res: IRI): IndexedSeq[IRI] = getValues(res, RDF.TYPE).collect{
		case classUri: IRI => classUri
	}

	final def getLiteralValues(subj: IRI, pred: IRI, dType: IRI, exp: CardinalityExpectation = Default): IndexedSeq[String] = {
		val values = getValues(subj, pred).collect{
			case lit: Literal if(lit.getDatatype === dType) => lit.stringValue
		}.distinct
		assertCardinality(values.size, exp, s"${dType.getLocalName} value(s) of $pred for $subj")
		values
	}

	final def getStringValues(subj: IRI, pred: IRI, exp: CardinalityExpectation = Default): IndexedSeq[String] =
		getLiteralValues(subj, pred, XSD.STRING, exp)

	final def getIntValues(subj: IRI, pred: IRI, exp: CardinalityExpectation = Default): IndexedSeq[Int] =
		getLiteralValues(subj, pred, XSD.INTEGER, exp).map(_.toInt)

	final def getUriLiteralValues(subj: IRI, pred: IRI, exp: CardinalityExpectation = Default): IndexedSeq[JavaUri] =
		getLiteralValues(subj, pred, XSD.ANYURI, exp).map(new JavaUri(_))
}

object InstanceServer:

	export CardinalityExpectation.{AtMostOne, AtLeastOne, ExactlyOne, Default}

	private def assertCardinality(actual: Int, expectation: CardinalityExpectation, errorTip: => String): Unit =
		expectation match
			case Default => ()
			case AtMostOne => assert(actual <= 1, s"Expected at most one $errorTip, but got $actual")
			case AtLeastOne => assert(actual >= 1, s"Expected at least one $errorTip, but got $actual")
			case ExactlyOne => assert(actual == 1, s"Expected exactly one $errorTip, but got $actual")


trait TriplestoreConnection extends SparqlRunner with AutoCloseable:
	def primaryContext: IRI
	def readContexts: Seq[IRI]
	def factory: ValueFactory

	def getStatements(subject: Option[IRI], predicate: Option[IRI], obj: Option[Value]): CloseableIterator[Statement]
	def hasStatement(subject: Option[IRI], predicate: Option[IRI], obj: Option[Value]): Boolean
	def hasStatement(subject: IRI | Null, predicate: IRI | Null, obj: Value | Null): Boolean
	def withContexts(primary: IRI, read: Seq[IRI]): TriplestoreConnection

	final def withReadContexts(read: Seq[IRI]): TriplestoreConnection =
		if readContexts == read then this
		else withContexts(primaryContext, read)

	final def primaryContextView: TriplestoreConnection =
		if readContexts.length == 1 && readContexts.head == primaryContext then this
		else withContexts(primaryContext, Seq(primaryContext))

	final def hasStatement(st: Statement): Boolean = st.getSubject() match
		case subj: IRI => hasStatement(subj, st.getPredicate(), st.getObject())
		case _ => false


object TriplestoreConnection:
	type TSC = TriplestoreConnection
	type TSC2[T] = TriplestoreConnection ?=> T
	type TSC2V[T] = TSC2[Validated[T]]

	import InstanceServer.*

	def getStatements(subject: Option[IRI], predicate: Option[IRI], obj: Option[Value]): TSC2[CloseableIterator[Statement]] =
		conn ?=> conn.getStatements(subject, predicate, obj)

	def getStatements(subject: IRI): TSC2[IndexedSeq[Statement]] = getStatements(Some(subject), None, None).toIndexedSeq

	def hasStatement(subject: Option[IRI], predicate: Option[IRI], obj: Option[Value]): TSC2[Boolean] =
		conn ?=> conn.hasStatement(subject, predicate, obj)

	def hasStatement(subject: IRI | Null, predicate: IRI | Null, obj: Value | Null): TSC2[Boolean] =
		conn ?=> conn.hasStatement(subject, predicate, obj)

	def resourceHasType[C <: TSC](res: IRI, tpe: IRI): C ?=> Boolean = hasStatement(res, RDF.TYPE, tpe)

	def getValues(instUri: IRI, propUri: IRI): TSC2[IndexedSeq[Value]] =
		conn ?=> conn.getStatements(Some(instUri), Some(propUri), None).map(_.getObject).toIndexedSeq

	def getTypes(res: IRI): TSC2[IndexedSeq[IRI]] = getValues(res, RDF.TYPE).collect:
		case classUri: IRI => classUri

	def getLiteralValues(subj: IRI, pred: IRI, dType: IRI): TSC2[IndexedSeq[String]] = getValues(subj, pred)
		.collect:
			case lit: Literal if(lit.getDatatype === dType) => lit.stringValue
		.distinct

	def getUriValues[C <: TSC](subj: IRI, pred: IRI): C ?=> IndexedSeq[IRI] =
		getValues(subj, pred).collect{case uri: IRI => uri}.distinct

	def getStringValues[C <: TSC](subj: IRI, pred: IRI): C ?=> IndexedSeq[String] =
		getLiteralValues(subj, pred, XSD.STRING)

	def getIntValues[C <: TSC](subj: IRI, pred: IRI): C ?=> IndexedSeq[Int] =
		getLiteralValues(subj, pred, XSD.INTEGER).flatMap(_.toIntOption)

	def getLongValues[C <: TSC](subj: IRI, pred: IRI): C ?=> IndexedSeq[Long] =
		getLiteralValues(subj, pred, XSD.LONG).flatMap(_.toLongOption)

	def getDoubleValues[C <: TSC](subj: IRI, pred: IRI): C ?=> IndexedSeq[Double] =
		getLiteralValues(subj, pred, XSD.DOUBLE).flatMap(_.toDoubleOption)

	def getFloatValues[C <: TSC](subj: IRI, pred: IRI): C ?=> IndexedSeq[Float] =
		getLiteralValues(subj, pred, XSD.FLOAT).flatMap(_.toFloatOption)

	def getUriLiteralValues[C <: TSC](subj: IRI, pred: IRI): C ?=> IndexedSeq[JavaUri] =
		getLiteralValues(subj, pred, XSD.ANYURI).map(new JavaUri(_))


	def validate[T](
		getter: (IRI, IRI) => TSC2[IndexedSeq[T]],
		subj: IRI,
		pred: IRI,
		card: CardinalityExpectation
	): TSC2V[IndexedSeq[T]] = Validated(getter(subj, pred)).flatMap: vals =>
		vals.validateSize(card, s"Expected ${card.descr} values of property $pred for resource $subj, but got ${vals.length}")
	end validate


	def getSingleUri[C <: TSC](subj: IRI, pred: IRI): C ?=> Validated[IRI] =
		validate(getUriValues, subj, pred, ExactlyOne).map(_.head)

	def getOptionalUri[C <: TSC](subj: IRI, pred: IRI): C ?=> Validated[Option[IRI]] =
		validate(getUriValues, subj, pred, AtMostOne).map(_.headOption)

	def getLabeledResource[C <: TSC](subj: IRI, pred: IRI): C ?=> Validated[UriResource] =
		getSingleUri(subj, pred).flatMap(getLabeledResource)

	def getLabeledResource[C <: TSC](uri: IRI): C ?=> Validated[UriResource] =
		getOptionalString[C](uri, RDFS.LABEL).map: label =>
			UriResource(uri.toJava, label, getStringValues(uri, RDFS.COMMENT))

	def getOptionalString[C <: TSC](subj: IRI, pred: IRI): C ?=> Validated[Option[String]] =
		validate(getStringValues, subj, pred, AtMostOne).map(_.headOption)

	def getSingleString[C <: TSC](subj: IRI, pred: IRI): C ?=> Validated[String] =
		validate(getStringValues, subj, pred, ExactlyOne).map(_.head)

	def getSingleInt[C <: TSC](subj: IRI, pred: IRI): C ?=> Validated[Int] =
		validate(getIntValues, subj, pred, ExactlyOne).map(_.head)

	def getOptionalInt[C <: TSC](subj: IRI, pred: IRI): C ?=> Validated[Option[Int]] =
		validate(getIntValues, subj, pred, AtMostOne).map(_.headOption)

	def getOptionalLong[C <: TSC](subj: IRI, pred: IRI): C ?=> Validated[Option[Long]] =
		validate(getLongValues, subj, pred, AtMostOne).map(_.headOption)

	def getOptionalDouble[C <: TSC](subj: IRI, pred: IRI): C ?=> Validated[Option[Double]] =
		validate(getDoubleValues, subj, pred, AtMostOne).map(_.headOption)

	def getOptionalFloat[C <: TSC](subj: IRI, pred: IRI): C ?=> Validated[Option[Float]] =
		validate(getFloatValues, subj, pred, AtMostOne).map(_.headOption)

	def getOptionalBool[C <: TSC](subj: IRI, pred: IRI): C ?=> Validated[Option[Boolean]] =
		validate(getLiteralValues(_, _, XSD.BOOLEAN), subj, pred, AtMostOne)
			.map(_.headOption.map(_.toLowerCase == "true"))

	def getSingleDouble[C <: TSC](subj: IRI, pred: IRI): C ?=> Validated[Double] =
		validate(getDoubleValues, subj, pred, ExactlyOne).map(_.head)

	def getInstantValues[C <: TSC](subj: IRI, pred: IRI): C ?=> IndexedSeq[Instant] =
		getLiteralValues(subj, pred, XSD.DATETIME).map(parseInstant)

	def getOptionalInstant[C <: TSC](subj: IRI, pred: IRI): C ?=> Validated[Option[Instant]] =
		validate(getInstantValues, subj, pred, AtMostOne).map(_.headOption)

	def getSingleInstant[C <: TSC](subj: IRI, pred: IRI): C ?=> Validated[Instant] =
		validate(getInstantValues, subj, pred, ExactlyOne).map(_.head)

	def getOptionalLocalDate[C <: TSC](subj: IRI, pred: IRI): C ?=> Validated[Option[LocalDate]] =
		validate(getLiteralValues(_, _, XSD.DATE), subj, pred, AtMostOne).map(_.headOption.map(LocalDate.parse))

	def getSingleUriLiteral[C <: TSC](subj: IRI, pred: IRI): C ?=> Validated[JavaUri] =
		validate(getUriLiteralValues, subj, pred, ExactlyOne).map(_.head)

	def getOptionalUriLiteral[C <: TSC](subj: IRI, pred: IRI): C ?=> Validated[Option[JavaUri]] =
		validate(getUriLiteralValues, subj, pred, AtMostOne).map(_.headOption)

	def getHashsum[C <: TSC](dataObjUri: IRI, pred: IRI): C ?=> Validated[Sha256Sum] =
		for
			hashLits <- validate(getLiteralValues(_, _, XSD.BASE64BINARY), dataObjUri, pred, ExactlyOne)
			hash <- Validated.fromTry(Sha256Sum.fromBase64(hashLits.head))
		yield hash

end TriplestoreConnection
