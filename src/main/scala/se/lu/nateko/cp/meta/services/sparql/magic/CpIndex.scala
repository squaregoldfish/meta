package se.lu.nateko.cp.meta.services.sparql.magic

import akka.event.LoggingAdapter
import org.eclipse.rdf4j.model.IRI
import org.eclipse.rdf4j.model.Literal
import org.eclipse.rdf4j.model.Statement
import org.eclipse.rdf4j.model.Value
import org.eclipse.rdf4j.model.ValueFactory
import org.eclipse.rdf4j.model.vocabulary.XSD
import org.eclipse.rdf4j.sail.Sail
import org.eclipse.rdf4j.sail.SailConnection
import org.roaringbitmap.buffer.BufferFastAggregation
import org.roaringbitmap.buffer.ImmutableRoaringBitmap
import org.roaringbitmap.buffer.MutableRoaringBitmap
import se.lu.nateko.cp.meta.core.crypto.Sha256Sum
import se.lu.nateko.cp.meta.instanceserver.RdfUpdate
import se.lu.nateko.cp.meta.services.CpVocab
import se.lu.nateko.cp.meta.services.CpmetaVocab
import se.lu.nateko.cp.meta.services.sparql.index.HierarchicalBitmap.FilterRequest
import se.lu.nateko.cp.meta.services.sparql.index.*
import se.lu.nateko.cp.meta.utils.*
import se.lu.nateko.cp.meta.utils.async.ReadWriteLocking
import se.lu.nateko.cp.meta.utils.rdf4j.*

import java.io.Serializable
import java.time.Instant
import java.util.ArrayList
import java.util.concurrent.ArrayBlockingQueue
import scala.collection.mutable.AnyRefMap
import scala.collection.mutable.ArrayBuffer
import scala.compiletime.uninitialized
import scala.jdk.CollectionConverters.IteratorHasAsScala
import scala.util.Using

import CpIndex.*

case class StatKey(spec: IRI, submitter: IRI, station: Option[IRI], site: Option[IRI]){
	private def this() = this(null, null, null, null)//for Kryo deserialization
}
case class StatEntry(key: StatKey, count: Int)

trait ObjSpecific{
	def hash: Sha256Sum
	def uri(factory: ValueFactory): IRI
}

trait ObjInfo extends ObjSpecific{
	def spec: IRI
	def submitter: IRI
	def station: IRI
	def site: IRI
	def fileName: Option[String]
	def sizeInBytes: Option[Long]
	def samplingHeightMeters: Option[Float]
	def dataStartTime: Option[Instant]
	def dataEndTime: Option[Instant]
	def submissionStartTime: Option[Instant]
	def submissionEndTime: Option[Instant]
}

class CpIndex(sail: Sail, data: IndexData)(log: LoggingAdapter) extends ReadWriteLocking{

	import data.*

	def this(sail: Sail, nObjects: Int = 10000)(log: LoggingAdapter) = {
		this(sail, IndexData(nObjects))(log)
		//Mass-import of the statistics data
		Using(sail.getConnection)(_
			.getStatements(null, null, null, false)
			.asPlainScalaIterator
			.foreach(s => put(RdfUpdate(s, true)))
		)
		flush()
		contMap.valuesIterator.foreach(_.optimizeAndTrim())
		stats.filterInPlace{case (_, bm) => !bm.isEmpty}
	}

	given factory: ValueFactory = sail.getValueFactory
	val vocab = new CpmetaVocab(factory)

	private val q = new ArrayBlockingQueue[RdfUpdate](UpdateQueueSize)
	//Mass-import of the specification info
	private val specRequiresStation: AnyRefMap[IRI, Boolean] = getStationRequirementsPerSpec(sail, vocab)

	def size: Int = objs.length
	def serializableData: Serializable = data

	private def boolBitmap(prop: BoolProperty): MutableRoaringBitmap = boolMap.getOrElseUpdate(prop, emptyBitmap)

	private def categMap(prop: CategProp): AnyRefMap[prop.ValueType, MutableRoaringBitmap] = categMaps
		.getOrElseUpdate(prop, new AnyRefMap[prop.ValueType, MutableRoaringBitmap])
		.asInstanceOf[AnyRefMap[prop.ValueType, MutableRoaringBitmap]]

