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
import org.http4s.headers.LinkValue


object resource {

  import api._

  type ResourceId = LinkValue

  val SELF_REL = Some("self")

  sealed trait ResourceAlgebra[Result]
  case class Store[R, Serializer[_]](id: ResourceId, r: R, ser: Serializer[R]) extends ResourceAlgebra[ApiResult[R]]
  case class Fetch[R, Deserializer[_]](id: ResourceId, deser: Deserializer[R]) extends ResourceAlgebra[ApiResult[R]]

  class ResourceDsl[Algebra[_], Serializer[R], Deserializer[R]](implicit I: InjectK[ResourceAlgebra, Algebra]) {

    def store[R](id: Uri, r: R)(implicit S: Serializer[R]): ApiFree[Algebra, R] =
      EitherT(inject(Store(LinkValue(id, SELF_REL), r, S)))

    def fetch[R](id: Uri)(implicit D: Deserializer[R]): ApiFree[Algebra, R] =
      EitherT(inject(Fetch(LinkValue(id, SELF_REL), D)))

    private def inject = Free.inject[ResourceAlgebra, Algebra]
  }

  object ResourceDsl {

    implicit def instance[F[_], Serializer[_], Deserializer[_]](
        implicit I: InjectK[ResourceAlgebra, F]): ResourceDsl[F, Serializer, Deserializer] =
      new ResourceDsl[F, Serializer, Deserializer]
  }
}
