/*
 * TODO: License goes here!
 */

package io.freemonads
package arango

import java.security.{NoSuchAlgorithmException, SecureRandom, Security}
import scala.concurrent.Future

import avokka.velocypack.{VPackDecoder, VPackEncoder}
import cats.data.{EitherK, Kleisli, OptionT}
import cats.effect.IO
import cats.~>
import cats.syntax.option._
import com.whisk.docker.impl.spotify._
import com.whisk.docker.specs2.DockerTestKit
import io.circe.generic.auto._
import io.freemonads.arango.docker.DockerArango
import io.freemonads.http.resource.{ResourceAlgebra, ResourceDsl, RestResource}
import io.freemonads.http.rest
//import io.freemonads.http.rest.{Http4sAlgebra, Http4sFreeDsl, http4sInterpreter}
import io.freemonads.specs2.Http4FreeIOMatchers
import org.http4s._
import org.http4s.dsl.Http4sDsl
import org.http4s.circe.CirceEntityCodec._
import org.http4s.dsl.io._
import org.http4s.headers.{Authorization, Location}
import org.http4s.implicits.{http4sKleisliResponseSyntaxOptionT, http4sLiteralsSyntax}
import org.http4s.server.AuthMiddleware
import org.specs2.Specification
import org.specs2.matcher.{IOMatchers, MatchResult}
import org.specs2.specification.core.{Env, SpecStructure}
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

trait IOMatchersWithLogger extends IOMatchers {

  implicit def unsafeLogger: Logger[IO] = Slf4jLogger.getLogger[IO]

}

trait MockServiceWithArango extends IOMatchersWithLogger {

  import api._
  import http.resource._

  case class Mock(id: String, user: String, age: Int)

  implicit val mockEncoder: VPackEncoder[Mock] = VPackEncoder.gen
  implicit val mockDecoder: VPackDecoder[Mock] = VPackDecoder.gen

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

}

trait AuthCases extends IOMatchersWithLogger {

  import api._
  import rest._

  import tsec.common._
  //  import tsec.jws.mac.JWTMac
  import tsec.jwt.JWTClaims
  //  import tsec.mac.jca.HMACSHA256


  implicit val interpreters = (http4sInterpreter[IO] or arangoIoInterpreter)

  case class UserRequest(address: String)
  case class User(address: String, nonce: String)

  implicit val userEncoder: VPackEncoder[User] = VPackEncoder.gen
  implicit val userDecoder: VPackDecoder[User] = VPackDecoder.gen


  val userAddress = "address1"
  val userNonce = "nonce1"
  val userRequest = UserRequest(userAddress)
  val expectedUser = User(userAddress, userNonce)

  val testKey = "zK55VIsxuDZBfTSr5rK4t9U5TY2FZUiu+dW0nCWcegw=".b64Bytes.get
  val claim = JWTClaims(subject = "1234567890".some, jwtId = None)
  val expectedJwtToken = "eyJ0eXAiOiJKV1QiLCJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.p96Ile3KTc_mp3i2J3dxqbvi16CJrT2-b448fZ8Hnz4"


  def userUri(user: User): Uri = userUri(user.address)
  def userUri(userAddress: String): Uri = uri"/users" / userAddress

  type CombinedAlgebra[R] = EitherK[Http4sAlgebra, ResourceAlgebra, R]


  def retrieveUser[Algebra[_]](
      implicit resourceDsl: ArangoDsl[Algebra],
      interpreters: Algebra ~> IO): Kleisli[IO, String, ApiResult[User]] =
    Kleisli(id => resourceDsl.fetch[User](userUri(id)).map(_.body).value.foldMap(interpreters))

  val onFailure: AuthedRoutes[ApiError, IO] = Kleisli(req => OptionT.liftF(req.context))

  def getUserFromHeader(request: Request[IO]): ApiResult[String] =
    request
        .headers
        .get[Authorization]
        .map(_.toString)
        .toResult(NonAuthorizedError("Couldn't find an Authorization header".some))

  def authUser[Algebra[_]](
      implicit resourceDsl: ArangoDsl[Algebra],
      interpreters: Algebra ~> IO): Kleisli[IO, Request[IO], ApiResult[User]] =
    Kleisli({ request =>
      val message = for {
        userAddress <- getUserFromHeader(request).liftFree[Algebra]
        user <- resourceDsl.fetch[User](userUri(userAddress))

      } yield user.body

      val result = message.value.foldMap(interpreters)
      result
    })

  def authMiddleware[Algebra[_]](
      implicit resourceDsl: ArangoDsl[Algebra],
      interpreters: Algebra ~> IO): AuthMiddleware[IO, User] = AuthMiddleware(authUser, onFailure)

  def userRoutes[Algebra[_]](
      implicit http4sFreeDsl: Http4sFreeDsl[Algebra],
      resourceDsl: ResourceDsl[Algebra, VPackEncoder, VPackDecoder],
      interpreters: Algebra ~> IO): HttpRoutes[IO] = {

    val dsl = new Http4sDsl[IO]{}
    import dsl._
    import http4sFreeDsl._
    import resourceDsl._

    HttpRoutes.of[IO] {
      case r @ POST -> Root / "users" =>
        for {
          userRequest <- parseRequest[IO, UserRequest](r)
          userAddress = userRequest.address
          user = User(userAddress, userNonce)
          storedUser <- store[User](userUri(user), user)
        } yield storedUser.created[IO]
    }
  }

  val userService = userRoutes[CombinedAlgebra]

  val createUserRequest: Request[IO] = Request[IO](Method.POST, uri"/users").withEntity(userRequest)


  // Windows testing hack
  private def tsecWindowsFix(): Unit =
    try {
      SecureRandom.getInstance("NativePRNGNonBlocking")
      ()
    } catch {
      case _: NoSuchAlgorithmException =>
        val secureRandom = new SecureRandom()
        val defaultSecureRandomProvider = secureRandom.getProvider.get(s"SecureRandom.${secureRandom.getAlgorithm}")
        secureRandom.getProvider.put("SecureRandom.NativePRNGNonBlocking", defaultSecureRandomProvider)
        Security.addProvider(secureRandom.getProvider)
        ()
    }

  tsecWindowsFix()
}

class ArangoServiceIT(env: Env)
    extends Specification
        with DockerKitSpotify
        with DockerArango
        with DockerTestKit
        with MockServiceWithArango
        with AuthCases
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