	private def bitmap(prop: ContProp): HierarchicalBitmap[prop.ValueType] = contMap.getOrElseUpdate(prop, prop match {
		/** Important to maintain type consistency between props and HierarchicalBitmaps here*/
		case FileName =>        StringHierarchicalBitmap.fileName(data)
		case FileSize =>        FileSizeHierarchicalBitmap(data)
		case SamplingHeight =>  SamplingHeightHierarchicalBitmap(data)
		case DataStart =>       DatetimeHierarchicalBitmap.dataStart(data)
		case DataEnd =>         DatetimeHierarchicalBitmap.dataEnd(data)
		case SubmissionStart => DatetimeHierarchicalBitmap.submStart(data)
		case SubmissionEnd =>   DatetimeHierarchicalBitmap.submEnd(data)
	}).asInstanceOf[HierarchicalBitmap[prop.ValueType]]

	def fetch(req: DataObjectFetch): Iterator[ObjInfo] = readLocked{
		//val start = System.currentTimeMillis

		val filter = filtering(req.filter).fold(initOk)(BufferFastAggregation.and(_, initOk))

		val idxIter: Iterator[Int] = req.sort match{
			case None =>
				filter.iterator.asScala.drop(req.offset).map(_.intValue)
			case Some(SortBy(prop, descending)) =>
				bitmap(prop).iterateSorted(Some(filter), req.offset, descending)
		}
		//println(s"Fetch from CpIndex complete in ${System.currentTimeMillis - start} ms")
		idxIter.map(objs.apply)
	}

	def filtering(filter: Filter): Option[ImmutableRoaringBitmap] = filter match{
		case And(filters) =>
			collectUnless(filters.iterator.flatMap(filtering))(_.isEmpty) match{
				case None => Some(emptyBitmap)
				case Some(bms) => and(bms)
			}

		case Not(filter) => filtering(filter) match {
			case None => Some(emptyBitmap)
			case Some(bm) => Some(negate(bm))
		}

		case Exists(prop) => prop match{
			case cp: ContProp => Some(bitmap(cp).all)
			case cp: CategProp => cp match{
				case optUriProp: OptUriProperty => categMap(optUriProp).get(None) match{
					case None => None
					case Some(deprived) if deprived.isEmpty => None
					case Some(deprived) => Some(negate(deprived))
				}
				case _ => None
			}
			case boo: BoolProperty => Some(boolBitmap(boo))
		}

		case ContFilter(property, condition) =>
			Some(bitmap(property).filter(condition))

		case CategFilter(category, values) if category == DobjUri =>
			val objIndices: Seq[Int] = values
				.collect{case iri: IRI => iri}
				.collect{case CpVocab.DataObject(hash, _) => idLookup.get(hash)}
				.flatten
			Some(ImmutableRoaringBitmap.bitmapOf(objIndices*))

		case CategFilter(category, values) =>
			val perValue = categMap(category)
			or(values.map(v => perValue.getOrElse(v, emptyBitmap)))

		case GeneralCategFilter(category, condition) => or(
			categMap(category).collect{
				case (cat, bm) if condition(cat) => bm
			}.toSeq
		)

		case Or(filters) =>
			collectUnless(filters.iterator.map(filtering))(_.isEmpty).flatMap{bmOpts =>
				or(bmOpts.flatten)
			}

		case All =>
			None
		case Nothing =>
			Some(emptyBitmap)
	}

	private def negate(bm: ImmutableRoaringBitmap) = ImmutableRoaringBitmap.flip(bm, 0, objs.length.toLong)

	private def collectUnless[T](iter: Iterator[T])(cond: T => Boolean): Option[Seq[T]] = {
		var condHappened = false
		val seq = iter.takeWhile(elem => {
			condHappened = cond(elem)
			!condHappened
		}).toIndexedSeq
		if(condHappened) None else Some(seq)
	}
	private def or(bms: Seq[ImmutableRoaringBitmap]): Option[MutableRoaringBitmap] =
		if(bms.isEmpty) Some(emptyBitmap) else Some(BufferFastAggregation.or(bms*))

	private def and(bms: Seq[ImmutableRoaringBitmap]): Option[MutableRoaringBitmap] =
		if(bms.isEmpty) None else Some(BufferFastAggregation.and(bms*))

