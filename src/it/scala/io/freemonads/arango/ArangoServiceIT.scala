/*
 * TODO: License goes here!
 */

package io.freemonads
package arango

import java.security.{NoSuchAlgorithmException, SecureRandom, Security}
import scala.concurrent.Future

import avokka.velocypack.{VPackDecoder, VPackEncoder}
import cats.data.{EitherK, Kleisli, OptionT}
import cats.effect.{IO, MonadThrow}
import cats.free.Free
import cats.implicits.catsSyntaxApplicativeId
import cats.syntax.option._
import cats.syntax.semigroupk._
import cats.~>
import com.whisk.docker.impl.spotify._
import com.whisk.docker.specs2.DockerTestKit
import io.circe.generic.auto._
import io.freemonads.docker.DockerArango
import io.freemonads.specs2.Http4FreeIOMatchers
import org.http4s._
import org.http4s.circe.CirceEntityCodec._
import org.http4s.dsl.Http4sDsl
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

  import httpStore._
  import interpreters.arangoStore._

  case class Mock(id: String, user: String, age: Int)

  implicit val mockEncoder: VPackEncoder[Mock] = VPackEncoder.gen
  implicit val mockDecoder: VPackDecoder[Mock] = VPackDecoder.gen

  implicit val arangoInterpreter: HttpStoreAlgebra ~> IO = arangoInterpreter

  val mock1 = Mock("aaa123", "Roger", 21)
  val updatedMock = Mock("456", "NewName", 36)
  val mocksUri = uri"/mocks"
  val mock1Uri = mocksUri / mock1.id

  def storeFetch(uri: Uri, mock: Mock)(implicit dsl: ArangoStoreDsl[HttpStoreAlgebra]): Free[HttpStoreAlgebra, Mock] = {

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
      implicit dsl: ArangoStoreDsl[HttpStoreAlgebra]): Free[HttpStoreAlgebra, Mock] = {

    import dsl._

    for {
      savedResource <- store[Mock](uri, mock)
      updatedResource <- store[Mock](savedResource.uri, newMock)
    } yield updatedResource.body
  }

  def fetchFromArango(uri: Uri)(implicit dsl: ArangoStoreDsl[HttpStoreAlgebra]): Free[HttpStoreAlgebra, Mock] =
    dsl.fetch[Mock](uri).map(_.body)

}

trait AuthCases extends IOMatchersWithLogger {

  import http._
  import httpStore._
  import error._
  import interpreters.arangoStore._
  import security._
  import jwt._
  import tsec.jwt.JWTClaims


  case class UserRequest(address: String)
  case class User(address: String, nonce: String, username: Option[String])
  case class UserUpdate(username: String)

  implicit val userEncoder: VPackEncoder[User] = VPackEncoder.gen
  implicit val userDecoder: VPackDecoder[User] = VPackDecoder.gen


  val userAddress = "address1"
  val userNonce = "nonce1"
  val username = "rogerthat"
  val userRequest = UserRequest(userAddress)
  val expectedCreatedUser = User(userAddress, userNonce, None)
  val expectedUpdatedUser = User(userAddress, userNonce, username.some)

  val claim = JWTClaims(subject = "address1".some, jwtId = None)
  val jwtToken = "eyJ0eXAiOiJKV1QiLCJhbGciOiJIUzI1NiJ9." +
      "eyJzdWIiOiJhZGRyZXNzMSJ9." +
      "hruJMUPgmxZwCYYqZJXB9l5x_shhGk5nYbvE_ryfECw"

  def userUri(user: User): Uri = userUri(user.address)
  def userUri(userAddress: String): Uri = uri"/users" / userAddress

  def retrieveUser[Algebra[_]](
      implicit storeDsl: ArangoStoreDsl[Algebra],
      interpreters: Algebra ~> IO): Kleisli[IO, String, User] =
    Kleisli(id => storeDsl.fetch[User](userUri(id)).map(_.body).foldMap(interpreters))

  val onFailure: AuthedRoutes[ApiError, IO] = Kleisli(req => OptionT.liftF(req.context))

  def jwtTokenFromRequest[Algebra[_]](request: Request[IO])(implicit F: MonadThrow[Algebra]): Algebra[String] =
    request.headers.get[Authorization] match {
      case Some(credentials) => credentials match {
        case Authorization(Credentials.Token(_, jwtToken)) => jwtToken.pure[Algebra]
        case _ => F.raiseError(NonAuthorizedError("Invalid Authorization header".some))
      }
      case None => F.raiseError(NonAuthorizedError("Couldn't find an Authorization header".some))
    }


  def log[Algebra[_]](text: String): Free[Algebra, Unit] = Free.pure(println(text))

  def authUser[Algebra[_]](
      implicit storeDsl: ArangoStoreDsl[Algebra],
      securityDsl: SecurityDsl[Algebra],
      interpreters: Algebra ~> IO): Kleisli[IO, Request[IO], User] =

    Kleisli({ request =>
      val message = for {
        jwtToken <- Free.liftF[Algebra, String](jwtTokenFromRequest(request))
        _ <- log(s"jwt token: $jwtToken")
        claim <- securityDsl.validateToken(Token(jwtToken))
        _ <- log(s"claim: $claim")
        user <- storeDsl.fetch[User](userUri(claim.subject.value))
        _ <- log(s"user: $user")
      } yield user.body

      message.foldMap(interpreters)
    })

