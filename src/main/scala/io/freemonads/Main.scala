/*
 * Copyright 2021 io.freemonads
 *
 * SPDX-License-Identifier: MIT
 */

package io.freemonads

import avokka.arangodb.ArangoConfiguration
import avokka.arangodb.fs2.Arango
import avokka.velocypack._
import cats.effect.{ExitCode, IO, IOApp}
import io.circe.generic.auto._
import io.freemonads.tagless.security.SecurityAlgebra
import org.http4s.HttpRoutes
import org.http4s.blaze.server.BlazeServerBuilder
import org.http4s.circe.CirceEntityCodec._
import org.http4s.dsl.io._
import org.http4s.implicits._
import org.typelevel.log4cats._
import org.typelevel.log4cats.slf4j.Slf4jLogger


case class Mock(name: String, age: Int)

object Main extends IOApp {

  import tagless.http._
  import tagless.httpStore._

  type ArangoResourceDsl = HttpStoreAlgebra[IO, VPackEncoder, VPackDecoder]

  import tagless.interpreters.arangoStore._
  import tagless.security.jwt._

  val arangoConfig = ArangoConfiguration.load()
  val arangoResource = Arango(arangoConfig)

  implicit val storeDsl: ArangoStoreAlgebra[IO] = ArangoStoreInterpreter(arangoResource)
  implicit val securityDsl: SecurityAlgebra[IO] = ioJwtSecurityInterpreter
  implicit val httpFreeDsl: HttpAlgebra[IO] = tagless.http.io.ioHttpAlgebraInterpreter

  implicit val mockEncoder: VPackEncoder[Mock] = VPackEncoder.gen
  implicit val mockDecoder: VPackDecoder[Mock] = VPackDecoder.gen

  def testRoutes(
      implicit httpFreeDsl: HttpAlgebra[IO],
      storeDsl: ArangoResourceDsl): HttpRoutes[IO] = {

    import httpFreeDsl._
    import storeDsl._

    HttpRoutes.of[IO] {
      case r @ GET -> Root / "mocks" / _ =>
        for  {
          mock <- fetch[Mock](r.uri)
          response <- mock.ok[IO]
        } yield response

      case r @ POST -> Root / "mocks" =>
        for {
          mockRequest <- parseRequest[Mock](r)
          savedMock <- store[Mock](r.uri / mockRequest.name.toLowerCase, mockRequest)
          response <- savedMock.created[IO]
        } yield response

      case POST -> Root / "mocks" / leftId / relType / rightId =>
        for {
          _ <- linkResources(uri"/" / "mocks" / leftId, uri"/" / "mocks" / rightId, relType)
          response <- Ok()
        } yield response
    }
  }

  implicit def unsafeLogger: Logger[IO] = Slf4jLogger.getLogger[IO]

  val app = testRoutes.orNotFound

  override def run(args: List[String]): IO[ExitCode] =
    BlazeServerBuilder[IO](executionContext)
        .bindHttp(8080, "0.0.0.0")
        .withHttpApp(app)
        .resource
        .use(_ => IO.never)
        .start
        .as(ExitCode.Success)
}
