/*
 * TODO: License goes here!
 */
package io.freemonads.api

import io.freemonads.api

trait ApiCalls {

  val someResultOk: ApiResult[String] = "someResult"
  val someApiError = NotImplementedError("createUser")
  val someException = new Exception("something went wront")

  val nameResult: ApiResult[String] = "This is name"
  val surnameResult: ApiResult[String] = "this is surname"
}

class ApiSpec {

}
