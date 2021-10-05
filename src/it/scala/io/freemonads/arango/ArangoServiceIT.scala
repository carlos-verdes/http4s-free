/*
 * TODO: License goes here!
 */

package io.freemonads
package arango

import java.security.{NoSuchAlgorithmException, SecureRandom, Security}
import scala.concurrent.Future

import avokka.arangodb.ArangoConfiguration
import avokka.arangodb.fs2.Arango
import avokka.velocypack.{VPackDecoder, VPackEncoder}
import cats.data.{Kleisli, OptionT}
import cats.effect.{IO, MonadThrow}
import cats.implicits.{catsSyntaxApplicativeId, toFlatMapOps, toFunctorOps, toSemigroupKOps}
import cats.syntax.option._
import com.whisk.docker.impl.spotify._
import com.whisk.docker.specs2.DockerTestKit
import io.circe.generic.auto._
import io.freemonads.docker.DockerArango
import io.freemonads.specs2.Http4FreeIOMatchers
import io.freemonads.tagless.http.HttpAlgebra
import io.freemonads.tagless.security.SecurityAlgebra
import org.http4s._
import org.http4s.circe.CirceEntityCodec._
import org.http4s.dsl.io._
import org.http4s.headers.{Authorization, Location}
import org.http4s.implicits._
import org.http4s.server.AuthMiddleware
import org.specs2.Specification
import org.specs2.matcher.{IOMatchers, MatchResult}
import org.specs2.specification.core.{Env, SpecStructure}
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

trait IOMatchersWithLogger extends IOMatchers {

  implicit def unsafeLogger: Logger[IO] = Slf4jLogger.getLogger[IO]
}

trait InterpretersAndDsls extends IOMatchersWithLogger {

  import tagless.interpreters.arangoStore._
  import tagless.security.jwt._

  val arangoConfig = ArangoConfiguration.load()
  val arangoResource = Arango(arangoConfig)

  implicit val storeDsl: ArangoStoreAlgebra[IO] = ArangoStoreInterpreter(arangoResource)
  implicit val securityDsl: SecurityAlgebra[IO] = ioJwtSecurityInterpreter
  implicit val httpFreeDsl: HttpAlgebra[IO] = tagless.http.io.ioHttpAlgebraInterpreter
}

trait MockServiceWithArango extends InterpretersAndDsls {

  import tagless.http._
  import tagless.interpreters.arangoStore.ArangoStoreAlgebra

  case class Mock(id: String, user: String, age: Int)
  case class Person(name: String)

  implicit val mockEncoder: VPackEncoder[Mock] = VPackEncoder.gen
  implicit val mockDecoder: VPackDecoder[Mock] = VPackDecoder.gen
  implicit val personEncoder: VPackEncoder[Person] = VPackEncoder.gen
  implicit val personDecoder: VPackDecoder[Person] = VPackDecoder.gen

  val mocksUri = uri"/mocks"
  val mock1 = HttpResource(mocksUri / "aaa123", Mock("aaa123", "Roger", 21))
  val updatedMock = Mock("456", "NewName", 36)
  val person1 = HttpResource(uri"/person/roget" , Person("Roger"))
  val person2 = HttpResource(uri"/person/that", Person("That"))
  val likes = "likes"

  def storeAndFetch[F[_]: MonadThrow](
      mockResource: HttpResource[Mock])(
      implicit dsl: ArangoStoreAlgebra[F]): F[Mock] = {

    import dsl._

    for {
      savedResource <- store[Mock](mockResource)
      fetchedResource <- fetch[Mock](savedResource.uri)
    } yield fetchedResource.body
  }

  def storeAndUpdate[F[_]: MonadThrow](
      mockResource: HttpResource[Mock],
      newMock: Mock)(
      implicit dsl: ArangoStoreAlgebra[F]): F[Mock] = {

    import dsl._

    for {
      savedResource <- store[Mock](mockResource)
      updatedResource <- store[Mock](savedResource.uri, newMock)
    } yield updatedResource.body
  }

  def storeAndLink[F[_]: MonadThrow, L: VPackEncoder : VPackDecoder, R: VPackEncoder : VPackDecoder](
      left: HttpResource[L],
      right: HttpResource[R],
      linkRel: String)(
      implicit dsl: ArangoStoreAlgebra[F]): F[Unit] = {

    import dsl._

    for {
      _ <- store[L](left)
      _ <- store[R](right)
      _ <- linkResources(left, right, linkRel)
    } yield ()
  }

  def fetchFromArango[F[_]: MonadThrow](
      uri: Uri)(
      implicit dsl: ArangoStoreAlgebra[F]): F[Mock] =
    dsl.fetch[Mock](uri).map(_.body)
}

