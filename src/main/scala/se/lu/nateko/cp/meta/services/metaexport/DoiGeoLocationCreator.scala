package se.lu.nateko.cp.meta.services.metaexport

import com.fasterxml.jackson.annotation.JsonFormat.Feature
import org.locationtech.jts.algorithm.ConvexHull
import org.locationtech.jts.algorithm.hull.ConcaveHull
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.GeometryCollection
import org.locationtech.jts.geom.Point as JtsPoint
import org.locationtech.jts.geom.Polygon as JtsPolygon
import se.lu.nateko.cp.doi.meta.GeoLocation
import se.lu.nateko.cp.meta.core.data.Circle
import se.lu.nateko.cp.meta.core.data.DataObject
import se.lu.nateko.cp.meta.core.data.FeatureCollection
import se.lu.nateko.cp.meta.core.data.GeoFeature
import se.lu.nateko.cp.meta.core.data.GeoTrack
import se.lu.nateko.cp.meta.core.data.LatLonBox
import se.lu.nateko.cp.meta.core.data.Pin
import se.lu.nateko.cp.meta.core.data.Polygon
import se.lu.nateko.cp.meta.core.data.Position
import se.lu.nateko.cp.meta.services.sparql.magic.ConcaveHullLengthRatio
import se.lu.nateko.cp.meta.services.sparql.magic.JtsGeoFactory
import se.lu.nateko.cp.meta.services.upload.DoiGeoLocationConverter

import scala.collection.mutable.ArrayBuffer

object JtsGeoFeatureConverter:
	def toPoint(p: Position): JtsPoint =
		JtsGeoFactory.createPoint(Coordinate(p.lon, p.lat))

	def toCollection(points: Seq[Position]) =
		GeometryCollection(points.toArray.map(toPoint), JtsGeoFactory)
	
	def toPolygon(polygon: Polygon): JtsPolygon =
		val firstPoint = polygon.vertices.headOption.toArray
		val vertices = (polygon.vertices.toArray ++ firstPoint).map(v => Coordinate(v.lon, v.lat))
		JtsGeoFactory.createPolygon(vertices)


object DoiGeoLocationCreator:
	import JtsGeoFeatureConverter.*

	def geoFeatureToJtsGeometry(gf: GeoFeature): Seq[Geometry] =
		val pointTest: PartialFunction[GeoFeature, Position] =
			case pos: Position => pos

		gf match
			case b: LatLonBox => Seq(JtsGeoFeatureConverter.toPolygon(b.asPolygon))
			case c: Circle => Seq(JtsGeoFeatureConverter.toPolygon(DoiGeoLocationConverter.toLatLonBox(c).asPolygon))
			case poly: Polygon => Seq(JtsGeoFeatureConverter.toPolygon(poly))
			case p: Position => Seq(toPoint(p))
			case pin: Pin => Seq(toPoint(pin.position))
			case gt: GeoTrack => Seq(ConcaveHull.concaveHullByLengthRatio(toCollection(gt.points), ConcaveHullLengthRatio))
			case fc: FeatureCollection =>
				val fcPoints = fc.features.collect(pointTest)
				val pointHull = ConcaveHull.concaveHullByLengthRatio(toCollection(fcPoints), ConcaveHullLengthRatio)
				val otherGeometries = fc.features.filterNot(pointTest.isDefinedAt)
				Seq(pointHull) ++ otherGeometries.flatMap(geoFeatureToJtsGeometry)


	def createHulls(geoFeatures: Seq[GeoFeature]): Seq[Geometry] =
		geoFeatures.flatMap(geoFeatureToJtsGeometry)

	def mergeHulls(hulls: Seq[Geometry]): Seq[Geometry] =

		val sortedHulls = hulls.map(hull => (hull, -hull.getArea)).sortBy(_._2).map(_._1)
		var res: ArrayBuffer[Geometry] = ArrayBuffer.empty

		for hull <- sortedHulls do
			var i = 0
			var added = false
			while (i < res.length && !added)
				if res(i).contains(hull) then
					added = true
				else if hull.intersects(res(i)) then
					val joined = GeometryCollection(Array(hull, res(i)), JtsGeoFactory)
					val newHull = joined.union()
					res(i) = newHull
					added = true
				i += 1
			if !added then res += hull

		res.toSeq

	def representativeCov(dataObjs: Seq[DataObject]): Seq[GeoLocation] =
		var geoFeatures: Seq[GeoFeature] = dataObjs.flatMap(_.coverage)

		val stationTest: PartialFunction[GeoFeature, Position] =
			case pos: Position if pos.label.isDefined => pos

		val stationPoints = geoFeatures.collect(stationTest)
		val otherGeometries = geoFeatures.filterNot(stationTest.isDefinedAt)

		val hulls = createHulls(otherGeometries.distinct)
		val mergedHulls = mergeHulls(hulls)

		val doiPoints = stationPoints.distinct.map(DoiGeoLocationConverter.toDoiGeoLocationWithPoint)
		val doiBoxes = mergedHulls.map(DoiGeoLocationConverter.fromJtsToDoiGeoLocation)

		doiPoints ++ doiBoxes

end DoiGeoLocationCreator
