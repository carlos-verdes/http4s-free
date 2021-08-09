/*
 * Copyright 2021 io.freemonads
 *
 * SPDX-License-Identifier: MIT
 */

package io.freemonads
package http

import cats.data.EitherT
import cats.effect.Sync
import cats.free.Free
import cats.syntax.flatMap._
import cats.syntax.functor._
import cats.{FlatMap, InjectK, ~>}
import org.http4s.dsl.Http4sDsl
import org.http4s.{DecodeFailure, EntityDecoder, Request, Response}
import org.log4s.getLogger

object rest {

  import api._

  private val logger = getLogger

  sealed trait Http4sAlgebra[Result]
  case class ParseRequest[F[_], R](request: Request[F], ED: EntityDecoder[F, R]) extends Http4sAlgebra[ApiResult[R]]

  class Http4sFreeDsl[Algebra[_]](implicit I: InjectK[Http4sAlgebra, Algebra]) {

    def parseRequest[F[_], R](request: Request[F])(implicit ED: EntityDecoder[F, R]): ApiFree[Algebra, R] = {
      val parseRequest: Http4sAlgebra[ApiResult[R]] = ParseRequest(request, ED)
      EitherT(inject(parseRequest))
    }

    private def inject = Free.liftInject[Algebra]
  }

  object Http4sFreeDsl {

    implicit def instance[F[_]](implicit I: InjectK[Http4sAlgebra, F]): Http4sFreeDsl[F] = new Http4sFreeDsl[F]
  }

  def http4sInterpreter[F[_]: FlatMap]: Http4sAlgebra ~> F = new (Http4sAlgebra ~> F) {

    override def apply[A](op: Http4sAlgebra[A]): F[A] = op match {

      case ParseRequest(req, decoder) =>

        val request: Request[F] = req.asInstanceOf[Request[F]]
        val ED: EntityDecoder[F, A] = decoder.asInstanceOf[EntityDecoder[F, A]]

        request
            .attemptAs[A](ED)
            .value
            .map(_.fold(decodeFailureToApiError(_).resultError[A], _.resultOk))
            .asInstanceOf[F[A]]
    }
  }

  implicit def algebraResultToResponse[F[_] : Sync, Algebra[_]](
      freeOp: ApiFree[Algebra, F[Response[F]]])(
      implicit interpreters: Algebra ~> F): F[Response[F]] =
    freeOp.value.foldMap(interpreters).flatMap(_.fold(apiErrorToResponse[F], identity))

  implicit def decodeFailureToApiError(decodeFailure: DecodeFailure): ApiError =
    RequestFormatError(Some(decodeFailure.message), decodeFailure.cause)

  implicit def apiErrorToResponse[F[_] : Sync](restError: ApiError): F[Response[F]] = {

    val dsl = new Http4sDsl[F] {}
    import dsl._

    logger.error(s"REST API error: $restError")

    restError match {
      case RequestFormatError(details, cause) =>
        details.foreach(d => logger.error(s"Request format error: $d"))
        BadRequest(causeMessage(cause))
      case NonAuthorizedError(details, cause) =>
        details.foreach(d => logger.error(s"Non authorized error: $d"))
        Forbidden(cause.map(_.getLocalizedMessage).getOrElse(""))
      case ResourceNotFoundError(cause) => NotFound(causeMessage(cause))
      case ConflictError(cause) => Conflict(causeMessage(cause))
      case RuntimeError(cause) => InternalServerError(causeMessage(cause))
      case NotImplementedError(method) =>
        val message = s"Method: $method not implemented"
        logger.error(message)
        NotImplemented(message)
    }
  }

  def causeMessage(cause: Option[Throwable]): String = {
    cause.foreach(logger.error(_)("API error"))
    cause.map(_.getLocalizedMessage).getOrElse("")
  }
}
