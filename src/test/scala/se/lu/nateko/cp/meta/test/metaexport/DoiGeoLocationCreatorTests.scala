package se.lu.nateko.cp.meta.test.metaexport

import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.io.WKTReader
import org.scalatest.funspec.AnyFunSpec
import se.lu.nateko.cp.meta.core.data.*
import se.lu.nateko.cp.meta.services.CpVocab.DataObject
import se.lu.nateko.cp.meta.services.metaexport.DoiGeoLocationCreator.*
import se.lu.nateko.cp.meta.services.sparql.magic.JtsGeoFactory

import TestGeoFeatures.*

// Test data:

// large collection:
// https://metalocal.icos-cp.eu/collections/T2wsrJcHPvTLl2dFf375dQ4h

// small collection:
// https://metalocal.icos-cp.eu/collections/QoycFqCewfdEXsTxZMU6XJH9
class DoiGeoLocationCreatorTests extends AnyFunSpec:
	describe("DoiGeoLocationCreator"):
		def convertStringsToJTS(geomStrings: String*): Seq[Geometry] =
			val wktReader = new WKTReader(JtsGeoFactory)
			geomStrings.map(wktReader.read)

		it("calling createHulls with empty seq does nothing"):
			val hulls = createHulls(Seq())

			assert(hulls == Seq())
		
		it("calling mergeHulls with empty seq does nothing"):
			val hulls = mergeHulls(Seq())

			assert(hulls == Seq())

		it("createHulls from ecosystem data"):
			val hulls = createHulls(TestGeoFeatures.geoFeatures)

			val expectedGeometries = convertStringsToJTS(
				"POINT (2.780096 48.476357)",
				"POLYGON ((2.779721884892631 48.47564119977188, 2.779721884892631 48.47609480022812, 2.7804061151073687 48.47609480022812, 2.7804061151073687 48.47564119977188, 2.779721884892631 48.47564119977188))",
				"POLYGON ((2.778997883361892 48.47586819977188, 2.778997883361892 48.47632180022812, 2.779682116638108 48.47632180022812, 2.779682116638108 48.47586819977188, 2.778997883361892 48.47586819977188))",
				"POLYGON ((2.7793398803880227 48.47630919977188, 2.7793398803880227 48.47676280022812, 2.7800241196119777 48.47676280022812, 2.7800241196119777 48.47630919977188, 2.7793398803880227 48.47630919977188))",
				"POLYGON ((2.7802158824852485 48.47599819977188, 2.7802158824852485 48.47645180022812, 2.7809001175147516 48.47645180022812, 2.7809001175147516 48.47599819977188, 2.7802158824852485 48.47599819977188))",
				"POLYGON ((2.779432 48.47323, 2.775239 48.476735, 2.783067 48.480903, 2.783584 48.480944, 2.785678 48.480789, 2.786456 48.480506, 2.786875 48.479848, 2.787856 48.478101, 2.783961 48.473986, 2.779432 48.47323))"
			)

			assert(hulls == expectedGeometries)

		it("mergeHulls from ecosystem data"):
			val hulls = createHulls(TestGeoFeatures.geoFeatures)
			val mergedHulls = mergeHulls(hulls)

			for (hull <- hulls)
				assert(mergedHulls.exists(_.contains(hull)))

			assert(mergedHulls.length == 1)

		it("createHulls from ocean data"):
			val hulls = createHulls(TestGeoFeatures.oceanGeoTracks)

			val expectedGeometries = convertStringsToJTS(
				"POLYGON ((12.654 56.036, 10.852 56.056, -43.881 59.562, -52.267 63.864, -52.275 63.996, -51.726 64.159, -22.047 64.188, -6.766 62, 11.164 57.669, 11.364 57.49, 12.654 56.036))",
				"POLYGON ((10.835 56.053, -42.009 58.666, -45.139 59.042, -48.277 60.092, -50.167 61.87, -52.277 63.889, -51.889 64.123, -13.521 64.9, 11.866 56.77, 10.835 56.053))",
				"POLYGON ((12.667 56.014, 10.823 56.052, -42.663 59.102, -48.399 59.791, -52.265 64.002, -51.722 64.167, -23.225 64.141, -6.766 62, -0.776 60.901, 11.13 57.679, 12.667 56.014))"
			)

			assert(hulls == expectedGeometries)

		it("mergeHulls from ocean data"):
			val hulls = createHulls(TestGeoFeatures.geoFeatures)
			val mergedHulls = mergeHulls(hulls)

			for (hull <- hulls)
				assert(mergedHulls.exists(_.contains(hull)))

			assert(mergedHulls.length == 1)

end DoiGeoLocationCreatorTests
