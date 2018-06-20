/*
 * Copyright 2018 HM Revenue & Customs
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package uk.gov.hmrc.customs.notification.spike.actors

import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

import akka.actor.{Actor, ActorLogging, Cancellable, Props}
import play.api.mvc.Results.{InternalServerError, Ok}
import play.api.mvc.{AnyContent, Request, Result}
import uk.gov.hmrc.customs.notification.spike.actors.NotificationsSenderActor._
import uk.gov.hmrc.customs.notification.spike.connectors.{NotificationConnector, NotificationConnector2}
import uk.gov.hmrc.customs.notification.spike.model.{ClientSubscriptionId, Notification}

import scala.collection.immutable.Seq
import scala.concurrent.{Future, blocking}
import scala.concurrent.duration._
import scala.concurrent.ExecutionContext.Implicits.global

object NotificationsSenderActor {
  private case object Tick
  case object Start
  case class Received(n: Notification)
  case object Stop
  case object Compare
  case class CompareReport(report: String)

  def props(connector: NotificationConnector2): Props = Props(classOf[NotificationsSenderActor], connector)
}

class NotificationsSenderActor(connector: NotificationConnector2) extends Actor with ActorLogging {

  // shared mutable state here to compare sent notifications with received
  var maybeTickTask: Option[Cancellable] = None
  var sent = scala.collection.mutable.Map[ClientSubscriptionId, State]()
  var received = scala.collection.mutable.Map[ClientSubscriptionId, State]()
  var sendErrors = SendErrors()

  private val seqA = new AtomicInteger()
  private val seqB = new AtomicInteger()

  case class State(seq: Seq[Notification] = Seq.empty) {
    def add(n: Notification): State = State(seq :+ n)
  }

  case class SendErrors(seq: Seq[ClientSubscriptionId] = Seq.empty) {
    def add(n: ClientSubscriptionId): SendErrors = SendErrors(seq :+ n)
  }

  override def receive: Receive = {
    case Tick =>
      interleaveClientRequests
    case Start =>
      sent.clear()
      received.clear()
      sendErrors = SendErrors()
      log.info(s"about to start sender")
      maybeTickTask.map(t => t.cancel)
      maybeTickTask = Some(context.system.scheduler.schedule(5.seconds, 5.seconds, self, Tick)) // new immutable scheduler
      sender ! "Started"
    case Received(notification) =>
      log.info(s"received payload for ${notification.clientSubscriptionId}")
      received.put(notification.clientSubscriptionId, received.get(notification.clientSubscriptionId).fold(State(Seq(notification)))(s => s.add(notification)))
    case Stop =>
      log.info("about to cancel sending")
      maybeTickTask.map(t => t.cancel)
      sender ! "Stoped"
    case Compare =>
      val compare = s"\nsent==received: ${sent.toSet.equals(received.toSet)}\nSend errors=\n${sendErrors.toString}\nsent=\n${sent}\nreceived=\n${received}"
      sender ! CompareReport(compare)
  }



  private def sendNotificationForClient(c: ClientSubscriptionId, seq: AtomicInteger): Future[Result] = {

    blocking {
      Thread.sleep(100) // we need this to preserve sequencing of callbacks - not sure why
    }

    val next = seq.addAndGet(1)

    connector.sendNotificationForClient(c, next).map{_ =>
      val notification = Notification(c, next)
      sent.put(c, sent.get(c).fold(State(Seq(notification)))(s => s.add(notification)))
      Ok
    }.recover{ case e: Throwable =>
      println(s"XXXXXXXXXXXXXXXXXXX Error sending notification for clientSubscriptionId $c" + e.getStackTrace.toString)
      sendErrors = sendErrors.add(c)
      InternalServerError // gets ignored
    }
  }

  private def interleaveClientRequests: Future[Result] = {
    val clientASubscriptionId = UUID.randomUUID().toString
    val clientBSubscriptionId = UUID.randomUUID().toString
    log.info(s"about to interleave requests for clientASubscriptionId={}, clientBSubscriptionId={}", clientASubscriptionId, clientBSubscriptionId)
    for {
      _ <- sendNotificationForClient(clientASubscriptionId, seqA) // composing Futures like this guarantees A happens before B etc
      _ <- sendNotificationForClient(clientBSubscriptionId, seqB)
      _ <- sendNotificationForClient(clientASubscriptionId, seqA)
      _ <- sendNotificationForClient(clientBSubscriptionId, seqB)
      _ <- sendNotificationForClient(clientASubscriptionId, seqA)
      response <- sendNotificationForClient(clientBSubscriptionId, seqB)
    } yield response
  }

}
