package modesofcomposition

import io.chrisdavenport.cats.effect.time.JavaTime

import scala.collection.immutable.SortedSet

object OrderProcessor {

  /** Delegates to dispatchElseBackorder to determine whether the order can be dispatched, then publishes
   * the appropriate message. If   */
  def processAvailableOrder[F[_]: Sync: Parallel: Clock: UuidRef: Inventory: Publish]
  (order: CustomerOrder): F[Unit] = {
    dispatchElseBackorder[F](order).flatMap {
      case Left((backorder, ordered)) =>
        F.publish(Topic.Backorder, backorder.asJsonBytes) >> ordered.parTraverse_(F.inventoryPut(_))
      case Right(dispatched) => F.publish(Topic.Dispatch, dispatched.asJsonBytes)
    }
  }

  /** Key order business logic: try to take all ordered items from inventory. If all are in stock,
   * the order is dispatched. If any have insufficient stock, then the order wont proceed: return all items
   * to inventory and raise a backorder. */
  def dispatchElseBackorder[F[_]: Sync: Parallel: Clock: UuidRef: Inventory](order: CustomerOrder):
  F[Either[(Backorder, Chain[SkuQuantity]), Dispatched]] = {
      val orderResultsF = order.items.parTraverse(item => F.inventoryTake(item)) //F[NEC[Either[InsufficientStock, SkuQuantity]]]
      orderResultsF.flatMap { orderResults =>
        insufficientsAndTaken(orderResults) match {
          case Some((insufficient, sufficient)) => backorder[F](insufficient, order).map(bo => Left[(Backorder, Chain[SkuQuantity]), Dispatched](bo, sufficient))
          case None => dispatch[F](order).map(d => Right[(Backorder, Chain[SkuQuantity]), Dispatched](d))
        }
      }
  }

  /** Generate a backorder by calculating the shortfall in stock to satisfy order */
  def backorder[F[_]: Sync: Clock]
  (insufficientStocks: NonEmptyChain[InsufficientStock], order: CustomerOrder):
  F[Backorder] = {
    for {
      currentTime <- JavaTime[F].getInstant
      shortFalls <- insufficientStocks.traverse {
        case InsufficientStock(SkuQuantity(sku, quantity), amount) =>
          PosInt.fromF[F](quantity - amount).map(SkuQuantity(sku, _)) //F[NonEmptyChain[Refined[Int, PosInt]]]
      }
    } yield Backorder(shortFalls, order, currentTime)
}

  /** generate a dispatch combining the order, a timestap and UUID */
  def dispatch[F[_]: Sync: Clock: UuidRef](order: CustomerOrder): F[Dispatched] = {
    (JavaTime[F].getInstant, UuidSeed.nextUuid[F]).mapN(Dispatched(order, _, _))
  }

  /** Transform a collection of inventory.take outcomes into details of a possible shortfall:
   * which items had insufficient stock, and which items were actually taken (since they need to be returned) */
  def insufficientsAndTaken(takes: NonEmptyChain[Either[InsufficientStock, SkuQuantity]]):
  Option[(NonEmptyChain[InsufficientStock], Chain[SkuQuantity])] = {
    val (insufficient, sufficient) = takes.toChain.separate
    NonEmptyChain.fromChain(insufficient).map((_, sufficient))
  }

}

