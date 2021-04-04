/*
 * TODO: License goes here!
 */


package io.freemonads.http

import cats.Id
import cats.syntax.option._
import org.specs2.Specification
import org.specs2.matcher.{IOMatchers, MatchResult}
import org.specs2.specification.core.SpecStructure


trait ApiCalls {

  import api._

  val someResultOk = "someResult".resultOk
  val someApiError = NotImplementedError("createUser")
  val someException = new Exception("something went wront")

  val composedResult: ApiResult[String] =
    for {
      name <- "this is name".resultOk
      surname <- "and surname".resultOk
    } yield name + " " + surname
}

class ApiSpec extends Specification with ApiCalls with IOMatchers { def is: SpecStructure =
  s2"""
      ApiResult should: <br/>
      Build from a variable R     $resultOk
      Build from ApiError         $errorOps
      Build from Throwable        $throwableOps
      Lift to ApiCallF            $resultToCallFree
      Build from Option (None)    $noneToResultError
      Build from Option (Some[R]) $someToResultOk
      Compose                     $composeResults
      """

  import api._

  def resultOk: MatchResult[ApiResult[String]] = "someResult".resultOk must beAnInstanceOf[ApiResult[String]]

  def errorOps: MatchResult[ApiResult[String]] = someApiError.resultError[String] must beAnInstanceOf[ApiResult[String]]

  def throwableOps: MatchResult[ApiResult[Int]] =
    someException.runtimeApiError[Int]("error") must beAnInstanceOf[ApiResult[Int]]

  def resultToCallFree: MatchResult[Any] = "resultOk".liftFree[Id] must beAnInstanceOf[ApiFree[Id, String]]

  def noneToResultError: MatchResult[ApiResult[String]] =
    Option.empty[String].toResult(ResourceNotFoundError("123".some)) must beAnInstanceOf[ApiResult[String]]

  def someToResultOk: MatchResult[ApiResult[String]] =
    Some("resultOk").toResult(ResourceNotFoundError("123".some)) must beAnInstanceOf[ApiResult[String]]

  def composeResults: MatchResult[ApiResult[String]] =
    composedResult must_===("this is name and surname".resultOk)
}
