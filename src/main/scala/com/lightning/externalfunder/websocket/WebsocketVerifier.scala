package com.lightning.externalfunder.websocket

import spray.json._
import octopus.dsl._
import net.ceedubs.ficus.Ficus._
import net.ceedubs.ficus.readers.ArbitraryTypeReader._
import com.lightning.externalfunder.wire.ImplicitJsonFormats._
import com.lightning.externalfunder.wire.{FundingTxCreated, Init}
import scala.concurrent.ExecutionContext.Implicits.global
import com.lightning.externalfunder.EmailVerifierConfig
import com.lightning.externalfunder.Utils.hex2String
import org.java_websocket.handshake.ClientHandshake
import javax.mail.internet.InternetAddress
import com.typesafe.config.ConfigFactory
import scala.concurrent.Future


trait WebsocketVerifier {
  def verify(ch: ClientHandshake): Future[Init]
  def notifyOnReady(ftc: FundingTxCreated): Future[Unit]
  def notifyOnFailed(init: Init): Future[Unit]
}

class EmailVerifier extends WebsocketVerifier {
  val EmailVerifierConfig(host, port, subject, from, password, minAmountSat, okTemplate, failTemplate) =
    ConfigFactory.parseResources("emailVerifier.conf") as[EmailVerifierConfig] "config"

  private val emailValidator = Validator[Init]
    .rule(_.userId.nonEmpty, "User id can not be an empty string")
    .rule(_.fundingAmount.amount >= minAmountSat, "Requested funding is too low")
    .rule(_.extra.forall(_ contains '@'), "Valid email address must contain an @ sign")
    .rule(_.extra.forall(_.split('@').last contains '.'), "Valid email address must contain a dot")

  def verify(ch: ClientHandshake) = Future {
    val json = hex2String(ch getFieldValue "body")
    val initMessage = json.parseJson.convertTo[Init]

    emailValidator validate initMessage match {
      case err :: _ => throw new Exception(err.message)
      case Nil => initMessage
    }
  }

  import courier.{Envelope, Mailer, Text}
  def sendEmail(to: String, text: String): Future[Unit] = {
    val envelope = Envelope from new InternetAddress(from) to new InternetAddress(to) content Text(text)
    Mailer(host, port).auth(true).as(from, password).startTtls(true).apply.apply(envelope subject subject)
  }

  def notifyOnReady(ftc: FundingTxCreated): Future[Unit] = ftc.init.extra match {
    // User email is optional so only send out a notification if it was provided
    // extra field can be anything but here we treat is as user email address

    case Some(userProvidedValidEmail) =>
      val left = new java.util.Date(System.currentTimeMillis - ftc.expiration)
      val message = okTemplate.format(ftc.init.fundingAmount.amount, ftc.init.userId, left)
      sendEmail(userProvidedValidEmail, message)

    case None =>
      Future.unit
  }

  def notifyOnFailed(init: Init): Future[Unit] = init.extra match {
    // User email is optional so only send out a notification if it was provided
    // extra field can be anything but here we treat is as user email address

    case Some(userProvidedValidEmail) =>
      // Funding has failed so it's nice to let user know about it
      val message = failTemplate.format(init.fundingAmount.amount)
      sendEmail(userProvidedValidEmail, message)

    case None =>
      Future.unit
  }
}