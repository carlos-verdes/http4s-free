/*
 * TODO: License goes here!
 */


package io.freemonads
package http

import cats.effect.IO
import cats.{Applicative, ~>}
import io.circe.generic.auto._
import io.circe.{Decoder, Encoder}
import org.http4s.circe.CirceEntityCodec._
import org.http4s.headers.{Link, LinkValue, Location}
import org.http4s.implicits.http4sLiteralsSyntax
import org.http4s.{Response, Status}
import org.specs2.Specification
import org.specs2.matcher.{IOMatchers, MatchResult}
import org.specs2.specification.core.SpecStructure

trait MockResources {

  import api._
  import resource._

  case class Mock(id: Option[String], name: String, age: Int)

  val existingId = "id456"
  val existingUri = uri"/mocks" / existingId
  val nonexistingUri = uri"/mocks" / "123"
  val existingMock = Mock(Some(existingId), "name123", 23)

  val newMockId = "newId123"
  val newMockIdUri = uri"/mocks" / newMockId
  val newMock = Mock(Some(newMockId), "other name", 56)

  implicit def interpreter[F[_]](implicit A: Applicative[F]): ResourceAlgebra ~> F = new (ResourceAlgebra ~> F) {
    override def apply[A](op: ResourceAlgebra[A]): F[A] = op match {

      case Store(_, r, _, _) => A.pure(RestResource(newMockIdUri, r).resultOk)

      case Fetch(resourceUri, _) =>
        A.pure((if (resourceUri == existingUri) RestResource(newMockIdUri, existingMock).resultOk
          else ResourceNotFoundError().resultError[Mock]).asInstanceOf[A])
    }
  }

  val responseCreated: IO[Response[IO]] = RestResource(existingUri, existingMock).created
  val responseOk: IO[Response[IO]] = RestResource(existingUri, existingMock).ok
}

class ApiResourceSpec
    extends Specification
    with MockResources
    with IOMatchers
    with specs2.Http4FreeIOMatchers { def is: SpecStructure =

  s2"""
      ApiResource should: <br/>
      Store a resource                                $store
      Fetch an existing resource                      $fetchFound
      Return not found error for nonexistent resource $fetchNotFound
      Add self Link header for resources              $selfLinkHeader
      Add Location header for created resources       $locationHeader
      """

  import resource.ResourceDsl._
  import resource._

  implicit val dsl = instance[ResourceAlgebra, Encoder, Decoder]

  def store: MatchResult[Any] = dsl.store[Mock](newMockIdUri, newMock).map(_.body) must resultOk(newMock)
  def fetchFound: MatchResult[Any] = dsl.fetch[Mock](existingUri).map(_.body) must resultOk(existingMock)
  def fetchNotFound: MatchResult[Any] = dsl.fetch[Mock](nonexistingUri).map(_.body) must resultErrorNotFound

  def selfLinkHeader: MatchResult[Any] =
    RestResource(existingUri, existingMock).ok[IO] must returnValue { (response: Response[IO]) =>
      response must haveStatus(Status.Ok) and
          (response must haveBody(existingMock)) and
          (response must containHeader(Link(LinkValue(existingUri, Some("self")))))
  }

  def locationHeader: MatchResult[Any] =
    RestResource(existingUri, existingMock).created[IO] must returnValue { (response: Response[IO]) =>
      response must haveStatus(Status.Created) and
          (response must haveBody(existingMock)) and
          (response must containHeader(Location(existingUri)))
    }
}

