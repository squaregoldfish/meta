package se.lu.nateko.cp.meta.upload.subforms

import scala.util.Try

import se.lu.nateko.cp.meta.upload._
import se.lu.nateko.cp.meta.{UploadDto, DataObjectDto}

import formcomponents._
import Utils._


class DataPanel(objSpecs: IndexedSeq[ObjSpec])(implicit bus: PubSubBus) extends PanelSubform(".data-section"){
	def nRows: Try[Option[Int]] = nRowsInput.value.withErrorContext("Number of rows")
	def objSpec: Try[ObjSpec] = objSpecSelect.value.withMissingError("Data type not set")
	def keywords: Try[String] = keywordsInput.value

	private val levelControl = new Radio[Int]("level-radio", onLevelSelected, s => Try(s.toInt).toOption, _.toString)
	private val objSpecSelect = new Select[ObjSpec]("objspecselect", _.name, cb = onSpecSelected)
	private val nRowsInput = new IntOptInput("nrows", notifyUpdate)
	private val keywordsInput = new TextInput("keywords", () => (), "keywords")

	def resetForm(): Unit = {
		levelControl.value = Int.MinValue
		objSpecSelect.setOptions(IndexedSeq.empty)
		nRowsInput.value = None
		keywordsInput.value = ""
	}

	bus.subscribe{
		case GotUploadDto(dto) => handleDto(dto)
	}

	private def onLevelSelected(level: Int): Unit = {
		objSpecSelect.setOptions(objSpecs.filter(_.dataLevel == level))
		bus.publish(LevelSelected(level))
	}

	private def onSpecSelected(): Unit = {
		objSpecSelect.value.foreach{ objSpec =>
			if(objSpec.hasDataset && objSpec.dataLevel <= 2) nRowsInput.enable() else nRowsInput.disable()
			bus.publish(ObjSpecSelected(objSpec))
		}
		notifyUpdate()
	}

	private def handleDto(upDto: UploadDto): Unit = upDto match {
		case dto: DataObjectDto =>
			objSpecs.find(_.uri == dto.objectSpecification).foreach{spec =>
				levelControl.value = spec.dataLevel
				onLevelSelected(spec.dataLevel)
				objSpecSelect.value = spec
				onSpecSelected()
			}
			keywordsInput.value = dto.references.fold("")(_.keywords.fold("")(_.mkString(", ")))
			dto.specificInfo.fold(
				_ => nRowsInput.reset(),
				l2 => nRowsInput.value = l2.nRows
			)

			show()
		case _ =>
			hide()
	}
}
