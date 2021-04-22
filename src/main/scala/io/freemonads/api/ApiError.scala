/*
 * TODO: License goes here!
 */


package io.freemonads.api


sealed trait ApiError
case class RequestFormatError(message: Option[String] = None, cause: Option[Throwable] = None) extends ApiError
case class NonAuthorizedError(cause: Option[Throwable]) extends ApiError
case class ResourceNotFoundError(cause: Option[Throwable] = None) extends ApiError
case class NotImplementedError(method: String) extends ApiError
case class ConflictError(cause: Option[Throwable] = None) extends ApiError
case class RuntimeError(cause: Option[Throwable] = None) extends ApiError
