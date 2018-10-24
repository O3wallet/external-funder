package com.lightning.externalfunder


object ExternalFunder extends App {
  import akka.actor.{ActorSystem, Props}
  println(System getProperty "user.home")
  val system = ActorSystem("funding-system")
  val supervisorClass = Props create classOf[Supervisor]
  system actorOf supervisorClass
}