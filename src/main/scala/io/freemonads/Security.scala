/*
 * Copyright 2021 io.freemonads
 *
 * SPDX-License-Identifier: MIT
 */

package io.freemonads

import cats.effect.Sync
import cats.free.Free
import cats.implicits.toFlatMapOps
import cats.syntax.functor._
import cats.syntax.option._
import cats.{Applicative, InjectK, ~>}
import io.freemonads.api._
import pureconfig.ConfigSource
import tsec.jws.mac.{JWSMacCV, JWTMac}
import tsec.jwt.JWTClaims
import tsec.mac.jca.HMACSHA256

object security {

  case class Subject(value: String) extends AnyVal
  case class Claim(subject: Subject)
  case class Token(value: String) extends AnyVal

  sealed trait SecurityAlgebra[Result]
  case class CreateToken(claim: Claim) extends SecurityAlgebra[Token]
  case class ValidateToken(token: Token) extends SecurityAlgebra[Claim]

  class SecurityDsl[Algebra[_]](implicit I: InjectK[SecurityAlgebra, Algebra]) {

    def createToken(subject: Subject): Free[Algebra, Token] = inject(CreateToken(Claim(subject)))
    def validateToken(token: Token): Free[Algebra, Claim] = inject(ValidateToken(token))

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

    def jwtSecurityInterpreter[F[_]: Applicative](
        implicit s: JWSMacCV[F, HMACSHA256],
        F: Sync[F]):  SecurityAlgebra ~> F = new (SecurityAlgebra ~> F) {

      override def apply[A](op: SecurityAlgebra[A]): F[A] = op match {
        case CreateToken(claim) =>
          val jwtClaims = JWTClaims(subject = claim.subject.value.some)
          JWTMac.buildToString[F, HMACSHA256](jwtClaims, signingKey).map(Token(_))
        case ValidateToken(token) =>
          JWTMac
              .verifyAndParse[F, HMACSHA256](token.value, signingKey)
              .flatMap(result => F.fromOption(result.body.subject, MISSING_SUBJECT_ERR))
              .map(sub => Claim(Subject(sub)))
      }
    }
  }
}

