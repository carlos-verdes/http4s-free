/*
 * TODO: License goes here!
 */

package io.freemonads
package arango

import scala.concurrent.Future

import avokka.velocypack.{VPackDecoder, VPackEncoder}
import cats.data.EitherK
import cats.effect.{IO, Sync, Timer}
import cats.{Functor, ~>}
import com.whisk.docker.impl.spotify._
import com.whisk.docker.specs2.DockerTestKit
import io.circe.generic.auto._
import io.freemonads.arango.docker.DockerArango
import io.freemonads.http.resource.{ResourceAlgebra, RestResource}
import io.freemonads.http.rest
import io.freemonads.specs2.Http4FreeIOMatchers
import org.http4s._
import org.http4s.dsl.Http4sDsl
import org.http4s.circe.CirceEntityCodec._
import org.http4s.dsl.io.Created
import org.http4s.headers.Location
import org.http4s.implicits.{http4sKleisliResponseSyntaxOptionT, http4sLiteralsSyntax}
import org.specs2.Specification
import org.specs2.matcher.{IOMatchers, MatchResult}
import org.specs2.specification.core.{Env, SpecStructure}
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger


trait MockServiceWithArango extends IOMatchers {

  import api._
  import http.resource._
  import rest._

  implicit def unsafeLogger: Logger[IO] = Slf4jLogger.getLogger[IO]

  case class Mock(id: String, user: String, age: Int)
  case class UserRequest(address: String)
  case class User(address: String, nonce: String)

  implicit val mockEncoder: VPackEncoder[Mock] = VPackEncoder.gen
  implicit val mockDecoder: VPackDecoder[Mock] = VPackDecoder.gen
  implicit val userEncoder: VPackEncoder[User] = VPackEncoder.gen
  implicit val userDecoder: VPackDecoder[User] = VPackDecoder.gen

  implicit val arangoInterpreter: ResourceAlgebra ~> IO = arangoIoInterpreter

  val mock1 = Mock("aaa123", "Roger", 21)
  val updatedMock = Mock("456", "NewName", 36)
  val mocksUri = uri"/mocks"
  val mock1Uri = mocksUri / mock1.id

  def storeAndFetch(uri: Uri, mock: Mock)(implicit dsl: ArangoDsl[ResourceAlgebra]): ApiFree[ResourceAlgebra, Mock] = {

    import dsl._

    for {
      savedResource <- store[Mock](uri, mock)
      fetchedResource <- fetch[Mock](savedResource.uri)
    } yield fetchedResource.body
  }

  def storeAndUpdate(
      uri: Uri,
      mock: Mock,
      newMock: Mock)(
      implicit dsl: ArangoDsl[ResourceAlgebra]): ApiFree[ResourceAlgebra, Mock] = {

    import dsl._

    for {
      savedResource <- store[Mock](uri, mock)
      updatedResource <- store[Mock](savedResource.uri, newMock)
    } yield updatedResource.body
  }

  def fetchFromArango(uri: Uri)(implicit dsl: ArangoDsl[ResourceAlgebra]): ApiFree[ResourceAlgebra, Mock] =
    dsl.fetch[Mock](uri).map(_.body)


  val userAddress = "address1"
  val userNonce = "nonce1"
  val userRequest = UserRequest(userAddress)
  val expectedUser = User(userAddress, userNonce)

  def userUri(user: User): Uri = uri"/users" / user.address

  def userRoutes[F[_]: Sync : Timer : Functor, Algebra[_]](
      implicit http4sFreeDsl: Http4sFreeDsl[Algebra],
      resourceDsl: ResourceDsl[Algebra, VPackEncoder, VPackDecoder],
      interpreters: Algebra ~> F): HttpRoutes[F] = {

    val dsl = new Http4sDsl[F]{}
    import dsl._
    import http4sFreeDsl._
    import resourceDsl._

    HttpRoutes.of[F] {
      case r @ POST -> Root / "users" =>
        for {
          userRequest <- parseRequest[F, UserRequest](r)
          userAddress = userRequest.address
          user = User(userAddress, userNonce)
          storedUser <- store[User](userUri(user), user)
        } yield storedUser.created[F]
    }
  }

  implicit val interpreters = (http4sInterpreter[IO] or arangoIoInterpreter)
  type CombinedAlgebra[R] = EitherK[Http4sAlgebra, ResourceAlgebra, R]

  val userService = userRoutes[IO, CombinedAlgebra]

  val createUserRequest: Request[IO] = Request[IO](Method.POST, uri"/users").withEntity(userRequest)
}

class ArangoServiceIT(env: Env)
    extends Specification
        with DockerKitSpotify
        with DockerArango
        with DockerTestKit
        with MockServiceWithArango
        with Http4FreeIOMatchers {

  implicit val ee = env.executionEnv
  implicit val dsl = arangoDsl[ResourceAlgebra]

  def is: SpecStructure = s2"""
      The ArangoDB container should be ready                  $arangoIsReady
      Store a new resource with specific id                   $storeNewResource
      Store and fetch from ArangoDB                           $storeAndFetch
      Store and update from ArangoDB                          $storeAndUpdate
      Return not found error when document doesn't exist      $returnNotFound
      Store a resource usind HTTP APi                         $storeResource

  """

  def arangoIsReady: MatchResult[Future[Boolean]] = isContainerReady(arangoContainer) must beTrue.await

  def storeNewResource: MatchResult[Any] = dsl.store[Mock](mock1Uri, mock1) must resultOk(RestResource(mock1Uri, mock1))

  def storeAndFetch: MatchResult[Any] = storeAndFetch(mocksUri, mock1) must resultOk(mock1)

  def storeAndUpdate: MatchResult[Any] = storeAndUpdate(mocksUri, mock1, updatedMock) must resultOk(updatedMock)

  def returnNotFound: MatchResult[Any] = fetchFromArango(uri"/emptyCollection/123") must resultErrorNotFound

  def storeResource: MatchResult[Any] =
    userService.orNotFound(createUserRequest) must returnValue { (response: Response[IO]) =>
      response must haveStatus(Created) and
          (response must haveBody(expectedUser)) and
          (response must containHeader(Location(userUri(expectedUser))))
    }

}
