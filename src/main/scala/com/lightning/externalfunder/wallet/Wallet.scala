package com.lightning.externalfunder.wallet


trait Wallet {
  import fr.acinq.bitcoin._
  import scala.concurrent.Future
  import com.lightning.externalfunder.Utils.UserId
  def rollback(userId: UserId, tx: Transaction): Future[Boolean]
  def commit(userId: UserId, tx: Transaction): Future[Transaction]
}