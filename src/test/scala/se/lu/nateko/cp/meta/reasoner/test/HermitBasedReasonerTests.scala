package se.lu.nateko.cp.meta.reasoner.test

import org.scalatest.FunSpec
import se.lu.nateko.cp.meta.Vocab
import se.lu.nateko.cp.meta.reasoner.HermitBasedReasoner
import se.lu.nateko.cp.meta.test.TestConfig
import se.lu.nateko.cp.meta.utils.owlapi._

class HermitBasedReasonerTests extends FunSpec{

	val owlOnto = TestConfig.owlOnto
	val reasoner = new HermitBasedReasoner(owlOnto)

	describe("getPropertiesWhoseDomainIncludes(owlClass)"){

		it("should return expected props"){
			val owlClass = Vocab.getOWLClass("ThematicCenter")
			val props = reasoner.getPropertiesWhoseDomainIncludes(owlClass)
				.map(oc => getLastFragment(oc.getIRI))
			assert(props.toSet === Set("hasName", "locatedAt"))
		}
	}

}