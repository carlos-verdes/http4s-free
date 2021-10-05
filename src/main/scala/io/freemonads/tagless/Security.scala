/*
 * Copyright 2021 io.freemonads
 *
 * SPDX-License-Identifier: MIT
 */

package io.freemonads.tagless

import java.security.{NoSuchAlgorithmException, SecureRandom, Security}

import cats.effect.{IO, MonadThrow, Sync}
import cats.implicits.{toFlatMapOps, toFunctorOps}
import cats.syntax.option._
import cats.tagless._
import io.freemonads.error
import pureconfig.ConfigSource
import tsec.jws.mac.JWTMac
import tsec.jwt.JWTClaims
import tsec.mac.jca.HMACSHA256

object security {

  case class Subject(value: String) extends AnyVal
  case class Claim(subject: Subject)
  case class Token(value: String) extends AnyVal

  @finalAlg
  @autoFunctorK
  @autoSemigroupalK
  trait SecurityAlgebra[F[_]] {

    def createToken(subject: Subject): F[Token]
    def validateToken(token: Token): F[Claim]

    def createToken(claim: Claim): F[Token] = createToken(claim.subject)
  }

  object jwt {

    import error._
    import pureconfig.generic.auto._
    import tsec.common._

    case class JwtConfig(signingKey: String)

    def at(conf: ConfigSource = ConfigSource.default): ConfigSource = conf.at("jwt")

    def load(conf: ConfigSource = ConfigSource.default): JwtConfig = at(conf).loadOrThrow[JwtConfig]

    val MISSING_SUBJECT_ERR = NonAuthorizedError(Some("Invalid token, subject is missing"))

    lazy val jwtConf: JwtConfig = load()
    lazy val signingKey = HMACSHA256.unsafeBuildKey(jwtConf.signingKey.b64Bytes.get)

    class JwtSecurityInterpreter[F[_] : MonadThrow](
        implicit F: MonadThrow[F],
        S: Sync[F],
        J: tsec.jws.mac.JWSMacCV[F, tsec.mac.jca.HMACSHA256]) extends SecurityAlgebra[F] {

      override def createToken(subject: Subject): F[Token] = {

        val jwtClaims = JWTClaims(subject = Claim(subject).subject.value.some)
        JWTMac.buildToString[F, HMACSHA256](jwtClaims, signingKey).map(Token(_))
      }

      override def validateToken(token: Token): F[Claim] =
        JWTMac
            .verifyAndParse[F, HMACSHA256](token.value, signingKey)
            .flatMap(result => F.fromOption(result.body.subject, MISSING_SUBJECT_ERR))
            .map(sub => Claim(Subject(sub)))
    }

    object ioJwtSecurityInterpreter extends JwtSecurityInterpreter[IO]

    // Windows hack
    private def tsecWindowsFix(): Unit =
      try {
        SecureRandom.getInstance("NativePRNGNonBlocking")
        ()
      } catch {
        case _: NoSuchAlgorithmException =>
          val secureRandom = new SecureRandom()
          val defaultSecureRandomProvider = secureRandom.getProvider.get(s"SecureRandom.${secureRandom.getAlgorithm}")
          secureRandom.getProvider.put("SecureRandom.NativePRNGNonBlocking", defaultSecureRandomProvider)
          Security.addProvider(secureRandom.getProvider)
          ()
      }

    tsecWindowsFix()
  }
}
