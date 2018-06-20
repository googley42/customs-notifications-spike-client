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

package uk.gov.hmrc.customs.notification.spike.controllers

import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.{Inject, Singleton}

import controllers.Default
import play.api.Configuration
import play.api.http.ContentTypes
import play.api.mvc.Results._
import play.api.mvc._
import uk.gov.hmrc.customs.notification.spike.connectors.NotificationConnector
import uk.gov.hmrc.customs.notification.spike.model.{ClientSubscriptionId, Notification}

import scala.collection.immutable.Seq
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.xml.{Node, NodeSeq}
import scala.concurrent.blocking

@Singleton
class Start @Inject()(config: Configuration, connector: NotificationConnector) {

  // shared state here to compare sent notifications with received
  @volatile
  private var sent = scala.collection.mutable.Map[ClientSubscriptionId, State]()
  @volatile
  private var received = scala.collection.mutable.Map[ClientSubscriptionId, State]()
  @volatile
  private var sendErrors = SendErrors()

  private val seqA = new AtomicInteger()
  private val seqB = new AtomicInteger()

  val PauseMilliseconds = 5000

  case class State(seq: Seq[Notification] = Seq.empty) {
    def add(n: Notification): State = State(seq :+ n)
  }

  case class SendErrors(seq: Seq[ClientSubscriptionId] = Seq.empty) {
    def add(n: ClientSubscriptionId): SendErrors = SendErrors(seq :+ n)
  }

  def start: Action[AnyContent] = Action.async {implicit request =>
    sent.clear()
    received.clear()

//    val clientASubscriptionId = config.getString("clientASubscriptionId").getOrElse(throw new IllegalStateException("cannot read clientASubscriptionId"))
//    val clientBSubscriptionId = config.getString("clientBSubscriptionId").getOrElse(throw new IllegalStateException("cannot read clientBSubscriptionId"))

    val range: Seq[Int] = (1 to 60) // 60 generates 5 mins elapsed of requests, one every 5 seconds

    Future {
      range.foreach { i =>
        val clientASubscriptionId = UUID.randomUUID().toString
        val clientBSubscriptionId = UUID.randomUUID().toString
        for {
          _ <- sendNotificationForClient(clientASubscriptionId, seqA)
          _ <- sendNotificationForClient(clientBSubscriptionId, seqB)
          _ <- sendNotificationForClient(clientASubscriptionId, seqA)
          _ <- sendNotificationForClient(clientBSubscriptionId, seqB)
          _ <- sendNotificationForClient(clientASubscriptionId, seqA)
          response <- sendNotificationForClient(clientBSubscriptionId, seqB)
        } yield response
      }
    }

    Future.successful(Ok)
  }

  def end: Action[AnyContent] = Action.async {implicit request =>
    Future.successful(Ok(s"\nsent==received: ${sent.toSet.equals(received.toSet)}\nSend errors=\n${sendErrors.toString}\nsent=\n${sent}\nreceived=\n${received}"))
  }

  def callbackEndpoint: Action[AnyContent] = Action.async { request =>
    val maybePayloadAsXml: Option[NodeSeq] = request.body.asXml
    val payloadAsXml: Node = maybePayloadAsXml.get.head
    val clientSubscriptionId = (payloadAsXml \ "clientSubscriptionId").text
    val seq = (payloadAsXml \ "seq").text.toInt
    val payload = Notification(clientSubscriptionId, seq)

    println(s"Extracted from payload: $payload")

    received.put(clientSubscriptionId, received.get(clientSubscriptionId).fold(State(Seq(payload)))(s => s.add(payload)))

    println(s"\n<<< $clientSubscriptionId callback OK, \nheaders=\n${request.headers.toSimpleMap}\nbody=\n${maybePayloadAsXml.getOrElse("EMPTY BODY")}\nreceived=\n$received")

    val response: Result = Status(Default.OK)(<ok>Received payload OK</ok>).as(ContentTypes.XML) // for customs-notification-gateway logging

    Future.successful(response)
  }


  private def sendNotificationForClient(c: ClientSubscriptionId, seq: AtomicInteger)(implicit r: Request[AnyContent]): Future[Result] = {

    blocking {
      Thread.sleep(PauseMilliseconds) // we need this to preserve sequencing of callbacks - not sure why
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

  //TODO: remove
  //  def clientACallbackEndpoint: Action[AnyContent] = clientCallbackEndpoint("ClientA")
  //
  //  def clientBCallbackEndpoint: Action[AnyContent] = clientCallbackEndpoint("ClientB")

  //TODO: remove
  private def clientCallbackEndpoint(name: String): Action[AnyContent] = Action.async { request =>
    val maybePayloadAsXml: Option[NodeSeq] = request.body.asXml
    val payloadAsXml: Node = maybePayloadAsXml.get.head
    val c = (payloadAsXml \ "clientSubscriptionId").text
    val seq = (payloadAsXml \ "seq").text.toInt
    val payload = Notification(c, seq)

    println(s"Extracted from payload: $payload")

    received.put(c, received.get(c).fold(State(Seq(payload)))(s => s.add(payload)))

    println(s"\n<<< $name callback OK, \nheaders=\n${request.headers.toSimpleMap}\nbody=\n${maybePayloadAsXml.getOrElse("EMPTY BODY")}\nreceived=\n$received")

    val response: Result = Status(Default.OK)(<ok>Received payload OK</ok>).as(ContentTypes.XML) // for customs-notification-gateway logging

    Future.successful(response)
  }

}
