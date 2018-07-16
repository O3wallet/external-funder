package com.lightning.externalfunder

import fr.acinq.bitcoin.{BinaryData, Transaction}
import scala.language.implicitConversions
import org.java_websocket.WebSocket


object Utils {
  type TxWithOutIndexCacheItem = CacheItem[TxWithOutIndex]
  type WebSocketConnSet = Set[WebSocket]
  type UserId = String

  val hex2String: String => String = hex => new String(BinaryData(hex), "UTF-8")
  implicit def arg2Apply[T](argument: T): ArgumentRunner[T] = new ArgumentRunner(argument)
  class ArgumentRunner[T](wrap: T) { def >>[V](fs: (T => V)*): Seq[V] = for (fun <- fs) yield fun apply wrap }
  def extract[T](src: Map[String, String], fn: String => T, args: String*): Seq[T] = args.map(src andThen fn)
}

case class TxWithOutIndex(tx: Transaction, idx: Int)
case class CacheItem[T](data: T, stamp: Long)

case class WebsocketManagerConfig(host: String, port: Int) {
  val inetSockAddress = new java.net.InetSocketAddress(host, port)
}

case class BitcoinWalletConfig(rpc: String, maxFundingSat: Long, minFundingSat: Long,
                               deadlineMsec: Int, reserveRetriesDelayMsec: Int, reserveRetriesNum: Int)

case class EmailVerifierConfig(host: String, port: Int, subject: String, from: String,
                               password: String, okTemplate: String, failTemplate: String)