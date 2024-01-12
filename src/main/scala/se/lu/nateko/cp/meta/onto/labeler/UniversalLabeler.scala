package se.lu.nateko.cp.meta.onto.labeler

import org.eclipse.rdf4j.model.IRI
import org.semanticweb.owlapi.model.OWLOntology
import org.semanticweb.owlapi.model.{IRI => OwlIri}
import se.lu.nateko.cp.meta.instanceserver.InstanceServer
import se.lu.nateko.cp.meta.instanceserver.TriplestoreConnection.TSC
import se.lu.nateko.cp.meta.onto.InstOnto
import se.lu.nateko.cp.meta.utils.rdf4j.*

import scala.collection.mutable

class UniversalLabeler(ontology: OWLOntology) extends InstanceLabeler:

	private val cache = mutable.Map.empty[IRI, InstanceLabeler]
	private val owlFactory = ontology.getOWLOntologyManager.getOWLDataFactory

	override def getLabel(instUri: IRI)(using TSC): String =
		try
			val theType: IRI = InstOnto.getSingleType(instUri)

			val theClass = owlFactory.getOWLClass(OwlIri.create(theType.toJava))

			val labeler = cache.getOrElseUpdate(theType, ClassIndividualsLabeler(theClass, ontology, this))

			labeler.getLabel(instUri)

		catch case _: Throwable =>
				super.getLabel(instUri)
