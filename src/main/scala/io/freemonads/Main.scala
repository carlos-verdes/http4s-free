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
import io.freemonads.interpreters.arangoStore.arangoStoreInterpreter
import org.http4s.HttpRoutes
import org.http4s.blaze.server.BlazeServerBuilder
import org.http4s.circe.CirceEntityCodec._
import org.http4s.dsl.io._
import org.http4s.headers.Location
import org.http4s.implicits._
import org.typelevel.log4cats._
import org.typelevel.log4cats.slf4j.Slf4jLogger


case class Mock(name: String, age: Int)
case class Pot(name: String)

object Main extends IOApp {

  import httpStore._
  import http._

  type TestAlgebra[R] = EitherK[HttpFreeAlgebra,  HttpStoreAlgebra, R]
  type ArangoResourceDsl = HttpStoreDsl[TestAlgebra, VPackEncoder, VPackDecoder]

  implicit val mockEncoder: VPackEncoder[Mock] = VPackEncoder.gen
  implicit val mockDecoder: VPackDecoder[Mock] = VPackDecoder.gen
  implicit val potEncoder: VPackEncoder[Pot] = VPackEncoder.gen
  implicit val potDecoder: VPackDecoder[Pot] = VPackDecoder.gen

  def testRoutes(
      implicit httpFreeDsl: HttpFreeDsl[TestAlgebra],
      storeDsl: ArangoResourceDsl,
      interpreters: TestAlgebra ~> IO): HttpRoutes[IO] = {

    import httpFreeDsl._
    import storeDsl._

    HttpRoutes.of[IO] {
      case r @ GET -> Root / "mocks" / _ =>
        for  {
          mock <- fetch[Mock](r.uri)
        } yield Ok(mock.body)

      case r @ POST -> Root / "mocks" =>
        for {
          mockRequest <- parseRequest[IO, Mock](r)
          savedMock <- store[Mock](r.uri / mockRequest.name.toLowerCase, mockRequest)
        } yield Created(savedMock.body, Location(savedMock.uri))

      case POST -> Root / "mocks" / leftId / relType / rightId =>
        for {
          _ <- link(uri"/" / "mocks" / leftId, uri"/" / "mocks" / rightId, relType)
        } yield Ok()

      case r @ POST -> Root / "pots" =>
        for {
          potRequest <- parseRequest[IO, Pot](r)
          savedPot <- store[Pot](r.uri / potRequest.name.toLowerCase, potRequest)
        } yield savedPot.created[IO]

      case POST -> Root / leftId / "eats" / rightId =>
        for {
          _ <- link(uri"/" / "mocks" / leftId, uri"/" / "pots" / rightId, "eats")
        } yield Ok()
    }
  }

  val arangoConfig = ArangoConfiguration.load()
  val arangoResource = Arango(arangoConfig)

  implicit def unsafeLogger: Logger[IO] = Slf4jLogger.getLogger[IO]

  implicit def interpreters: TestAlgebra ~> IO =
    httpFreeInterpreter[IO] or arangoStoreInterpreter(arangoResource)


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
