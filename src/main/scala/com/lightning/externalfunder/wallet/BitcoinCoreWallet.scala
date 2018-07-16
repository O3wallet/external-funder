package com.lightning.externalfunder.wallet

import fr.acinq.bitcoin._
import net.ceedubs.ficus.Ficus._
import scala.concurrent.duration._
import com.lightning.walletapp.ln._
import scala.collection.JavaConverters._
import com.lightning.externalfunder.wire._
import com.lightning.externalfunder.wire.FundMsg._
import net.ceedubs.ficus.readers.ArbitraryTypeReader._

import com.lightning.externalfunder.{BitcoinWalletConfig, CacheItem, FundingInfo}
import com.lightning.externalfunder.Utils.{FundingInfoCacheItem, UserId}
import com.lightning.walletapp.ln.Tools.{errlog, log}
import scala.util.{Failure, Success, Try}

import wf.bitcoin.javabitcoindrpcclient.BitcoindRpcClient.RawTransaction
import com.lightning.externalfunder.websocket.WebsocketVerifier
import scala.concurrent.ExecutionContext.Implicits.global
import com.lightning.walletapp.ln.Scripts.multiSig2of2
import com.lightning.walletapp.ln.Tools.randomPrivKey
import com.typesafe.config.ConfigFactory
import scala.concurrent.Future
import akka.actor.Actor


class BitcoinCoreWallet(verifier: WebsocketVerifier) extends Actor with Wallet {
  val BitcoinWalletConfig(rpc, maxFundingSat, minFundingSat, deadlineMsec, reserveRetriesDelayMsec,
    reserveRetriesNum) = ConfigFactory.parseResources("bitcoinCoreWallet.conf") as[BitcoinWalletConfig] "config"

  case class ReserveOutputs(start: Start, triesDone: Int)
  private var pendingFundingTries = Map.empty[UserId, ReserveOutputs]
  private var pendingDummyTxs = Map.empty[UserId, FundingInfoCacheItem]
  private var pendingPreparedTxs = Map.empty[UserId, Transaction]

