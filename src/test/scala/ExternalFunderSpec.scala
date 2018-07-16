import java.util

import com.lightning.externalfunder.Supervisor
import com.lightning.externalfunder.wire._
import com.neovisionaries.ws.client.{WebSocket, WebSocketAdapter, WebSocketFactory}
import fr.acinq.bitcoin.{BinaryData, Satoshi, Script}
import org.scalatest.FunSuite
import spray.json._
import com.lightning.externalfunder.wire.ImplicitJsonFormats._
import com.lightning.walletapp.ln.Scripts.multiSig2of2
import com.lightning.walletapp.ln.Tools.randomPrivKey
    
class ExternalFunderSpec extends FunSuite {

  import akka.actor.{ActorSystem, Props}
  val system = ActorSystem("funding-system")
  val supervisorClass: Props = Props create classOf[Supervisor]
  system actorOf supervisorClass


  test("ExternalFunderSpec") {
    val userId = "user-1"
    val start = Start(userId, Satoshi(100000L), "ws://127.0.0.1:9001", Some("anton.kumaigorodskiy@outlook.com"))
    val started = Started(start, System.currentTimeMillis + 300000)

    val url = start.url + "/" + BinaryData(start.toJson.toString getBytes "UTF-8").toString
    val ws: WebSocket = (new WebSocketFactory).createSocket(url, 7500)

    ws addListener new WebSocketAdapter {
      override def onConnected(websocket: WebSocket, headers: util.Map[String, util.List[String]]): Unit = println("CONNECTED")
      override def onTextMessage(ws: WebSocket, message: String): Unit = {
        val msg = message.parseJson.convertTo[FundMsg]

        msg match {
          case started: Started =>
            println(s"GOT A STARTED MESSAGE: $started")
            Thread.sleep(2000L)

            val dummyMultisigScript = multiSig2of2(randomPrivKey.publicKey, randomPrivKey.publicKey)
            val publicKeyScript = Script.write(Script pay2wsh dummyMultisigScript)
            val sign = PrepareFundingTx(userId, publicKeyScript)
            ws.sendText(sign.toJson.toString)

          case ready: FundingTxReady =>
            println(s"GOT A READY MESSAGE: $ready")
            Thread.sleep(2000L)

            val broadcast = BroadcastFundingTx(userId, ready.txHash)
            ws.sendText(broadcast.toJson.toString)

          case other =>
            println(s"GOT $other")
        }
      }
    }

    ws.connect
    Thread.sleep(1000000L)
  }
}
