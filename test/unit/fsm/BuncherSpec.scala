package unit.fsm

import akka.actor.{ActorSystem, Props}
import akka.testkit.{ImplicitSender, TestKit}
import org.scalatest.BeforeAndAfterAll
import uk.gov.hmrc.play.test.UnitSpec

class BuncherSpec extends TestKit(ActorSystem("MySpec")) with ImplicitSender with UnitSpec with BeforeAndAfterAll {

  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }

  "FSM Actor" should {
    "batch correctly" in {
      val buncher = system.actorOf(Props(classOf[Buncher]))
      buncher ! SetTarget(testActor)
      buncher ! Queue(42)
      buncher ! Queue(43)
      expectMsg(Batch(Seq(42, 43)))
      buncher ! Queue(44)
      buncher ! Flush
      buncher ! Queue(45)
      expectMsg(Batch(Seq(44)))
      expectMsg(Batch(Seq(45)))
    }
  }

}