  private val bitcoin = new wf.bitcoin.javabitcoindrpcclient.BitcoinJSONRPCClient(rpc)
  context.system.scheduler.schedule(30.seconds, 30.seconds)(self ! System.currentTimeMillis)
  context.system.scheduler.schedule(30.seconds, reserveRetriesDelayMsec.milliseconds)(self ! 'retry)

  def receive: Receive = {
    case start @ Start(userId, funding, _, _, _) => pendingDummyTxs get userId match {
      case None if funding.amount > maxFundingSat => sender ! Fail(FAIL_AMOUNT_TOO_LARGE, "Funding amount is too high", userId)
      case None if funding.amount < minFundingSat => sender ! Fail(FAIL_AMOUNT_TOO_SMALL, "Funding amount is too small", userId)
      case None if pendingFundingTries contains userId => sender ! Fail(FAIL_FUNDING_PENDING, "Funding pending already", userId)
      case Some(item) if item.data.tx.txOut(item.data.idx).amount == funding => sender ! Started(start, item.stamp, item.data.fee)
      case Some(_) => sender ! Fail(FAIL_FUNDING_EXISTS, "Other funding already present", userId)
      case None => self ! ReserveOutputs(start, triesDone = 0)
    }

    case currentMillis: Long =>
      // Remove pending funding tries with too many attempts and send an event
      for (userId \ reserveOuts <- pendingFundingTries if reserveOuts.triesDone > reserveRetriesNum) {
        context.system.eventStream publish Fail(FAIL_RESERVE_FAILED, s"Funding reservation failed", userId)
        pendingFundingTries = pendingFundingTries - userId
        verifier.notifyOnFailed(reserveOuts.start)
      }

      // Remove unsigned txs which has been pending for too long
      for (userId \ item <- pendingDummyTxs if item.stamp < currentMillis) {
        // Irregardless of rollback result we remove peding user data and inform user about it
        context.system.eventStream publish Fail(FAIL_RESERVE_EXPIRED, "Funding expired", userId)
        pendingPreparedTxs = pendingPreparedTxs - userId
        pendingDummyTxs = pendingDummyTxs - userId

        rollback(userId, item.data.tx) onComplete {
          case Failure(rollbackError) => errlog(rollbackError)
          case Success(false) => log(s"Can not rollback $item")
          case Success(true) => log(s"Rolled back an $item")
        }
      }

    case ReserveOutputs(start, n) =>
      // Make a reservation by creating a dummy tx with locked outputs
      val dummyMultisigScript = multiSig2of2(randomPrivKey.publicKey, randomPrivKey.publicKey)
      val out = TxOut(publicKeyScript = Script.write(Script pay2wsh dummyMultisigScript), amount = start.fundingAmount)
      val hex = Transaction write Transaction(version = 2, txIn = Seq.empty[TxIn], txOut = out :: Nil, lockTime = 0)

      Try(bitcoin fundRawTransaction hex.toString) match {
        // Note that funding may add outputs and mix them up
        // So we'll find out dummy output with reserved sum

        case Success(rawTx) =>
          val deadline = System.currentTimeMillis + deadlineMsec
          val finder = new PubKeyScriptIndexFinder(Transaction read rawTx)
          val outIndex = finder.findPubKeyScriptIndex(out.publicKeyScript)
          val info = FundingInfo(finder.tx, getFee(rawTx), outIndex)
          val item = CacheItem(info, deadline)

          pendingFundingTries = pendingFundingTries - start.userId
          pendingDummyTxs = pendingDummyTxs.updated(start.userId, item)
          context.system.eventStream publish Started(start, deadline, info.fee)

        case Failure(fundingError) =>
          val reserveOuts1 = ReserveOutputs(start, triesDone = n + 1)
          // Retry a bit later and increase a limit, do not send a user error right away
          pendingFundingTries = pendingFundingTries.updated(start.userId, reserveOuts1)
          errlog(fundingError)
      }

    case PrepareFundingTx(userId, pubkeyScript) =>
      // Attempt to update a stored dummy funding tx
      replacePubKeyScript(userId, pubkeyScript) match {

        case Some(two1) =>
          val response = FundingTxReady(userId, two1.tx.hash, two1.idx)
          pendingPreparedTxs = pendingPreparedTxs.updated(userId, two1.tx)
          context.system.eventStream publish response

        case None =>
          // This funding has probably expired by now, inform user about it
          val response = Fail(FAIL_RESERVE_EXPIRED, "No funding reserved", userId)
          context.system.eventStream publish response
      }

    case BroadcastFundingTx(userId, txHash)
      // Make sure it exists AND also has a correct hash
      if !pendingPreparedTxs.values.exists(tx => txHash == tx.hash) =>
      val response = Fail(FAIL_RESERVE_EXPIRED, "No prepared tx present", userId)
      context.system.eventStream publish response

    case BroadcastFundingTx(userId, _) =>
      // Tx is guaranteed to be present here
      val readyTx = pendingPreparedTxs(userId)

      commit(userId, readyTx) onComplete {
        case Success(signedBroadcastedTx) =>
          // We are done here, free all the resources
          val ok = FundingTxBroadcasted(userId, signedBroadcastedTx)
          pendingFundingTries = pendingFundingTries - userId
          pendingPreparedTxs = pendingPreparedTxs - userId
          pendingDummyTxs = pendingDummyTxs - userId
          context.system.eventStream publish ok

        case Failure(broadcastError) =>
          // An internal error happened, inform user, log to inspect it later
          val response = Fail(FAIL_PUBLISH_ERROR, "Could not broadcast", userId)
          context.system.eventStream publish response
          errlog(broadcastError)
      }

    case 'retry =>
      // Retry until it's either a success or we run out of limit
      for (reserveOuts <- pendingFundingTries.values) self ! reserveOuts
  }

  private def getFee(raw: String) = {
    val native: RawTransaction = bitcoin decodeRawTransaction raw
    val outSum = native.vIn.asScala.map(_.getTransactionOutput.value).sum
    val inSum = native.vOut.asScala.map(_.value).sum
    val fee = (outSum - inSum) * 100000000L
    Satoshi(fee.toLong)
  }

  def replacePubKeyScript(userId: UserId, realPubkeyScript: BinaryData): Option[FundingInfo] = for {
    CacheItem(FundingInfo(Transaction(v, ins, outs, lock), fee, idx), _) <- pendingDummyTxs get userId
    patchedOuts = outs.patch(idx, outs(idx).copy(publicKeyScript = realPubkeyScript) :: Nil, 1)
  } yield FundingInfo(Transaction(v, ins, patchedOuts, lock), fee, idx)

  def rollback(userId: UserId, tx: Transaction): Future[Boolean] = Future {
    val bitcoinTx = bitcoin decodeRawTransaction Transaction.write(tx).toString
    bitcoin.lockUnspent(bitcoinTx, true)
  }

  def commit(userId: UserId, tx: Transaction): Future[Transaction] = Future {
    val signedRawTx = bitcoin signRawTransaction Transaction.write(tx).toString
    val isPublished = bitcoin.sendRawTransaction(signedRawTx) == tx.txid.toString
    if (isPublished) tx else throw new Exception("Could not broadcast")
  }
}