/*
 * TODO: License goes here!
 */


package io.freemonads
package http


import cats.effect.{IO, Sync, Timer}
import cats.{Functor, ~>}
import io.circe.generic.auto._
import io.circe.literal.JsonStringContext
import io.freemonads.specs2.Http4FreeIOMatchers
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

  import http._

  case class Mock(id: Option[String], name: String, age: Int)

  val newMock = Mock(None, "name123", 23)
  val createdId = "id123"
  val createdMock = Mock(Some(createdId), "name123", 23)

  val userAddress = "address1"
  val userNonce = "nonce1"

  val existingId = "id456"
  val existingMock = Mock(Some(existingId), "name123", 23)

  def testRoutes[F[_]: Sync : Timer : Functor, Algebra[_]](
      implicit httpFreeDsl: HttpFreeDsl[Algebra],
      interpreters: Algebra ~> F): HttpRoutes[F] = {

    val dsl = new Http4sDsl[F]{}
    import dsl._
    import httpFreeDsl._

    HttpRoutes.of[F] {
      /*
      case GET -> Root / "mocks" =>
        for  {
          mock <- Free.pure[Algebra, Mock](Mock(Some("id123"), "name123", 23))
        } yield Ok(mock)
       */
      case r @ POST -> Root / "mocks" =>
        for {
          mockRequest <- parseRequest[F, Mock](r)
        } yield {
          Created(mockRequest.copy(id = Some(createdId)), Location(uri"/mocks" / createdId))
        }
    }
  }

  implicit def unsafeLogger: Logger[IO] = Slf4jLogger.getLogger[IO]
  implicit val interpreters = httpFreeInterpreter[IO]

  val testService = testRoutes[IO, HttpFreeAlgebra]


  val createMockRequest: Request[IO] = Request[IO](Method.POST, uri"/mocks").withEntity(newMock)
  val invalidRequest: Request[IO] = Request[IO](Method.POST, uri"/mocks").withEntity(json"""{ "wrongJson": "" }""")
  val notFoundRequest: Request[IO] = Request[IO](Method.GET, uri"/wrongUri")
}

class Http4sFreeIT extends Specification with RestRoutes with Http4FreeIOMatchers {

  def is: SpecStructure =
      s2"""
          Http4s general IT test should: <br/>
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
    testService.orNotFound(createMockRequest) must returnValue { (response: Response[IO]) =>
      response must haveStatus(Created) and
          (response must haveBody(createdMock)) and
          (response must containHeader(Location(createMockRequest.uri / createdMock.id.getOrElse(""))))
    }

  def manageParsingErrors: MatchResult[Any] = testService.orNotFound(invalidRequest) must returnStatus(BadRequest)

  def manageAuthErrors: MatchResult[Any] =
    rest.apiErrorToResponse[IO](NonAuthorizedError(None)) must returnStatus(Forbidden)

  def manageNotFound: MatchResult[Any] =
    rest.apiErrorToResponse[IO](ResourceNotFoundError()) must returnStatus(NotFound)

  def manageNotImplementedErrors: MatchResult[Any] =
    rest.apiErrorToResponse[IO](NotImplementedError("some method")) must returnStatus(NotImplemented)

  def manageConflictErrors: MatchResult[Any] =
    rest.apiErrorToResponse[IO](ConflictError()) must returnStatus(Conflict)

  def manageRuntimeErrors: MatchResult[Any] =
    rest.apiErrorToResponse[IO](RuntimeError()) must returnStatus(InternalServerError)
}
