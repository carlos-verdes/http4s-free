/*
 * TODO: License goes here!
 */


package io.freemonads
package http

import cats.effect.IO
import cats.~>
import io.circe.generic.auto._
import io.circe.{Decoder, Encoder}
import org.http4s.Uri
import org.http4s.implicits.http4sLiteralsSyntax
import org.specs2.Specification
import org.specs2.matcher.{IOMatchers, MatchResult}
import org.specs2.specification.core.SpecStructure

trait MockResources extends IOMatchers {

  import api._
  import resource._
  import rest._


  case class Mock(id: Option[String], name: String, age: Int)

  val existingId = "id456"
  val existingUri = uri"/mocks" / existingId
  val nonexistingUri = uri"/mocks" / "123"
  val existingMock = Mock(Some(existingId), "name123", 23)

  val newMockId = "newId123"
  val newMockIdUri = uri"/mocks" / newMockId
  val newMock = Mock(Some(newMockId), "other name", 56)

  implicit val interpreter: ResourceAlgebra ~> IO = new (ResourceAlgebra ~> IO) {
    override def apply[A](op: ResourceAlgebra[A]): IO[A] = op match {

      case Store(_, r, _, _) =>
        IO(RestResource(newMockIdUri, r).resultOk).asInstanceOf[IO[A]]

      case Fetch(resourceUri, _) =>
        IO(
          if (resourceUri == existingUri) RestResource(newMockIdUri, existingMock).resultOk
          else ResourceNotFoundError().resultError[Mock]).asInstanceOf[IO[A]]
    }
  }

  def storeProgram[F[_]](
      id: Uri,
      mock: Mock)(
      implicit dsl: ResourceDsl[F, Encoder, Decoder],
      E: Encoder[Mock]): ApiFree[F, Mock] =
    for {
      mock <- dsl.store[Mock](id, mock)
    } yield mock.body

  def fetchProgram[F[_]](id: Uri)(implicit dsl: ResourceDsl[F, Encoder, Decoder], D: Decoder[Mock]): ApiFree[F, Mock] =
    for {
      mock <- dsl.fetch[Mock](id)
    } yield mock.body

  implicit val interpreters = http4sInterpreter[IO]
}

class ApiResource extends Specification with MockResources with specs2.Http4FreeIOMatchers { def is: SpecStructure =

  s2"""
      ApiResource should: <br/>
      Store a resource                                $store
      Fetch an existing resource                      $fetchFound
      Return not found error for nonexistent resource $fetchNotFound
      """

  import api._
  import resource.ResourceDsl._
  import resource._

  implicit val dslInstance = instance[ResourceAlgebra, Encoder, Decoder]

  def store: MatchResult[Any] = storeProgram(newMockIdUri, newMock) must resultOk(newMock)
  def fetchFound: MatchResult[Any] = fetchProgram(existingUri) must resultOk(existingMock)
  def fetchNotFound: MatchResult[Any] = fetchProgram(nonexistingUri) must resultError(ResourceNotFoundError())
}
