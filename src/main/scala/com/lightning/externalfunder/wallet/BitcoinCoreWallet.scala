package com.lightning.externalfunder.wallet

import fr.acinq.bitcoin._
import net.ceedubs.ficus.Ficus._
import scala.concurrent.duration._
import com.lightning.walletapp.ln._
import com.lightning.externalfunder.wire._
import com.lightning.externalfunder.wire.FundMsg._
import net.ceedubs.ficus.readers.ArbitraryTypeReader._

import com.lightning.externalfunder.{BitcoinWalletConfig, CacheItem, TxWithOutIndex}
import com.lightning.externalfunder.Utils.{UnsignedTxCacheItem, UserId}
import com.lightning.walletapp.ln.Tools.{errlog, log}
import scala.util.{Failure, Success, Try}

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
  private var pendingUnsignedTxs = Map.empty[UserId, UnsignedTxCacheItem]
  private var pendingSignedTxs = Map.empty[UserId, Transaction]

  private val bitcoin = new wf.bitcoin.javabitcoindrpcclient.BitcoinJSONRPCClient(rpc)
  context.system.scheduler.schedule(30.seconds, 30.seconds)(self ! System.currentTimeMillis)
  context.system.scheduler.schedule(30.seconds, reserveRetriesDelayMsec.milliseconds)(self ! 'retry)

  def receive: Receive = {
    case currentMillis: Long =>
      // Remove pending funding tries with too many attempts and send an event
      for (userId \ reserveOuts <- pendingFundingTries if reserveOuts.triesDone > reserveRetriesNum) {
        context.system.eventStream publish Fail(FAIL_COULD_NOT_RESERVE, s"Funding reservation failed", userId)
        pendingFundingTries = pendingFundingTries - userId
        verifier.notifyOnFailed(reserveOuts.start)
      }

      // Remove unsigned txs which has been pending for too long
      for (userId \ item <- pendingUnsignedTxs if item.stamp < currentMillis) {
        // Irregardless of rollback result we remove peding user data and inform them about it
        context.system.eventStream publish Fail(FAIL_RESERVE_EXPIRED, "Funding expired", userId)
        pendingUnsignedTxs = pendingUnsignedTxs - userId
        pendingSignedTxs = pendingSignedTxs - userId

        rollback(userId, item.data.tx) onComplete {
          case Failure(rollbackError) => errlog(rollbackError)
          case Success(false) => log(s"Can not rollback $item")
          case Success(true) => log(s"Rolled back an $item")
        }
      }

    case start @ Start(userId, fundingAmount, _, _) => pendingUnsignedTxs get userId match {
      case None if fundingAmount.amount < minFundingSat => sender ! Fail(FAIL_AMOUNT_TOO_SMALL, "Funding amount is too small", userId)
      case None if fundingAmount.amount > maxFundingSat => sender ! Fail(FAIL_AMOUNT_TOO_LARGE, "Funding amount is too high", userId)
      case None if pendingFundingTries contains userId => sender ! Fail(FAIL_FUNDING_PENDING, "Funding pending already", userId)
      case Some(item) if item.data.tx.txOut(item.data.idx).amount == fundingAmount => sender ! Started(start, item.stamp)
      case Some(_) => sender ! Fail(FAIL_FUNDING_EXISTS, "Other funding already present", userId)
      case None => self ! ReserveOutputs(start, triesDone = 0)
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
          val item = CacheItem(TxWithOutIndex(finder.tx, outIndex), deadline)

          pendingFundingTries = pendingFundingTries - start.userId
          pendingUnsignedTxs = pendingUnsignedTxs.updated(start.userId, item)
          context.system.eventStream publish Started(start, deadline)

        case Failure(fundingError) =>
          val reserveOuts1 = ReserveOutputs(start, triesDone = n + 1)
          // Retry a bit later and increase a limit, do not send a user error right away
          pendingFundingTries = pendingFundingTries.updated(start.userId, reserveOuts1)
          errlog(fundingError)
      }

    case SignFundingTx(userId, pubkeyScript) =>
      // Attempt to sign a stored dummy funding tx
      // crucially, we don't broadcast it right away
      signTx(userId, pubkeyScript) onComplete {

        case Success(two) =>
          val response = FundingTxSigned(userId, two.tx.hash, two.idx)
          pendingSignedTxs = pendingSignedTxs.updated(userId, two.tx)
          context.system.eventStream publish response

        case Failure(_: NoSuchElementException) =>
          // This funding has probably expired by now, inform user about it
          val response = Fail(FAIL_FUNDING_NONE, "No funding reserved", userId)
          context.system.eventStream publish response

        case Failure(signingError) =>
          // An internal error happened, log to inspect it later
          val response = Fail(FAIL_INTERNAL_ERROR, "Could not sign", userId)
          context.system.eventStream publish response
          errlog(signingError)
      }

    case BroadcastFundingTx(userId, txHash)
      if !pendingSignedTxs.get(userId).exists(_.hash == txHash) =>
      sender ! Fail(FAIL_SIGNED_NONE, "No signed tx present", userId)

    case BroadcastFundingTx(userId, _) =>
      val signedTx = pendingSignedTxs(userId)
      commit(userId, signedTx) onComplete {

        case Success(true) =>
          // We are done with this one, free all resources
          val response = FundingTxBroadcasted(userId, signedTx)
          pendingFundingTries = pendingFundingTries - userId
          pendingUnsignedTxs = pendingUnsignedTxs - userId
          pendingSignedTxs = pendingSignedTxs - userId
          context.system.eventStream publish response

        case Success(false) =>
          // Unable to publish but not an internal error, inform user about it
          val response = Fail(FAIL_INTERNAL_ERROR, "Could not publish", userId)
          context.system.eventStream publish response

        case Failure(broadcastingError) =>
          // An internal error happened, inform user, log to inspect it later
          val response = Fail(FAIL_INTERNAL_ERROR, "Could not publish", userId)
          context.system.eventStream publish response
          errlog(broadcastingError)
      }

    case 'retry =>
      // Retry until it's either a success or we run out of limit
      for (reserveOuts <- pendingFundingTries.values) self ! reserveOuts
  }

  def replacePubKeyScript(userId: UserId, pubkeyScript: BinaryData): Option[TxWithOutIndex] = for {
    CacheItem(TxWithOutIndex(Transaction(v, ins, outs, lock), idx), _) <- pendingUnsignedTxs get userId
    patchedOuts = outs.patch(idx, outs(idx).copy(publicKeyScript = pubkeyScript) :: Nil, 1)
  } yield TxWithOutIndex(Transaction(v, ins, patchedOuts, lock), idx)

  def signTx(userId: UserId, realFundingPubKeyScript: BinaryData) = Future {
    replacePubKeyScript(userId, pubkeyScript = realFundingPubKeyScript) match {
      case Some(two) => TxWithOutIndex(Transaction read bitcoin.signRawTransaction(Transaction.write(two.tx).toString), two.idx)
      case None => throw new NoSuchElementException("Requested dummy transaction has not been found in pendingUnsignedTxs")
    }
  }

  def rollback(userId: UserId, tx: Transaction): Future[Boolean] = Future {
    val bitcoinTx = bitcoin.decodeRawTransaction(Transaction.write(tx).toString)
    bitcoin.lockUnspent(bitcoinTx, true)
  }

  def commit(userId: UserId, tx: Transaction): Future[Boolean] = Future {
    bitcoin.sendRawTransaction(Transaction.write(tx).toString).nonEmpty
  }
}