package se.lu.nateko.cp.meta.utils.streams

import java.io.BufferedOutputStream
import java.io.OutputStream
import java.util.concurrent.ArrayBlockingQueue

import scala.concurrent.ExecutionContext
import scala.concurrent.Future
import scala.concurrent.Promise
import scala.util.Failure
import scala.util.Success
import scala.util.Try

import akka.Done
import akka.stream.Attributes
import akka.stream.Outlet
import akka.stream.SourceShape
import akka.stream.scaladsl.Source
import akka.stream.stage.AsyncCallback
import akka.stream.stage.GraphStageLogic
import akka.stream.stage.GraphStageWithMaterializedValue
import akka.stream.stage.OutHandler
import akka.util.ByteString

object OutputStreamWriterSource {

	def apply(writer: OutputStream => Unit)(implicit ctxt: ExecutionContext) : Source[ByteString, Future[Done]] =
		Source.fromGraph(new OutputStreamWriterSource(writer))
}

private class OutputStreamWriterSource(writer: OutputStream => Unit)(implicit ctxt: ExecutionContext) extends
		GraphStageWithMaterializedValue[SourceShape[ByteString], Future[Done]]{

	private val out: Outlet[ByteString] = Outlet("OutputStreamWriterSourceOutput")

	override val shape = SourceShape(out)

	override def createLogicAndMaterializedValue(inheritedAttributes: Attributes) = {
		val done = Promise[Done]()
		val bsq = new ArrayBlockingQueue[ByteString](3)

		val logic = new GraphStageLogic(shape){

			private[this] val hasInitialized = Promise[Unit]()

			val asyncPush: Future[AsyncCallback[Unit]] = hasInitialized.future.map(
				_ => getAsyncCallback[Unit]{_ =>
					if(isAvailable(out)) {
						push(out, bsq.take())
					}
				}
			)

			override def preStart(): Unit = {
				val completer = getAsyncCallback[Try[Done]]{
					case Success(_) =>
						if(isAvailable(out)) {
							pushIfAny()
							if(bsq.peek == null) completeStage()
						}
					case Failure(err) => failStage(err)
				}
				done.future.onComplete(completer.invoke)
				hasInitialized.success(())
			}

			setHandler(out, new OutHandler {
				override def onPull(): Unit = pushIfAny()
			})

			private def pushIfAny(): Unit = {
				val head = bsq.poll()
				if(head != null) {
					push(out, head)
				} else if(done.isCompleted) completeStage() //queue is empty and writer finished
			}
		}

		val os: Future[OutputStream] = logic.asyncPush.map{asyncPush =>
			val os = new OutputStream{
				override def write(b: Array[Byte]) = {
					bsq.put(ByteString(b))
					asyncPush.invoke(())
				}
				override def write(b: Array[Byte], off: Int, len: Int) = {
					bsq.put(ByteString.fromArray(b, off, len))
					asyncPush.invoke(())
				}
				override def write(b: Int) = {
					bsq.put(ByteString(b.toByte))
					asyncPush.invoke(())
				}
			}
			new BufferedOutputStream(os)
		}
		os.flatMap(os => Future{writer(os); Done}).onComplete(done.complete)

		(logic, done.future)
	}
}
