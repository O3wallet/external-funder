package com.lightning.walletapp.ln

import fr.acinq.bitcoin.Crypto.PrivateKey
import language.implicitConversions
import scala.collection.mutable
import crypto.RandomGenerator


object \ {
  // Matching Tuple2 via arrows with much less noise
  def unapply[A, B](t2: (A, B) /* Got a tuple */) = Some(t2)
}

object Tools {
  type Bytes = Array[Byte]
  val random = new RandomGenerator
  def runAnd[T](result: T)(action: Any): T = result
  def log(normalMessage: String): Unit = println("ExternalFunder", normalMessage)
  def randomPrivKey = PrivateKey(random getBytes 32, compressed = true)
  def none: PartialFunction[Any, Unit] = { case _ => }

  def errlog: PartialFunction[Throwable, Unit] = {
    case err => println("ExternalFunder error", err.getMessage)
  }
}