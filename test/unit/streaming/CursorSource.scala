package unit.streaming

import akka.stream.stage.{GraphStage, GraphStageLogic, OutHandler}
import akka.stream.{Attributes, Outlet, SourceShape}

class CursorSource(seq: Int *) extends GraphStage[SourceShape[Int]] {
  val out: Outlet[Int] = Outlet("NumbersSource")
  override val shape: SourceShape[Int] = SourceShape(out)

  override def createLogic(inheritedAttributes: Attributes): GraphStageLogic =
    new GraphStageLogic(shape) {
      // All state MUST be inside the GraphStageLogic,
      // never inside the enclosing GraphStage.
      // This state is safe to access and modify from all the
      // callbacks that are provided by GraphStageLogic and the
      // registered handlers.
      private val iterator = seq.iterator

      setHandler(out, new OutHandler {
        override def onPull(): Unit = {
          if (iterator.hasNext) {
            println(s"XXXXXXXXXXXXX hasNext()")
            push(out, iterator.next())
          } else {
            println(s"XXXXXXXXXXXXX does not have next")
          }
        }

        override def onDownstreamFinish(): Unit = {
          println(s"XXXXXXXXXXXXXx onDownstreamFinish called")
          super.onDownstreamFinish()
        }


      })
    }
}
