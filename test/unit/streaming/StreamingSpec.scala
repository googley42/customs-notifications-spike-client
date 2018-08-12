package unit.streaming

import akka.actor.ActorSystem
import akka.stream.{ActorMaterializer, ClosedShape, Graph, SourceShape}
import akka.{Done, NotUsed}
import akka.stream.scaladsl.{Broadcast, Flow, GraphDSL, Keep, Merge, Partition, RunnableGraph, Sink, Source}
import uk.gov.hmrc.play.test.UnitSpec

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
    "simplest async flow" in {
      //https://stackoverflow.com/questions/35146418/difference-between-map-and-mapasync
      val start = System.currentTimeMillis()
      val mapper: Int => Future[String] = i => Future{
        val sleep = Random.nextInt(1000)
        Thread.sleep(sleep)
        s"item $i, sleep = $sleep  elapsed=${System.currentTimeMillis() - start}"
      }
      val asynchFlow = Flow[Int].mapAsync[String](1)(mapper)
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
