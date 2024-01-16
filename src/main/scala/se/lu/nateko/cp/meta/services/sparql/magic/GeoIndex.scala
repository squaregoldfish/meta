package se.lu.nateko.cp.meta.services.sparql.magic

import org.locationtech.jts.algorithm.hull.ConcaveHull
import org.locationtech.jts.geom.Coordinate
import org.locationtech.jts.geom.Envelope
import org.locationtech.jts.geom.Geometry
import org.locationtech.jts.geom.GeometryCollection
import org.locationtech.jts.geom.GeometryFactory
import org.locationtech.jts.geom.Point
import org.locationtech.jts.geom.Polygon
import org.roaringbitmap.buffer.ImmutableRoaringBitmap
import org.roaringbitmap.buffer.MutableRoaringBitmap

import scala.collection.mutable
import scala.collection.mutable.ArrayBuffer
import scala.jdk.CollectionConverters.IteratorHasAsJava
import scala.util.Failure
import scala.util.Success

val JtsGeoFactory = new GeometryFactory()

trait Cluster:
	def area: Geometry
	def getFilter(bbox: Geometry, otherFilter: Option[ImmutableRoaringBitmap]): ImmutableRoaringBitmap
	def printTree(level: Int): Unit

trait SimpleCluster extends Cluster:
	protected def objectIds = new MutableRoaringBitmap
	def filter: ImmutableRoaringBitmap = objectIds
	def addObject(dobjCov: DataObjCov): SimpleCluster
	def removeObject(dobjCov: DataObjCov): Option[SimpleCluster]
	//def addObjectId(id: Int): Unit = _objectIds.add(id)

case class GeoEvent(
	objIdx: Int,
	isAssertion: Boolean,
	geometry: Geometry,
	clusterId: String
)

case class DataObjCov(val idx: Int, val geo: Geometry)

def calculateBoundingBox(shapes: Seq[Geometry]): Geometry =
	val collection = GeometryCollection(shapes.toArray, JtsGeoFactory)
	collection.getEnvelope()

class CompositeCluster(val area: Geometry, val children: IndexedSeq[Cluster]) extends Cluster:

	def printTree(level: Int): Unit =
		for (i <- 1 until level)
			print("\t")

		println(area.toString())

		for (child <- children)
			child.printTree(level + 1)

	def addCluster(c: Cluster): CompositeCluster = if !belongs(c) then this else
		var hasAddedToChildren: Boolean = false

		val newChildren = if c.area.contains(area) then children else
			children.map:
				case sc: SimpleCluster => sc
				case cc: CompositeCluster =>
					val newCc = cc.addCluster(c)
					if newCc.ne(cc) then hasAddedToChildren = true
					newCc

		val updatedChildren = if hasAddedToChildren then newChildren else newChildren :+ c

		CompositeCluster(area, updatedChildren)

	def removeCluster(c: SimpleCluster): CompositeCluster = if !belongs(c) then this else
		val newChildren = children.collect:
			case sc: SimpleCluster if sc != c => sc
			case cc: CompositeCluster => cc.removeCluster(c)
		CompositeCluster(area, newChildren)

	private def belongs(c: Cluster): Boolean = c match
		case cc: CompositeCluster => area.contains(cc.area)
		case sc: SimpleCluster => area.intersects(sc.area)


	override def getFilter(bbox: Geometry, otherFilter: Option[ImmutableRoaringBitmap]): ImmutableRoaringBitmap =

		if bbox.intersects(area) then
			ImmutableRoaringBitmap.or(children.map(_.getFilter(bbox, otherFilter)).iterator.asJava)
		else
			new MutableRoaringBitmap

end CompositeCluster


def createEmptyTopClusters: IndexedSeq[CompositeCluster] =

	val f = JtsGeoFactory

	val root = new Envelope(-180, 180, -90, 90)

	val topLevelEnvelopes = IndexedSeq(
		new Envelope(-180, -60, 0, 90), // America
		new Envelope(-60, 60, 0, 90), // Europe
		new Envelope(60, 180, 0, 90),
		new Envelope(-180, -60, -90, 0),
		new Envelope(-60, 60, -90, 0),
		new Envelope(60, 180, -90, 0)
	) // Envelope(maxLon, minLon, maxLat, minLat)

	val worldLevelClusters = topLevelEnvelopes.map(e => CompositeCluster(f.toGeometry(e), IndexedSeq.empty)).toBuffer

	val europeLongitudes = IndexedSeq(-60, -30, 0, 30) 
	val europeLatitudes = IndexedSeq(90, 60, 30)

	val europeEnvelopes = ArrayBuffer[Envelope]().empty

	for (lon <- europeLongitudes)
		for (lat <- europeLatitudes)
			europeEnvelopes.append(new Envelope(lon + 30, lon, lat - 30, lat))

	val europeClusters = europeEnvelopes.map(e => CompositeCluster(f.toGeometry(e), IndexedSeq.empty))

	for (c <- europeClusters)
		worldLevelClusters(1) = worldLevelClusters(1).addCluster(c)
		
	val topLevelClusters = IndexedSeq(CompositeCluster(f.toGeometry(root), IndexedSeq.empty)).toBuffer

	for (w <- worldLevelClusters)
		topLevelClusters(0) = topLevelClusters(0).addCluster(w)

	// topLevelClusters.foreach(_.printTree(1))

	topLevelClusters.toIndexedSeq

