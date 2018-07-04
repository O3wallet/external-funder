package com.lightning.externalfunder.websocket

import spray.json._
import net.ceedubs.ficus.Ficus._
import scala.concurrent.duration._
import com.lightning.walletapp.ln._
import com.lightning.externalfunder.wire._
import net.ceedubs.ficus.readers.ArbitraryTypeReader._
import com.lightning.externalfunder.wire.ImplicitJsonFormats._
import com.lightning.externalfunder.Utils.{UserId, WebSocketConnSet}
import com.lightning.walletapp.ln.Tools.{errlog, log}
import scala.util.{Failure, Success}
import akka.actor.{Actor, ActorRef}

import com.lightning.externalfunder.WebsocketManagerConfig
import scala.concurrent.ExecutionContext.Implicits.global
import org.java_websocket.handshake.ClientHandshake
import org.java_websocket.server.WebSocketServer
import scala.language.implicitConversions
import com.typesafe.config.ConfigFactory
import org.java_websocket.WebSocket


class WebsocketManager(verifier: WebsocketVerifier, wallet: ActorRef) extends Actor { me =>
  private var conns = Map.empty[UserId, WebSocketConnSet] withDefaultValue Set.empty[WebSocket]
  override def preStart: Unit = context.system.eventStream.subscribe(channel = classOf[Error], subscriber = self)
  implicit def conn2UserId(webSocketConnection: WebSocket): UserId = webSocketConnection.getAttachment[UserId]
  context.system.scheduler.schedule(10.minutes, 10.minutes)(self ! 'cleanup)

  private val inetSockAddress =
    ConfigFactory.parseResources("websocketManager.conf")
      .as[WebsocketManagerConfig]("config").inetSockAddress

  val server = new WebSocketServer(inetSockAddress) {
    def onOpen(conn: WebSocket, handshake: ClientHandshake): Unit =
      // Once connected we immediately verify a supplied credentials
      // user may issue commands only after successfully verified

      verifier verify handshake onComplete {
        // Verification may take quite some time
        // user should always wait until it's done

        case Failure(verificationError) =>
          // Could not start an internal verification
          // fail connection right away and let user know
          val err = Error(101, verificationError.getMessage)
          conn send err.toJson.toString
          conn.close

        case Success(initMessage) =>
          conns = conns.updated(conn, conns(conn) + conn)
          conn setAttachment initMessage.userId
          conn send initMessage.toJson.toString
          wallet ! initMessage
      }

    def onMessage(conn: WebSocket, incomingUserMessage: String): Unit = try {
      // Socket is always considered verified if it has an attached userId information
      if (conn2UserId(conn) == null) conn send Error(102, "Not verified").toJson.toString
      else wallet ! incomingUserMessage.parseJson.convertTo[FundMsg]
    } catch errlog

    def onClose(conn: WebSocket, c: Int, rs: String, rm: Boolean): Unit = cancelSocket(conn)
    def onError(conn: WebSocket, exception: Exception): Unit = errlog(exception)
    def onStart: Unit = log("Websocket server has started")
    run
  }

  override def receive: Receive = {
    case fundingTxCreated: FundingTxCreated =>
      send(fundingTxCreated.userId, fundingTxCreated)
      verifier.notifyOnReady(fundingTxCreated)

    case fundingBroadcasted: FundingBroadcasted =>
      send(fundingBroadcasted.userId, fundingBroadcasted)
      conns(fundingBroadcasted.userId) foreach cancelSocket

    case fundMessage: FundMsg =>
      // Simply relay regular messages
      // this includes subscription errors
      send(fundMessage.userId, fundMessage)

    case 'cleanup =>
      // Remove empty slots which pile up over time
      conns = conns filter { case _ \ cs => cs.nonEmpty }
  }

  def cancelSocket(conn: WebSocket): Unit = {
    conns = conns.updated(conn, conns(conn) - conn)
    conn.setAttachment(null)
    conn.close
  }

  def send(userId: UserId, message: FundMsg): Unit = for {
    // Send a message to currently connected userId sockets
    connections: WebSocketConnSet <- conns get userId
    connection: WebSocket <- connections
    text = message.toJson.toString
  } connection send text
}