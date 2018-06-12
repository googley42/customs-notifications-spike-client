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
import scala.util.control.NonFatal
import scala.xml.{Node, NodeSeq, Utility, XML}

@Singleton
class Start {

  // shared state here to compare sent notifications with received
  @volatile
  private var sent = scala.collection.mutable.Map[ClientSubscriptionId, State]()
  @volatile
  private var received = scala.collection.mutable.Map[ClientSubscriptionId, State]()

  case class State(seq: Seq[Node] = Seq.empty[Node]) {
    def add(n: Node): State = State(seq :+ Utility.trim(n))
  }

  def start: Action[AnyContent] = Action.async {implicit request =>
    sent.clear()
    received.clear()

    for {
      _ <- sendNotificationForClient(ClientA, seq = 1)
      _ <- sendNotificationForClient(ClientA, seq = 2)
      response <- sendNotificationForClient(ClientA, seq = 3)
    } yield response

  }

  def end: Action[AnyContent] = Action.async {implicit request =>
    //TODO: using toString at the moment to force evaluation of XML Elem. Does not work without toString.
    Future.successful(Ok(s"\nsent=\n${sent.toSet}\nreceived=\n${received.toSet}\nsent==received: ${(sent.toSet.toString).equals((received.toSet.toString))}"))
  }

  def clientACallbackEndpoint: Action[AnyContent] = clientCallbackEndpoint("ClientA")

  private def sendNotificationForClient(c: ClientSubscriptionId, seq: Int)(implicit r: Request[AnyContent]) = {
    implicit val hc = HeaderCarrier()
    val headers = createHeaders(c)

    Thread.sleep(50) // we need this to preserve sequencing of callbacks - not sure why

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
//    val pls_delete = (maybePayloadAsXml.get)(0).toString
    val payloadAsXml: Node = string2xml((maybePayloadAsXml.get)(0).toString())
    val c = (payloadAsXml \ "clientSubscriptionId").text
    println(s"Extracted clientSubscriptionId from payload: $c")
    received.put(c, received.get(c).fold(State(Seq(payloadAsXml)))(s => s.add(payloadAsXml)))
    println(s"\n<<< $name callback OK, \nheaders=\n${request.headers.toSimpleMap}\nbody=\n${maybePayloadAsXml.getOrElse("EMPTY BODY")}\nreceived=\n$received")
    val s = sent.get(c).get.seq(0).toString()
    val r = received.get(c).get.seq(0).toString()
    val eq = s.equals(r)
    println(s"\n<<< XXXXXXXXXXXXXXXXXX sent = ${sent.get(c).get.seq(0)}")
    println(s"\n<<< XXXXXXXXXXXXXXXXXX sent = ${sent.get(c).get.seq(0).getClass}")
    println(s"\n<<< XXXXXXXXXXXXXXXXXX received = ${received.get(c).get.seq(0).getClass}")
    println(s"\n<<< XXXXXXXXXXXXXXXXXX = ${sent.get(c).get.seq(0) == received.get(c).get.seq(0)}")
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

  protected def string2xml(s: String): Node = {
    val xml = try {
      XML.loadString(s)
    } catch {
      case NonFatal(thr) => throw thr
    }
    Utility.trim(xml)
  }
}

case class HttpPostImpl() extends HttpPost with WSPost {
  override val hooks: Seq[HttpHook] = Seq.empty
}