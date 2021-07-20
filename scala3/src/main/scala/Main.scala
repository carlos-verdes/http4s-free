

import cats.syntax.functor._

import io.freemonads.api._

///def composedResult(a: ApiResult[String], b: ApiResult[String]): ApiResult[String] = a + " and " + b

@main def hello: Unit =

  import io.freemonads.api.{given Functor[ApiResult]}

  val nameResult: ApiResult[String] = "This is name"
  val surnameResult: ApiResult[String] = "this is surname"

  println(s"Name: $nameResult")

  println(nameResult.map(_.length))

  /*
  val composed: ApiResult[hString] =
    for
      name <- nameResult
      surname <- surnameResult
    yield name + " and " + surname

  */
  //println(nameResult.map(name => s"Hello $name"))
