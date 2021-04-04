/*
 * TODO: License goes here!
 */


package io.freemonads.http

import cats.effect.IO
import cats.~>
import io.circe.{Decoder, Encoder}
import io.circe.generic.auto._
import io.freemonads.http.resource.{ResourceAlgebra, SELF_REL}
import org.http4s.Uri
import org.http4s.headers.LinkValue
import org.http4s.implicits.http4sLiteralsSyntax
import org.specs2.Specification
import org.specs2.matcher.{IOMatchers, MatchResult}
import org.specs2.specification.core.SpecStructure

trait Resources extends IOMatchers {

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

      case Store(_, r, _) =>
        IO(r.resultOk).asInstanceOf[IO[A]]

      case Fetch(id, _) =>
        IO(if (id.uri == existingUri) existingMock.resultOk else notFound[Mock](id)).asInstanceOf[IO[A]]
    }
  }

  def storeProgram[F[_]](
      id: Uri,
      mock: Mock)(
      implicit dsl: ResourceDsl[F, Encoder, Decoder],
      E: Encoder[Mock]): ApiFree[F, Mock] =
    for {
      mock <- dsl.store[Mock](id, mock)
    } yield mock

  def fetchProgram[F[_]](id: Uri)(implicit dsl: ResourceDsl[F, Encoder, Decoder], D: Decoder[Mock]): ApiFree[F, Mock] =
    for {
      mock <- dsl.fetch[Mock](id)
    } yield mock

  implicit val interpreters = http4sInterpreter[IO]
}

class ApiResource extends Specification with Resources with IOMatchers { def is: SpecStructure =
  s2"""
      ApiResource should: <br/>
      Store a resource                                $store
      Fetch an existing resource                      $fetchFound
      Return not found error for nonexistent resource $fetchNotFound
      """

  import resource.ResourceDsl._
  import api._

  implicit val dslInstance = instance[ResourceAlgebra, Encoder, Decoder]

  def store: MatchResult[Any] =
    storeProgram(newMockIdUri, newMock).value.foldMap(interpreter) must returnValue(Right(newMock))

  def fetchFound: MatchResult[Any] =
    fetchProgram(existingUri).value.foldMap(interpreter) must returnValue(Right(existingMock))

  def fetchNotFound: MatchResult[Any] =
    fetchProgram(nonexistingUri).value.foldMap(interpreter) must
        returnValue(notFound[Mock](LinkValue(nonexistingUri, SELF_REL)))

}