	def statEntries(filter: Filter): Iterable[StatEntry] = readLocked{
		val filterOpt: Option[ImmutableRoaringBitmap] = filtering(filter)

		stats.flatMap{
			case (key, bm) =>
				val count = filterOpt.fold(bm.getCardinality)(ImmutableRoaringBitmap.andCardinality(bm, _))
				if(count > 0) Some(StatEntry(key, count))
				else None
		}
	}

	def lookupObject(hash: Sha256Sum): Option[ObjInfo] = idLookup.get(hash).map(objs.apply)

	private def getObjEntry(hash: Sha256Sum): ObjEntry = idLookup.get(hash).fold{
			val oe = new ObjEntry(hash, objs.length, "")
			objs += oe
			idLookup += hash -> oe.idx
			oe
		}(objs.apply)

	def put(st: RdfUpdate): Unit = {
		q.put(st)
		if(q.remainingCapacity == 0) flush()
	}

	def flush(): Unit = if(!q.isEmpty) writeLocked{
		if(!q.isEmpty) {
			val list = new ArrayList[RdfUpdate](UpdateQueueSize)
			q.drainTo(list)

			list.forEach{
				case RdfUpdate(Rdf4jStatement(subj, pred, obj), isAssertion) =>
					processUpdate(subj, pred, obj, isAssertion)
				case _ => ()
			}
			list.clear()
		}
	}

