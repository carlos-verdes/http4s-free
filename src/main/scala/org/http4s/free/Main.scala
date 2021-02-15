package org.http4s.free

import cats.effect.{ExitCode, IO, IOApp}

object Main extends IOApp {
  def run(args: List[String]) =
    Http4sfreeServer.stream[IO].compile.drain.as(ExitCode.Success)
}
