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
import uk.gov.hmrc.customs.notification.spike.model.CustomHeaderNames._
import uk.gov.hmrc.customs.notification.spike.model.Payloads._
import uk.gov.hmrc.customs.notification.spike.model.{ClientSubscriptionId, Payloads}
import uk.gov.hmrc.http.hooks.HttpHook
import uk.gov.hmrc.http.{HeaderCarrier, HttpPost}
import uk.gov.hmrc.play.http.ws.WSPost

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.xml.NodeSeq

@Singleton
class Start {

  // shared state here to compare sent notifications with received
  val sent: Map[ClientSubscriptionId, Seq[NodeSeq]] = Map.empty
  val received: Map[ClientSubscriptionId, Seq[NodeSeq]] = Map.empty

  def start: Action[AnyContent] = Action.async {
    Future.successful(Ok)

    val headers = createHeaders(ClientA)

    implicit val hc = HeaderCarrier()

    val payload = clientPlayload(ClientA).toString()

    println(s"Start - about to POST payload: " + payload)

    HttpPostImpl().POSTString(
      "http://localhost:9821/customs-notification/notify",
      payload,
      headers
    ).map {_ =>
      println("Start OK")
      Ok
    }.recover{case e: Throwable =>
      println(e.getStackTrace.toString)
      throw e
    }
  }

  def clientACallbackEndpoint: Action[AnyContent] = Action.async { request =>
    println(s"clientACallbackEndpoint OK, \nheaders=\n${request.headers.toSimpleMap}\nbody=\n${request.body.asXml.getOrElse("EMPTY BODY")}")
    val respose: Result = Status(Default.OK)(<ok>Received payload OK</ok>).as(ContentTypes.XML)
    Future.successful(respose)
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