	private def processUpdate(subj: IRI, pred: IRI, obj: Value, isAssertion: Boolean): Unit = {
		import vocab.*
		import vocab.prov.{wasAssociatedWith, startedAtTime, endedAtTime}


		def targetUri = if(isAssertion && obj.isInstanceOf[IRI]) obj.asInstanceOf[IRI] else null

		def handleContinuousPropUpdate(prop: ContProp, key: prop.ValueType, idx: Int): Unit = {
			def helpTxt = s"value $key of property $prop on object ${objs(idx).hash.base64Url}"
			if(isAssertion) {
				if(!bitmap(prop).add(key, idx)){
					log.warning(s"Value already existed: asserted $helpTxt")
				}
			} else if(!bitmap(prop).remove(key, idx)){
					log.warning(s"Value was not present: tried to retract $helpTxt")
			}
		}

		def updateCategSet[T <: AnyRef](set: AnyRefMap[T, MutableRoaringBitmap], categ: T, idx: Int): Unit = {
			val bm = set.getOrElseUpdate(categ, emptyBitmap)
			if(isAssertion) bm.add(idx) else bm.remove(idx)
		}

		def updateStrArrayProp(prop: StringCategProp, parser: String => Option[Array[String]], idx: Int): Unit = obj
			.asOptInstanceOf[Literal].flatMap(asString).flatMap(parser).toSeq.flatten.foreach{strVal =>
				updateCategSet(categMap(prop), strVal, idx)
			}

		def updateHasVarList(idx: Int): Unit = {
			val hasVarsBm = boolBitmap(HasVarList)
			if(isAssertion) hasVarsBm.add(idx) else hasVarsBm.remove(idx)
		}

		pred match{

			case `hasObjectSpec` => obj match{
				case spec: IRI =>
					modForDobj(subj){oe =>
						updateCategSet(categMap(Spec), spec, oe.idx)
						if(!specRequiresStation.getOrElse(spec, true)) updateCategSet(categMap(Station), None, oe.idx)
						if(isAssertion) {
							if(oe.spec != null) removeStat(oe)
							oe.spec = spec
							addStat(oe)
						} else if(spec === oe.spec) {
							removeStat(oe)
							oe.spec = null
						}
					}
			}

			case `hasName` => modForDobj(subj){oe =>
				val fName = obj.stringValue
				if(isAssertion) oe.fName = fName
				else if(oe.fName == fName) oe.fileName == null
				handleContinuousPropUpdate(FileName, fName, oe.idx)
			}

			case `wasAssociatedWith` => subj match{
				case CpVocab.Submission(hash) =>
					val oe = getObjEntry(hash)
					removeStat(oe)
					oe.submitter = targetUri
					if(isAssertion) addStat(oe)
					obj match{ case subm: IRI => updateCategSet(categMap(Submitter), subm, oe.idx) }

				case CpVocab.Acquisition(hash) =>
					val oe = getObjEntry(hash)
					removeStat(oe)
					oe.station = targetUri
					if(isAssertion) addStat(oe)
					obj match{ case stat: IRI => updateCategSet(categMap(Station), Some(stat), oe.idx) }
				case _ =>
			}

			case `wasPerformedAt` => subj match {
				case CpVocab.Acquisition(hash) =>
					val oe = getObjEntry(hash)
					removeStat(oe)
					oe.site = targetUri
					if(isAssertion) addStat(oe)
					obj match{case site: IRI => updateCategSet(categMap(Site), Some(site), oe.idx)}
				case _ =>
			}

			case `hasStartTime` => ifDateTime(obj){ dt =>
				modForDobj(subj){oe =>
					oe.dataStart = dt
					handleContinuousPropUpdate(DataStart, dt, oe.idx)
				}
			}

			case `hasEndTime` => ifDateTime(obj){ dt =>
				modForDobj(subj){oe =>
					oe.dataEnd = dt
					handleContinuousPropUpdate(DataEnd, dt, oe.idx)
				}
			}

			case `startedAtTime` => ifDateTime(obj){ dt =>
				subj match{
					case CpVocab.Acquisition(hash) =>
						val oe = getObjEntry(hash)
						oe.dataStart = dt
						handleContinuousPropUpdate(DataStart, dt, oe.idx)
					case CpVocab.Submission(hash) =>
						val oe = getObjEntry(hash)
						oe.submissionStart = dt
						handleContinuousPropUpdate(SubmissionStart, dt, oe.idx)
					case _ =>
				}
			}

			case `endedAtTime` => ifDateTime(obj){ dt =>
				subj match{
					case CpVocab.Acquisition(hash) =>
						val oe = getObjEntry(hash)
						oe.dataEnd = dt
						handleContinuousPropUpdate(DataEnd, dt, oe.idx)
					case CpVocab.Submission(hash) =>
						val oe = getObjEntry(hash)
						oe.submissionEnd = dt
						handleContinuousPropUpdate(SubmissionEnd, dt, oe.idx)
					case _ =>
				}
			}

			case `isNextVersionOf` =>
				modForDobj(obj)(oe => {
					val deprecated = boolBitmap(DeprecationFlag)
					if(isAssertion) deprecated.add(oe.idx)
					else if(
						deprecated.contains(oe.idx) && //this was to prevent needless repo access
						!Using(sail.getConnection)(_.hasStatement(null, pred, obj, false)).getOrElse(false)
					) deprecated.remove(oe.idx)
				})

			case `hasSizeInBytes` => ifLong(obj){size =>
				modForDobj(subj){oe =>
					if(isAssertion) oe.size = size
					else if(oe.size == size) oe.size = -1
					if(size >= 0) handleContinuousPropUpdate(FileSize, size, oe.idx)
				}
			}

			case `hasSamplingHeight` => ifFloat(obj){height =>
				subj match{
					case CpVocab.Acquisition(hash) =>
						val oe = getObjEntry(hash)
						if(isAssertion) oe.samplingHeight = height
						else if(oe.samplingHeight == height) oe.samplingHeight = Float.NaN
						handleContinuousPropUpdate(SamplingHeight, height, oe.idx)
					case _ =>
				}
			}

			case `hasActualColumnNames` => modForDobj(subj){oe =>
				updateStrArrayProp(VariableName, parseJsonStringArray, oe.idx)
				updateHasVarList(oe.idx)
			}

			case `hasActualVariable` => obj match{
				case CpVocab.VarInfo(hash, varName) =>
					val oe = getObjEntry(hash)
					updateCategSet(categMap(VariableName), varName, oe.idx)
					updateHasVarList(oe.idx)
				case _ =>
			}

			case `hasKeywords` => modForDobj(subj){oe =>
				updateStrArrayProp(Keyword, s => Some(parseCommaSepList(s)), oe.idx)
			}

			case _ =>
		}
	}

	private def modForDobj(dobj: Value)(mod: ObjEntry => Unit): Unit = dobj match{
		case CpVocab.DataObject(hash, prefix) =>
			val entry = getObjEntry(hash)
			if(entry.prefix == "") entry.prefix = prefix.intern()
			mod(entry)

		case _ =>
	}

	private def keyForDobj(obj: ObjEntry): Option[StatKey] = if(
		obj.spec == null || obj.submitter == null ||
		(obj.station == null && specRequiresStation.getOrElse(obj.spec, true))
	) None else Some(
		StatKey(obj.spec, obj.submitter, Option(obj.station), Option(obj.site))
	)

