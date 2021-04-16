/*
 * Copyright 2021 io.freemonads
 *
 * SPDX-License-Identifier: MIT
 */

package io.freemonads.http

import cats.InjectK
import cats.data.EitherT
import cats.free.Free
import org.http4s.Uri


object resource {

  import api._

  case class RestResource[R](uri: Uri, body: R)

  sealed trait ResourceAlgebra[Result]

  case class Store[R, Ser[_], Des[_]](
      uri: Uri,
      resourceBody: R,
      serializer: Ser[R],
      deserializer: Des[R]) extends ResourceAlgebra[ApiResult[RestResource[R]]]

  case class Fetch[R, Des[_]](
      resourceUri: Uri,
      deserializer: Des[R]) extends ResourceAlgebra[ApiResult[RestResource[R]]]

  class ResourceDsl[Algebra[_], Serializer[R], Deserializer[R]](implicit I: InjectK[ResourceAlgebra, Algebra]) {

    def store[R](uri: Uri, r: R)(implicit S: Serializer[R], D: Deserializer[R]): ApiFree[Algebra, RestResource[R]] =
      EitherT(inject(Store(uri, r, S, D)))

    def fetch[R](resourceUri: Uri)(implicit D: Deserializer[R]): ApiFree[Algebra, RestResource[R]] =
      EitherT(inject(Fetch(resourceUri, D)))

    private def inject = Free.inject[ResourceAlgebra, Algebra]
  }

  object ResourceDsl {

    implicit def instance[F[_], Serializer[_], Deserializer[_]](
        implicit I: InjectK[ResourceAlgebra, F]): ResourceDsl[F, Serializer, Deserializer] =
      new ResourceDsl[F, Serializer, Deserializer]
  }
}
