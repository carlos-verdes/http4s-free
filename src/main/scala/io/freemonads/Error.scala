/*
 * Copyright 2021 io.freemonads
 *
 * SPDX-License-Identifier: MIT
 */

package io.freemonads

import cats.{InjectK, ~>}
import cats.effect.MonadThrow
import cats.free.Free
import cats.implicits.catsSyntaxApplicativeError

object error {

  sealed trait ApiError extends Throwable
  case class RequestFormatError(message: Option[Any] = None, cause: Option[Throwable] = None) extends ApiError
  case class NonAuthorizedError(message: Option[Any] = None, cause: Option[Throwable] = None) extends ApiError
  case class ResourceNotFoundError(message: Option[Any] = None, cause: Option[Throwable] = None) extends ApiError
  case class NotImplementedError(method: String) extends ApiError
  case class ConflictError(cause: Option[Throwable] = None) extends ApiError
  case class RuntimeError(cause: Option[Throwable] = None) extends ApiError

  trait ErrorAlgebra[SomeError]
  case class RaiseError(error: ApiError) extends ErrorAlgebra[ApiError]

  class ErrorDsl[Algebra[_]](implicit I: InjectK[ErrorAlgebra, Algebra]) {

    def raiseError(error: ApiError): Free[Algebra, ApiError] = {
      val _raiseError: ErrorAlgebra[ApiError] = RaiseError(error)
      inject(_raiseError)
    }

    private def inject = Free.liftInject[Algebra]
  }

  object ErrorDsl {

    implicit def instance[F[_]](implicit I: InjectK[ErrorAlgebra, F]): ErrorDsl[F] = new ErrorDsl[F]
  }

  def monadThrowInterpreter[F[_]](implicit F: MonadThrow[F]): ErrorAlgebra ~> F = new (ErrorAlgebra ~> F) {
    override def apply[A](op: ErrorAlgebra[A]): F[A] = op match {
      case RaiseError(error) => F.raiseError[A](error)
    }
  }

  implicit class ApiErrorOps[F[_]: MonadThrow, R](effectWithErrors: F[R]) {

    def ifNotFound(handleNotFound: => F[R]): F[R] =
      effectWithErrors.handleErrorWith {
        case _: ResourceNotFoundError => handleNotFound
      }

    def inConflict(handleConflict: => F[R]): F[R] =
      effectWithErrors.handleErrorWith {
        case _: ConflictError => handleConflict
      }
  }
}
