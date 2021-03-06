import sbt._

object AppDependencies {

  private val bootstrapPlay25Version = "1.5.0"
  private val hmrcTestVersion = "3.0.0"
  private val scalaTestVersion = "3.0.5"
  private val scalatestplusVersion = "2.0.1"
  private val mockitoVersion = "2.18.3"
  private val pegdownVersion = "1.6.0"
  private val wireMockVersion = "2.17.0"
  private val customsApiCommonVersion = "1.26.0"
  private val testScope = "test,it"

  val xmlResolver = "xml-resolver" % "xml-resolver" % "1.2"

  val bootstrapPlay25 = "uk.gov.hmrc" %% "bootstrap-play-25" % bootstrapPlay25Version

  val hmrcTest = "uk.gov.hmrc" %% "hmrctest" % hmrcTestVersion % testScope

  val scalaTest = "org.scalatest" %% "scalatest" % scalaTestVersion % testScope

  val pegDown = "org.pegdown" % "pegdown" % pegdownVersion % testScope

  val scalaTestPlusPlay = "org.scalatestplus.play" %% "scalatestplus-play" % scalatestplusVersion % testScope

  val wireMock = "com.github.tomakehurst" % "wiremock" % wireMockVersion % testScope exclude("org.apache.httpcomponents","httpclient") exclude("org.apache.httpcomponents","httpcore")

  val mockito =  "org.mockito" % "mockito-core" % mockitoVersion % testScope

  val customsApiCommon = "uk.gov.hmrc" %% "customs-api-common" % customsApiCommonVersion

  val customsApiCommonTests = "uk.gov.hmrc" %% "customs-api-common" % customsApiCommonVersion % testScope classifier "tests"

}
