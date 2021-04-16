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
import io.circe.generic.auto._
import io.circe.literal._
import io.freemonads.specs2.Http4FreeIOMatchers
import org.http4s._
import org.http4s.circe.CirceEntityCodec._
import org.http4s.implicits.http4sLiteralsSyntax
import org.specs2.Specification
import org.specs2.matcher.{Http4sMatchers, IOMatchers, MatchResult}
import org.specs2.specification.core.{Env, SpecStructure}
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger


trait MockServiceWithArango extends IOMatchers {

  import http.api._
  import http.resource._

  implicit def unsafeLogger: Logger[IO] = Slf4jLogger.getLogger[IO]

  type ArangoDsl = ResourceDsl[ResourceAlgebra, VPackEncoder, VPackDecoder]

  case class Mock(user: String, age: Int)

  implicit val mockEncoder: VPackEncoder[Mock] = VPackEncoder.gen
  implicit val mockDecoder: VPackDecoder[Mock] = VPackDecoder.gen

  val arangoConfig = ArangoConfiguration.load()
  val arangoResource = Arango(arangoConfig)

  implicit val arangoInterpreter: ResourceAlgebra ~> IO = arangoResourceInterpreter(arangoResource)


  def storeAndFetch(uri: Uri, mock: Mock)(implicit dsl: ArangoDsl): ApiFree[ResourceAlgebra, Mock] = {

    import dsl._

    for {
      savedResource <- store[Mock](uri, mock)
      fetchedResource <- fetch[Mock](savedResource.uri)
    } yield fetchedResource.body
  }
  def fetchFromArango(uri: Uri)(implicit dsl: ArangoDsl): ApiFree[ResourceAlgebra, Mock] =
    dsl.fetch[Mock](uri).map(_.body)


  val mock1 = Mock("Roger", 21)

  val MOCKS_URI = uri"/mocks"
  val createMockRequest: Request[IO] = Request[IO](Method.POST, MOCKS_URI).withEntity(mock1)
  val invalidRequest: Request[IO] = Request[IO](Method.POST, MOCKS_URI).withEntity(json"""{ "wrongJson": "" }""")
  val notFoundRequest: Request[IO] = Request[IO](Method.GET, uri"/wrongUri")
}

class ArangoServiceIT(env: Env)
    extends Specification
        with DockerKitSpotify
        with DockerArangoDbService
        with DockerTestKit
        with MockServiceWithArango
        with Http4sMatchers[IO]
        with Http4FreeIOMatchers {

  implicit val ee = env.executionEnv

  def is: SpecStructure = s2"""
      The ArangoDB container should be ready $arangoIsReady
      Store and fetch from ArangoDB          $storeAndFetch
      Return not found error when document doesn't exist returnNotFound
  """

  import http.api._

  def arangoIsReady: MatchResult[Future[Boolean]] = isContainerReady(arangoContainer) must beTrue.await

  def storeAndFetch: MatchResult[Any] = storeAndFetch(MOCKS_URI, mock1) must resultOk(mock1)

  def returnNotFound: MatchResult[Any] =
    fetchFromArango(uri"/emptyCollection/123") must resultError(ResourceNotFoundError())
}
