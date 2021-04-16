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

  sealed trait ResourceAlgebra[Result]
  case class Store[R, Ser[_], Des[_]](uri: Uri, r: R, ser: Ser[R], deser: Des[R]) extends ResourceAlgebra[ApiResult[R]]
  case class Fetch[R, Des[_]](resourceUri: Uri, deserializer: Des[R]) extends ResourceAlgebra[ApiResult[R]]

  class ResourceDsl[Algebra[_], Serializer[R], Deserializer[R]](implicit I: InjectK[ResourceAlgebra, Algebra]) {

    def store[R](resourceUri: Uri, r: R)(implicit S: Serializer[R], D: Deserializer[R]): ApiFree[Algebra, R] =
      EitherT(inject(Store(resourceUri, r, S, D)))

    def fetch[R](resourceUri: Uri)(implicit D: Deserializer[R]): ApiFree[Algebra, R] =
      EitherT(inject(Fetch(resourceUri, D)))

    private def inject = Free.inject[ResourceAlgebra, Algebra]
  }

  object ResourceDsl {

    implicit def instance[F[_], Serializer[_], Deserializer[_]](
        implicit I: InjectK[ResourceAlgebra, F]): ResourceDsl[F, Serializer, Deserializer] =
      new ResourceDsl[F, Serializer, Deserializer]
  }
}
