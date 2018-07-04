package com.lightning.externalfunder

import com.lightning.externalfunder.websocket._
import akka.actor.{Actor, OneForOneStrategy, Props}
import com.lightning.walletapp.ln.Tools.{errlog, none}
import com.lightning.externalfunder.wallet.BitcoinCoreWallet
import akka.actor.SupervisorStrategy.Resume


class Supervisor extends Actor {
  private val verifier: EmailVerifier = new EmailVerifier
  private val wallet = context actorOf Props.create(classOf[BitcoinCoreWallet], verifier)
  private val wsManager = context actorOf Props.create(classOf[WebsocketManager], verifier, wallet)
  def receive: Receive = none

  override def supervisorStrategy: OneForOneStrategy = OneForOneStrategy(-1) {
    // No matter what happens we just keep going because wallet has an inner state
    // but log an exception to inspect it at a later time
    case reason: Throwable =>
      errlog(reason)
      Resume
  }
}
