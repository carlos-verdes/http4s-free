/*
 * Copyright 2021 io.freemonads
 *
 * SPDX-License-Identifier: MIT
 */

package io.freemonads

import cats.effect.{IO, MonadThrow}
import cats.implicits.catsSyntaxApplicativeId
import io.freemonads.specs2.Http4FreeMatchers
import org.specs2.Specification
import org.specs2.matcher.{IOMatchers, MatchResult}
import org.specs2.specification.core.SpecStructure


trait SomeErrors {

  import error._

  def effectWithNotFound[F[_]](implicit F: MonadThrow[F]): F[String] = F.raiseError(ResourceNotFoundError())
  def effectWithConflict[F[_]](implicit F: MonadThrow[F]): F[String] = F.raiseError(ConflictError())
}

class ErrorSpec
    extends Specification
    with IOMatchers
    with Http4FreeMatchers[IO]
    with SomeErrors { def is: SpecStructure =
  s2"""
      ApiErrorOps should: <br/>
      Manage not found errors     $manageNotFound
      Manage conflict errors      $manageConflict
      """

  import error._

  def manageNotFound: MatchResult[IO[String]] =
    effectWithNotFound[IO].ifNotFound("someString".pure[IO]) must returnValue("someString")

  def manageConflict: MatchResult[IO[String]] =
    effectWithConflict[IO].inConflict("someString".pure[IO]) must returnValue("someString")
}
