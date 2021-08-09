/*
 * TODO: License goes here!
 */
package io.freemonads.security


import java.security.{NoSuchAlgorithmException, SecureRandom, Security}

import cats.effect.IO
import cats.syntax.option._
import org.specs2.Specification
import org.specs2.matcher.{IOMatchers, MatchResult}
import org.specs2.specification.core.SpecStructure
import tsec.common._
import tsec.jws.mac.JWTMac
import tsec.jwt.JWTClaims
import tsec.mac.jca.HMACSHA256

trait JwtClaims {

  val testKey = "zK55VIsxuDZBfTSr5rK4t9U5TY2FZUiu+dW0nCWcegw=".b64Bytes.get
  val claim = JWTClaims(subject = "address1".some, jwtId = None)
  val expectedJwtToken = "eyJ0eXAiOiJKV1QiLCJhbGciOiJIUzI1NiJ9." +
      "eyJzdWIiOiJhZGRyZXNzMSJ9." +
      "hruJMUPgmxZwCYYqZJXB9l5x_shhGk5nYbvE_ryfECw"

  // Windows testing hack
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

  def jwtMonadic(claims: JWTClaims): IO[String] =
    for {
      key             <- HMACSHA256.buildKey[IO](testKey)
      //jwt             <- JWTMac.build[F, HMACSHA256](claims, key) //You can sign and build a jwt object directly
      //verifiedFromObj <- JWTMac.verifyFromInstance[F, HMACSHA256](jwt, key) //Verify from an object directly
      stringjwt       <- JWTMac.buildToString[IO, HMACSHA256](claims, key) //Or build it straight to string
      //isverified   <- JWTMac.verifyFromString[IO, HMACSHA256](stringjwt, key) //You can verify straight from a string
      //parsed       <- JWTMac.verifyAndParse[F, HMACSHA256](stringjwt, key) //Or verify and return the actual instance
    } yield stringjwt
}

class JwtSpec extends Specification with JwtClaims with IOMatchers { def is: SpecStructure =
  s2"""
      ApiResult should: <br/>
      Create valid Jwt claim     $createJwtClaim
      """

  def createJwtClaim: MatchResult[IO[String]] = jwtMonadic(claim) must returnValue(expectedJwtToken)
}
