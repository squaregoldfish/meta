package se.lu.nateko.cp.meta.services.labeling

import scala.util.Try

import java.net.{URI => JavaURI}

import org.openrdf.model.Literal
import org.openrdf.model.URI
import org.openrdf.model.vocabulary.RDF

import se.lu.nateko.cp.cpauth.core.UserId
import se.lu.nateko.cp.meta.LabelingUserDto
import se.lu.nateko.cp.meta.services.UnauthorizedUserInfoUpdateException
import se.lu.nateko.cp.meta.utils.sesame._

trait UserInfoService { self: StationLabelingService =>

	private val (factory, vocab) = getFactoryAndVocab(provisionalInfoServer)

	private val userToTcsLookup: Map[String, Seq[JavaURI]] = {
		val userTcPairs = for(
			(tcUri, userMails) <- config.tcUserIds.toSeq;
			userMail <- userMails
		) yield (userMail, tcUri)

		userTcPairs.groupBy(_._1).mapValues(pairs => pairs.map(_._2))
	}

	def getLabelingUserInfo(uinfo: UserId): LabelingUserDto = {
		val allEmails = provisionalInfoServer.getStatements(None, Some(vocab.hasEmail), None)

		val piUriOpt = allEmails.collectFirst{
			case SesameStatement(uri: URI, _, mail)
				if(mail.stringValue.equalsIgnoreCase(uinfo.email)) => uri
		}
		allEmails.close()

		val tcs = userToTcsLookup.get(uinfo.email).getOrElse(Nil)
		val isDg: Boolean = config.dgUserId == uinfo.email

		piUriOpt match{
			case None =>
				LabelingUserDto(None, uinfo.email, false, isDg, tcs, None, None)
			case Some(piUri) =>
				val props = provisionalInfoServer
					.getStatements(piUri)
					.groupBy(_.getPredicate)
					.map{case (pred, statements) => (pred, statements.head)} //ignoring multiprops
					.collect{case (pred, SesameStatement(_, _, v: Literal)) => (pred, v.getLabel)} //keeping only data props
					.toMap
				LabelingUserDto(
					uri = Some(piUri),
					mail = uinfo.email,
					isPi = true,
					isDg = isDg,
					tcs = tcs,
					firstName = props.get(vocab.hasFirstName),
					lastName = props.get(vocab.hasLastName),
					affiliation = props.get(vocab.hasAffiliation),
					phone = props.get(vocab.hasPhone)
				)
		}
	}

	def saveUserInfo(info: LabelingUserDto, uploader: UserId): Unit = {
		if(info.uri.isEmpty) throw new UnauthorizedUserInfoUpdateException("User must be identified by a URI")
		val userUri = factory.createURI(info.uri.get)
		val userEmail = getPiEmails(userUri).toIndexedSeq.headOption.getOrElse(
			throw new UnauthorizedUserInfoUpdateException("User had no email in the database")
		)
		if(!userEmail.equalsIgnoreCase(uploader.email))
			throw new UnauthorizedUserInfoUpdateException("User is allowed to update only his/her own information")

		def fromString(pred: URI)(str: String) = factory.createStatement(userUri, pred, vocab.lit(str))

		val newInfo = Seq(
			info.firstName.map(fromString(vocab.hasFirstName)),
			info.lastName.map(fromString(vocab.hasLastName)),
			info.affiliation.map(fromString(vocab.hasAffiliation)),
			info.phone.map(fromString(vocab.hasPhone))
		).flatten

		val protectedPredicates = Set(vocab.hasEmail, RDF.TYPE)

		val currentInfo = provisionalInfoServer.getStatements(userUri).filter{
			case SesameStatement(_, pred, _) if protectedPredicates.contains(pred) => false
			case _ => true
		}

		provisionalInfoServer.applyDiff(currentInfo, newInfo)
	}
}
