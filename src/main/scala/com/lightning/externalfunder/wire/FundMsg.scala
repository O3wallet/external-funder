package com.lightning.externalfunder.wire

import spray.json._
import fr.acinq.bitcoin.{BinaryData, Satoshi, Transaction}
import com.lightning.externalfunder.Utils.UserId
import spray.json.DefaultJsonProtocol


trait FundMsg { def userId: UserId }
case class Fail(code: Int, reason: String, userId: UserId = "noUserId") extends FundMsg
case class Start(userId: UserId, fundingAmount: Satoshi, extra: Option[String] = None) extends FundMsg
case class FundingTxCreated(start: Start, expiration: Long) extends FundMsg { def userId: UserId = start.userId }
case class FundingTxAwaits(start: Start, expiration: Long) extends FundMsg { def userId: UserId = start.userId }
case class FundingTxSigned(userId: UserId, txHash: BinaryData, outIndex: Int) extends FundMsg
case class SignFundingTx(userId: UserId, pubkeyScript: BinaryData) extends FundMsg
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
      case unserialiedMessage: SignFundingTx => unserialiedMessage.toJson
      case unserialiedMessage: FundingTxSigned => unserialiedMessage.toJson
      case unserialiedMessage: BroadcastFundingTx => unserialiedMessage.toJson
      case unserialiedMessage: FundingTxBroadcasted => unserialiedMessage.toJson
      case unserialiedMessage: FundingTxCreated => unserialiedMessage.toJson
      case unserialiedMessage: FundingTxAwaits => unserialiedMessage.toJson
      case unserialiedMessage: Start => unserialiedMessage.toJson
      case unserialiedMessage: Fail => unserialiedMessage.toJson
    }

    def read(serialized: JsValue): FundMsg =
      serialized.asJsObject fields "tag" match {
        case JsString("SignFundingTx") => serialized.convertTo[SignFundingTx]
        case JsString("FundingTxSigned") => serialized.convertTo[FundingTxSigned]
        case JsString("BroadcastFundingTx") => serialized.convertTo[BroadcastFundingTx]
        case JsString("FundingTxBroadcasted") => serialized.convertTo[FundingTxBroadcasted]
        case JsString("FundingTxCreated") => serialized.convertTo[FundingTxCreated]
        case JsString("FundingTxAwaits") => serialized.convertTo[FundingTxAwaits]
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
    taggedJsonFmt(jsonFormat[UserId, Satoshi, Option[String],
      Start](Start.apply, "userId", "fundingAmount", "extra"), tag = "Start")

  implicit val fundingTxCreatedFmt: JsonFormat[FundingTxCreated] = taggedJsonFmt(jsonFormat[Start, Long,
    FundingTxCreated](FundingTxCreated.apply, "start", "expiration"), tag = "FundingTxCreated")

  implicit val fundingTxAwaitsFmt: JsonFormat[FundingTxAwaits] = taggedJsonFmt(jsonFormat[Start, Long,
    FundingTxAwaits](FundingTxAwaits.apply, "start", "expiration"), tag = "FundingTxAwaits")

  implicit val fundingTxSignedFmt: JsonFormat[FundingTxSigned] = taggedJsonFmt(jsonFormat[UserId, BinaryData, Int,
    FundingTxSigned](FundingTxSigned.apply, "userId", "txHash", "outIndex"), tag = "FundingTxSigned")

  implicit val signFundingTxFmt: JsonFormat[SignFundingTx] = taggedJsonFmt(jsonFormat[UserId, BinaryData,
    SignFundingTx](SignFundingTx.apply, "userId", "pubkeyScript"), tag = "SignFundingTx")

  implicit val broadcastFundingTxFmt: JsonFormat[BroadcastFundingTx] = taggedJsonFmt(jsonFormat[UserId, BinaryData,
    BroadcastFundingTx](BroadcastFundingTx.apply, "userId", "txHash"), tag = "BroadcastFundingTx")

  implicit val fundingTxBroadcastedFmt: JsonFormat[FundingTxBroadcasted] = taggedJsonFmt(jsonFormat[UserId, Transaction,
    FundingTxBroadcasted](FundingTxBroadcasted.apply, "userId", "tx"), tag = "FundingTxBroadcasted")
}