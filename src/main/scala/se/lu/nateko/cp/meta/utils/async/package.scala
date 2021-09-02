package se.lu.nateko.cp.meta.utils

import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicBoolean

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.concurrent.duration.FiniteDuration
import scala.util.control.NoStackTrace

import akka.actor.Scheduler
import akka.Done

package object async {

	def ok: Future[Done] = Future.successful(Done)

	def error[T](msg: String): Future[T] = Future.failed(new Exception(msg))
	def errorLite[T](msg: String): Future[T] = Future.failed(new Exception(msg) with NoStackTrace)

	def timeLimit[T](
		future: Future[T],
		duration: FiniteDuration,
		using: Scheduler
	)(implicit ec: ExecutionContext): Future[T] = if(future.isCompleted) future else {

		val p = Promise[T]()
		future.onComplete(p.tryComplete)

		using.scheduleOnce(duration){

			p.tryFailure(new TimeoutException(s"Future timed out after $duration"))

		}

		p.future
	}

	def throttle(
		action: () => Unit,
		delay: FiniteDuration,
		using: Scheduler
	)(implicit ec: ExecutionContext): () => Unit = {
		val ongoing = new AtomicBoolean(false)

		() => {
			val wasOngoing = ongoing.getAndSet(true)
			if(!wasOngoing) using.scheduleOnce(delay){
				action()
				ongoing.set(false)
			}
		}
	}

	def traverseFut[T, R](on: Iterable[T])(thunk: T => Future[R])(implicit ctxt: ExecutionContext): Future[Seq[R]] =
		on.foldLeft(Future.successful(Seq.empty[R])){(acc, next) =>
			for(
				accRes <- acc;
				nextRes <- thunk(next)
			) yield accRes :+ nextRes
		}

	def executeSequentially[T](on: Iterable[T])(thunk: T => Future[Done])(implicit ctxt: ExecutionContext): Future[Done] =
		traverseFut(on)(thunk).map(_ => Done)
}
