/*
 * Copyright 2021 io.freemonads
 *
 * SPDX-License-Identifier: MIT
 */

package io.freemonads

import avokka.arangodb.ArangoConfiguration
import avokka.arangodb.fs2.Arango
import avokka.velocypack._
import cats.data.EitherK
import cats.effect.{ExitCode, IO, IOApp}
import cats.~>
import io.circe.generic.auto._
import org.http4s.HttpRoutes
import org.http4s.dsl.io._
import org.http4s.circe.CirceEntityCodec._
import org.http4s.headers.Location
import org.http4s.implicits._
import org.http4s.server.blaze.BlazeServerBuilder
import org.typelevel.log4cats._
import org.typelevel.log4cats.slf4j.Slf4jLogger


case class Mock(name: String, age: Int)

object Main extends IOApp {

  import io.freemonads.arango._
  import http.resource._
  import http.rest._

  type TestAlgebra[R] = EitherK[Http4sAlgebra,  ResourceAlgebra, R]
  type ArangoResourceDsl = ResourceDsl[TestAlgebra, VPackEncoder, VPackDecoder]

  implicit val testEncoder: VPackEncoder[Mock] = VPackEncoder.gen
  implicit val testDecoder: VPackDecoder[Mock] = VPackDecoder.gen

  def testRoutes(
      implicit http4sFreeDsl: Http4sFreeDsl[TestAlgebra],
      resourceDsl: ArangoResourceDsl,
      interpreters: TestAlgebra ~> IO): HttpRoutes[IO] = {

    import http4sFreeDsl._
    import resourceDsl._

    HttpRoutes.of[IO] {
      case r @ GET -> Root / "mocks" / _ =>
        for  {
          mock <- fetch[Mock](r.uri)
        } yield Ok(mock.body)

      case r @ POST -> Root / "mocks" =>
        for {
          mockRequest <- parseRequest[IO, Mock](r)
          savedMock <- store[Mock](r.uri, mockRequest)
        } yield Created(savedMock.body, Location(savedMock.uri))
    }
  }

  val arangoConfig = ArangoConfiguration.load()
  val arangoResource = Arango(arangoConfig)

  implicit def unsafeLogger: Logger[IO] = Slf4jLogger.getLogger[IO]


  implicit def interpreters: TestAlgebra ~> IO =
    http4sInterpreter[IO] or arangoResourceInterpreter(arangoResource)


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
