package se.lu.nateko.cp.meta.services.labeling

import java.net.URI

import org.eclipse.rdf4j.model.IRI
import org.eclipse.rdf4j.model.Statement

import se.lu.nateko.cp.cpauth.core.UserId
import se.lu.nateko.cp.meta.instanceserver.InstanceServer
import se.lu.nateko.cp.meta.mail.SendMail
import se.lu.nateko.cp.meta.utils.rdf4j.*
import se.lu.nateko.cp.meta.services.IllegalLabelingStatusException
import se.lu.nateko.cp.meta.services.UnauthorizedStationUpdateException

import scala.concurrent.{Future, ExecutionContext}
import scala.util.Try
import scala.util.Success
import scala.util.Failure

trait LifecycleService { self: StationLabelingService =>

	import LifecycleService.*

	private val mailer = SendMail(config.mailing)

	def updateStatus(station: URI, newStatus: String, newStatusComment: Option[String], user: UserId)(implicit ctxt: ExecutionContext): Try[Unit] = {
		val stationUri = factory.createIRI(station)

		for(
			toStatus <- lookupAppStatus(newStatus);
			fromStatus <- getCurrentStatus(stationUri);
			_ <- authorizeTransition(fromStatus, toStatus, user, stationUri)
		) yield {
			updateStatusComment(stationUri, newStatusComment)
			if(fromStatus != toStatus){
				writeStatusChange(toStatus, stationUri)
				sendMailOnStatusUpdate(fromStatus, toStatus, newStatusComment, stationUri, user)
			}
		}
	}

	private def authorizeTransition(fromStatus: AppStatus, toStatus: AppStatus, user: UserId, stationUri: IRI): Try[Unit] =
		if(fromStatus == toStatus){

			if(Role.values.exists(role => userHasRole(user, role, stationUri)))
				Success(())
			else
				Failure(new UnauthorizedStationUpdateException(s"User lacks any role for station $stationUri, cannot update status comment"))

		} else getRoleForTransition(fromStatus, toStatus).flatMap{role =>
			if(userHasRole(user, role, stationUri))
				Success(())
			else
				Failure(new UnauthorizedStationUpdateException(s"User lacks the role of $role for station $stationUri"))
		}

	private def updateStatusComment(stationUri: IRI, newStatusCommentOpt: Option[String]): Unit = {
		val currentCommentStats = getStatements(stationUri, vocab.hasAppStatusComment)

		val newCommentStats = newStatusCommentOpt.toSeq.map{newStatusComment =>
			factory.createStatement(stationUri, vocab.hasAppStatusComment, factory.createLiteral(newStatusComment))
		}
		server.applyDiff(currentCommentStats, newCommentStats)
	}

	private def getTcUsers(station: IRI): Seq[String] = lookupStationClass(station)
		.flatMap(cls => config.tcUserIds.get(cls.toJava))
		.toSeq
		.flatten
		.map(_.toLowerCase)

	private def userHasRole(user: UserId, role: Role, station: IRI): Boolean = role match{
		case Role.PI =>
			userIsPiOrDeputy(user, station)
		case Role.TC =>
			getTcUsers(station).contains(user.email.toLowerCase)
		case Role.DG =>
			config.dgUserId.equalsIgnoreCase(user.email)
	}

	private def getCurrentStatus(station: IRI): Try[AppStatus] = {
		server.getStringValues(station, vocab.hasApplicationStatus)
			.headOption
			.map(lookupAppStatus)
			.getOrElse(Success(AppStatus.neverSubmitted))
	}

	private def stationIsAtmospheric(station: IRI): Boolean =
		lookupStationClass(station).map(_ == vocab.atmoStationClass).getOrElse(false)

	private def getStatements(subject: IRI, predicate: IRI): IndexedSeq[Statement] =
		server.getStatements(Some(subject), Some(predicate), None).toIndexedSeq

	private def writeStatusChange(to: AppStatus, station: IRI): Unit = {
		val current = getStatements(station, vocab.hasApplicationStatus) ++ getStatements(station, vocab.hasAppStatusDate)
		val newStats = Seq(
			factory.createStatement(station, vocab.hasApplicationStatus, vocab.lit(to.toString)),
			factory.createStatement(station, vocab.hasAppStatusDate, vocab.lit(java.time.Instant.now()))
		)
		server.applyDiff(current, newStats)
	}

