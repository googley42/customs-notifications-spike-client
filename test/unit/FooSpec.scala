package unit

import uk.gov.hmrc.customs.notification.spike.model.{ClientSubscriptionId, Payloads}
import uk.gov.hmrc.play.test.UnitSpec

import scala.xml.{Node, Utility}

class FooSpec extends UnitSpec {
  // shared state here to compare sent notifications with received
  private val sent = scala.collection.mutable.Map[ClientSubscriptionId, State]()
  private val received = scala.collection.mutable.Map[ClientSubscriptionId, State]()

  case class State(seq: Seq[scala.xml.Node] = Seq.empty) {
    def add(n: Node): State = State(seq :+ Utility.trim(n)) //scala.xml.Utility.trim(n)
  }
  "a" should {
    "b" in {
      val p1_1 = Payloads.clientPlayload("c1", 1)
      sent.put("c1", sent.get("c1").fold(State(Seq(p1_1)))(s => s.add(p1_1)))
      val p1_2 = Payloads.clientPlayload("c1", 2)
      sent.put("c1", sent.get("c1").fold(State(Seq(p1_2)))(s => s.add(p1_2)))

      val p2_1 = Payloads.clientPlayload("c1", 1)
      received.put("c1", received.get("c1").fold(State(Seq(p1_1)))(s => s.add(p1_1)))
      val p2_2 = Payloads.clientPlayload("c1", 2)
      received.put("c1", received.get("c1").fold(State(Seq(p1_2)))(s => s.add(p1_2)))

      val x: Set[(ClientSubscriptionId, State)] = received.toSet

      println(s"\nsent=\n${sent.toSet}\nreceived=\n${received.toSet}\nsent==received: ${sent.toSet.equals(received.toSet)}")

      sent == received shouldBe true

      Set(1, 2) == Set(2, 1) shouldBe true
    }
  }
}
