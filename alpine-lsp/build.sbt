scalaVersion := "2.13.12"

name := "alpine-lsp"
organization := "ch.epfl.scala"
version := "1.0"

libraryDependencies ++= List(
    "org.scala-lang.modules" %% "scala-parser-combinators" % "2.3.0",
    "org.eclipse.lsp4j" % "org.eclipse.lsp4j" % "0.10.0",
)

// Cheat needed to extend LanguageServer in Main.scala
scalacOptions += "-Xmixin-force-forwarders:false"

// Specify the main class for the project
mainClass in Compile := Some("Main")
