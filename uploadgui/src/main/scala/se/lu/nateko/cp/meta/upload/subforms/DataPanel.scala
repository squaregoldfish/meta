package se.lu.nateko.cp.meta.upload.subforms

import scala.util.Try

import se.lu.nateko.cp.meta.upload._
import se.lu.nateko.cp.meta.{UploadDto, DataObjectDto}

import formcomponents._
import Utils._
import se.lu.nateko.cp.meta.SubmitterProfile
import UploadApp.whenDone


class DataPanel(
	objSpecs: IndexedSeq[ObjSpec],
	submitter: () => Option[SubmitterProfile]
)(implicit bus: PubSubBus) extends PanelSubform(".data-section"){
	def nRows: Try[Option[Int]] = nRowsInput.value.withErrorContext("Number of rows")
	def objSpec: Try[ObjSpec] = objSpecSelect.value.withMissingError("Data type not set")
	def keywords: Try[String] = keywordsInput.value

	private val levelControl = new Radio[Int]("level-radio", onLevelSelected, s => Try(s.toInt).toOption, _.toString)
	private val objSpecSelect = new Select[ObjSpec]("objspecselect", _.name, cb = onSpecSelected)
	private val nRowsInput = new IntOptInput("nrows", notifyUpdate)
	private val dataTypeKeywords = new TagCloud("data-keywords")
	private val keywordsInput = new TextInput("keywords", () => (), "keywords")
	private val dataTypeButton = new Button("data-type-variable-list-button", showDataTypeModal)
	private val dataTypeInfoModal = new Modal("data-type-info-modal")

	def resetForm(): Unit = {
		levelControl.value = Int.MinValue
		objSpecSelect.setOptions(IndexedSeq.empty)
		nRowsInput.value = None
		dataTypeKeywords.setList(Seq.empty)
		keywordsInput.value = ""
	}

	bus.subscribe{
		case GotUploadDto(dto) => handleDto(dto)
	}

	private def onLevelSelected(level: Int): Unit = {

		val levelFilter: ObjSpec => Boolean = _.dataLevel == level

		val specFilter = submitter().fold(levelFilter)(subm => {

			val themeOk = (spec: ObjSpec) => subm.authorizedThemes.fold(true)(_.contains(spec.theme))
			val projOk = (spec: ObjSpec) => subm.authorizedProjects.fold(true)(_.contains(spec.project))

			spec => themeOk(spec) && projOk(spec) && levelFilter(spec)
		})

		objSpecSelect.setOptions(objSpecs.filter(specFilter))
		dataTypeKeywords.setList(Seq.empty)
		bus.publish(LevelSelected(level))
	}

	private def onSpecSelected(): Unit = {
		objSpecSelect.value.foreach{ objSpec =>
			if(objSpec.dataset.nonEmpty && objSpec.dataLevel <= 2) nRowsInput.enable() else nRowsInput.disable()
			if(objSpec.dataset.nonEmpty) dataTypeButton.enable() else dataTypeButton.disable("No data type information available")
			dataTypeKeywords.setList(objSpec.keywords)
			bus.publish(ObjSpecSelected(objSpec))
		}
		notifyUpdate()
	}

	private def showDataTypeModal(): Unit = {
		objSpecSelect.value.map(spec => spec.dataset.map(datasetUri => whenDone(Backend.getDatasetVariables(datasetUri)){ datasetColumns =>
			val tableHeader = """<table class="table"><thead><th>Label</th><th>Name</th><th>Unit</th><th>Required</th></thead><tbody>"""
			val checkIcon = """<i class="fas fa-check"></i>"""
			dataTypeInfoModal.setTitle(spec.name)
			dataTypeInfoModal.setBody(datasetColumns.map { column =>
				s"""<tr><td>${column.label}</td><td>${column.name}</td><td>${column.unit}</td><td>${if(column.isOptionalColumn) "" else checkIcon}</td></tr>"""
			}.mkString(tableHeader, "", "</tbody></table>"))
		}))
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
