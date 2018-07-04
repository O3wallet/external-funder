package com.lightning.walletapp.ln

import fr.acinq.bitcoin._
import fr.acinq.bitcoin.Crypto.PublicKey


object Scripts {
  type ScriptEltSeq = Seq[ScriptElt]
  def multiSig2of2(pubkey1: PublicKey, pubkey2: PublicKey): ScriptEltSeq =
    LexicographicalOrdering.isLessThan(pubkey1.toBin, pubkey2.toBin) match {
      case false => Script.createMultiSigMofN(m = 2, pubkey2 :: pubkey1 :: Nil)
      case true => Script.createMultiSigMofN(m = 2, pubkey1 :: pubkey2 :: Nil)
    }
}

class PubKeyScriptIndexFinder(val tx: Transaction) {
  private[this] var indexesAlreadyUsed = Set.empty[Long]
  private[this] val indexedOutputs = tx.txOut.zipWithIndex

  def findPubKeyScriptIndex(pubkeyScript: BinaryData, amountOpt: Option[Satoshi] = None): Int = {
    // It is never enough to resolve on pubkeyScript alone because we may have duplicate HTLC payments
    // hence we collect an already used output indexes and make sure payment sums are matched in some cases

    val index = indexedOutputs indexWhere { case out \ idx =>
      val isOutputUsedAlready = indexesAlreadyUsed contains idx
      val amountMatches = amountOpt.forall(_ == out.amount)
      val scriptOk = out.publicKeyScript == pubkeyScript
      !isOutputUsedAlready & amountMatches & scriptOk
    }

    if (index < 0) throw new Exception
    indexesAlreadyUsed += index
    index
  }
}