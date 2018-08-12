package unit.fsm

import akka.actor.{ActorSystem, Props}
import akka.testkit.{ImplicitSender, TestKit}
import org.mockito.ArgumentMatchers._
import org.mockito.Mockito._
import org.scalatest.BeforeAndAfterAll
import org.scalatest.mockito.MockitoSugar
import uk.gov.hmrc.play.test.UnitSpec

import scala.concurrent.Future

class ClientWorkerSpec extends TestKit(ActorSystem("MySpec")) with ImplicitSender with UnitSpec with BeforeAndAfterAll with MockitoSugar {

  override def afterAll {
    TestKit.shutdownActorSystem(system)
  }

  trait SetUp {
    val mockRepo = mock[Repo]
    val mockDeclarant = mock[Declarant]
    val mockPush = mock[Push]
    val mockPull = mock[Pull]
    val worker = system.actorOf(Props(classOf[ClientWorker], "csid1", "lockOwner1", mockRepo, mockDeclarant, mockPush, mockPull))
  }

  "Client Worker FSM Actor" should {
    "happy path" in new SetUp {
      when(mockRepo.fetch(any[String])).thenReturn(Future.successful(List(ClientNotification(1))), Future.successful(Nil))
      when(mockRepo.release(any[String], any[String])).thenReturn(Future.successful(()))
      when(mockDeclarant.fetch(any[String])).thenReturn(Future.successful(Some(DeclarantDetails(1))))
      when(mockPush.send(any[DeclarantDetails], any[ClientNotification])).thenReturn(Future.successful(()))
      when(mockRepo.delete(any[ClientNotification])).thenReturn(Future.successful(true))
      worker ! StartEvt

      Thread.sleep(2000)
    }

    "repo throws exception" in new SetUp {
      when(mockRepo.fetch(any[String])).thenReturn(Future.failed(new RuntimeException("BOOM!")))
      when(mockRepo.release(any[String], any[String])).thenReturn(Future.successful(()))
      worker ! StartEvt

      Thread.sleep(2000)
    }

    "push fails and we send to PULL queue" in new SetUp {
      when(mockRepo.fetch(any[String])).thenReturn(Future.successful(List(ClientNotification(1))), Future.successful(Nil))
      when(mockRepo.release(any[String], any[String])).thenReturn(Future.successful(()))
      when(mockDeclarant.fetch(any[String])).thenReturn(Future.successful(Some(DeclarantDetails(1))))
      when(mockPush.send(any[DeclarantDetails], any[ClientNotification])).thenReturn(Future.failed(new RuntimeException("PUSH SEND BOOM!")))
      when(mockPull.send(any[ClientNotification])).thenReturn(Future.successful(()))
      when(mockRepo.delete(any[ClientNotification])).thenReturn(Future.successful(true))
      worker ! StartEvt

      Thread.sleep(2000)
    }

    "push AND pull fail and we leave notification in DB" in new SetUp {
      when(mockRepo.fetch(any[String])).thenReturn(Future.successful(List(ClientNotification(1))), Future.successful(Nil))
      when(mockRepo.release(any[String], any[String])).thenReturn(Future.successful(()))
      when(mockDeclarant.fetch(any[String])).thenReturn(Future.successful(Some(DeclarantDetails(1))))
      when(mockPush.send(any[DeclarantDetails], any[ClientNotification])).thenReturn(Future.failed(new RuntimeException("PUSH SEND BOOM!")))
      when(mockPull.send(any[ClientNotification])).thenReturn(Future.failed((new RuntimeException("PULL SEND BOOM!"))))
      worker ! StartEvt

      Thread.sleep(2000)
    }
  }

}
