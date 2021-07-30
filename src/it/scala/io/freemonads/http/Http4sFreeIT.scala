/*
 * TODO: License goes here!
 */


package io.freemonads
package http

import java.util.UUID

import avokka.velocypack.{VPackDecoder, VPackEncoder}
import cats.data.EitherK
import cats.effect.{IO, Sync, Timer}
import cats.{Functor, ~>}
import io.circe.generic.auto._
import io.circe.literal.JsonStringContext
import io.freemonads.http.resource.{ResourceAlgebra, ResourceDsl, RestResource}
import io.freemonads.specs2.Http4FreeMatchers
import org.http4s._
import org.http4s.circe.CirceEntityCodec._
import org.http4s.dsl.Http4sDsl
import org.http4s.headers.Location
import org.http4s.implicits.{http4sKleisliResponseSyntaxOptionT, http4sLiteralsSyntax}
import org.specs2.Specification
import org.specs2.matcher.{IOMatchers, MatchResult}
import org.specs2.specification.core.SpecStructure
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

trait RestRoutes extends IOMatchers {

  import api._
  import arango._
  import rest._

  case class Mock(id: Option[String], name: String, age: Int)
  case class UserRequest(userId: String)
  case class User(publicAddress: String, nonce: String)

  implicit val mockEncoder: VPackEncoder[Mock] = VPackEncoder.gen
  implicit val mockDecoder: VPackDecoder[Mock] = VPackDecoder.gen
  implicit val userEncoder: VPackEncoder[User] = VPackEncoder.gen
  implicit val userDecoder: VPackDecoder[User] = VPackDecoder.gen

  val newMock = Mock(None, "name123", 23)
  val createdId = "id123"
  val createdMock = Mock(Some(createdId), "name123", 23)

  val existingId = "id456"
  val existingMock = Mock(Some(existingId), "name123", 23)

  def mockRoutes[F[_]: Sync : Timer : Functor, Algebra[_]](
      implicit http4sFreeDsl: Http4sFreeDsl[Algebra],
      resourceDsl: ResourceDsl[Algebra, VPackEncoder, VPackDecoder],
      interpreters: Algebra ~> F): HttpRoutes[F] = {

    val dsl = new Http4sDsl[F]{}
    import dsl._
    import http4sFreeDsl._
    import resourceDsl._

    HttpRoutes.of[F] {
      case GET -> Root / "mocks" =>
        for  {
          mock <- Mock(Some("id123"), "name123", 23).resultOk.liftFree[Algebra]
        } yield Ok(mock)

      case r @ POST -> Root / "mocks" =>
        for {
          mockRequest <- parseRequest[F, Mock](r)
        } yield Created(mockRequest.copy(id = Some(createdId)), Location(uri"/mocks" / createdId))

      case r @ POST -> Root / "users" =>
        for {
          userRequest <- parseRequest[F, UserRequest](r)
          userId = userRequest.userId
          nonce = UUID.randomUUID().toString
          RestResource(userUri, storedUser) <- store[User](uri"/users" / userId, User(userId, nonce))
        } yield Created(storedUser, Location(userUri))
    }
  }

  implicit def unsafeLogger: Logger[IO] = Slf4jLogger.getLogger[IO]
  implicit val interpreters = (http4sInterpreter[IO] or arangoIoInterpreter)

  type CombinedAlgebra[R] = EitherK[Http4sAlgebra, ResourceAlgebra, R]

  val mockService = mockRoutes[IO, CombinedAlgebra]

  val createMockRequest: Request[IO] = Request[IO](Method.POST, uri"/mocks").withEntity(newMock)
  val invalidRequest: Request[IO] = Request[IO](Method.POST, uri"/mocks").withEntity(json"""{ "wrongJson": "" }""")
  val notFoundRequest: Request[IO] = Request[IO](Method.GET, uri"/wrongUri")
}

class Http4sFreeIT extends Specification with RestRoutes with Http4FreeMatchers[IO] with IOMatchers {

  def is: SpecStructure =
      s2"""
          RestSpec should: <br/>
          Be able to use free monads on Rest APIs        $httpWithFreeMonads
          Response with 400 error if request parse fails $manageParsingErrors
          Response with 403 error on auth issues         $manageAuthErrors
          Response with 404 error if not found           $manageNotFound
          Response with 409 error on conflict issues     $manageConflictErrors
          Response with 501 error if not implemented     $manageNotImplementedErrors
          Response with 500 error for runtime issues     $manageRuntimeErrors
          """

  import api._
  import org.http4s.dsl.io._

  def httpWithFreeMonads: MatchResult[Any] =
    mockService.orNotFound(createMockRequest) must returnValue { (response: Response[IO]) =>
      response must haveStatus(Created) and
          (response must haveBody(createdMock)) and
          (response must containHeader(Location(createMockRequest.uri / createdMock.id.getOrElse(""))))
    }

  def manageParsingErrors: MatchResult[Any] = mockService.orNotFound(invalidRequest) must returnStatus(BadRequest)

  def manageAuthErrors: MatchResult[Any] =
    rest.apiErrorToResponse[IO](NonAuthorizedError(None)) must returnStatus(Forbidden)

  def manageNotFound: MatchResult[Any] = rest.apiErrorToResponse[IO](ResourceNotFoundError()) must returnStatus(NotFound)

  def manageNotImplementedErrors: MatchResult[Any] =
    rest.apiErrorToResponse[IO](NotImplementedError("some method")) must returnStatus(NotImplemented)

  def manageConflictErrors: MatchResult[Any] = rest.apiErrorToResponse[IO](ConflictError()) must returnStatus(Conflict)

  def manageRuntimeErrors: MatchResult[Any] = rest.apiErrorToResponse[IO](RuntimeError()) must returnStatus(InternalServerError)
}
