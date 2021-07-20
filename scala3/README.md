# http4s-free

Free monads on top of http4s

The main goal is to write your API's using free monads + interpreters

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
