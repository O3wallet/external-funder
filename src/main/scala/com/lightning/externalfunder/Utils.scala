package com.lightning.externalfunder

import fr.acinq.bitcoin.{BinaryData, Satoshi, Transaction}
import scala.language.implicitConversions
import org.java_websocket.WebSocket


object Utils {
  type UserId = String
  type WebSocketConnSet = Set[WebSocket]
  type FundingInfoCacheItem = CacheItem[FundingInfo]
  val datadir: String = System.getProperty("ef.datadir", System.getProperty("user.home") + "/.ef")
  val string2Hex: String => String = string => BinaryData(string getBytes "UTF-8").toString
  val hex2String: String => String = hex => new String(BinaryData(hex).toArray, "UTF-8")
}

case class CacheItem[T](data: T, stamp: Long)
case class FundingInfo(tx: Transaction, fee: Satoshi, idx: Int)

case class WebsocketManagerConfig(host: String, port: Int)
case class BitcoinWalletConfig(rpc: String, maxFundingSat: Long, minFundingSat: Long,
                               deadlineMsec: Int, reserveRetriesDelayMsec: Int, reserveRetriesNum: Int)

case class EmailVerifierConfig(host: String, port: Int, subject: String, from: String,
                               password: String, okTemplate: String, failTemplate: String)