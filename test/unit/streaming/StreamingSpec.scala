package unit.streaming

import java.util.concurrent.atomic.AtomicBoolean

import akka.actor.ActorSystem
import akka.stream._
import akka.{Done, NotUsed}
import akka.stream.scaladsl.{Broadcast, Flow, GraphDSL, Keep, Merge, Partition, RestartSource, RunnableGraph, Sink, Source}
import uk.gov.hmrc.play.test.UnitSpec

import scala.collection.immutable.Range.Inclusive
import scala.collection.immutable.Seq
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.concurrent.duration._
import scala.util.Random

/*
 ActorMaterializer
 contains a bunch of configurable options on how to run the stream, including things such as what dispatcher to use
 and how to size the input and output buffers for the processing stages

 Error handling
 https://doc.akka.io/docs/akka/2.5/stream/stream-error.html
*/
class StreamingSpec extends UnitSpec {
  implicit val system = ActorSystem()
  implicit val mater = ActorMaterializer()

  override implicit val defaultTimeout = 20 seconds

  "flow" should {
    "simplest example" in {
      case class Notification(i: Int)
      val source:Source[Notification, NotUsed] = Source(1 to 5).map(i => Notification(i))
      val sink:Sink[Notification, Future[Done]] = Sink.foreach[Notification](println)
      val dataflow:RunnableGraph[NotUsed] = source.to(sink)
      dataflow.run
    }
    "Cursor Source" in {
      val source = Source.fromGraph(new CursorSource(1,2,3))
      val sink = Sink.foreach[Int](i => println(s"i=$i"))
      val dataflow:RunnableGraph[NotUsed] = source.to(sink)
      dataflow.run
    }
    "some folding" in {
      val source:Source[Int, NotUsed] = Source(1 to 5)
      val sink2:Sink[Int, Future[Int]] = Sink.fold(0)(_+_)
      val dataflow2:RunnableGraph[Future[Int]] =
        source.toMat(sink2)(Keep.right)
      val fut:Future[Int] = dataflow2.run
      fut.onComplete(println)(system.dispatcher)
      dataflow2.run
    }
    "simplest flow" in {
      val source:Source[Int, NotUsed] = Source(1 to 5)
      val sink2:Sink[Int, Future[Int]] = Sink.fold(0)(_+_)
      val flow = Flow[Int].map(_*2).filter(_ % 2 == 0)
      val fut2 = source.via(flow).toMat(sink2)(Keep.right).run
      fut2.onComplete(println)(system.dispatcher)
    }
    "simplest flow TWO" in {
//      val source:Source[Int, NotUsed] = RestartSource.onFailuresWithBackoff(1 second, 2 second, 0.0, 2)(() => Source(1 to 5))
      val source:Source[Int, NotUsed] = Source(1 to 5)
      val sink2: Sink[Int, Future[Done]] = Sink.foreach(i => println(s"i=$i"))
      val flow = Flow[Int].map{i =>
        println(s"flow i=$i")
        if (i == 3) {
          println(s"flow i=$i about throw")
          throw new IllegalStateException("BOOM!")
        }
        //println(s"i=$i")
        i
      }.recoverWithRetries(20, {case e:IllegalStateException =>
        println(s"caught exception")
        Source(1 to 5)})
      source.via(flow).runWith(sink2)
      //fut2.onComplete(println)(system.dispatcher)
    }
    "simplest async flow" in {
      //https://stackoverflow.com/questions/35146418/difference-between-map-and-mapasync
      val start = System.currentTimeMillis()
      val mapper: Int => Future[String] = i => Future{
        val sleep = Random.nextInt(1000)
        Thread.sleep(sleep)
        s"item $i, sleep = $sleep  elapsed=${System.currentTimeMillis() - start}"
      }
      val asynchFlow = Flow[Int].mapAsync[String](20)(mapper)
      val maxItems = 20
      val source:Source[Int, NotUsed] = Source(1 to maxItems)
      val sink2 = Sink.foreach[String](println)
      val fut2 = source.via(asynchFlow).toMat(sink2)(Keep.right).run
      fut2.onComplete{
        println
      }(system.dispatcher)
      await(fut2)
    }
    "graph builder" in {
      val g = RunnableGraph.fromGraph(GraphDSL.create() {
        implicit builder: GraphDSL.Builder[NotUsed] =>

          import GraphDSL.Implicits._
          val in = Source(1 to 5)
          val out = Sink.foreach[Int](i => print(s"$i "))
          val f1 = Flow[Int].map(_*2)
          val f2 = Flow[Int].map(_ * 1)
          val f3 = Flow[Int].map(_*2)
          val f4 = Flow[Int].map(_+1)

          val bcast = builder.add(Broadcast[Int](2))
          val merge = builder.add(Merge[Int](2))

          in ~> f1 ~> bcast ~> f2 ~> merge  ~> f4 ~> out
          bcast ~> f3 ~> merge
          ClosedShape
      })
      g.run
    }
    /* FOR ASYNC NOTE ORDER OF PUSH/PULL IS NOT GUARANTEED
    Testing started at 09:53 ...
    Foo(1,item 1, flip=true sleep = 962  elapsed=1168,true)
    Foo(2,item 2, flip=true sleep = 214  elapsed=435,true)
    Foo(3,item 3, flip=true sleep = 326  elapsed=555,true)
    Foo(4,item 4, flip=true sleep = 798  elapsed=1036,true)
    Foo(5,item 5, flip=true sleep = 0  elapsed=260,true)
    Foo(6,item 6, flip=true sleep = 433  elapsed=685,true)
    Foo(7,item 7, flip=true sleep = 500  elapsed=752,true)
    Foo(8,item 8, flip=true sleep = 0  elapsed=264,true)
    Foo(9,item 9, flip=true sleep = 0  elapsed=264,true)
    Foo(10,item 10, flip=true sleep = 0  elapsed=264,true)
    */
    "graph builder with async stage" in {
      val flip = new AtomicBoolean(false)
      val max = 20
      case class Foo(i: Int,  msg: String, ok: Boolean = true)
      val g = RunnableGraph.fromGraph(GraphDSL.create() {
        implicit builder: GraphDSL.Builder[NotUsed] =>
          val start = System.currentTimeMillis()
          val mapper: Int => Future[Foo] = i => Future{
//println(s"i=$i flip=${flip.get} condition=${!flip.get() && i == 5}")
            flip.compareAndSet(false, !flip.get() && i == 5)
//            if (!flip.get() && i == 5) {
//              flip.set(true)
//            }
            var sleep = 0
            if (flip.get == false) {
              sleep = Random.nextInt(1000)
              Thread.sleep(sleep)
            }
            val msg = s"item $i, flip=$flip sleep = $sleep  elapsed=${System.currentTimeMillis() - start}"
            Foo(i, msg, flip.get)
          }
          val asynchFlow = Flow[Int].mapAsync[Foo](5)(mapper)

          import GraphDSL.Implicits._
          val in = Source(1 to max)
          val out = Sink.foreach[Foo](i => println(i))

          in ~> asynchFlow ~>  out
          ClosedShape
      })
      g.run()
      Thread.sleep(7000)
    }

    /*
    https://stackoverflow.com/questions/36681879/alternative-flows-based-on-condition-for-akka-stream

    You can use Broadcast to split the stream, then you will able to use filter or collect on each of streams to filter required data.

    val split = builder.add(Broadcast[Int](2))

    Src -> F1 -> split -> filterCondA -> F3 -> F6 -> Merge -> Sink
                       -> filterCondB -> F4 -> F5 -> Merge
    Also, there is Partition stage which handles the number of output ports and the map function from value to port number f: T => Int.

    val portMapper(value: T): Int = value match {
      case CondA => 0
      case CondB => 1
    }

    val split = builder.add(Partition[T](2, portMapper))

    Src -> F1 -> split -> F3 -> F6 -> Merge -> Sink
                 split -> F4 -> F5 -> Merge
     */
    "XOR graph flow" in {
      case class Notification(i: Int, isPush: Boolean = true)
      case class Declarant(i: Int)
      case class PushNotification(n: Notification, d: Option[Declarant])
      def portMapper(value: Notification): Int = value match {
        case Notification(i, _) if i % 2 == 0 => 0
        case _ => 1
      }

      val g = RunnableGraph.fromGraph(GraphDSL.create() {
        implicit builder: GraphDSL.Builder[NotUsed] =>

          import GraphDSL.Implicits._
          val seq = scala.collection.immutable.Seq(1,2,3,4,5)
          val in = Source(seq).map(i => Notification(i))
          val lookupDec = Flow[Notification].map(n => PushNotification(Notification(n.i), Some(Declarant(n.i))))
          val push = Sink.foreach[PushNotification](n => println(s"push $n "))
          val pull = Sink.foreach[Notification](n => println(s"pull $n "))
          val f1 = Flow[Notification].map(n =>Notification(n.i + 1))

          val router = builder.add(Partition[Notification](2, portMapper))

          in ~> f1 ~> router ~> lookupDec ~> push
                      router ~> pull
          ClosedShape
      })
      g.run
    }

    "Simple Feedback XOR graph flow" in {
      case class Notification(i: Int, isPush: Boolean = true)
      case class Declarant(i: Int)
      case class PushNotification(n: Notification, d: Option[Declarant])
      def portMapper(value: Notification): Int = value match {
        case Notification(_, isPush) if isPush => 0
        case _ => 1
      }

      val g = RunnableGraph.fromGraph(GraphDSL.create() {
        implicit builder: GraphDSL.Builder[NotUsed] =>

          import GraphDSL.Implicits._
          val seq = scala.collection.immutable.Seq(1,2,3,4,5)
          val in = Source(seq).map(i => Notification(i, i % 2 == 0))
          val lookupDec = Flow[Notification].map(n => PushNotification(n, Some(Declarant(n.i))))
          val push = Sink.foreach[PushNotification](n => println(s"push $n "))
          val pull = Sink.foreach[Notification](n => println(s"pull $n "))
          val f1 = Flow[Notification].map(n => n)
          val f2 = Flow[Notification].map(n => n.copy(i = n.i * 10, isPush = true))

          val merge = builder.add(Merge[Notification](2))
          val router = builder.add(Partition[Notification](2, portMapper))


          in ~> merge ~> router ~> f1 ~> pull
                         router ~> f2 ~> merge
          ClosedShape
      })
      g.run
    }

    /*
    TODO:
    flows need to be async stages
    extra partition stages for failure (could be reusable error handlers)
      if failed future goto other (1) port
    common delete stage
    ignored materialized value
     */
    "Complex Feedback XOR graph flow" in {
      case class Notification(i: Int)
      case class Declarant(i: Int)
      case class Holder(n: Notification, isPush: Boolean = true, d: Option[Declarant] = None, error: Option[String] = None)
      def portMapper(value: Holder): Int = value match {
        case Holder(_, isPush, _, _) if isPush => 0
        case _ => 1
      }

      val g = RunnableGraph.fromGraph(GraphDSL.create() {
        implicit builder: GraphDSL.Builder[NotUsed] =>

          import GraphDSL.Implicits._
          val seq = scala.collection.immutable.Seq(1,2,3,4,5)
          val in = Source(seq).map(i => Holder(Notification(i), i % 2 == 0))
          //          val lookupDec = Flow[Notification].map(n => PushNotification(n, Some(Declarant(n.i))))
          val push = Sink.foreach[Holder](h => println(s"push $h "))
          val pull = Sink.foreach[Holder](h => println(s"pull $h "))
          val f1 = Flow[Holder].map(h => h)
          val f2 = Flow[Holder].map(h => h.copy(n = h.n.copy(i = h.n.i * 10), isPush = true))

          val merge = builder.add(Merge[Holder](2))
          val router = builder.add(Partition[Holder](2, portMapper))

          in ~> merge ~> router ~> f1 ~> pull
          router ~> f2 ~> merge
          ClosedShape
      })
      g.run
    }

    "Complex Feedback XOR async graph flow" in {
      case class Notification(i: Int)
      case class Declarant(i: Int)
      case class Holder(n: Notification, isPush: Boolean = true, d: Option[Declarant] = None, error: Option[String] = None)
      def portMapper(value: Holder): Int = value match {
        case Holder(_, isPush, _, _) if isPush => 0
        case _ => 1
      }

      val g = RunnableGraph.fromGraph(GraphDSL.create() {
        implicit builder: GraphDSL.Builder[NotUsed] =>

          import GraphDSL.Implicits._
          val seq = scala.collection.immutable.Seq(1,2,3,4,5)
          val in = Source(seq).map { i =>
//            println(s"i=$i")
            Holder(Notification(i), i % 2 == 0)
          }
//          val lookupDec = Flow[Notification].map(n => PushNotification(n, Some(Declarant(n.i))))
          val push = Sink.foreach[Holder](h => println(s"push $h "))
          val pull = Sink.foreach[Holder](h => println(s"pull $h "))
          val f1 = Flow[Holder].mapAsync(1)(h => Future(h))
          val f2 = Flow[Holder].mapAsync(1)(h => Future(h.copy(n = h.n.copy(i = h.n.i * 10), isPush = true)))

          val merge = builder.add(Merge[Holder](2))
          val router = builder.add(Partition[Holder](2, portMapper))

          in ~> merge ~> router ~> f1 ~> pull
          router ~> f2 ~> merge

//          in ~> f1 ~> f2 ~> pull
          ClosedShape
      })
      g.run
      Thread.sleep(1000)
    }

    "Complex linear async graph flow" in {
      case class Notification(i: Int)
      case class Declarant(i: Int)
      case class Holder(n: Notification, isPush: Boolean = true, d: Option[Declarant] = None, error: Option[String] = None)

      def asyncFlow(isPush: Boolean)(block: Holder => Future[Holder]): Holder => Future[Holder] = h =>
        if (h.isPush == isPush) {
          block(h)
        } else {
          Future.successful(h)
        }

      val g = RunnableGraph.fromGraph(GraphDSL.create() {
        implicit builder: GraphDSL.Builder[NotUsed] =>

          import GraphDSL.Implicits._
          val seq = scala.collection.immutable.Seq(1,2,3,4,5)
          val in = Source(seq).map { i =>
            //            println(s"i=$i")
            Holder(Notification(i), i % 2 == 0)
          }
          val lookupDec = Flow[Holder].mapAsync(1)(asyncFlow(isPush = true){h =>
            Future(h.copy(d = Some(Declarant(h.n.i))))
          })
          val sink = Sink.foreach[Holder](h => println(s"push $h "))
          val push = Flow[Holder].mapAsync(1) {
            asyncFlow(isPush = true) { h =>
              println(s"doing push stuff for $h")
              Future(h)
            }
          }
          val pull = Flow[Holder].mapAsync(1) {
            asyncFlow(isPush = false) { h =>
              println(s"doing pull stuff for $h")
              Future(h.copy(n = h.n.copy(i = h.n.i * 10), isPush = false))
            }
          }

          in ~> lookupDec ~> push ~> pull ~> sink

          ClosedShape
      })
      g.run
      Thread.sleep(1000)
    }

    /*
    - RestartSource
      - RestartWithBackoffSource, imps startGraph & backOff
        - startGraph
          - sourceFactory().runWith(sinkIn.sink)(subFusingMaterializer)

        - RestartWithBackoffLogic
    Source.fromGraph(new RestartWithBackoffSource(...))
    RestartWithBackoffLogic
     */
    "RestartSource" in {
      val source = RestartSource.withBackoff(1 second, 5 seconds, 0.0, 2)(() => Source(Seq(1,2,3)))
      val sink = Sink.foreach(println)
      val dataflow = source.toMat(sink)(Keep.right)
      await(dataflow.run)
    }

    "Sink.last" in {
      val source = Source(Seq())
      val sink = Sink.last[Int]
      val dataflow = source.toMat(sink)(Keep.right)

      val error = intercept[NoSuchElementException](await(dataflow.run))

      error.getMessage shouldBe "last of empty stream"
    }



    "Sink.last with recover" in {
      val source = Source(Seq())
      val sink = Sink.lastOption[Int]
//        .map{i =>
//        throw new IllegalStateException("restart")
//      } //.recover{case _:NoSuchElementException => None}
      val dataflow = source.toMat(sink)(Keep.right)

      val r = await(dataflow.run)
      println(s"r = $r")
    }

    "Sink.head" in {
      val source = Source(Seq())
      val sink = Sink.head[Int]
      val dataflow = source.toMat(sink)(Keep.right)

      val error = intercept[NoSuchElementException](await(dataflow.run))

      error.getMessage shouldBe "head of empty stream"
    }

    //TODO get this working
    "RestartSource on Sink.last" in {
      var restartCount = 0
      val loggingMaterializer = ActorMaterializer(ActorMaterializerSettings(system).withSupervisionStrategy { e =>
        restartCount += 1
        println(s"Exception: $e restartCount=$restartCount")
        if (restartCount < 3) {
          Supervision.Restart
        } else {
          Supervision.stop
        }
      })

      //https://stackoverflow.com/questions/49225365/count-number-of-elements-in-akka-streams
      var counter = -1
      val map: Map[Int, Seq[Int]] = Map(0 -> scala.collection.immutable.Seq(1, 2), 1 -> scala.collection.immutable.Seq(3,4), 2 -> scala.collection.immutable.Seq())
      def cycleSeq: Seq[Int] = {
        counter += 1
        println(s"cycleSeq counter=$counter")
        map(counter)
      }
//      val source = RestartSource.onFailuresWithBackoff(1 second, 2 seconds, 0.0, 2)(() => Source(cycleSeq))
//      val source = Source(cycleSeq)
      val source = Source(1 to 2)
      val sink = Sink.foreach(println)
      val sink2 = Sink.last[Int]
      val dataflow =
        source.alsoToMat(sink)(Keep.right)
          .toMat(sink2)(Keep.both)
      val (fIgnore, fInt) = await(dataflow.run()/*(loggingMaterializer)*/)
      val i = await(fInt)
      println(s"Sink.last i=$i")
    }


    "cycle source" in {
      var counter = -1
      val map: Map[Int, Seq[Inclusive]] = Map(0 -> scala.collection.immutable.Seq(1 to 2), 1 -> scala.collection.immutable.Seq(3 to 4), 2 -> scala.collection.immutable.Seq(5 to 5))
      def myCycle = {
        counter += 1
        map(counter)
      }

      val source = Source.cycle(() => Seq.empty.iterator /*myCycle.iterator*/ )
      val sink = Sink.foreach(println)
      val dataflow = source.toMat(sink)(Keep.right)
      await(dataflow.run)
    }

    "simple custom source that emits a stream of ones" in {
      // A GraphStage is a proper Graph, just like what GraphDSL.create would return
      val sourceGraph: Graph[SourceShape[Int], NotUsed] = new NumbersSource
      // Create a Source from the Graph to access the DSL
      val mySource: Source[Int, NotUsed] = Source.fromGraph(sourceGraph)

      // Returns 55
      await(mySource.take(10).runFold(0)(_ + _)) shouldBe 55

      // The source is reusable. This returns 5050
      await(mySource.take(100).runFold(0)(_ + _)) shouldBe 5050
    }

    "custom source that fetched records twice" in {
      // A GraphStage is a proper Graph, just like what GraphDSL.create would return
      val sourceGraph: Graph[SourceShape[Int], NotUsed] = new NumbersSource
      // Create a Source from the Graph to access the DSL
      val mySource: Source[Int, NotUsed] = Source.fromGraph(sourceGraph)

      // Returns 55
      await(mySource.take(10).runFold(0)(_ + _)) shouldBe 55

      // The source is reusable. This returns 5050
      await(mySource.take(100).runFold(0)(_ + _)) shouldBe 5050
    }
  }
}
