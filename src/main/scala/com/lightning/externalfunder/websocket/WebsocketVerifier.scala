package com.lightning.externalfunder.websocket

import spray.json._
import octopus.dsl._
import net.ceedubs.ficus.Ficus._
import com.lightning.externalfunder.Utils._
import net.ceedubs.ficus.readers.ArbitraryTypeReader._
import com.lightning.externalfunder.wire.ImplicitJsonFormats._
import com.lightning.externalfunder.{EmailVerifierConfig, Utils}
import com.lightning.externalfunder.wire.{Start, Started}
import scala.concurrent.ExecutionContext.Implicits.global
import org.java_websocket.handshake.ClientHandshake
import javax.mail.internet.InternetAddress
import com.typesafe.config.ConfigFactory
import scala.concurrent.Future
import java.io.File


trait WebsocketVerifier {
  def verify(ch: ClientHandshake): Future[Start]
  def notifyOnReady(started: Started): Future[Unit]
  def notifyOnFailed(start: Start): Future[Unit]
}

class EmailVerifier extends WebsocketVerifier {
  val EmailVerifierConfig(host, port, subject, from, password, okTemplate, failTemplate) =
    ConfigFactory parseFile new File(Utils.datadir, "emailVerifier.conf") as[EmailVerifierConfig] "config"

  private val emailValidator = Validator[Start]
    .rule(_.userId.nonEmpty, "User ID can not be an empty string")
    .rule(_.extra.forall(_ contains '@'), "Valid email address must contain an @ sign")
    .rule(_.extra.forall(_.split('@').last contains '.'), "Valid email address must contain a dot")

  def verify(ch: ClientHandshake) = Future {
    val json = hex2String(ch.getResourceDescriptor drop 1)
    val startMessage = json.parseJson.convertTo[Start]

    emailValidator validate startMessage match {
      case err :: _ => throw new Exception(err.message)
      case Nil => startMessage
    }
  }

  import courier.{Envelope, Mailer, Text}
  def sendEmail(to: String, text: String): Future[Unit] = {
    val envelope = Envelope from new InternetAddress(from) to new InternetAddress(to) content Text(text)
    Mailer(host, port).auth(true).as(from, password).startTtls(true).apply.apply(envelope subject subject)
  }

  def notifyOnReady(started: Started): Future[Unit] = started.start.extra match {
    // User email is optional so only send out a notification if it was provided
    // extra field can be anything but here we treat is as user email address

    case Some(userEmail) =>
      val hex = string2Hex(started.toJson.toString)
      val left = new java.util.Date(started.expiry)
      val msg = okTemplate.format(hex, left)
      sendEmail(userEmail, msg)

    case None =>
      Future.unit
  }

  def notifyOnFailed(start: Start): Future[Unit] = start.extra match {
    // User email is optional so only send out a notification if it was provided
    // extra field can be anything but here we treat is as user email address

    case Some(userEmail) =>
      // Funding has failed so it's nice to let user know
      val msg = failTemplate.format(start.fundingAmount.amount)
      sendEmail(userEmail, msg)

    case None =>
      Future.unit
  }
}