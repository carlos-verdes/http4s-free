/*
 * Copyright 2021 io.freemonads
 *
 * SPDX-License-Identifier: MIT
 */

package io.freemonads
package security

import cats.data.EitherT
import cats.effect.Sync
import cats.free.Free
import cats.syntax.functor._
import cats.syntax.option._
import cats.{Applicative, InjectK, ~>}
import io.freemonads.api._
import pureconfig.ConfigSource
import tsec.jws.mac.{JWSMacCV, JWTMac}
import tsec.jwt.JWTClaims
import tsec.mac.jca.HMACSHA256

case class Subject(value: String) extends AnyVal
case class Claim(subject: Subject)
case class Token(value: String) extends AnyVal

sealed trait SecurityAlgebra[Result]
case class CreateToken(claim: Claim) extends SecurityAlgebra[ApiResult[Token]]
case class ValidateToken(token: Token) extends SecurityAlgebra[ApiResult[Claim]]

class SecurityDsl[Algebra[_]](implicit I: InjectK[SecurityAlgebra, Algebra]) {

  def createToken(subject: Subject): ApiFree[Algebra, Token] = EitherT(inject(CreateToken(Claim(subject))))
  def validateToken(token: Token): ApiFree[Algebra, Claim] = EitherT(inject(ValidateToken(token)))

  private def inject = Free.liftInject[Algebra]
}

object SecurityDsl {

  implicit def instance[F[_]](implicit I: InjectK[SecurityAlgebra, F]): SecurityDsl[F] = new SecurityDsl[F]
}

object jwt {

  import pureconfig.generic.auto._
  import tsec.common._

  case class JwtConfig(signingKey: String)

  def at(conf: ConfigSource = ConfigSource.default): ConfigSource = conf.at("jwt")
  def load(conf: ConfigSource = ConfigSource.default): JwtConfig = at(conf).loadOrThrow[JwtConfig]

  val MISSING_SUBJECT_ERR = NonAuthorizedError(Some("Invalid token, subject is missing"))

  lazy val jwtConf: JwtConfig = load()
  lazy val signingKey = HMACSHA256.unsafeBuildKey(jwtConf.signingKey.b64Bytes.get)

  def jwtSecurityInterpreter[F[_]: Applicative : Sync](
      implicit s: JWSMacCV[F, HMACSHA256]):  SecurityAlgebra ~> F = new (SecurityAlgebra ~> F) {

    override def apply[A](op: SecurityAlgebra[A]): F[A] = op match {
      case CreateToken(claim) =>
        val jwtClaims = JWTClaims(subject = claim.subject.value.some)
        JWTMac.buildToString[F, HMACSHA256](jwtClaims, signingKey).map(Token(_).resultOk)
      case ValidateToken(token) =>
        JWTMac
            .verifyAndParse[F, HMACSHA256](token.value, signingKey)
            .map(_.body.subject.toResult(MISSING_SUBJECT_ERR).map(sub => Claim(Subject(sub))))
    }
  }
}
