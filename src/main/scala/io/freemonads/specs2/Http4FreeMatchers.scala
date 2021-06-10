/*
 * Copyright 2021 io.freemonads
 *
 * SPDX-License-Identifier: MIT
 */

package io.freemonads
package specs2

import scala.concurrent.duration.FiniteDuration
import scala.reflect.ClassTag

import cats.effect.IO
import cats.{Id, Monad, ~>}
import org.specs2.matcher.{Matcher, Matchers, RunTimedMatchers, ValueCheck}

trait Http4FreeMatchers[F[_]] extends RunTimedMatchers[F] with Matchers {

  import api._

  def resultOk[A[_], T](check: ValueCheck[T])(implicit interpreter: A ~> F, M: Monad[F]):  Matcher[ApiFree[A, T]] =
    returnValue[ApiResult[T]](beRight(check)) ^^ (_.value.foldMap(interpreter).aka("Free logic"), 0)

  def resultError[A[_], T, E <: ApiError :  ClassTag](implicit interprtr: A ~> F, M: Monad[F]): Matcher[ApiFree[A, T]] =
    returnValue[ApiResult[T]](beLeft(haveClass[E])) ^^ (_.value.foldMap(interprtr).aka("Free logic"), 0)

  def resultErrorNotFound[A[_], T](implicit interprtr: A ~> F, M: Monad[F]): Matcher[ApiFree[A, T]] =
    resultError[A, T, ResourceNotFoundError]

  def resultErrorConflict[A[_], T](implicit interprtr: A ~> F, M: Monad[F]): Matcher[ApiFree[A, T]] =
    resultError[A, T, ConflictError]

  def resultFormatError[A[_], T](implicit interprtr: A ~> F, M: Monad[F]): Matcher[ApiFree[A, T]] =
    resultError[A, T, RequestFormatError]
}

trait Http4FreeIOMatchers extends Http4FreeMatchers[IO]

trait Http4FreeIdMatchers extends Http4FreeMatchers[Id] {

  override protected def runWithTimeout[A](fa: Id[A], timeout: FiniteDuration): A = fa
  override protected def runAwait[A](fa: Id[A]): A = fa
}
