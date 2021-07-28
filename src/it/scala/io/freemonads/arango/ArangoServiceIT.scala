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
import io.freemonads.arango.docker.DockerArango
import io.freemonads.http.resource.{ResourceDsl, RestResource}
import io.freemonads.specs2.Http4FreeIOMatchers
import org.http4s._
import org.http4s.implicits.http4sLiteralsSyntax
import org.specs2.Specification
import org.specs2.matcher.{IOMatchers, MatchResult}
import org.specs2.specification.core.{Env, SpecStructure}
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger


trait MockServiceWithArango extends IOMatchers {

  import api._
  import http.resource._

  implicit def unsafeLogger: Logger[IO] = Slf4jLogger.getLogger[IO]

  type ArangoDsl = ResourceDsl[ResourceAlgebra, VPackEncoder, VPackDecoder]

  case class Mock(id: String, user: String, age: Int)

  implicit val mockEncoder: VPackEncoder[Mock] = VPackEncoder.gen
  implicit val mockDecoder: VPackDecoder[Mock] = VPackDecoder.gen

  val arangoConfig = ArangoConfiguration.load()
  val arangoResource = Arango(arangoConfig)

  implicit val arangoInterpreter: ResourceAlgebra ~> IO = arangoResourceInterpreter(arangoResource)

  val mock1 = Mock("aaa123", "Roger", 21)
  val updatedMock = Mock("456", "NewName", 36)
  val mocksUri = uri"/mocks"
  val mock1Uri = mocksUri / mock1.id

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
        with DockerArango
        with DockerTestKit
        with MockServiceWithArango
        with Http4FreeIOMatchers {

  implicit val ee = env.executionEnv

  implicit val dsl: ArangoDsl = ResourceDsl.instance

  def is: SpecStructure = s2"""
      The ArangoDB container should be ready                  $arangoIsReady
      Store a new resource with specific id                   $storeNewResource
      Store and fetch from ArangoDB                           $storeAndFetch
      Store and update from ArangoDB                          $storeAndUpdate
      Return not found error when document doesn't exist      $returnNotFound
  """

  def arangoIsReady: MatchResult[Future[Boolean]] = isContainerReady(arangoContainer) must beTrue.await

  def storeNewResource: MatchResult[Any] = dsl.store[Mock](mock1Uri, mock1) must resultOk(RestResource(mock1Uri, mock1))

  def storeAndFetch: MatchResult[Any] = storeAndFetch(mocksUri, mock1) must resultOk(mock1)

  def storeAndUpdate: MatchResult[Any] = storeAndUpdate(mocksUri, mock1, updatedMock) must resultOk(updatedMock)

  def returnNotFound: MatchResult[Any] = fetchFromArango(uri"/emptyCollection/123") must resultErrorNotFound
}
