package com.lightning.externalfunder.wire

import spray.json._
import fr.acinq.bitcoin.{BinaryData, Satoshi, Transaction}
import com.lightning.externalfunder.Utils.UserId
import spray.json.DefaultJsonProtocol


object FundMsg {
  val FAIL_VERIFY_ERROR = 101
  val FAIL_NOT_VERIFIED_YET = 102
  val FAIL_INTERNAL_ERROR = 301

  val FAIL_RESERVE_FAILED = 201
  val FAIL_RESERVE_EXPIRED = 202
  val FAIL_AMOUNT_TOO_LARGE = 203
  val FAIL_AMOUNT_TOO_SMALL = 204
  val FAIL_FUNDING_PENDING = 205
  val FAIL_FUNDING_EXISTS = 206
  val FAIL_PUBLISH_ERROR = 207
}

// Setup
trait FundMsg { def userId: UserId }
case class Fail(code: Int, reason: String, userId: UserId = "noUserId") extends FundMsg
case class Started(start: Start, expiry: Long, fee: Satoshi) extends FundMsg { def userId: UserId = start.userId }
case class Start(userId: UserId, fundingAmount: Satoshi, host: String, port: Int, extra: Option[String] = None) extends FundMsg

// Switching remote peers
case class PrepareFundingTx(userId: UserId, pubkeyScript: BinaryData) extends FundMsg
case class FundingTxReady(userId: UserId, txHash: BinaryData, outIndex: Int) extends FundMsg

// Finalizing
case class BroadcastFundingTx(userId: UserId, txHash: BinaryData) extends FundMsg
case class FundingTxBroadcasted(userId: UserId, tx: Transaction) extends FundMsg


object ImplicitJsonFormats extends DefaultJsonProtocol { me =>
  def taggedJsonFmt[T](base: JsonFormat[T], tag: String): JsonFormat[T] = new JsonFormat[T] {
    def write(unserialized: T): JsValue = JsObject(base.write(unserialized).asJsObject.fields + extension)
    def read(serialized: JsValue): T = base read serialized
    private val extension = "tag" -> JsString(tag)
  }

  implicit object JsonMessageFmt extends JsonFormat[FundMsg] {
    def write(unserialized: FundMsg): JsValue = unserialized match {
      case unserialiedMessage: FundingTxReady => unserialiedMessage.toJson
      case unserialiedMessage: PrepareFundingTx => unserialiedMessage.toJson
      case unserialiedMessage: BroadcastFundingTx => unserialiedMessage.toJson
      case unserialiedMessage: FundingTxBroadcasted => unserialiedMessage.toJson
      case unserialiedMessage: Started => unserialiedMessage.toJson
      case unserialiedMessage: Start => unserialiedMessage.toJson
      case unserialiedMessage: Fail => unserialiedMessage.toJson
    }

    def read(serialized: JsValue): FundMsg =
      serialized.asJsObject fields "tag" match {
        case JsString("FundingTxReady") => serialized.convertTo[FundingTxReady]
        case JsString("PrepareFundingTx") => serialized.convertTo[PrepareFundingTx]
        case JsString("BroadcastFundingTx") => serialized.convertTo[BroadcastFundingTx]
        case JsString("FundingTxBroadcasted") => serialized.convertTo[FundingTxBroadcasted]
        case JsString("Started") => serialized.convertTo[Started]
        case JsString("Start") => serialized.convertTo[Start]
        case JsString("Fail") => serialized.convertTo[Fail]
        case _ => throw new RuntimeException
      }
  }

  val json2String: JsValue => String = _.convertTo[String]
  implicit object BinaryDataFmt extends JsonFormat[BinaryData] {
    def read(json: JsValue): BinaryData = BinaryData(me json2String json)
    def write(internal: BinaryData): JsValue = internal.toString.toJson
  }

  implicit object TransactionFmt extends JsonFormat[Transaction] {
    def read(json: JsValue): Transaction = Transaction.read(me json2String json)
    def write(internal: Transaction): JsValue = Transaction.write(internal).toString.toJson
  }

  implicit val satoshiFmt: RootJsonFormat[Satoshi] =
    jsonFormat[Long, Satoshi](Satoshi.apply, "amount")

  implicit val failFmt: JsonFormat[Fail] =
    taggedJsonFmt(jsonFormat[Int, String, String,
      Fail](Fail.apply, "userId", "code", "reason"), tag = "Fail")

  implicit val startFmt: JsonFormat[Start] =
    taggedJsonFmt(jsonFormat[UserId, Satoshi, String, Int, Option[String],
      Start](Start.apply, "userId", "fundingAmount", "host", "port", "extra"), tag = "Start")

  implicit val prepareFundingTxFmt: JsonFormat[PrepareFundingTx] = taggedJsonFmt(jsonFormat[UserId, BinaryData,
    PrepareFundingTx](PrepareFundingTx.apply, "userId", "pubkeyScript"), tag = "PrepareFundingTx")

  implicit val fundingTxReadyFmt: JsonFormat[FundingTxReady] = taggedJsonFmt(jsonFormat[UserId, BinaryData, Int,
    FundingTxReady](FundingTxReady.apply, "userId", "txHash", "outIndex"), tag = "FundingTxReady")

  implicit val broadcastFundingTxFmt: JsonFormat[BroadcastFundingTx] = taggedJsonFmt(jsonFormat[UserId, BinaryData,
    BroadcastFundingTx](BroadcastFundingTx.apply, "userId", "txHash"), tag = "BroadcastFundingTx")

  implicit val fundingTxBroadcastedFmt: JsonFormat[FundingTxBroadcasted] = taggedJsonFmt(jsonFormat[UserId, Transaction,
    FundingTxBroadcasted](FundingTxBroadcasted.apply, "userId", "tx"), tag = "FundingTxBroadcasted")

  implicit val startedFmt: JsonFormat[Started] = taggedJsonFmt(jsonFormat[Start, Long, Satoshi,
    Started](Started.apply, "start", "expiry", "fee"), tag = "Started")
}