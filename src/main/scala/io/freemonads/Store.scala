/*
 * Copyright 2021 io.freemonads
 *
 * SPDX-License-Identifier: MIT
 */

package io.freemonads

import cats.free.Free
import cats.{Applicative, InjectK}
import org.http4s.dsl.Http4sDsl
import org.http4s.headers.{Link, LinkValue, Location}
import org.http4s.{EntityEncoder, Response, Uri}

object store {

  import http2._

  case class HttpResource[R](uri: Uri, body: R)

  implicit class HttpResourceOps[R](rr: HttpResource[R]) {

    def ok[F[_]: Applicative](implicit EE: EntityEncoder[F, R]): F[Response[F]] = {

      val dsl = new Http4sDsl[F]{}
      import dsl._

      Ok(rr.body, Link(LinkValue(rr.uri, rel = Some(REL_SELF))))
    }

    def created[F[_]: Applicative](implicit EE: EntityEncoder[F, R]): F[Response[F]] = {

      val dsl = new Http4sDsl[F]{}
      import dsl._

      Created(rr.body, Location(rr.uri))
    }
  }

  sealed trait HttpStoreAlgebra[Result]

  case class Store[R, Ser[_], Des[_]](
      uri: Uri,
      resourceBody: R,
      serializer: Ser[R],
      deserializer: Des[R]) extends HttpStoreAlgebra[HttpResource[R]]

  case class Fetch[R, Des[_]](
      resourceUri: Uri,
      deserializer: Des[R]) extends HttpStoreAlgebra[HttpResource[R]]

  case class LinkResources(leftUri: Uri, rightUri: Uri, relType: String) extends HttpStoreAlgebra[Unit]

  class HttpStoreDsl[Algebra[_], Serializer[R], Deserializer[R]](implicit I: InjectK[HttpStoreAlgebra, Algebra]) {

    def store[R](uri: Uri, r: R)(implicit S: Serializer[R], D: Deserializer[R]): Free[Algebra, HttpResource[R]] =
      inject(Store(uri, r, S, D))

    def fetch[R](resourceUri: Uri)(implicit D: Deserializer[R]): Free[Algebra, HttpResource[R]] =
      inject(Fetch(resourceUri, D))

    def link(left: HttpResource[Any], right: HttpResource[Any], relType: String): Free[Algebra, Unit] =
      link(left.uri, right.uri, relType)

    def link(leftUri: Uri, rightUri: Uri, relType: String): Free[Algebra, Unit] =
      inject(LinkResources(leftUri, rightUri, relType))

    private def inject = Free.liftInject[Algebra]
  }

  object HttpStoreDsl {

    implicit def instance[F[_], Serializer[_], Deserializer[_]](
        implicit I: InjectK[HttpStoreAlgebra, F]): HttpStoreDsl[F, Serializer, Deserializer] =
      new HttpStoreDsl[F, Serializer, Deserializer]
  }
}
