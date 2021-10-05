/*
 * Copyright 2021 io.freemonads
 *
 * SPDX-License-Identifier: MIT
 */

package io.freemonads

import cats.effect.IO
import cats.syntax.applicative._
import cats.syntax.option._
import io.circe.generic.auto._
import io.circe.{Decoder, Encoder}
import io.freemonads.error.ResourceNotFoundError
import org.http4s.circe.CirceEntityCodec._
import org.http4s.headers.{Link, LinkValue, Location}
import org.http4s.implicits.http4sLiteralsSyntax
import org.http4s.{Response, Status, Uri}
import org.specs2.Specification
import org.specs2.matcher.{IOMatchers, MatchResult}
import org.specs2.specification.core.SpecStructure

trait MockResources {

  import error._
  import tagless.http._
  import tagless.httpStore._

  case class Mock(id: Option[String], name: String, age: Int)

  val existingId = "id456"
  val existingUri = uri"/mocks" / existingId
  val nonexistingUri = uri"/mocks" / "123"
  val existingMock = Mock(Some(existingId), "name123", 23)

  val newMockId = "newId123"
  val newMockIdUri = uri"/mocks" / newMockId
  val newMock = Mock(Some(newMockId), "other name", 56)

  implicit object mockStoreDsl extends HttpStoreAlgebra[IO, Encoder, Decoder] {
    override def store[R](uri: Uri, resource: R)(implicit S: Encoder[R], D: Decoder[R]): IO[HttpResource[R]] =
      IO.pure(HttpResource(newMockIdUri, resource))

    override def fetch[R](resourceUri: Uri)(implicit deserializer: Decoder[R]): IO[HttpResource[R]] =
      (if (resourceUri == existingUri) HttpResource(newMockIdUri, existingMock.asInstanceOf[R]).pure[IO]
      else IO.raiseError[HttpResource[R]](ResourceNotFoundError(s"resource not found $resourceUri".some)))

    override def linkResources(leftUri: Uri, rightUri: Uri, relType: String): IO[Unit] =
      ().pure[IO]
  }

  val responseCreated: Response[IO] = HttpResource(existingUri, existingMock).created
  val responseOk: Response[IO] = HttpResource(existingUri, existingMock).ok
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

  import tagless.http._

  def store: MatchResult[Any] = mockStoreDsl.store[Mock](newMockIdUri, newMock).map(_.body) must returnValue(newMock)
  def fetchFound: MatchResult[Any] = mockStoreDsl.fetch[Mock](existingUri).map(_.body) must returnValue(existingMock)

  def fetchNotFound: MatchResult[Any] =
    mockStoreDsl.fetch[Mock](nonexistingUri).map(_.body) must returnError[Mock, ResourceNotFoundError]

  def selfLinkHeader: MatchResult[Any] =
    HttpResource(existingUri, existingMock).ok[IO].pure[IO] must returnValue { (response: Response[IO]) =>
      response must haveStatus(Status.Ok) and
          (response must haveBody(existingMock)) and
          (response must containHeader(Link(LinkValue(existingUri, Some("self")))))
    }

  def locationHeader: MatchResult[Any] =
    HttpResource[Mock](existingUri, existingMock).created[IO].pure[IO] must returnValue { (response: Response[IO]) =>
      response must haveStatus(Status.Created) and
          (response must haveBody(existingMock)) and
          (response must containHeader(Location(existingUri)))
    }
}