trait AuthCases extends InterpretersAndDsls {

  import error._
  import tagless.http._
  import tagless.interpreters.arangoStore._
  import tagless.security._
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

  def retrieveUser: Kleisli[IO, String, User] = Kleisli(id => storeDsl.fetch[User](userUri(id)).map(_.body))

  def onFailure: AuthedRoutes[Throwable, IO] = Kleisli(req => OptionT.liftF(Forbidden(req.context.toString)))

  def jwtTokenFromAuthHeader(request: Request[IO]): IO[String] =
    request.headers.get[Authorization] match {
      case Some(credentials) => credentials match {
        case Authorization(Credentials.Token(_, jwtToken)) => jwtToken.pure[IO]
        case _ => IO.raiseError(NonAuthorizedError("Invalid Authorization header".some))
      }
      case None => IO.raiseError(NonAuthorizedError("Couldn't find an Authorization header".some))
    }


  def log(text: String): IO[Unit] = println(text).pure[IO]

  def authUser: Kleisli[IO, Request[IO], Either[Throwable, User]] =
    Kleisli({ request =>
      val message = for {
        jwtToken <- httpFreeDsl.getJwtTokenFromHeader(request)
        _ <- log(s"jwt token: $jwtToken")
        claim <- securityDsl.validateToken(Token(jwtToken))
        _ <- log(s"claim: $claim")
        user <- storeDsl.fetch[User](userUri(claim.subject.value))
        _ <- log(s"user: $user")
      } yield user.body

      message.attempt
    })

  def authMiddleware: AuthMiddleware[IO, User] =
    AuthMiddleware[IO, Throwable, User](authUser, onFailure)

  def publicRoutes(
      implicit httpFreeDsl: HttpAlgebra[IO],
      httpStoreDsl: ArangoStoreAlgebra[IO]): HttpRoutes[IO] = {

    import httpFreeDsl._
    import httpStoreDsl._

    HttpRoutes.of[IO] {
      case r @ POST -> Root / "users" =>
        for {
          userRequest <- parseRequest[UserRequest](r)
          userAddress = userRequest.address
          user = User(userAddress, userNonce, None)
          storedUser <- store[User](userUri(user), user)
        } yield storedUser.created[IO]
    }
  }

  def privateRoutes(
      implicit httpFreeDsl: HttpAlgebra[IO],
      httpStoreDsl: ArangoStoreAlgebra[IO]): AuthedRoutes[User, IO] = {

    import httpFreeDsl._
    import httpStoreDsl._

    val wrongAddressError = NonAuthorizedError("wrong address".some)

    AuthedRoutes.of[User, IO] {
      case GET -> Root / "profile" as user => Ok(user)

      case r @ PUT -> Root / "users" / address as user =>
        for {
          UserUpdate(newUsername) <- parseRequest[UserUpdate](r.req)
          _ <-
            if (address == user.address)
              IO.pure[Unit](())
            else {
              IO.raiseError[Unit](wrongAddressError)
            }
          updatedUser <- store[User](r.req.uri, user.copy(username = newUsername.some))
          response <- Ok(updatedUser.body)
        } yield response
    }
  }



  val authMiddlewareInstance = authMiddleware
  val userService = publicRoutes <+> authMiddlewareInstance(privateRoutes)

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

  import error._

  implicit val ee = env.executionEnv

  def is: SpecStructure = s2"""
      The ArangoDB container should be ready                  $arangoIsReady
      Store a new resource with specific id                   $storeNewResource
      Store and fetch from ArangoDB                           $testStoreAndFetch
      Store and update from ArangoDB                          $storeAndUpdate
      Store and link two resources                            $storeAndLinkResources
      Return not found error when document doesn't exist      $returnNotFound
      Store a resource using HTTP API                         $storeResource

  """

  def arangoIsReady: MatchResult[Future[Boolean]] = isContainerReady(arangoContainer) must beTrue.await

  def storeNewResource: MatchResult[Any] =
    storeDsl.store[Mock](mocksUri, mock1.body).map(_.body) must returnValue(mock1.body)

  def testStoreAndFetch: MatchResult[Any] = storeAndFetch[IO](mock1) must returnValue(mock1.body)

  def storeAndUpdate: MatchResult[Any] = storeAndUpdate[IO](mock1, updatedMock) must returnValue(updatedMock)

  def storeAndLinkResources: MatchResult[Any] =
    (storeAndLink[IO, Person, Person](person1, person2, likes)  must returnValue(())) and
        (storeAndLink[IO, Person, Mock](person1, mock1, likes) must returnValue(()))

  def returnNotFound: MatchResult[Any] =
    fetchFromArango[IO](uri"/emptyCollection/123") must returnError[Mock, ResourceNotFoundError]

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
