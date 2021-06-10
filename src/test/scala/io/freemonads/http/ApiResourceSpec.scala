/*
 * TODO: License goes here!
 */


package io.freemonads
package http

import cats.{Id, ~>}
import io.circe.generic.auto._
import io.circe.{Decoder, Encoder}
import org.http4s.implicits.http4sLiteralsSyntax
import org.specs2.Specification
import org.specs2.matcher.MatchResult
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

  implicit val interpreter: ResourceAlgebra ~> Id = new (ResourceAlgebra ~> Id) {
    override def apply[A](op: ResourceAlgebra[A]): Id[A] = op match {

      case Store(_, r, _, _) => RestResource(newMockIdUri, r).resultOk.asInstanceOf[Id[A]]

      case Fetch(resourceUri, _) =>
        (if (resourceUri == existingUri) RestResource(newMockIdUri, existingMock).resultOk
          else ResourceNotFoundError().resultError[Mock]).asInstanceOf[Id[A]]
    }
  }
}

class ApiResourceSpec extends Specification with MockResources with specs2.Http4FreeIdMatchers { def is: SpecStructure =

  s2"""
      ApiResource should: <br/>
      Store a resource                                $store
      Fetch an existing resource                      $fetchFound
      Return not found error for nonexistent resource $fetchNotFound
      """

  import resource.ResourceDsl._
  import resource._

  implicit val dsl = instance[ResourceAlgebra, Encoder, Decoder]

  def store: MatchResult[Any] = dsl.store[Mock](newMockIdUri, newMock).map(_.body) must resultOk(newMock)
  def fetchFound: MatchResult[Any] = dsl.fetch[Mock](existingUri).map(_.body) must resultOk(existingMock)
  def fetchNotFound: MatchResult[Any] = dsl.fetch[Mock](nonexistingUri).map(_.body) must resultErrorNotFound
}
