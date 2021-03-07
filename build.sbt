val Http4sVersion = "0.21.16"
val CatsVersion = "2.3.1"
val CirceVersion = "0.13.0"
val LogbackVersion = "1.2.3"
val Specs2Version = "4.9.3"
val Http4sSpecs2Version = "1.0.0"

resolvers ++= Seq(Resolver.sonatypeRepo("releases"))

lazy val root = (project in file("."))
  .settings(
    scalaVersion := "2.13.4",
    organization := "io.freemonads",
    name := "http4s-free",
    version := "0.0.1-SNAPSHOT",
    homepage := Some(url("https://github.com/carlos-verdes/http4s-free")),
    scmInfo := Some(ScmInfo(url("https://github.com/carlos-verdes/http4s-free"), "git@github.com:carlos-verdes/http4s-free.git")),
    developers := List(Developer("carlos-verdes", "Carlos Verdes", "cverdes@gmail.com", url("https://github.com/carlos-verdes"))),
    publishMavenStyle := true,
    libraryDependencies ++= Seq(
      "org.http4s"                 %% "http4s-blaze-server" % Http4sVersion,
      "org.http4s"                 %% "http4s-blaze-client" % Http4sVersion,
      "org.http4s"                 %% "http4s-circe"        % Http4sVersion,
      "org.http4s"                 %% "http4s-dsl"          % Http4sVersion,
      "org.typelevel"              %% "cats-free"           % CatsVersion,
      "io.circe"                   %% "circe-generic"       % CirceVersion,
      "io.circe"                   %% "circe-literal"       % CirceVersion,
      "ch.qos.logback"             %  "logback-classic"     % LogbackVersion,
      "org.specs2"                 %% "specs2-core"         % Specs2Version % Test,
      "org.specs2"                 %% "specs2-cats"         % Specs2Version % Test,
      "org.specs2"                 %% "specs2-http4s"       % Http4sSpecs2Version % Test
    ),
    addCompilerPlugin("org.typelevel" %% "kind-projector"     % "0.10.3"),
    addCompilerPlugin("com.olegpy"    %% "better-monadic-for" % "0.3.1")
  )

addCommandAlias("prepare", ";clean ;headerCreate ;publishSigned")
addCommandAlias("sanity", ";clean ;compile ;scalastyle ;coverage ;test ;coverageOff ;coverageReport ;project")

organizationName := "io.freemonads"
startYear := Some(2021)
licenses += ("MIT", new URL("https://opensource.org/licenses/MIT"))
headerLicenseStyle := HeaderLicenseStyle.SpdxSyntax
headerSettings(Test)

publishMavenStyle := true
publishTo :=
    {
      val nexus = "https://s01.oss.sonatype.org/"
      if (isSnapshot.value) Some("snapshots" at nexus + "content/repositories/snapshots")
      else Some("releases" at nexus + "service/local/staging/deploy/maven2")
    }

credentials += Credentials(Path.userHome / ".sbt" / "sonatype_credentials")

import xerial.sbt.Sonatype._
sonatypeProjectHosting := Some(GitHubHosting("carlos-verdes", "http4s-free", "cverdes@gmail.com"))

// realease with sbt-release plugin
import ReleaseTransformations._
releaseCrossBuild := true
