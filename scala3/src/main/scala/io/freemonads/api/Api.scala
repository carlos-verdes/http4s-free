/*
 * TODO: License goes here!
 */

package io.freemonads.api

import scala.reflect.TypeTest

import cats.Functor

type ApiResult[R] = ApiError | R

enum ApiError(message: Option[Any] = None, cause: Option[Throwable] = None):
  case RequestFormatError(req: Any, m: Option[Any] = None, c: Option[Throwable] = None) extends ApiError(m, c)
  case NonAuthorizedError(m: Option[Any] = None, c: Option[Throwable] = None) extends ApiError(m, c)
  case ResourceNotFoundError(m: Option[Any] = None, c: Option[Throwable] = None) extends ApiError(m, c)
  case NotImplementedError(m: Option[Any] = None, c: Option[Throwable] = None) extends ApiError(m, c)
  case ConflictError(m: Option[Any] = None, c: Option[Throwable] = None) extends ApiError(m, c)
  case RuntimeError(m: Option[Any] = None, c: Option[Throwable] = None) extends ApiError(m, c)

def test[A](x: ApiResult[A]): ApiResult[A] = x match
  case e: ApiError => e
  case _ => x

/*
extension [A](a: ApiResult[A])
  def map[B](f: A => B)(using tt: TypeTest[ApiResult[A], A]) = a match {
    case e: ApiError => e
    case a: A => f(a)
    case other => ApiError.RequestFormatError(a, Some("Expected ApiResult[A]"))
  }
*/

/*
@typeclass trait Functor[F[_]] extends Invariant[F] { self =>
  def map[A, B](fa: F[A])(f: A => B): F[B]
*/

trait Functor[F[_]]:
  extension [A](x: F[A])
    def map[B](f: A => B): F[B]

given Functor[ApiResult] with
  extension [A](x: ApiResult[A]) def map[B](f: A => B): ApiResult[B] = x match {
    case e: ApiError => e
    case a => f(a.asInstanceOf[A])
  }