/*
 * Copyright 2021 io.freemonads
 *
 * SPDX-License-Identifier: MIT
 */

package io.freemonads.api


sealed trait ApiError extends Throwable
case class RequestFormatError(message: Option[Any] = None, cause: Option[Throwable] = None) extends ApiError
case class NonAuthorizedError(message: Option[Any] = None, cause: Option[Throwable] = None) extends ApiError
case class ResourceNotFoundError(cause: Option[Throwable] = None) extends ApiError
case class NotImplementedError(method: String) extends ApiError
case class ConflictError(cause: Option[Throwable] = None) extends ApiError
case class RuntimeError(cause: Option[Throwable] = None) extends ApiError
