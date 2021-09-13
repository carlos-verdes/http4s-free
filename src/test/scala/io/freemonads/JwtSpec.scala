/*
 * Copyright 2021 io.freemonads
 *
 * SPDX-License-Identifier: MIT
 */

package io.freemonads

import cats.effect.IO
import cats.free.Free
import cats.implicits.catsSyntaxFlatMapOps
import org.specs2.Specification
import org.specs2.matcher.{IOMatchers, MatchResult}
import org.specs2.specification.core.SpecStructure


trait JwtClaims {

  import security._

  val subject = Subject("address1")
  val expectedClaim = Claim(subject)
  val tokenWihtoutSubject = Token(
    "eyJ0eXAiOiJKV1QiLCJhbGciOiJIUzI1NiJ9." +
        "eyJyYW5kb20iOiJhZGRyZXNzMSJ9." +
        "ynbxqXi2xyM4w_tDgdYYYTbyMw2pmB3JqCNWYNw1RBA")
}

class JwtSpec extends Specification with JwtClaims with specs2.Http4FreeIOMatchers with IOMatchers {
  def is: SpecStructure =
    s2"""
        Security Algebra should: <br/>
        
        Create a token from claim and get back    $createValidToken
        Raise error if token doesn't have subject $noSubjectError
        """

  import security._
  import jwt._

  implicit val interpreter = jwtSecurityInterpreter[IO]
  val dsl = SecurityDsl.instance[SecurityAlgebra]
  import dsl._


  def createValidToken: MatchResult[Free[SecurityAlgebra, Claim]] =
    (createToken(subject) >>= validateToken)  must matchFree(expectedClaim)
  def noSubjectError: MatchResult[Free[SecurityAlgebra, Claim]] =
    validateToken(tokenWihtoutSubject)  must matchFreeNonAuthorizedError
}

