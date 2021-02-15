val Http4sVersion = "0.21.16"
val CatsVersion = "2.3.1"
val CirceVersion = "0.13.0"
val LogbackVersion = "1.2.3"
val Specs2Version = "4.9.3"
val Http4sSpecs2Version = "1.0.0"

lazy val root = (project in file("."))
  .settings(
    organization := "org.http4s.free",
    name := "http4s-free",
    version := "0.0.1-SNAPSHOT",
    scalaVersion := "2.13.4",
    libraryDependencies ++= Seq(
      "org.http4s"                 %% "http4s-blaze-server" % Http4sVersion,
      "org.http4s"                 %% "http4s-blaze-client" % Http4sVersion,
      "org.http4s"                 %% "http4s-circe"        % Http4sVersion,
      "org.http4s"                 %% "http4s-dsl"          % Http4sVersion,
      "org.typelevel"              %% "cats-free"           % CatsVersion,
      "io.circe"                   %% "circe-generic"       % CirceVersion,
      "ch.qos.logback"             %  "logback-classic"     % LogbackVersion,
      "org.specs2"                 %% "specs2-core"         % Specs2Version % Test,
      "org.specs2"                 %% "specs2-cats"         % Specs2Version % Test,
      "org.specs2"                 %% "specs2-http4s"       % Http4sSpecs2Version % Test
    ),
    addCompilerPlugin("org.typelevel" %% "kind-projector"     % "0.10.3"),
    addCompilerPlugin("com.olegpy"    %% "better-monadic-for" % "0.3.1")
  )
