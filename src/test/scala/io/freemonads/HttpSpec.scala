/*
 * Copyright 2021 io.freemonads
 *
 * SPDX-License-Identifier: MIT
 */

package io.freemonads

import cats.effect.{IO, MonadThrow}
import cats.implicits.catsSyntaxApplicativeId
import cats.~>
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

  import error._
  import httpStore._

  case class Mock(id: Option[String], name: String, age: Int)

  val existingId = "id456"
  val existingUri = uri"/mocks" / existingId
  val nonexistingUri = uri"/mocks" / "123"
  val existingMock = Mock(Some(existingId), "name123", 23)

  val newMockId = "newId123"
  val newMockIdUri = uri"/mocks" / newMockId
  val newMock = Mock(Some(newMockId), "other name", 56)

  implicit def interpreter[F[_]](implicit F: MonadThrow[F]):  HttpStoreAlgebra ~> F = new (HttpStoreAlgebra ~> F) {
    override def apply[A](op: HttpStoreAlgebra[A]): F[A] = op match {

      case Store(_, r, _, _) => F.pure(HttpResource(newMockIdUri, r))

      case Fetch(resourceUri, _) =>
        (if (resourceUri == existingUri) HttpResource(newMockIdUri, existingMock).pure[F]
        else F.raiseError(ResourceNotFoundError())).asInstanceOf[F[A]]

      case LinkResources(_, _, _) => ().asInstanceOf[A].pure[F]
    }
  }

  val responseCreated: IO[Response[IO]] = HttpResource(existingUri, existingMock).created
  val responseOk: IO[Response[IO]] = HttpResource(existingUri, existingMock).ok
}

class HttpSpec
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

  import httpStore._

  implicit val dsl = HttpStoreDsl.instance[HttpStoreAlgebra, Encoder, Decoder]

  def store: MatchResult[Any] = dsl.store[Mock](newMockIdUri, newMock).map(_.body) must matchFree(newMock)
  def fetchFound: MatchResult[Any] = dsl.fetch[Mock](existingUri).map(_.body) must matchFree(existingMock)
  def fetchNotFound: MatchResult[Any] = dsl.fetch[Mock](nonexistingUri).map(_.body) must matchFreeErrorNotFound

  def selfLinkHeader: MatchResult[Any] =
    HttpResource(existingUri, existingMock).ok[IO] must returnValue { (response: Response[IO]) =>
      response must haveStatus(Status.Ok) and
          (response must haveBody(existingMock)) and
          (response must containHeader(Link(LinkValue(existingUri, Some("self")))))
    }

  def locationHeader: MatchResult[Any] =
    HttpResource[Mock](existingUri, existingMock).created[IO] must returnValue { (response: Response[IO]) =>
      response must haveStatus(Status.Created) and
          (response must haveBody(existingMock)) and
          (response must containHeader(Location(existingUri)))
    }
}