	private def removeStat(obj: ObjEntry): Unit = for(key <- keyForDobj(obj)){
		stats.get(key).foreach(_.remove(obj.idx))
		initOk.remove(obj.idx)
	}

	private def addStat(obj: ObjEntry): Unit = for(key <- keyForDobj(obj)){
		stats.getOrElseUpdate(key, emptyBitmap).add(obj.idx)
		initOk.add(obj.idx)
	}

}

object CpIndex{
	val UpdateQueueSize = 1 << 13

	def emptyBitmap = MutableRoaringBitmap.bitmapOf()

	class IndexData(nObjects: Int) extends Serializable{
		private def this() = this(0)//for Kryo deserialization
		val idLookup = new AnyRefMap[Sha256Sum, Int](nObjects * 2)
		val objs = new ArrayBuffer[ObjEntry](nObjects)
		val boolMap = new AnyRefMap[BoolProperty, MutableRoaringBitmap]
		val categMaps = new AnyRefMap[CategProp, AnyRefMap[_, MutableRoaringBitmap]]
		val contMap = new AnyRefMap[ContProp, HierarchicalBitmap[_]]
		val stats = new AnyRefMap[StatKey, MutableRoaringBitmap]
		val initOk = emptyBitmap
	}

	class ObjEntry(val hash: Sha256Sum, val idx: Int, var prefix: String) extends ObjInfo with Serializable{
		private def this() = this(null, 0, null)//for Kryo deserialization
		var spec: IRI = uninitialized
		var submitter: IRI = uninitialized
		var station: IRI = uninitialized
		var site: IRI = uninitialized
		var size: Long = -1
		var fName: String = uninitialized
		var samplingHeight: Float = Float.NaN
		var dataStart: Long = Long.MinValue
		var dataEnd: Long = Long.MinValue
		var submissionStart: Long = Long.MinValue
		var submissionEnd: Long = Long.MinValue

		private def dateTimeFromLong(dt: Long): Option[Instant] =
			if(dt == Long.MinValue) None
			else Some(Instant.ofEpochMilli(dt))

		def sizeInBytes: Option[Long] = if(size >= 0) Some(size) else None
		def fileName: Option[String] = Option(fName)
		def samplingHeightMeters: Option[Float] = if(samplingHeight == Float.NaN) None else Some(samplingHeight)
		def dataStartTime: Option[Instant] = dateTimeFromLong(dataStart)
		def dataEndTime: Option[Instant] = dateTimeFromLong(dataEnd)
		def submissionStartTime: Option[Instant] = dateTimeFromLong(submissionStart)
		def submissionEndTime: Option[Instant] = dateTimeFromLong(submissionEnd)

		def uri(factory: ValueFactory): IRI = factory.createIRI(prefix + hash.base64Url)
	}

	private def ifDateTime(dt: Value)(mod: Long => Unit): Unit = dt match{
		case lit: Literal if lit.getDatatype === XSD.DATETIME =>
			try{
				mod(Instant.parse(lit.stringValue).toEpochMilli)
			}catch{
				case _: Throwable => //ignoring wrong dateTimes
			}
	}

	private def ifLong(dt: Value)(mod: Long => Unit): Unit = dt match{
		case lit: Literal if lit.getDatatype === XSD.LONG =>
			try{
				mod(lit.longValue)
			}catch{
				case _: Throwable => //ignoring wrong longs
			}
	}

	private def ifFloat(dt: Value)(mod: Float => Unit): Unit = dt match{
		case lit: Literal if lit.getDatatype === XSD.FLOAT =>
			try{
				mod(lit.floatValue)
			}catch{
				case _: Throwable => //ignoring wrong floats
			}
	}

	private def getStationRequirementsPerSpec(sail: Sail, vocab: CpmetaVocab): AnyRefMap[IRI, Boolean] = {
		val map = new AnyRefMap[IRI, Boolean]
		Using(sail.getConnection)(_
			.getStatements(null, vocab.hasSpecificDatasetType, null, false)
			.asPlainScalaIterator
			.foreach{
				case Rdf4jStatement(subj, _, obj: IRI) =>
					val objSpecRequiresStation = vocab.stationTimeSeriesDs === obj
					map.update(subj, objSpecRequiresStation)
				case _ =>
			}
		)
		map
	}

}
