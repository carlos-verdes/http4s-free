/*
 * TODO: License goes here!
 */


package io.freemonads

import cats.{Applicative, ApplicativeError, Functor}
import cats.data.EitherT
import cats.free.Free
import cats.syntax.applicative._
import cats.syntax.applicativeError._
import cats.syntax.either._
import cats.syntax.functor._

trait Api {

  type ApiResult[R] = Either[ApiError, R]

  type ApiCall[F[_], R] = EitherT[F, ApiError, R]
  type ApiCallF[F[_], R] = EitherT[Free[F, *], ApiError, R]


  implicit class ApiResourceOps[R](r: R) {

    def resultOk: ApiResult[R] = r.asRight
    def liftCall[F[_]: Applicative]: ApiCall[F, R] = ApiResultOps(r.resultOk).liftCall[F]
    def liftFree[F[_]]: ApiCallF[F, R] = ApiResultOps(r.resultOk).liftFree[F]
  }

  implicit def errorToResultError[R](error: ApiError): ApiResult[R] = error.asLeft[R]

  implicit class ErrorOps(error: ApiError) {

    def resultError[R]: ApiResult[R] = errorToResultError(error)
  }

  def errorFromThrowable(m: String, t: Throwable): ApiError = RuntimeError(m, Some(t))

  val emptyResult: ApiResult[Unit] = Right(())

  implicit class ThrowableOps(t: Throwable) {

    def runtimeApiError[R](message: String): ApiResult[R] = errorFromThrowable(message, t)

  }

  implicit class ApiResultOps[R](result: ApiResult[R]){

    def liftCall[F[_]: Applicative]: ApiCall[F, R] = EitherT(result.pure[F])
    def liftFree[F[_]]: ApiCallF[F, R] = EitherT[Free[F, *], ApiError, R](Free.pure(result))
  }

  implicit class ApiEffectOps[F[_]: Functor, R, E](effect: F[R])(implicit AE: ApplicativeError[F, E]) {

    def handleErrors(body: E => ApiError): ApiCall[F, R] = EitherT(effect.attempt.map(_.leftMap(body)))

    def liftCall: ApiCall[F, R] = handleErrors {
      case e: Throwable => errorFromThrowable("Error during API call", e)
    }
  }

  implicit class ApiOptionOps[R](optional: Option[R]) {

    def toResult(error: => ApiError): ApiResult[R] = optional.fold(error.resultError[R])(_.resultOk)
  }
}

sealed trait ApiError
final case class RequestFormatError(request: Any, details: Any, cause: Option[Throwable] = None) extends ApiError
final case class NonAuthorizedError(resource: Option[Any]) extends ApiError
final case class ResourceNotFoundError(id: Option[String] = None, cause: Option[Throwable] = None) extends ApiError
final case class NotImplementedError(method: String) extends ApiError
final case class ResourceAlreadyExistError(id: String, cause: Option[Throwable] = None) extends ApiError
final case class RuntimeError(message: String, cause: Option[Throwable] = None) extends ApiError
