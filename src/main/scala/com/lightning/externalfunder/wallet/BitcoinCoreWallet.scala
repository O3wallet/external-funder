package com.lightning.externalfunder.wallet

import fr.acinq.bitcoin._
import net.ceedubs.ficus.Ficus._
import scala.concurrent.duration._
import com.lightning.walletapp.ln._
import com.lightning.externalfunder.wire._
import net.ceedubs.ficus.readers.ArbitraryTypeReader._
import com.lightning.externalfunder.Utils.{UnsignedTxCacheItem, UserId}
import com.lightning.externalfunder.{BitcoinWalletConfig, CacheItem}
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
  val BitcoinWalletConfig(rpc, maxFundingSat, deadlineMsec, reserveRetriesDelayMsec, reserveRetriesNum) =
    ConfigFactory.parseResources("bitcoinCoreWallet.conf") as[BitcoinWalletConfig] "config"

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
        context.system.eventStream publish Fail(201, s"Failed after $reserveRetriesNum attempts", userId)
        pendingFundingTries = pendingFundingTries - userId
        verifier.notifyOnFailed(reserveOuts.start)
      }

      // Remove unsigned txs which has been pending for too long and send an event
      for (userId \ CacheItem(tx, stamp) <- pendingUnsignedTxs if stamp < currentMillis) rollback(userId, tx) onComplete {
        // Must be very careful here since improper use may result in UTXO deadlock, log every error type to inspect it later

        case Failure(rollbackError) => errlog(rollbackError)
        case Success(false) => log(s"Can not rollback $tx")

        case Success(true) =>
          // Only remove a related funding txs if used UTXO indeed was unlocked
          context.system.eventStream publish Fail(202, "Funding expired", userId)
          pendingUnsignedTxs = pendingUnsignedTxs - userId
          pendingSignedTxs = pendingSignedTxs - userId
      }

    case start @ Start(userId, sum, _) =>
      pendingUnsignedTxs get userId match {
        case None if sum.amount > maxFundingSat => sender ! Fail(204, "Max funding amount is exceeded", userId)
        case None if pendingFundingTries contains userId => sender ! Fail(203, "Reservation pending already", userId)
        case Some(item) if item.data.txOut.head.amount == sum => sender ! FundingTxAwaits(start, item.stamp)
        case Some(_) => sender ! Fail(204, "Other funding already present", userId)
        case None => self ! ReserveOutputs(start, triesDone = 0)
      }

    case ReserveOutputs(start, n) =>
      // Make a reservation by creating a dummy tx with locked outputs
      val hex = Transaction write dummyTransaction(start.fundingAmount)
      Try(bitcoin fundRawTransaction hex.toString) match {

        case Success(rawTx) =>
          val deadline = System.currentTimeMillis + deadlineMsec
          val item = CacheItem(data = Transaction read rawTx, deadline)
          pendingUnsignedTxs = pendingUnsignedTxs.updated(start.userId, item)
          sender ! FundingTxCreated(start, deadline)

        case Failure(fundingError) =>
          val reserveOuts1 = ReserveOutputs(start, triesDone = n + 1)
          // Probably no free outputs, retry a bit later and increase a limit
          pendingFundingTries = pendingFundingTries.updated(start.userId, reserveOuts1)
          errlog(fundingError)
      }

    case SignFundingTx(userId, pubkeyScript) =>
      // Attempt to sign a stored dummy funding tx
      // crucially, we don't broadcast it right away
      signTx(userId, pubkeyScript) onComplete {

        case Success(tx) =>
          val finder = new PubKeyScriptIndexFinder(tx)
          val outIndex = finder.findPubKeyScriptIndex(pubkeyScript)
          pendingSignedTxs = pendingSignedTxs.updated(userId, tx)
          sender ! FundingTxSigned(userId, tx.hash, outIndex)

        case Failure(_: NoSuchElementException) =>
          // This funding has probably expired, inform user
          sender ! Fail(205, "No funding reserved", userId)

        case Failure(signingError) =>
          // An internal error happened, log to inspect
          sender ! Fail(206, "Could not sign", userId)
          errlog(signingError)
      }

    case BroadcastFundingTx(userId, txHash)
      if !pendingSignedTxs.get(userId).exists(_.hash == txHash) =>
      sender ! Fail(207, "No signed funding tx present", userId)

    case BroadcastFundingTx(userId, _) =>
      val signedTx = pendingSignedTxs(userId)
      commit(userId, signedTx) onComplete {

        case Success(true) =>
          sender ! FundingTxBroadcasted(userId, signedTx)
          // We are done with this one, free all resources
          pendingFundingTries = pendingFundingTries - userId
          pendingUnsignedTxs = pendingUnsignedTxs - userId
          pendingSignedTxs = pendingSignedTxs - userId

        case Success(false) =>
          // Unable to publish but not an internal error
          sender ! Fail(208, "Could not publish", userId)

        case Failure(broadcastingError) =>
          // An internal error happened, log to inspect
          sender ! Fail(208, "Could not publish", userId)
          errlog(broadcastingError)
      }

    case 'retry =>
      // Retry until it's either a success or we run out of limit
      for (reserveOuts <- pendingFundingTries.values) self ! reserveOuts
  }

  def dummyTransaction(sum: Satoshi): Transaction = {
    val dummyMultisigScript = multiSig2of2(randomPrivKey.publicKey, randomPrivKey.publicKey)
    val out = TxOut(publicKeyScript = Script.write(Script pay2wsh dummyMultisigScript), amount = sum)
    Transaction(version = 2, Seq.empty[TxIn], out :: Nil, lockTime = 0)
  }

  def replacePubKeyScript(userId: UserId, pubkeyScript: BinaryData): Option[Transaction] = for {
    CacheItem(Transaction(v, txIn, TxOut(sum, _) :: Nil, 0), lock) <- pendingUnsignedTxs get userId
  } yield Transaction(v, txIn, TxOut(sum, pubkeyScript) :: Nil, lock)

  def signTx(userId: UserId, realFundingPubKeyScript: BinaryData) = Future {
    replacePubKeyScript(userId, pubkeyScript = realFundingPubKeyScript) match {
      // It looks for unsigned funding tx and throws a specific exception if it was not found
      case Some(tx) => Transaction read bitcoin.signRawTransaction(Transaction.write(tx).toString)
      case None => throw new NoSuchElementException("Requested dummy transaction was not found")
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