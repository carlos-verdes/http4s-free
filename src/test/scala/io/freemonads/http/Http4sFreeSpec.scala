/*
 * TODO: License goes here!
 */


package io.freemonads.http

import cats.effect.{IO, Sync, Timer}
import cats.{Functor, ~>}
import io.circe.generic.auto._
import io.circe.literal.JsonStringContext
import org.http4s._
import org.http4s.circe.CirceEntityCodec._
import org.http4s.dsl.Http4sDsl
import org.http4s.implicits.{http4sKleisliResponseSyntaxOptionT, http4sLiteralsSyntax}
import org.specs2.Specification
import org.specs2.matcher.{Http4sMatchers, IOMatchers, MatchResult}
import org.specs2.specification.core.SpecStructure

trait RestRoutes extends IOMatchers {

  import api._
  import rest._

  case class Mock(id: Option[String], name: String, age: Int)

  val newMock = Mock(None, "name123", 23)
  val createdId = "id123"
  val createdMock = Mock(Some(createdId), "name123", 23)

  val existingId = "id456"
  val existingMock = Mock(Some(existingId), "name123", 23)

  def mockRoutes[F[_]: Sync : Timer : Functor, Algebra[_]](
      implicit http4sFreeDsl: Http4sFreeDsl[Algebra],
      interpreters: Algebra ~> F): HttpRoutes[F] = {

    val dsl = new Http4sDsl[F]{}
    import dsl._
    import http4sFreeDsl._

    HttpRoutes.of[F] {
      case GET -> Root / "mock" =>
        for  {
          mock <- Mock(Some("id123"), "name123", 23).resultOk.liftFree[Algebra]
        } yield Ok(mock)

      case r @ POST -> Root / "mock" =>
        for {
          mockRequest <- parseRequest[F, Mock](r)
        } yield Created(mockRequest.copy(id = Some(createdId)))
    }
  }


  implicit val interpreters = http4sInterpreter[IO]
  val mockService = mockRoutes[IO, Http4sAlgebra]

  val createMockRequest: Request[IO] = Request[IO](Method.POST, uri"/mock").withEntity(newMock)
  val invalidRequest: Request[IO] = Request[IO](Method.POST, uri"/mock").withEntity(json"""{ "wrongJson": "" }""")
  val notFoundRequest: Request[IO] = Request[IO](Method.GET, uri"/wrongUri")
}

class HttpfsFreeSpec extends Specification with RestRoutes with Http4sMatchers[IO] with IOMatchers {

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
      response must haveStatus(Created)
      response must haveBody(createdMock)
    }

  def manageParsingErrors: MatchResult[Any] =
    mockService.orNotFound(invalidRequest) must returnStatus(BadRequest)

  def manageAuthErrors: MatchResult[Any] =
    rest.apiErrorToResponse[IO](NonAuthorizedError(None)) must returnStatus(Forbidden)

  def manageNotFound: MatchResult[Any] =
    rest.apiErrorToResponse[IO](ResourceNotFoundError(Some("someId"))) must returnStatus(NotFound)

  def manageNotImplementedErrors: MatchResult[Any] =
    rest.apiErrorToResponse[IO](NotImplementedError("some method")) must returnStatus(NotImplemented)

  def manageConflictErrors: MatchResult[Any] =
    rest.apiErrorToResponse[IO](ResourceAlreadyExistError("someId")) must returnStatus(Conflict)

  def manageRuntimeErrors: MatchResult[Any] =
    rest.apiErrorToResponse[IO](RuntimeError("someId")) must returnStatus(InternalServerError)
}
