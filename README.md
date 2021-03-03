# http4s-free

Free monads on top of http4s

The main goal is to write your API's using free monads + interpreters

### Example
Extract from `HttpfsFreeSpec` test:
```scala
  import cats.effect.{IO, Sync, Timer}
  import cats.{Functor, ~>}
  import io.circe.generic.auto._
  import io.freemonads.http4sFree._
  import org.http4s._
  import org.http4s.circe.CirceEntityCodec._
  import org.http4s.dsl.Http4sDsl
  
  // some model
  case class Mock(id: Option[String], name: String, age: Int)

  // our routes need the Algebra dsls + interpreters so we can write free monads logic
  def mockRoutes[F[_]: Sync : Timer : Functor, Algebra[_]](
      implicit http4sFreeDsl: Http4sFreeDsl[Algebra],
      interpreters: Algebra ~> F): HttpRoutes[F] = {

    val dsl = new Http4sDsl[F]{}
    import dsl._
    import http4sFreeDsl._

    HttpRoutes.of[F] {
      case GET -> Root / "mock" =>
        for  {
          // here we lift a pure value into free monad
          mock <- Mock(Some("id123"), "name123", 23).resultOk.liftFree[Algebra]
        } yield Ok(mock)

      case r @ POST -> Root / "mock" =>
        for {
          // here we use directly http free dsl (which is already a free monad)
          mockRequest <- parseRequest[F, Mock](r)
        } yield Created(mockRequest.copy(id = Some("id123")))
    }
  }
```

### API model

The API has three main types:
```scala
  type ApiResult[R] = Either[ApiError, R]
  type ApiCall[F[_], R] = EitherT[F, ApiError, R]
  type ApiCallF[F[_], R] = EitherT[Free[F, *], ApiError, R]
```

- ApiResult represents the result of an API call, which can be Ok (and retrieves the model) or and error (more on this below)
- ApiCall wraps the API call inside effects (normally for async code), it's used only when we implement the interpreters
- ApiCallF is the free representation of an API call and is used to write the logic of our API

The errors supported are next (and are mapped to HTTP error codes on API response):
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

## Algebras and DSLs

The code is organized around Algebras, DSL's and interpreters.

The first Algebra implemented is just to parse the request into a model with type `R`, let's use this as an example.

First we define the Algebra and the dsl methods (check `org.http4s.free.Http4sFree`):
```scala
  
  // we start with an Algebra which is a data structure that represents functions
  sealed trait Http4sAlgebra[Result]
  // on this example we have only 1 function that parse a http4s request into an ApiResult R
  // if parsing is ok then we get R and if there is an error a RequestFormatError (http 400 error) is returned 
  case class ParseRequest[F[_], R](request: Request[F], ED: EntityDecoder[F, R]) extends Http4sAlgebra[ApiResult[R]]

  // then we define a dsl so we can use the Algebra in our router logic (check first example)
  class Http4sFreeDsl[Algebra[_]](implicit I: InjectK[Http4sAlgebra, Algebra]) {

    def parseRequest[F[_], R](request: Request[F])(implicit ED: EntityDecoder[F, R]): ApiCallF[Algebra, R] =
      EitherT(inject(ParseRequest[F, R](request, ED)))

    private def inject[F[_], R] = Free.inject[Http4sAlgebra, F]
  }
```

Second we use the dsl in our routers (check first example or full test code):
```scala
    HttpRoutes.of[F] {
      case r @ POST -> Root / "mock" =>
        for {
          // here we use directly http free dsl (which is already a free monad)
          mockRequest <- parseRequest[F, Mock](r)
        } yield Created(mockRequest.copy(id = Some("id123")))
    }
  }
```

Third we implement the interpreter/s depending which context we want to run the logic:
```scala
  def http4sInterpreter[F[_]: FlatMap]: Http4sAlgebra ~> F = new (Http4sAlgebra ~> F) {

    override def apply[A](op: Http4sAlgebra[A]): F[A] = op match {

      case ParseRequest(req, decoder) =>

        val request: Request[F] = req.asInstanceOf[Request[F]]
        val ED: EntityDecoder[F, A] = decoder.asInstanceOf[EntityDecoder[F, A]]

        request
            .attemptAs[A](ED)
            .value
            .map(_.fold(df => RequestFormatError(request, df).resultError[A], _.resultOk))
            .asInstanceOf[F[A]]
    }
  }
```

Free Monad interpreters are just a natural transformation from our Free Monad context `Free[_]` into the effects context
we want to use `F[_]`.
As you can see on previous implementation we only need to make sure our effect implements FlatMap type class.

Example to instance a Cats IO interpreter:
```scala

  import io.freemonads.http4sFree._
  
  implicit val interpreters = http4sInterpreter[IO]
```
