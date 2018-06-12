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

package uk.gov.hmrc.customs.notification.spike.model

import scala.xml.{Elem, Node}


object CustomHeaderNames {
  val X_CDS_CLIENT_ID_HEADER_NAME: String = "X-CDS-Client-ID"
  val SUBSCRIPTION_FIELDS_ID_HEADER_NAME: String = "api-subscription-fields-id"
  val X_CONVERSATION_ID_HEADER_NAME: String = "X-Conversation-ID"
  val X_BADGE_ID_HEADER_NAME : String = "X-Badge-Identifier"
}

object Payloads {

  def clientPlayload(c: ClientSubscriptionId, seq: Int): Node =
<Notification>
  <clientSubscriptionId>{c}</clientSubscriptionId>
  <seq>{seq}</seq>
</Notification>

}

case class Notification(clientSubscriptionId: ClientSubscriptionId, seq: Int)