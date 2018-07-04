package com.lightning.externalfunder.wire

import spray.json._
import fr.acinq.bitcoin.{BinaryData, Satoshi, Transaction}
import com.lightning.externalfunder.Utils.UserId
import spray.json.DefaultJsonProtocol


trait FundMsg { def userId: UserId }
case class Error(code: Int, reason: String, userId: UserId = "noUserId") extends FundMsg
case class Init(userId: UserId, fundingAmount: Satoshi, extra: Option[String] = None) extends FundMsg
case class FundingTxCreated(init: Init, expiration: Long) extends FundMsg { def userId: UserId = init.userId }
case class FundingTxAwaits(init: Init, expiration: Long) extends FundMsg { def userId: UserId = init.userId }
case class FundingSigned(userId: UserId, txHash: BinaryData, outIndex: Int) extends FundMsg
case class SignFunding(userId: UserId, pubkeyScript: BinaryData) extends FundMsg
case class BroadcastFunding(userId: UserId, txHash: BinaryData) extends FundMsg
case class FundingBroadcasted(userId: UserId, tx: Transaction) extends FundMsg


object ImplicitJsonFormats extends DefaultJsonProtocol { me =>
  def taggedJsonFmt[T](base: JsonFormat[T], tag: String): JsonFormat[T] = new JsonFormat[T] {
    def write(unserialized: T): JsValue = JsObject(base.write(unserialized).asJsObject.fields + extension)
    def read(serialized: JsValue): T = base read serialized
    private val extension = "tag" -> JsString(tag)
  }

  implicit object JsonMessageFmt extends JsonFormat[FundMsg] {
    def write(unserialized: FundMsg): JsValue = unserialized match {
      case unserialiedMessage: SignFunding => unserialiedMessage.toJson
      case unserialiedMessage: FundingSigned => unserialiedMessage.toJson
      case unserialiedMessage: BroadcastFunding => unserialiedMessage.toJson
      case unserialiedMessage: FundingBroadcasted => unserialiedMessage.toJson
      case unserialiedMessage: FundingTxCreated => unserialiedMessage.toJson
      case unserialiedMessage: FundingTxAwaits => unserialiedMessage.toJson
      case unserialiedMessage: Error => unserialiedMessage.toJson
      case unserialiedMessage: Init => unserialiedMessage.toJson
    }

    def read(serialized: JsValue): FundMsg =
      serialized.asJsObject fields "tag" match {
        case JsString("SignFunding") => serialized.convertTo[SignFunding]
        case JsString("FundingSigned") => serialized.convertTo[FundingSigned]
        case JsString("BroadcastFunding") => serialized.convertTo[BroadcastFunding]
        case JsString("FundingBroadcasted") => serialized.convertTo[FundingBroadcasted]
        case JsString("FundingTxCreated") => serialized.convertTo[FundingTxCreated]
        case JsString("FundingTxAwaits") => serialized.convertTo[FundingTxAwaits]
        case JsString("Error") => serialized.convertTo[Error]
        case JsString("Init") => serialized.convertTo[Init]
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

  implicit val errorFmt: JsonFormat[Error] =
    taggedJsonFmt(jsonFormat[Int, String, String,
      Error](Error.apply, "userId", "code", "reason"), tag = "Error")

  implicit val initFmt: JsonFormat[Init] =
    taggedJsonFmt(jsonFormat[UserId, Satoshi, Option[String],
      Init](Init.apply, "userId", "fundingAmount", "extra"), tag = "Init")

  implicit val fundingTxCreatedFmt: JsonFormat[FundingTxCreated] = taggedJsonFmt(jsonFormat[Init, Long,
    FundingTxCreated](FundingTxCreated.apply, "init", "expiration"), tag = "FundingTxCreated")

  implicit val fundingTxAwaitsFmt: JsonFormat[FundingTxAwaits] = taggedJsonFmt(jsonFormat[Init, Long,
    FundingTxAwaits](FundingTxAwaits.apply, "init", "expiration"), tag = "FundingTxAwaits")

  implicit val fundingSignedFmt: JsonFormat[FundingSigned] = taggedJsonFmt(jsonFormat[UserId, BinaryData, Int,
    FundingSigned](FundingSigned.apply, "userId", "txHash", "outIndex"), tag = "FundingSigned")

  implicit val signFundingFmt: JsonFormat[SignFunding] = taggedJsonFmt(jsonFormat[UserId, BinaryData,
    SignFunding](SignFunding.apply, "userId", "pubkeyScript"), tag = "SignFunding")

  implicit val broadcastFundingFmt: JsonFormat[BroadcastFunding] = taggedJsonFmt(jsonFormat[UserId, BinaryData,
    BroadcastFunding](BroadcastFunding.apply, "userId", "txHash"), tag = "BroadcastFunding")

  implicit val fundingBroadcastedFmt: JsonFormat[FundingBroadcasted] = taggedJsonFmt(jsonFormat[UserId, Transaction,
    FundingBroadcasted](FundingBroadcasted.apply, "userId", "tx"), tag = "FundingBroadcasted")
}