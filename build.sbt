val scala3Version = "3.2.1"

val commonSettings = Seq(
  scalaVersion := scala3Version,
  addCompilerPlugin("com.github.ghik" % "zerowaste" % "0.2.1" cross CrossVersion.full),
  scalacOptions += "-Werror",
)

ThisBuild / testFrameworks += new TestFramework("weaver.framework.CatsEffect")

lazy val root = project.in(file(".")).aggregate(core, circe, client)

lazy val core = project
  .in(file("modules/core"))
  .settings(commonSettings)
  .settings(
    name    := "surrealdb.scala",
    version := "0.1.0-SNAPSHOT",
    libraryDependencies ++= Seq(
      "com.softwaremill.sttp.client3" %% "core"        % "3.8.3",
      "co.fs2"                        %% "fs2-core"    % "3.4.0",
      "org.typelevel"                 %% "cats-effect" % "3.4.1",
    ),
  )

lazy val circe = project
  .in(file("modules/circe"))
  .settings(commonSettings)
  .settings(
    libraryDependencies ++= Seq(
      "io.circe" %% "circe-core"    % "0.14.1",
      "io.circe" %% "circe-generic" % "0.14.1",
      "io.circe" %% "circe-parser"  % "0.14.1",
    )
  )
  .dependsOn(core)

lazy val client = project
  .in(file("modules/client"))
  .settings(commonSettings)
  .settings(
    libraryDependencies ++= Seq(
      "com.disneystreaming"           %% "weaver-cats"  % "0.8.1"  % Test,
      "com.softwaremill.sttp.client3" %% "fs2"          % "3.8.3"  % Test,
      "org.slf4j"                      % "slf4j-simple" % "1.7.36" % Test
    ),
    testFrameworks += new TestFramework("weaver.framework.CatsEffect")
  )
  .dependsOn(circe, core)
