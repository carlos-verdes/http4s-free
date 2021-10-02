/*
 * Copyright 2021 io.freemonads
 *
 * SPDX-License-Identifier: MIT
 */

package io.freemonads.tagless

import cats.Applicative
import cats.data.{Kleisli, OptionT}
import cats.effect.MonadThrow
import cats.implicits.{catsSyntaxApplicativeError, toBifunctorOps, toFlatMapOps, toFunctorOps}
import cats.syntax.option._
import cats.tagless._
import io.freemonads.error._
import org.http4s._
import org.http4s.dsl.Http4sDsl
import org.http4s.headers.{Authorization, Link, LinkValue, Location}
import org.log4s.getLogger


object http {

  case class HttpResource[R](uri: Uri, body: R)

  private val logger = getLogger
  val REL_SELF = "self"

  @finalAlg
  trait HttpAlgebra[F[_]] {
    def parseRequest[R](request: Request[F])(implicit ED: EntityDecoder[F, R]): F[R]
    def getAuthHeader(request: Request[F]): F[Authorization]
    def getJwtTokenFromHeader(request: Request[F]): F[String]
  }

  implicit class HttpResourceOps[R](rr: HttpResource[R]) {

    def ok[F[_]: Applicative](implicit EE: EntityEncoder[F, R]): F[Response[F]] = {

      val dsl = new Http4sDsl[F]{}
      import dsl._

      Ok(rr.body, Link(LinkValue(rr.uri, rel = Some(REL_SELF))))
    }

    def created[F[_]: Applicative](implicit EE: EntityEncoder[F, R]): F[Response[F]] = {

      val dsl = new Http4sDsl[F]{}
      import dsl._

      Created(rr.body, Location(rr.uri))
    }
  }

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
        logger.error(other)("other error")
        InternalServerError(causeMessage(other.some))
    }
  }

  def causeMessage(cause: Option[Throwable]): String = {
    cause.foreach(logger.error(_)("API error"))
    cause.map(_.getLocalizedMessage).getOrElse("")
  }

  implicit def decodeFailureToApiError(decodeFailure: DecodeFailure): Throwable =
    RequestFormatError(Some(decodeFailure.message), decodeFailure.cause)


  def apiErrorToOptionT[F[_]: MonadThrow]: Throwable => OptionT[F, Response[F]] =
    (t: Throwable) => OptionT.liftF(apiErrorToResponse[F](t))

  def apiErrorMidleware[F[_]: MonadThrow](service: HttpRoutes[F]): HttpRoutes[F] = Kleisli { (req: Request[F]) =>
    service(req).handleErrorWith(apiErrorToOptionT)
  }

  class MonadThrowHttpInterpreter[F[_]: MonadThrow] extends HttpAlgebra[F] {

    val F: MonadThrow[F] = implicitly[MonadThrow[F]]

    override def parseRequest[R](request: Request[F])(implicit ED: EntityDecoder[F, R]): F[R] =
      request
          .attemptAs[R](ED)
          .value
          .map(_.leftMap(decodeFailureToApiError))
          .flatMap(F.fromEither)

    override def getAuthHeader(request: Request[F]): F[Authorization] =
      request.headers.get[Authorization] match {
        case Some(auth) => F.pure(auth)
        case None => F.raiseError(NonAuthorizedError("Couldn't find an Authorization header".some))
      }

    override def getJwtTokenFromHeader(request: Request[F]): F[String] =
      for {
        auth <- getAuthHeader(request)
        token <- auth match {
          case Authorization(Credentials.Token(_, jwtToken)) => F.pure(jwtToken)
          case _ => F.raiseError(NonAuthorizedError("Invalid Authorization header".some))
        }
      } yield token
  }

  object io {

    import cats.effect.IO

    implicit object ioHttpAlgebraInterpreter extends MonadThrowHttpInterpreter[IO]
  }
}