	private def sendMailOnStatusUpdate(
		from: AppStatus, to: AppStatus, statusComment: Option[String],
		station: IRI, user: UserId
	)(implicit ctxt: ExecutionContext): Unit = if(config.mailing.mailSendingActive) Future{

		val stationId = lookupStationId(station).getOrElse("???")

		(from, to) match{
			case (_, AppStatus.step1submitted) =>
				val recipients: Seq[String] = getTcUsers(station)
				val subject = "Application for labeling received"
				val body = views.html.LabelingEmailSubmitted1(user, stationId).body

				mailer.send(recipients, subject, body, cc = Seq(user.email))

			case (AppStatus.step1approved, AppStatus.step2ontrack) =>
				val calLabRecipients = if(stationIsAtmospheric(station)) config.calLabEmails else Nil
				val recipients = (getTcUsers(station) :+ config.dgUserId) ++ calLabRecipients
				val subject = s"Labeling Step2 activated for $stationId"
				val body = views.html.LabelingEmailActivated2(user, stationId).body

				mailer.send(recipients, subject, body, cc = Seq(user.email))

			case (_, AppStatus.step2approved) | (_, AppStatus.step2stalled) | (_, AppStatus.step2delayed) if(from != AppStatus.step3approved) =>
				val status: String = to match{
					case AppStatus.step2delayed => "delayed"
					case AppStatus.step2stalled => "stalled"
					case _ => "approved"
				}

				val comment = to match{
					case AppStatus.step2delayed | AppStatus.step2stalled => statusComment
					case _ => None
				}

				val recipients = getStationPiOrDeputyEmails(station)
				val cc = getTcUsers(station) :+ config.riComEmail
				val subject = s"Labeling Step2 ${status} for $stationId"
				val body = views.html.LabelingEmailDecided2(stationId, status, comment).body

				mailer.send(recipients, subject, body, cc)

			case (_, AppStatus.step3approved) =>
				val recipients = getStationPiOrDeputyEmails(station)
				val subject = s"Labeling complete for $stationId"
				val body = views.html.LabelingEmailApproved3(stationId).body
				val cc = Seq(config.riComEmail)

				mailer.send(recipients, subject, body, cc)

			case _ => ()
		}
	}
}

object LifecycleService{

	enum AppStatus(title: String):
		case neverSubmitted    extends AppStatus("NEVER SUBMITTED")
		case step1notsubmitted extends AppStatus("NOT SUBMITTED")
		case step1submitted    extends AppStatus("SUBMITTED")
		case step1acknowledged extends AppStatus("ACKNOWLEDGED")
		case step1approved     extends AppStatus("APPROVED")
		case rejected          extends AppStatus("REJECTED")
		case step2ontrack      extends AppStatus("STEP2ONTRACK")
		case step2started_old  extends AppStatus("STEP2STARTED")
		case step2delayed      extends AppStatus("STEP2DELAYED")
		case step2stalled      extends AppStatus("STEP2STALLED")
		case step2approved     extends AppStatus("STEP2APPROVED")
		case step3approved     extends AppStatus("STEP3APPROVED")
		override def toString = title

	object AppStatus{
		val lookup: Map[String, AppStatus] = AppStatus.values.map(as => as.toString -> as).toMap
	}

	enum Role(title: String):
		case PI extends Role("Station PI")
		case TC extends Role("ICOS TC representative")
		case DG extends Role("ICOS DG")
		override def toString = title

	import AppStatus.*
	import Role.*

	val transitions: Map[AppStatus, Map[AppStatus, Role]] = Map(
		neverSubmitted -> Map(step1submitted -> PI),
		step1submitted -> Map(step1acknowledged -> TC),
		step1acknowledged -> Map(
			step1notsubmitted -> TC,
			step1approved -> TC,
			rejected -> TC
		),
		step1notsubmitted -> Map(
			step1approved -> TC,
			rejected -> TC,
			step1submitted -> PI
		),
		step1approved -> Map(
			rejected -> TC,
			step1notsubmitted -> TC,
			step2ontrack -> PI
		),
		rejected -> Map(
			step1approved -> TC,
			step1notsubmitted -> TC
		),
		step2ontrack -> Map(
			step1approved -> TC,
			step2approved -> TC,
			step2stalled -> TC,
			step2delayed -> TC
		),
		step2approved -> Map(
			step2ontrack -> TC,
			step3approved -> DG
		),
		step2stalled -> Map(
			step2ontrack -> TC,
			step2delayed -> TC,
			step2approved -> TC
		),
		step2delayed -> Map(
			step2ontrack -> TC,
			step2stalled -> TC,
			step2approved -> TC
		),
		step3approved -> Map(step2approved -> DG)
	)

	private def getRoleForTransition(from: AppStatus, to: AppStatus): Try[Role] = {
		transitions.get(from) match{

			case None =>
				val message = s"No transitions defined from status: $from"
				Failure(new IllegalLabelingStatusException(message))

			case Some(destinations) =>
				destinations.get(to) match {

					case None =>
						val message = s"No transitions defined from $from to $to"
						Failure(new IllegalLabelingStatusException(message))

					case Some(role) =>
						Success(role)
				}
		}
	}

	private def lookupAppStatus(name: String): Try[AppStatus] = AppStatus.lookup.get(name).fold{
		val msg = s"Unsupported labeling application status '$name'"
		Failure(new IllegalLabelingStatusException(msg))
	}(Success.apply)

}
