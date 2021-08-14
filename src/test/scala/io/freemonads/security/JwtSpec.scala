/*
 * TODO: License goes here!
 */
package io.freemonads
package security


import java.security.{NoSuchAlgorithmException, SecureRandom, Security}

import cats.effect.IO
import cats.implicits.catsSyntaxFlatMapOps
import org.specs2.Specification
import org.specs2.matcher.{IOMatchers, MatchResult}
import org.specs2.specification.core.SpecStructure

trait JwtClaims {

  val subject = Subject("address1")
  val expectedClaim = Claim(subject)
  val tokenWihtoutSubject = Token(
      "eyJ0eXAiOiJKV1QiLCJhbGciOiJIUzI1NiJ9." +
      "eyJyYW5kb20iOiJhZGRyZXNzMSJ9." +
      "ynbxqXi2xyM4w_tDgdYYYTbyMw2pmB3JqCNWYNw1RBA")

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
}

class JwtSpec extends Specification with JwtClaims with specs2.Http4FreeIOMatchers with IOMatchers {
  def is: SpecStructure =
    s2"""
        Security Algebra should: <br/>
        Create a token from claim and get back    $createValidToken
        Raise error if token doesn't have subject $noSubjectError
        """

  import jwt._

  implicit val interpreter = jwtSecurityInterpreter[IO]
  val dsl = SecurityDsl.instance[SecurityAlgebra]
  import dsl._


  def createValidToken: MatchResult[Any] = (createToken(subject) >>= validateToken)  must resultOk(expectedClaim)
  def noSubjectError: MatchResult[Any] = validateToken(tokenWihtoutSubject)  must resultNonAuthorizedError
}
