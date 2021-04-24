# http4s-free

Free monads on top of http4s

The main goal is to write your API's using free monads + interpreters

### Example
Extract from `HttpfsFreeSpec` test:

```scala
import cats.effect.{IO, Sync, Timer}
import cats.{Functor, ~>}
import io.circe.generic.auto._
import io.freemonads.http.api._
import io.freemonads.http.resource._
import io.freemonads.http.rest._
import org.http4s._
import org.http4s.circe.CirceEntityCodec._
import org.http4s.dsl.Http4sDsl

// some model
case class Mock(id: Option[String], name: String, age: Int)

// our routes need the Algebra dsls + interpreters so we can write free monads logic
def mockRoutes[F[_] : Sync : Timer : Functor, Algebra[_], Serializer[_], Deserializer[_]](
    implicit http4sFreeDsl: Http4sFreeDsl[Algebra],
    resourceDsl: ResourceDsl[Algebra, Serializer, Deserializer],
    interpreters: Algebra ~> F): HttpRoutes[F] = {

  val dsl = new Http4sDsl[F] {}
  import dsl._
  import http4sFreeDsl._
  import resourceDsl._

  HttpRoutes.of[F] {
    case r @ GET -> Root / "mock" / id =>
      for {
        mock <- fetch[Mock](r.uri) //
      } yield Ok(mock)

    case r @ POST -> Root / "mock" =>
      for {
        mockRequest <- parseRequest[F, Mock](r)
        savedResource <- store[Mock](r.uri, mockRequest)
      } yield Created(savedResource)
  }
}
```

### API model

The API has next main types:
```scala
  type ApiResult[R] = Either[ApiError, R]
  type ApiFree[F[_], R] = EitherT[Free[F, *], ApiError, R]
```


- `ApiResult` represents the result of an API call, which can be successful (and retrieves the model) or an error (more on this below)
- `ApiFree` represents a program that is a composition of different Api calls (free monad composition)

Errors supported (mapped to HTTP error codes on API response):
```scala
  sealed trait ApiError
  final case class RequestFormatError(request: Any, details: Any, cause: Option[Throwable] = None) extends ApiError
  final case class NonAuthorizedError(resource: Option[Any]) extends ApiError
  final case class ResourceNotFoundError(id: Option[String] = None, cause: Option[Throwable] = None) extends ApiError
  final case class NotImplementedError(method: String) extends ApiError
  final case class ResourceAlreadyExistError(id: String, cause: Option[Throwable] = None) extends ApiError
  final case class RuntimeError(message: String, cause: Option[Throwable] = None) extends ApiError
```

This allows the business logic to give enough detail on the type of error to the API controller.

## Algebras, DSLs and interpreters

The code is organized around Algebras, DSL's and interpreters:
- Algebras define a set of functions in a domain (for example store and fetch for resource algebra)
- DSL's are used in your monadic compositions to create programs
- interpreters implement the logic inside the Algebras

### Rest Algebra (Http4sAlgebra)

Basic REST functions:
- `parseRequest[F, R](request: Request[F])`: parse request to a type `R`, retrieving the model or format error

Usage:
```scala
  import io.freemonads.http.rest._

  for {
    mockRequest <- parseRequest[F, Mock](r)
  } yield Created(mockRequest)


```

### Resource Algebra

Http resource management:
- `store[R](id: Uri, r: R)`: stores a resource `R` using it's id as an uri, returns saved resource or error
- `fetch[R](id: Uri)`: retrieves a resource by id (uri) or not found error

Usage
```scala

import io.freemonads.http.resource._

def storeProgram[F[_]](id: Uri, mock: Mock)(implicit resourceDsl: ResourceDsl[F, Serializer, Deserializer]) =
  for {
    _ <- validate(mock) // example where you can implement some kind of validation
    mock <- resourceDsl.store[Mock](id, mock)
  } yield mock
  
def fetchProgram[F[_]](id: Uri)(implicit resourceDsl: ResourceDsl[F, Serializer, Deserializer]) =
  for {
    mock <- resourceDsl.fetch[Mock](id)
  } yield mock

```

## Interpreters
Free Monad interpreters are just a natural transformation from our Free Monad context `Free[_]` into the effects context
we want to use `F[_]: FlatMap`. 

Example to instance a Cats IO interpreter:

```scala

import io.freemonads._

implicit val interpreters = http4sInterpreter[IO]
```
