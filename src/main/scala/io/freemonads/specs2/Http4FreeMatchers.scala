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
import cats.syntax.flatMap._
import cats.{Id, Monad, MonadError, ~>}
import org.http4s._
import org.specs2.matcher.{Matcher, Matchers, RunTimedMatchers, ValueCheck}

trait Http4FreeMatchers[F[_]] extends RunTimedMatchers[F] with Matchers {

  import api._

  def haveStatus(expected: Status): Matcher[Response[F]] =
    be_===(expected) ^^ { r: Response[F] => r.status.aka("the response status") }

  def returnStatus(s: Status): Matcher[F[Response[F]]] =
    returnValue(haveStatus(s)) ^^ { (m: F[Response[F]]) => m.aka("the returned response status") }

  def haveBody[A](a: ValueCheck[A])(
      implicit F: MonadError[F, Throwable],
      ee: EntityDecoder[F, A]): Matcher[Message[F]] =
    returnValue(a) ^^ { (m: Message[F]) => m.as[A].aka("the message body") }

  def returnBody[A](a: ValueCheck[A])(
      implicit F: MonadError[F, Throwable],
      ee: EntityDecoder[F, A]): Matcher[F[Message[F]]] =
    returnValue(a) ^^ { (m: F[Message[F]]) => m.flatMap(_.as[A]).aka("the returned message body") }

  def haveHeaders(hs: Headers): Matcher[Message[F]] =
    be_===(hs) ^^ { (m: Message[F]) => m.headers.aka("the headers") }

  def containHeader[H](hs: H)(implicit H: Header.Select[H]): Matcher[Message[F]] =
    beSome(hs) ^^ { (m: Message[F]) =>
      m.headers.get[H](H).asInstanceOf[Option[H]].aka("the particular header")
    }

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

  def resultNonAuthorizedError[A[_], T](implicit intr: A ~> F, M: Monad[F]): Matcher[ApiFree[A, T]] =
    resultError[A, T, NonAuthorizedError]
}

trait Http4FreeIOMatchers extends Http4FreeMatchers[IO]

trait Http4FreeIdMatchers extends Http4FreeMatchers[Id] {

  override protected def runWithTimeout[A](fa: Id[A], timeout: FiniteDuration): A = fa
  override protected def runAwait[A](fa: Id[A]): A = fa
}