  def authMiddleware[Algebra[_]](
      implicit storeDsl: ArangoStoreDsl[Algebra],
      securityDsl: SecurityDsl[Algebra],
      interpreters: Algebra ~> IO): AuthMiddleware[IO, User] = AuthMiddleware(authUser, onFailure)

  def publicRoutes[Algebra[_]](
      implicit httpFreeDsl: HttpFreeDsl[Algebra],
      resourceDsl: ArangoStoreDsl[Algebra],
      interpreters: Algebra ~> IO): HttpRoutes[IO] = {

    val dsl = new Http4sDsl[IO]{}
    import dsl._
    import httpFreeDsl._
    import resourceDsl._

    HttpRoutes.of[IO] {
      case r @ POST -> Root / "users" =>
        for {
          userRequest <- parseRequest[IO, UserRequest](r)
          userAddress = userRequest.address
          user = User(userAddress, userNonce, None)
          storedUser <- httpStore[User](userUri(user), user)
        } yield storedUser.created[IO]
    }
  }

  def privateRoutes[Algebra[_]](
      implicit httpFreeDsl: HttpFreeDsl[Algebra],
      resourceDsl: ArangoStoreDsl[Algebra],
      interpreters: Algebra ~> IO): AuthedRoutes[User, IO] = {

    val dsl = new Http4sDsl[IO]{}
    import dsl._
    import httpFreeDsl._
    import resourceDsl._

    val wrongAddressError = NonAuthorizedError("wrong address".some)

    AuthedRoutes.of[User, IO] {
      case r @ GET -> Root / "profile" as user =>
        for {
          _ <- log(s"request for profile: $r")
          userFree <- Free.pure[Algebra](user)
        } yield Ok(userFree)

      case r @ PUT -> Root / "users" / address as user =>
        for {
          UserUpdate(newUsername) <- parseRequest[IO, UserUpdate](r.req)
          _ <- Free.liftF[IO, String](if (address == user.address) ().pure[IO] else IO.raiseError(wrongAddressError))
          updatedUser <- httpStore[User](r.req.uri, user.copy(username = newUsername.some))
        } yield updatedUser.ok[IO]
    }
  }

  type CombinedAlgebraA[R] = EitherK[HttpStoreAlgebra, SecurityAlgebra, R]
  type CombinedAlgebra[R] = EitherK[Http4sAlgebra, CombinedAlgebraA, R]

  implicit val securityDsl = SecurityDsl.instance[CombinedAlgebra]

  implicit val interpreters: CombinedAlgebra ~> IO =
    (httpFreeInterpreter[IO] or (arangoIoInterpreter or jwtSecurityInterpreter[IO]))

  val authMiddlewareInstance = authMiddleware[CombinedAlgebra]

  val userService = publicRoutes[CombinedAlgebra] <+> authMiddlewareInstance(privateRoutes[CombinedAlgebra])

  val validAuthHeader = Headers(Authorization(Credentials.Token(AuthScheme.Bearer, jwtToken)))
  val createUserRequest: Request[IO] = Request[IO](Method.POST, uri"/users").withEntity(userRequest)
  val getProfileRequest: Request[IO] = Request[IO](Method.GET,uri"/profile").withHeaders(validAuthHeader)
  val updateUserRequest: Request[IO] =
    Request[IO](Method.PUT,uri"/users/address1")
        .withEntity(UserUpdate(username))
        .withHeaders(validAuthHeader)

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

  import http.resource._

  implicit val ee = env.executionEnv
  implicit val dsl = HttpStoreDsl.instance[HttpStoreAlgebra, VPackEncoder, VPackDecoder]

  def is: SpecStructure = s2"""
      The ArangoDB container should be ready                  $arangoIsReady
      Store a new resource with specific id                   $storeNewResource
      Store and fetch from ArangoDB                           $storeAndFetch
      Store and update from ArangoDB                          $storeAndUpdate
      Return not found error when document doesn't exist      $returnNotFound
      Store a resource using HTTP API                         $storeResource

  """

  def arangoIsReady: MatchResult[Future[Boolean]] = isContainerReady(arangoContainer) must beTrue.await

  def storeNewResource: MatchResult[Any] = dsl.store[Mock](mock1Uri, mock1) must matchFree(RestResource(mock1Uri, mock1))

  def storeAndFetch: MatchResult[Any] = storeFetch(mocksUri, mock1) must matchFree(mock1)

  def storeAndUpdate: MatchResult[Any] = storeAndUpdate(mocksUri, mock1, updatedMock) must matchFree(updatedMock)

  def returnNotFound: MatchResult[Any] = fetchFromArango(uri"/emptyCollection/123") must resultErrorNotFound

  def storeResource: MatchResult[Any] =
    (userService.orNotFound(createUserRequest) must returnValue { (response: Response[IO]) =>
      response must haveStatus(Created) and
          (response must haveBody(expectedCreatedUser)) and
          (response must containHeader(Location(userUri(expectedCreatedUser))))
    }) and (
      userService.orNotFound(getProfileRequest) must returnValue { response: Response[IO] =>
        response must haveStatus(Ok) and (response must haveBody(expectedCreatedUser))
      }) and (
        userService.orNotFound(updateUserRequest) must returnValue { response: Response[IO] =>
          response must haveStatus(Ok) and (response must haveBody(expectedUpdatedUser))
      })



}
