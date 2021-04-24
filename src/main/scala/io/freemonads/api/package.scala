/*
 * Copyright 2021 io.freemonads
 *
 * SPDX-License-Identifier: MIT
 */

package io.freemonads

import cats.data.EitherT
import cats.free.Free
import cats.syntax.either._

package object api {

  type ApiResult[R] = Either[ApiError, R]
  type ApiFree[F[_], R] = EitherT[Free[F, *], ApiError, R]

  implicit class ApiOps[R](r: R) {

    def resultOk: ApiResult[R] = r.asRight
    def liftFree[F[_]]: ApiFree[F, R] = ApiResultOps(r.resultOk).liftFree[F]
  }

  implicit def errorToResultError[R](error: ApiError): ApiResult[R] = error.asLeft[R]

  implicit class ErrorOps(error: ApiError) {

    def resultError[R]: ApiResult[R] = errorToResultError(error)
  }

  def errorFromThrowable(t: Throwable): ApiError = RuntimeError(Some(t))

  val emptyResult: ApiResult[Unit] = Right(())

  implicit class ThrowableOps(t: Throwable) {

    def resultError[R]: ApiResult[R] = errorFromThrowable(t)
  }

  implicit class ApiResultOps[R](result: ApiResult[R]){

    def liftFree[F[_]]: ApiFree[F, R] = EitherT[Free[F, *], ApiError, R](Free.pure(result))
  }

  implicit class ApiOptionOps[R](optional: Option[R]) {

    def toResult(error: => ApiError): ApiResult[R] = optional.fold(error.resultError[R])(_.resultOk)
  }
}
