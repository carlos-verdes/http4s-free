/*
 * TODO: License goes here!
 */

package io.freemonads
package arango

import scala.concurrent.Future

import avokka.arangodb.ArangoConfiguration
import avokka.arangodb.fs2.Arango
import avokka.velocypack.{VPackDecoder, VPackEncoder}
import cats.effect.IO
import cats.~>
import com.whisk.docker.impl.spotify._
import com.whisk.docker.specs2.DockerTestKit
import io.freemonads.arango.test.DockerKitConfigWithArango
import io.freemonads.specs2.Http4FreeIOMatchers
import org.http4s._
import org.http4s.implicits.http4sLiteralsSyntax
import org.specs2.Specification
import org.specs2.matcher.{Http4sMatchers, IOMatchers, MatchResult}
import org.specs2.specification.core.{Env, SpecStructure}
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger


trait MockServiceWithArango extends IOMatchers {

  import api._
  import http.resource._

  implicit def unsafeLogger: Logger[IO] = Slf4jLogger.getLogger[IO]

  type ArangoDsl = ResourceDsl[ResourceAlgebra, VPackEncoder, VPackDecoder]

  case class Mock(user: String, age: Int)

  implicit val mockEncoder: VPackEncoder[Mock] = VPackEncoder.gen
  implicit val mockDecoder: VPackDecoder[Mock] = VPackDecoder.gen

  val arangoConfig = ArangoConfiguration.load()
  val arangoResource = Arango(arangoConfig)

  implicit val arangoInterpreter: ResourceAlgebra ~> IO = arangoResourceInterpreter(arangoResource)

  val mocksUri = uri"/mocks"
  val mock1 = Mock("Roger", 21)
  val updatedMock = Mock("NewName", 36)

  def storeAndFetch(uri: Uri, mock: Mock)(implicit dsl: ArangoDsl): ApiFree[ResourceAlgebra, Mock] = {

    import dsl._

    for {
      savedResource <- store[Mock](uri, mock)
      fetchedResource <- fetch[Mock](savedResource.uri)
    } yield fetchedResource.body
  }

  def storeAndUpdate(uri: Uri, mock: Mock, newMock: Mock)(implicit dsl: ArangoDsl): ApiFree[ResourceAlgebra, Mock] = {

    import dsl._

    for {
      savedResource <- store[Mock](uri, mock)
      updatedResource <- store[Mock](savedResource.uri, newMock)
    } yield updatedResource.body
  }

  def fetchFromArango(uri: Uri)(implicit dsl: ArangoDsl): ApiFree[ResourceAlgebra, Mock] =
    dsl.fetch[Mock](uri).map(_.body)
}

class ArangoServiceIT(env: Env)
    extends Specification
        with DockerKitSpotify
        with DockerKitConfigWithArango
        with DockerTestKit
        with MockServiceWithArango
        with Http4sMatchers[IO]
        with Http4FreeIOMatchers {

  implicit val ee = env.executionEnv

  def is: SpecStructure = s2"""
      The ArangoDB container should be ready                  $arangoIsReady
      Store and fetch from ArangoDB                           $storeAndFetch
      Store and update from ArangoDB                          $storeAndUpdate
      Return not found error when document doesn't exist      $returnNotFound
  """

  def arangoIsReady: MatchResult[Future[Boolean]] = isContainerReady(arangoContainer) must beTrue.await

  def storeAndFetch: MatchResult[Any] = storeAndFetch(mocksUri, mock1) must resultOk(mock1)

  def storeAndUpdate: MatchResult[Any] = storeAndUpdate(mocksUri, mock1, updatedMock) must resultOk(updatedMock)

  def returnNotFound: MatchResult[Any] = fetchFromArango(uri"/emptyCollection/123") must resultErrorNotFound
}
