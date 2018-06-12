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

import javax.inject.Singleton

import controllers.Default
import play.api.http.HeaderNames.{ACCEPT, CONTENT_TYPE}
import play.api.http.{ContentTypes, MimeTypes}
import play.api.mvc.Results._
import play.api.mvc._
import uk.gov.hmrc.customs.notification.spike.model.ClientSubscriptionId
import uk.gov.hmrc.customs.notification.spike.model.CustomHeaderNames._
import uk.gov.hmrc.customs.notification.spike.model.Payloads._
import uk.gov.hmrc.http.hooks.HttpHook
import uk.gov.hmrc.http.{HeaderCarrier, HttpPost}
import uk.gov.hmrc.play.http.ws.WSPost

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.xml.NodeSeq

@Singleton
class Start {

  // shared state here to compare sent notifications with received
  private val sent = scala.collection.mutable.Map[ClientSubscriptionId, State]()
  private val received = scala.collection.mutable.Map[ClientSubscriptionId, State]()

  case class State(seq: Seq[NodeSeq] = NodeSeq.Empty) {
    def add(n: NodeSeq): State = State(seq :+ n)
  }

  def start: Action[AnyContent] = Action.async {implicit request =>
    sent.clear()
    received.clear()

    for {
      _ <- sendNotificationForClient(ClientA, 1)
      _ <- sendNotificationForClient(ClientA, 2)
      response <- sendNotificationForClient(ClientA, 3)
    } yield response

  }

  def clientACallbackEndpoint: Action[AnyContent] = clientCallbackEndpoint("ClientA")

  private def sendNotificationForClient(c: ClientSubscriptionId, seq: Int)(implicit r: Request[AnyContent]) = {
    implicit val hc = HeaderCarrier()
    val headers = createHeaders(c)

    Thread.sleep(50) // we need this to preserve sequencing of callbacks

    val payload = clientPlayload(ClientA, seq)
    val payloadAsString = payload.toString

    println(s"\n>>> Start - about to POST notification. \nheaders=\n${r.headers.toSimpleMap}\npayload=\n" + payload)

    sent.put(c, sent.get(c).fold(State(Seq(payload)))(s => s.add(payload)))

    HttpPostImpl().POSTString(
      "http://localhost:9821/customs-notification/notify",
      payloadAsString,
      headers
    ).map { _ =>
      println(s"Start - sent OK. \nsent=\n$sent")
      Ok
    }.recover { case e: Throwable =>
      println(e.getStackTrace.toString)
      throw e
    }
  }

  private def clientCallbackEndpoint(name: String): Action[AnyContent] = Action.async { request =>
    val maybePayloadAsXml: Option[NodeSeq] = request.body.asXml
    val payloadAsXml = maybePayloadAsXml.get
    val c = (payloadAsXml \ "clientSubscriptionId").text
    println(s"XXXXXXXXXXXXXXXXXXXXX c = $c")
    //received.put(c, received(c).add(maybePayloadAsXml.get))
    received.put(c, received.get(c).fold(State(Seq(payloadAsXml)))(s => s.add(payloadAsXml)))
    println(s"\n<<< $name callback OK, \nheaders=\n${request.headers.toSimpleMap}\nbody=\n${maybePayloadAsXml.getOrElse("EMPTY BODY")}\nreceived=\n$received")
    val response: Result = Status(Default.OK)(<ok>Received payload OK</ok>).as(ContentTypes.XML) // for customs-notification-gateway logging
    Future.successful(response)
  }

  private def createHeaders(clientSubscriptionId: ClientSubscriptionId): Seq[(String, String)] = {
    Seq(
      CONTENT_TYPE -> (MimeTypes.XML + "; charset=UTF-8"),
      ACCEPT -> MimeTypes.XML,
      X_CDS_CLIENT_ID_HEADER_NAME -> clientSubscriptionId,
      X_CONVERSATION_ID_HEADER_NAME -> "a93d197d-fe29-4ff5-bbd2-148f21bf0f36"
    )
  }

}

case class HttpPostImpl() extends HttpPost with WSPost {
  override val hooks: Seq[HttpHook] = Seq.empty
}