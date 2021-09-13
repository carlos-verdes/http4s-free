/*
 * Copyright 2021 io.freemonads
 *
 * SPDX-License-Identifier: MIT
 */

package io.freemonads

import cats.effect.MonadThrow
import cats.free.Free
import cats.implicits.{catsSyntaxApplicativeError, catsSyntaxOptionId, toBifunctorOps, toFlatMapOps, toFunctorOps}
import cats.{Applicative, InjectK, ~>}
import org.http4s.dsl.Http4sDsl
import org.http4s.headers.Authorization
import org.http4s.{Credentials, DecodeFailure, EntityDecoder, Request, Response}
import org.log4s.getLogger

object http {

  import error._

  private val logger = getLogger
  val REL_SELF = "self"

  sealed trait HttpFreeAlgebra[Result]
  case class ParseRequest[F[_], R](request: Request[F], ED: EntityDecoder[F, R]) extends HttpFreeAlgebra[R]
  case class GetAuthHeader[F[_]](request: Request[F]) extends HttpFreeAlgebra[Authorization]
  case class GetJwtTokenFromHeader[F[_]](request: Request[F]) extends HttpFreeAlgebra[String]

  class HttpFreeDsl[Algebra[_]](implicit I: InjectK[HttpFreeAlgebra, Algebra]) {

    def parseRequest[F[_], R](request: Request[F])(implicit ED: EntityDecoder[F, R]): Free[Algebra, R] = {
      val _parseRequest: HttpFreeAlgebra[R] = ParseRequest(request, ED)
      inject(_parseRequest)
    }

    def getAuthHeader[F[_], R](request: Request[F]): Free[Algebra, Authorization] = {
      val _getAuthHeader: GetAuthHeader[F] = GetAuthHeader(request)
      inject(_getAuthHeader)
    }

    def getJwtTokenFromHeader[F[_], R](request: Request[F]): Free[Algebra, String] = {
      val _getJwtTokenFromHeader: GetJwtTokenFromHeader[F] = GetJwtTokenFromHeader(request)
      inject(_getJwtTokenFromHeader)
    }

    private def inject = Free.liftInject[Algebra]
  }

  object HttpFreeDsl {

    implicit def instance[F[_]](implicit I: InjectK[HttpFreeAlgebra, F]): HttpFreeDsl[F] = new HttpFreeDsl[F]
  }

  def httpFreeInterpreter[F[_]](implicit F: MonadThrow[F]): HttpFreeAlgebra ~> F = new (HttpFreeAlgebra ~> F) {

    override def apply[A](op: HttpFreeAlgebra[A]): F[A] = op match {

      case ParseRequest(req, decoder) =>

        val request: Request[F] = req.asInstanceOf[Request[F]]
        val ED: EntityDecoder[F, A] = decoder.asInstanceOf[EntityDecoder[F, A]]

        request
            .attemptAs[A](ED)
            .value
            .map(_.leftMap(decodeFailureToApiError))
            .flatMap(F.fromEither)

      case GetAuthHeader(req) =>

        val request: Request[F] = req.asInstanceOf[Request[F]]

        getAuthFromHead(request).asInstanceOf[F[A]]

      case GetJwtTokenFromHeader(req) =>
        val request: Request[F] = req.asInstanceOf[Request[F]]

        getJwtTokenFromHead(request).asInstanceOf[F[A]]
    }
  }

  private def getAuthFromHead[F[_]](request: Request[F])(implicit F: MonadThrow[F]): F[Authorization] = {
    request.headers.get[Authorization] match {
      case Some(auth) => F.pure(auth)
      case None => F.raiseError(NonAuthorizedError("Couldn't find an Authorization header".some))
    }
  }

  private def getJwtTokenFromHead[F[_]](request: Request[F])(implicit F: MonadThrow[F]): F[String] = {
    for {
      auth <- getAuthFromHead(request)
      token <- auth match {
        case Authorization(Credentials.Token(_, jwtToken)) => F.pure(jwtToken)
        case _ => F.raiseError(NonAuthorizedError("Invalid Authorization header".some))
      }
    } yield token
  }

  implicit def decodeFailureToApiError(decodeFailure: DecodeFailure): Throwable =
    RequestFormatError(Some(decodeFailure.message), decodeFailure.cause)


  implicit def freeResponseToHttpResponse[F[_]: MonadThrow, Algebra[_]](
      freeOp: Free[Algebra, F[Response[F]]])(
      implicit interpreters: Algebra ~> F): F[Response[F]] =
    freeOp
        .foldMap(interpreters)
        .flatMap(identity)
        .handleErrorWith(apiErrorToResponse)

  implicit def apiErrorToResponse[F[_]: Applicative](restError: Throwable): F[Response[F]] = {

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
      case ResourceNotFoundError(details, cause) =>
        details.foreach(d => logger.error(s"Resource not found error: $d"))
        NotFound(causeMessage(cause))
      case ConflictError(cause) => Conflict(causeMessage(cause))
      case NotImplementedError(method) =>
        val message = s"Method: $method not implemented"
        logger.error(message)
        NotImplemented(message)
      case RuntimeError(cause) => InternalServerError(causeMessage(cause))
      case other =>
        println(s"Other error")
        logger.error(other)("error")

        InternalServerError(causeMessage(other.some))
    }
  }

  def causeMessage(cause: Option[Throwable]): String = {
    cause.foreach(logger.error(_)("API error"))
    cause.map(_.getLocalizedMessage).getOrElse("")
  }
}
