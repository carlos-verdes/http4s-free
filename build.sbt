
ThisBuild / sonatypeCredentialHost := "s01.oss.sonatype.org"

val Http4sVersion = "0.21.16"
val CatsVersion = "2.3.1"
val CatsLogVersion = "1.2.0"
val CirceVersion = "0.13.0"
val LogbackVersion = "1.2.3"
val Specs2Version = "4.9.3"
val Http4sSpecs2Version = "1.0.0"
val ArangoVersion = "0.0.6"
val DockerTestVersion = "0.9.9"

resolvers ++= Seq(Resolver.sonatypeRepo("releases"))

lazy val root = (project in file("."))
  .configs(IntegrationTest)
  .settings(
    scalaVersion := "2.13.4",
    organization := "io.freemonads",
    name := "http4s-free",
    homepage := Some(url("https://github.com/carlos-verdes/http4s-free")),
    scmInfo := Some(ScmInfo(url("https://github.com/carlos-verdes/http4s-free"), "git@github.com:carlos-verdes/http4s-free.git")),
    developers := List(Developer("carlos-verdes", "Carlos Verdes", "cverdes@gmail.com", url("https://github.com/carlos-verdes"))),
    publishMavenStyle := true,
    Defaults.itSettings,
    libraryDependencies ++= Seq(
      "org.http4s"       %% "http4s-blaze-server"         % Http4sVersion,
      "org.http4s"       %% "http4s-blaze-client"         % Http4sVersion,
      "org.http4s"       %% "http4s-circe"                % Http4sVersion,
      "org.http4s"       %% "http4s-dsl"                  % Http4sVersion,
      "org.typelevel"    %% "cats-free"                   % CatsVersion,
      "org.typelevel"    %% "log4cats-slf4j"              % CatsLogVersion,
      "io.circe"         %% "circe-generic"               % CirceVersion,
      "io.circe"         %% "circe-literal"               % CirceVersion,
      "com.bicou"        %% "avokka-velocypack"           % ArangoVersion,
      "com.bicou"        %% "avokka-arangodb-fs2"         % ArangoVersion,
      "ch.qos.logback"   %  "logback-classic"             % LogbackVersion,
      "org.specs2"       %% "specs2-core"                 % Specs2Version,
      "org.specs2"       %% "specs2-cats"                 % Specs2Version,
      "org.specs2"       %% "specs2-http4s"               % Http4sSpecs2Version % "it, test",
      "com.whisk"        %% "docker-testkit-config"       % DockerTestVersion,
      "com.whisk"        %% "docker-testkit-specs2"       % DockerTestVersion % "it, test",
      "com.whisk"        %% "docker-testkit-impl-spotify" % DockerTestVersion % "it, test",
      "javax.activation" %  "activation"                  % "1.1.1" % "it",
      "javax.xml.bind"   %  "jaxb-api"                    % "2.3.0" % "it",
      "com.sun.xml.bind" %  "jaxb-core"                   % "2.3.0" % "it",
      "com.sun.xml.bind" %  "jaxb-impl"                   % "2.3.0" % "it"
    ),
    addCompilerPlugin("org.typelevel" %% "kind-projector"     % "0.10.3"),
    addCompilerPlugin("com.olegpy"    %% "better-monadic-for" % "0.3.1")
  )

addCommandAlias("prepare", ";clean ;headerCreate ;publishSigned")
addCommandAlias("sanity", ";clean ;compile ;scalastyle ;coverage ;test ;it:test ;coverageOff ;coverageReport ;project")

coverageExcludedPackages := """io.freemonads.Main"""

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
sonatypeCredentialHost := "s01.oss.sonatype.org"
sonatypeRepository := "https://s01.oss.sonatype.org/service/local"

// realease with sbt-release plugin
import ReleaseTransformations._
releaseCrossBuild := true
releasePublishArtifactsAction := PgpKeys.publishSigned.value
