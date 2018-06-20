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

import java.util.concurrent.TimeUnit
import javax.inject.{Inject, Singleton}

import akka.actor.{ActorSystem, Props}
import akka.pattern.ask
import controllers.Default
import play.api.Configuration
import play.api.http.ContentTypes
import play.api.mvc.Results._
import play.api.mvc._
import uk.gov.hmrc.customs.notification.spike.actors.NotificationsSenderActor
import uk.gov.hmrc.customs.notification.spike.actors.NotificationsSenderActor._
import uk.gov.hmrc.customs.notification.spike.connectors.{NotificationConnector, NotificationConnector2}
import uk.gov.hmrc.customs.notification.spike.model.Notification

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.xml.{Node, NodeSeq}

@Singleton
class SenderController @Inject()(config: Configuration, connector: NotificationConnector2) {

  val actorSystem = ActorSystem("sender")
  val senderActor = actorSystem.actorOf(props(connector), "notificationsSenderActor")
  implicit val timeout: akka.util.Timeout = akka.util.Timeout(10, TimeUnit.SECONDS)

  def start: Action[AnyContent] = Action.async {implicit request =>
    (senderActor ? Start) map {
      case msg: String => Ok(msg)
      case _ => Ok("Did not get started confirmation")
    }
  }

  def stop: Action[AnyContent] = Action.async {implicit request =>
    (senderActor ? Stop) map {
      case msg: String => Ok(msg)
      case _ => Ok("Did not get stopped confirmation")
    }
  }

  def compare: Action[AnyContent] = Action.async {implicit request =>
    (senderActor ? Compare) map {
      case CompareReport(report) =>
        Ok(report)
    }
  }

  def callbackEndpoint: Action[AnyContent] = Action.async { request =>
    val maybePayloadAsXml: Option[NodeSeq] = request.body.asXml
    val payloadAsXml: Node = maybePayloadAsXml.get.head
    val clientSubscriptionId = (payloadAsXml \ "clientSubscriptionId").text
    val seq = (payloadAsXml \ "seq").text.toInt
    val payload = Notification(clientSubscriptionId, seq)

    println(s"Extracted from payload: $payload")

    senderActor ! Received(payload)

    println(s"\n<<< $clientSubscriptionId callback OK, \nheaders=\n${request.headers.toSimpleMap}\nbody=\n${maybePayloadAsXml.getOrElse("EMPTY BODY")}")

    val response: Result = Status(Default.OK)(<ok>Received payload OK</ok>).as(ContentTypes.XML) // for customs-notification-gateway logging

    Future.successful(response)
  }


}
