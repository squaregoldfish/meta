package se.lu.nateko.cp.meta.icos

import java.time.Instant

import akka.stream.scaladsl.Source
import se.lu.nateko.cp.meta.core.data.Position


sealed trait Entity[+T <: TC]{
	def cpId: String
	def tcId: TcId[T]
}

trait CpIdSwapper[E]{
	def withCpId(e: E, id: String): E
}

object Entity{

	implicit class IdSwapOps[E](e: E)(implicit swapper: CpIdSwapper[E]){
		def withCpId(id: String): E = swapper.withCpId(e, id)
	}

	implicit def persCpIdSwapper[T <: TC] = new CpIdSwapper[Person[T]]{
		def withCpId(p: Person[T], id: String) = p.copy(cpId = id)
	}

	implicit def orgCpIdSwapper[T <: TC] = new CpIdSwapper[Organization[T]]{
		def withCpId(org: Organization[T], id: String) = org match{
			case ss: CpStationaryStation[T] => ss.copy(cpId = id)
			case ms: CpMobileStation[T] => ms.copy(cpId = id)
			case ci: CompanyOrInstitution[T] => ci.copy(cpId = id)
		}
	}

	implicit def instrCpIdSwapper[T <: TC] = new CpIdSwapper[Instrument[T]]{
		def withCpId(instr: Instrument[T], id: String) = instr.copy(cpId = id)
	}

}

case class Person[+T <: TC](cpId: String, tcId: TcId[T], fname: String, lName: String, email: Option[String]) extends Entity[T]

sealed trait Organization[+T <: TC] extends Entity[T]{ def name: String }

sealed trait CpStation[+T <: TC] extends Organization[T]{
	def id: String
	def country: Option[CountryCode]
}

case class CpStationaryStation[+T <: TC](
	cpId: String,
	tcId: TcId[T],
	name: String,
	id: String,
	country: Option[CountryCode],
	pos: Position
) extends CpStation[T]

case class CpMobileStation[+T <: TC](
	cpId: String,
	tcId: TcId[T],
	name: String,
	id: String,
	country: Option[CountryCode],
	geoJson: Option[String]
) extends CpStation[T]

class TcStation[+T <: TC](val station: CpStation[T], val pi: T#Pis)

case class CompanyOrInstitution[+T <: TC](cpId: String, tcId: TcId[T], name: String, label: Option[String]) extends Organization[T]

case class Instrument[+T <: TC](
	cpId: String,
	tcId: TcId[T],
	model: String,
	sn: String,
	name: Option[String] = None,
	vendor: Option[Organization[T]] = None,
	owner: Option[Organization[T]] = None,
	partsCpIds: Seq[String] = Nil
) extends Entity[T]

class AssumedRole[+T <: TC](val role: Role, val holder: Person[T], val org: Organization[T]){
	def id = (role.name, holder.tcId, org.tcId)
	override def toString = s"AssumedRole($role , $holder , $org )"
}

class TcAssumedRole[+T <: TC](role: NonPiRole, holder: Person[T], org: Organization[T]) extends AssumedRole(role, holder, org)
case class Membership[+T <: TC](cpId: String, role: AssumedRole[T], start: Option[Instant], stop: Option[Instant])

class CpTcState[+T <: TC : TcConf](val stations: Seq[CpStation[T]], val roles: Seq[Membership[T]], val instruments: Seq[Instrument[T]]){
	def tcConf: TcConf[T] = implicitly[TcConf[T]]
}

class TcState[+T <: TC](val stations: Seq[TcStation[T]], val roles: Seq[TcAssumedRole[T]], val instruments: Seq[Instrument[T]])

abstract class TcMetaSource[T <: TC]{
	type State = TcState[T]
	type Station = TcStation[T]
	def state: Source[State, Any]
}
