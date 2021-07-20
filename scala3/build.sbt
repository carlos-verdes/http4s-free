val scala2Version = "2.13.6"
val scala3Version = "3.0.0"


lazy val root = project
  .in(file("."))
  .settings(
    organization := "io.freemonads",
    name := "http4s-free",
    homepage := Some(url("https://github.com/carlos-verdes/http4s-free")),
    scmInfo := Some(ScmInfo(url("https://github.com/carlos-verdes/http4s-free"), "git@github.com:carlos-verdes/http4s-free.git")),
    developers := List(Developer("carlos-verdes", "Carlos Verdes", "cverdes@gmail.com", url("https://github.com/carlos-verdes"))),

    scalaVersion := scala3Version,
    crossScalaVersions ++= Seq(scala2Version, scala3Version),

    publishMavenStyle := true,
    Defaults.itSettings,
    libraryDependencies ++= Seq(
        "org.typelevel" %% "cats-core" % "2.6.1",
        "org.specs2"    %% "specs2-core" % "5.0.0-ALPHA-03" % "test",
        "com.novocode"  % "junit-interface" % "0.11" % "test"))

scalacOptions ++=
  Seq(
    "-encoding",
    "UTF-8",
    "-feature",
    "-language:implicitConversions",
    "-Xfatal-warnings",
    //"-new-syntax", "-rewrite",
    "-indent", "-rewrite"
  )
