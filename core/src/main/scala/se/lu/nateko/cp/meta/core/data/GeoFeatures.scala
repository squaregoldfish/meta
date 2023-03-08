package se.lu.nateko.cp.meta.core.data

import java.text.DecimalFormat
import java.net.URI

sealed trait GeoFeature{
	def label: Option[String]
	def withOptLabel(label: Option[String]): GeoFeature
	def textSpecification: String
	def withLabel(label: String): GeoFeature = withOptLabel(Some(label))
}

case class FeatureCollection(features: Seq[GeoFeature], label: Option[String]) extends GeoFeature {
	def textSpecification = features.map(_.textSpecification).mkString("Geometries: ", "; ", "")

	def flatten = {
		def flattenFeature(f: GeoFeature): Seq[GeoFeature] = f match{
			case FeatureCollection(geometries, _) => geometries.flatMap(flattenFeature)
			case _ => Seq(f)
		}
		copy(features = features.flatMap(flattenFeature))
	}

	def withOptLabel(label: Option[String]): GeoFeature = copy(label = label)
}

case class Position(lat: Double, lon: Double, alt: Option[Float], label: Option[String]) extends GeoFeature{

	def textSpecification = s"Lat: $lat6, Lon: $lon6" + alt.fold("")(alt => s", Alt: $alt m")

	def lat6 = PositionUtil.format6(lat)
	def lon6 = PositionUtil.format6(lon)

	def withOptLabel(label: Option[String]): GeoFeature = copy(label = label)
}

object PositionUtil{
	private val numForm = new DecimalFormat("###.######")
	def format6(d: Double): String = numForm.format(d).replace(',', '.')
	def average(ps: Iterable[Position]): Option[Position] = {
		var latSum, lonSum: Double = 0
		var n: Int = 0
		var heightSum: Float = 0
		var nHeight: Int = 0
		ps.foreach{p =>
			n += 1
			latSum += p.lat
			lonSum += p.lon
			p.alt.foreach{height =>
				nHeight += 1
				heightSum += height
			}
		}
		if(n == 0) None else Some(Position(
			lat = latSum / n,
			lon = lonSum / n,
			alt = (if(nHeight == 0) None else Some(heightSum / nHeight)),
			label = None
		))
	}
}

case class LatLonBox(min: Position, max: Position, label: Option[String], uri: Option[URI]) extends GeoFeature{

	def asPolygon = Polygon(
		Seq(
			min, Position(lon = min.lon, lat = max.lat, alt = None, label = None),
			max, Position(lon = max.lon, lat = min.lat, alt = None, label = None),
		),
		label
	)

	def textSpecification = s"S: ${min.lat6}, W: ${min.lon6}, N: ${max.lat6}, E: ${max.lon6}"

	def withOptLabel(label: Option[String]): GeoFeature = copy(label = label)
}

case class GeoTrack(points: Seq[Position], label: Option[String]) extends GeoFeature{

	def textSpecification = points.map(p => s"(${p.textSpecification})").mkString("[", ", ", "]")

	def withOptLabel(label: Option[String]): GeoFeature = copy(label = label)

}

case class Polygon(vertices: Seq[Position], label: Option[String]) extends GeoFeature{

	def textSpecification = vertices.map(p => s"(${p.textSpecification})").mkString("[", ", ", "]")

	def withOptLabel(label: Option[String]): GeoFeature = copy(label = label)
}

case class Circle(center: Position, radius: Float, label: Option[String]) extends GeoFeature{

	def textSpecification: String = s"(${center.textSpecification}, Rad: $radius m)"

	def withOptLabel(label: Option[String]) = copy(label = label)
}

enum PinKind:
	case Sensor, Other

case class Pin(position: Position, kind: PinKind) extends GeoFeature:
	def label = position.label
	def textSpecification: String = position.textSpecification
	def withOptLabel(label: Option[String]): GeoFeature = copy(position = position.copy(label = label))