class DenseCluster(val area: Geometry, objectIds: MutableRoaringBitmap) extends SimpleCluster:

	override def printTree(level: Int): Unit = 
		for (i <- 1 until level)
			print("\t")

		println("dense cluster: " + area.toString())

	override def addObject(dobjCov: DataObjCov): SimpleCluster =
		if dobjCov.geo == area then
			objectIds.add(dobjCov.idx)
			this
		else
			val currentDataCovs = new ArrayBuffer[DataObjCov]()

			objectIds.forEach: objId =>
				currentDataCovs.addOne(DataObjCov(objId, area))

			currentDataCovs.addOne(dobjCov)

			val joined = GeometryCollection(Array(area, dobjCov.geo), JtsGeoFactory)

			objectIds.add(dobjCov.idx)

			SparseCluster(ConcaveHull(joined).getHull(), currentDataCovs.toSeq, objectIds)

	override def removeObject(dobjCov: DataObjCov): Option[SimpleCluster] =
		objectIds.remove(dobjCov.idx)

		if objectIds.isEmpty() then None else Some(this)


	override def getFilter(bbox: Geometry, otherFilter: Option[ImmutableRoaringBitmap]): ImmutableRoaringBitmap =
		if bbox.intersects(area) then objectIds
		else new MutableRoaringBitmap


class SparseCluster(val area: Geometry, children: Seq[DataObjCov], objectIds: MutableRoaringBitmap) extends SimpleCluster:

	override def printTree(level: Int): Unit = 
		for (i <- 1 until level)
			print("\t")

		println("sparse cluster: " + area.toString())

	override def addObject(dobjCov: DataObjCov): SimpleCluster =
		val newChildren = children :+ dobjCov
		objectIds.add(dobjCov.idx)
		val newArea = if area.contains(dobjCov.geo) then area else
			val joined = GeometryCollection(Array(area, dobjCov.geo), JtsGeoFactory)
			ConcaveHull(joined).getHull()
		SparseCluster(newArea, newChildren, objectIds)

	override def removeObject(dobjCov: DataObjCov): Option[SimpleCluster] =
		val newChildren = children.filter(_.idx != dobjCov.idx)
		val newGeometries = newChildren.map(_.geo).toSet
		objectIds.remove(dobjCov.idx)

		if newGeometries.size == 0 then None
		else if newGeometries.size == 1 then
			Some(DenseCluster(newGeometries.head, objectIds))
		else
			val joined = GeometryCollection(newGeometries.toArray, JtsGeoFactory)
			val newArea = ConcaveHull(joined).getHull()
			Some(SparseCluster(newArea, newChildren, objectIds))

	override def getFilter(bbox: Geometry, otherFilter: Option[ImmutableRoaringBitmap]): ImmutableRoaringBitmap =

		if bbox.contains(area) then
			objectIds
		else if bbox.intersects(area) then

			val otherTest: Int => Boolean = otherFilter.fold[Int => Boolean](_ => true)(_.contains)
			val matchingObjects = new MutableRoaringBitmap

			children.foreach: dobjCov =>
				if otherTest(dobjCov.idx) && bbox.intersects(dobjCov.geo)
				then matchingObjects.add(dobjCov.idx)

			matchingObjects
		else
			new MutableRoaringBitmap

class GeoIndex:
	val allClusters = mutable.Map.empty[String, SimpleCluster]
	private var topClusters: IndexedSeq[CompositeCluster] = createEmptyTopClusters
	def compositeClusters = topClusters

	def put(event: GeoEvent): Unit = innerPut(event, false)
	def putQuickly(event: GeoEvent): Unit = innerPut(event, true)

	private def replaceCluster(currentCluster: SimpleCluster, updatedCluster: SimpleCluster) =
		topClusters = topClusters.map(_.removeCluster(currentCluster))
		placeCluster(updatedCluster)

	private def removeCluster(clusterId: String, cluster: SimpleCluster) =
		topClusters = topClusters.map(_.removeCluster(cluster))
		allClusters.remove(clusterId)

	def remove(event: GeoEvent, currentCluster: SimpleCluster): Unit =
		val updatedCluster = currentCluster.removeObject(DataObjCov(event.objIdx, event.geometry))

		updatedCluster match
			case None => removeCluster(event.clusterId, currentCluster)
			case Some(c) =>
				currentCluster match
					case dc: DenseCluster => replaceCluster(currentCluster, c)
					case sc: SparseCluster =>
						val clusterChanged = updatedCluster.ne(currentCluster)
						if clusterChanged then
							allClusters.update(event.clusterId, c)
							replaceCluster(currentCluster, c)

	private def innerPut(event: GeoEvent, quick: Boolean): Unit =
		val clusterExists = allClusters.contains(event.clusterId)
		val currentCluster = allClusters.getOrElseUpdate(event.clusterId, DenseCluster(event.geometry, new MutableRoaringBitmap))

		if event.isAssertion then
			val updatedCluster = currentCluster.addObject(DataObjCov(event.objIdx, event.geometry))
			val clusterChanged = clusterExists && (updatedCluster.ne(currentCluster))
			if !clusterExists || clusterChanged then
				allClusters.update(event.clusterId, updatedCluster)
			if !quick then
				if clusterChanged then
					topClusters = topClusters.map(_.removeCluster(currentCluster))
				if !clusterExists || clusterChanged then placeCluster(updatedCluster)
		else
			if clusterExists then remove(event, currentCluster)


	private def placeCluster(cluster: SimpleCluster): Unit =
		topClusters = topClusters.map(_.addCluster(cluster))

	def arrangeClusters(): Unit =
		allClusters.values.foreach(placeCluster)

	def getFilter(bbox: Geometry, otherFilter: Option[ImmutableRoaringBitmap]): ImmutableRoaringBitmap =
		ImmutableRoaringBitmap.or(topClusters.map(_.getFilter(bbox, otherFilter)).iterator.asJava)
