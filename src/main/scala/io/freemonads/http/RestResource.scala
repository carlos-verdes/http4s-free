/*
 * Copyright 2021 io.freemonads
 *
 * SPDX-License-Identifier: MIT
 */

package io.freemonads
package http

import cats.data.EitherT
import cats.free.Free
import cats.{Applicative, InjectK}
import org.http4s.dsl.Http4sDsl
import org.http4s.headers.{Link, LinkValue, Location}
import org.http4s.{EntityEncoder, Response, Uri}


object resource {

  import api._

  case class RestResource[R](uri: Uri, body: R)

  val REL_SELF = "self"

  implicit class RestResourceOps[R](rr: RestResource[R]) {

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

  sealed trait ResourceAlgebra[Result]

  case class Store[R, Ser[_], Des[_]](
      uri: Uri,
      resourceBody: R,
      serializer: Ser[R],
      deserializer: Des[R]) extends ResourceAlgebra[ApiResult[RestResource[R]]]

  case class Fetch[R, Des[_]](
      resourceUri: Uri,
      deserializer: Des[R]) extends ResourceAlgebra[ApiResult[RestResource[R]]]

  case class LinkResources(leftUri: Uri, rightUri: Uri, relType: String) extends ResourceAlgebra[ApiResult[Unit]]

  class ResourceDsl[Algebra[_], Serializer[R], Deserializer[R]](implicit I: InjectK[ResourceAlgebra, Algebra]) {

    def store[R](uri: Uri, r: R)(implicit S: Serializer[R], D: Deserializer[R]): ApiFree[Algebra, RestResource[R]] =
      EitherT(inject(Store(uri, r, S, D)))

    def fetch[R](resourceUri: Uri)(implicit D: Deserializer[R]): ApiFree[Algebra, RestResource[R]] =
      EitherT(inject(Fetch(resourceUri, D)))

    def link(left: RestResource[Any], right: RestResource[Any], relType: String): ApiFree[Algebra, Unit] =
      link(left.uri, right.uri, relType)

    def link(leftUri: Uri, rightUri: Uri, relType: String): ApiFree[Algebra, Unit] =
      EitherT(inject(LinkResources(leftUri, rightUri, relType)))

    private def inject = Free.liftInject[Algebra]
  }

  object ResourceDsl {

    implicit def instance[F[_], Serializer[_], Deserializer[_]](
        implicit I: InjectK[ResourceAlgebra, F]): ResourceDsl[F, Serializer, Deserializer] =
      new ResourceDsl[F, Serializer, Deserializer]
  }
}
