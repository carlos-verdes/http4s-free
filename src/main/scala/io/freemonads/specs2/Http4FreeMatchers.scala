/*
 * TODO: License goes here!
 */


package io.freemonads
package specs2

import cats.effect.IO
import cats.{Monad, ~>}
import org.specs2.matcher.{Matcher, Matchers, RunTimedMatchers, ValueCheck}

trait Http4FreeMatchers[F[_]] extends RunTimedMatchers[F] with Matchers {

  import http.api._

  def resultOk[A[_], T](check: ValueCheck[T])(implicit interpreter: A ~> F, M: Monad[F]):  Matcher[ApiFree[A, T]] =
    returnValue[ApiResult[T]](beRight(check)) ^^ (_.value.foldMap(interpreter).aka("Free logic"), 0)

  def resultError[A[_], T](chk: ValueCheck[ApiError])(implicit interprtr: A ~> F, M: Monad[F]): Matcher[ApiFree[A, T]] =
    returnValue[ApiResult[T]](beLeft(chk)) ^^ (_.value.foldMap(interprtr).aka("Free logic"), 0)

  def notFound[A[_], T](chk: ValueCheck[ApiError])(implicit interprtr: A ~> F, M: Monad[F]): Matcher[ApiFree[A, T]] =
    returnValue[ApiResult[T]](beLeft(chk)) ^^ (_.value.foldMap(interprtr).aka("Free logic"), 0)
}

trait Http4FreeIOMatchers extends Http4FreeMatchers[IO]
