import Dependencies._
import Libraries._

ThisBuild / sonatypeCredentialHost := "s01.oss.sonatype.org"

resolvers ++= Seq(Resolver.sonatypeRepo("releases"))

val http4sLibraries = Seq(http4sdsl, http4sServer, http4sBlazeServer, http4sClient, http4sCirce)
val catsLibraries = Seq(catsCore, catsFree, catsTaglessMacros)
val circeLibraries = Seq(circeGeneric, circeLiteral)
val avokkaLibraries = Seq(avokkaFs2, avokkaVelocipack)
val secLibraries = Seq(tsecSig, tsecMac, web3)

val codeLibraries = http4sLibraries ++ catsLibraries ++ circeLibraries ++ avokkaLibraries ++ secLibraries

val logLibraries = Seq(logback, logCatsSlf4j, jansi)
val testLibraries = Seq(specs2Core, specs2Cats)

val dockerLibraries = Seq(dockerTestConfig, dockerTestSpecs2, dockerTestSpotify)
val javaxLibraries = Seq(javaxBind, javaxActivation, jaxbCore, jaxbImpl)

val allLib = codeLibraries ++ logLibraries ++ testLibraries ++ dockerLibraries ++ javaxLibraries

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
    libraryDependencies ++= allLib,
    scalacOptions += "-Ymacro-annotations",
    addCompilerPlugin("org.typelevel" %% "kind-projector"     % "0.10.3"),
    addCompilerPlugin("com.olegpy"    %% "better-monadic-for" % "0.3.1")
  )

addCommandAlias("prepare", ";clean ;headerCreate ;publishSigned")
addCommandAlias("sanity", ";clean ;compile ;scalastyle ;coverage ;test ;it:test ;coverageOff ;coverageReport ;project")

coverageExcludedPackages := """io.freemonads.Main; io.freemonads.*.autoDerive"""

organizationName := "io.freemonads"
startYear := Some(2021)
licenses += ("MIT", new URL("https://opensource.org/licenses/MIT"))
headerLicenseStyle := HeaderLicenseStyle.SpdxSyntax
headerSettings(Test)

publishMavenStyle := true
publishTo := {
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
