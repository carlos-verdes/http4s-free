/*
 * Copyright 2021 io.freemonads
 *
 * SPDX-License-Identifier: MIT
 */

package io.freemonads.http

import cats.data.EitherT
import cats.free.Free
import cats.syntax.either._

object api {

  type ApiResult[R] = Either[ApiError, R]
  type ApiFree[F[_], R] = EitherT[Free[F, *], ApiError, R]

  sealed trait ApiError
  case class RequestFormatError(request: Any, details: Any, cause: Option[Throwable] = None) extends ApiError
  case class NonAuthorizedError(resource: Option[Any]) extends ApiError
  case class ResourceNotFoundError(id: Option[Any] = None, cause: Option[Throwable] = None) extends ApiError
  case class NotImplementedError(method: String) extends ApiError
  case class ResourceAlreadyExistError(id: String, cause: Option[Throwable] = None) extends ApiError
  case class RuntimeError(message: String, cause: Option[Throwable] = None) extends ApiError

  implicit class ApiOps[R](r: R) {

    def resultOk: ApiResult[R] = r.asRight
    def liftFree[F[_]]: ApiFree[F, R] = ApiResultOps(r.resultOk).liftFree[F]
  }

  implicit def errorToResultError[R](error: ApiError): ApiResult[R] = error.asLeft[R]
  def notFound[R](id: Any): ApiResult[R] = ResourceNotFoundError(Some(id)).resultError[R]

  implicit class ErrorOps(error: ApiError) {

    def resultError[R]: ApiResult[R] = errorToResultError(error)
  }

  def errorFromThrowable(m: String, t: Throwable): ApiError = RuntimeError(m, Some(t))

  val emptyResult: ApiResult[Unit] = Right(())

  implicit class ThrowableOps(t: Throwable) {

    def runtimeApiError[R](message: String): ApiResult[R] = errorFromThrowable(message, t)

  }

  implicit class ApiResultOps[R](result: ApiResult[R]){

    def liftFree[F[_]]: ApiFree[F, R] = EitherT[Free[F, *], ApiError, R](Free.pure(result))
  }

  implicit class ApiOptionOps[R](optional: Option[R]) {

    def toResult(error: => ApiError): ApiResult[R] = optional.fold(error.resultError[R])(_.resultOk)
  }
}
