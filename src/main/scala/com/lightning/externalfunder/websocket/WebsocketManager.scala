package com.lightning.externalfunder.websocket

import spray.json._
import net.ceedubs.ficus.Ficus._
import com.lightning.externalfunder.wire._
import com.lightning.externalfunder.wire.FundMsg._
import net.ceedubs.ficus.readers.ArbitraryTypeReader._
import com.lightning.externalfunder.wire.ImplicitJsonFormats._
import com.lightning.externalfunder.Utils.{UserId, WebSocketConnSet}
import com.lightning.externalfunder.{Utils, WebsocketManagerConfig}
import com.lightning.walletapp.ln.Tools.{errlog, log}
import scala.util.{Failure, Success}
import akka.actor.{Actor, ActorRef}

import scala.concurrent.ExecutionContext.Implicits.global
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import scala.language.implicitConversions
import com.typesafe.config.ConfigFactory
import org.java_websocket.WebSocket
import scala.concurrent.Future
import java.io.File


class WebsocketManager(verifier: WebsocketVerifier, wallet: ActorRef) extends Actor { me =>
  private var conns = Map.empty[UserId, WebSocketConnSet] withDefaultValue Set.empty[WebSocket]
  implicit def conn2UserId(webSocketConnection: WebSocket): UserId = webSocketConnection.getAttachment[UserId]
  context.system.eventStream.subscribe(channel = classOf[FundMsg], subscriber = self)

  private val inetSockAddress = {
    val config = ConfigFactory parseFile new File(Utils.datadir, "websocketManager.conf")
    val WebsocketManagerConfig(host, port) = config.as[WebsocketManagerConfig]("config")
    new java.net.InetSocketAddress(host, port)
  }

  val server = new WebSocketServer(inetSockAddress) {
    def onOpen(conn: WebSocket, handshake: ClientHandshake): Unit =
      // Once connected we immediately verify a supplied credentials
      // user may issue commands only after successfully verified

      verifier verify handshake onComplete {
        // Verification may take quite some time
        // user should always wait until it's done

        case Failure(why) =>
          // Could not start an internal verification
          // fail connection right away and let user know
          val err = Fail(FAIL_VERIFY_ERROR, why.getMessage)
          conn send err.toJson.toString
          conn.close

        case Success(startMessage) =>
          conn setAttachment startMessage.userId
          // First set an attachment, then rely on it
          conns = conns.updated(conn, conns(conn) + conn)
          wallet ! startMessage
      }

    def onMessage(conn: WebSocket, incomingUserMessage: String): Unit = try {
      // Socket is only considered verified if conains a userId information, throw an error otherwise
      if (conn2UserId(conn) == null) conn send Fail(FAIL_NOT_VERIFIED_YET, "Not verified").toJson.toString
      else wallet ! incomingUserMessage.parseJson.convertTo[FundMsg]
    } catch errlog

    def onClose(conn: WebSocket, c: Int, rs: String, rm: Boolean): Unit = {
      // Make this socket unverified to ensure it can't be reused and disconnect
      // also remove it from connection pool for a given user id
      conns = conns.updated(conn, conns(conn) - conn)
      conn.setAttachment(null)
      conn.close
    }

    def onError(conn: WebSocket, reason: Exception): Unit = errlog(reason)
    def onStart: Unit = log(s"Websocket server started at $inetSockAddress")
    Future(run)
  }

  override def receive: Receive = {
    case startedResponse: Started =>
      // Notify if user is not online now
      verifier.notifyOnReady(startedResponse)
      send(startedResponse.userId, startedResponse)

    case fundMessage: FundMsg =>
      // Simply relay regular messages
      send(fundMessage.userId, fundMessage)
  }

  def send(userId: UserId, message: FundMsg): Unit = for {
    // Send a message to currently connected userId sockets
    connections: WebSocketConnSet <- conns get userId
    connection: WebSocket <- connections
    text = message.toJson.toString
  } connection send text
}