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

package uk.gov.hmrc.customs.notification.spike.connectors

import javax.inject.Singleton

import play.api.http.HeaderNames.{ACCEPT, CONTENT_TYPE}
import play.api.http.MimeTypes
import play.api.mvc.Results.Ok
import play.api.mvc.{AnyContent, Request, Result}
import uk.gov.hmrc.customs.notification.spike.model.CustomHeaderNames.{X_CDS_CLIENT_ID_HEADER_NAME, X_CONVERSATION_ID_HEADER_NAME}
import uk.gov.hmrc.customs.notification.spike.model.Payloads.clientPlayload
import uk.gov.hmrc.customs.notification.spike.model.{ClientSubscriptionId, Notification}
import uk.gov.hmrc.http.hooks.HttpHook
import uk.gov.hmrc.http.{HeaderCarrier, HttpPost}
import uk.gov.hmrc.play.http.ws.WSPost

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

@Singleton
class NotificationConnector2 {

  def sendNotificationForClient(c: ClientSubscriptionId, seq: Int): Future[Result] = {
    implicit val hc = HeaderCarrier()
    val headers = createHeaders(c)

    val payload = clientPlayload(c, seq)
    val payloadAsString = payload.toString

//    println(s"\n>>> Start - about to POST notification. \nheaders=\n${r.headers.toSimpleMap}\npayload=\n" + payload)

    val notification = Notification(c, seq)

    //sent.put(c, sent.get(c).fold(State(Seq(notification)))(s => s.add(notification)))

    HttpPostImpl2().POSTString(
      "http://localhost:9821/customs-notification/notify-spike",
      payloadAsString,
      headers
    ).map { _ =>
      println("Start - sent OK")
      Ok
    }.recover { case e: Throwable =>
      println(e.getStackTrace.toString)
      throw e
    }
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

case class HttpPostImpl2() extends HttpPost with WSPost {
  override val hooks: Seq[HttpHook] = Seq.empty
}
