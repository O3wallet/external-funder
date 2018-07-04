name := "ExternalFunder"

version := "1.0"

scalaVersion := "2.12.1"

resolvers += "lightshed-maven" at "http://dl.bintray.com/content/lightshed/maven"

libraryDependencies += "com.iheart" % "ficus_2.12" % "1.4.3"
libraryDependencies += "io.spray" % "spray-json_2.12" % "1.3.4"
libraryDependencies += "fr.acinq" % "bitcoin-lib_2.12" % "0.9.17"
libraryDependencies += "com.typesafe.akka" %% "akka-actor" % "2.5.13"
libraryDependencies += "org.java-websocket" % "Java-WebSocket" % "1.3.8"
libraryDependencies += "com.github.krzemin" % "octopus-cats_2.12" % "0.3.3"
libraryDependencies += "ch.lightshed" %% "courier" % "0.1.4"