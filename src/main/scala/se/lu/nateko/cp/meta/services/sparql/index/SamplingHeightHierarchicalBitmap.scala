package se.lu.nateko.cp.meta.services.sparql.index

import java.time.Instant
import HierarchicalBitmap.*
import scala.annotation.tailrec
import se.lu.nateko.cp.meta.services.sparql.magic.CpIndex.IndexData

/**
 * Factory for HierarchivalBitmap[Float] suitable for representing air/water sampling heights
 * between -3000 and 3000 with precision of 0.001. Assumes limited number of discrete heights.
*/
object SamplingHeightHierarchicalBitmap{

	val SpilloverThreshold = 2

	def getCoordinate(key: Float, depth: Int): Coord = if(depth <= 0 || depth > 3) 0 else {
		Math.signum(key).toInt * (
			(Math.abs(key) * pow10(depth)).toLong & Short.MaxValue
		)
	}.toShort

	def apply(idx: IndexData): HierarchicalBitmap[Float] = {

		given Geo[Float] = new SamplingHeightGeo(idx)
		import scala.math.Ordering.Float.IeeeOrdering

		new HierarchicalBitmap[Float](0, None)
	}

	private def pow10(n: Int): Int = {
		var res = 1; var i = 0
		while(i < n){
			res *= 10; i += 1
		}
		res
	}

	class SamplingHeightGeo(idx: IndexData) extends Geo[Float]{
		private def this() = this(null)//for Kryo deserialization
		val spilloverThreshold: Int = SpilloverThreshold
		def keyLookup(value: Int): Float = idx.objs(value).samplingHeight
		def coordinate(key: Float, depth: Int): Coord = getCoordinate(key, depth)
	}

}
