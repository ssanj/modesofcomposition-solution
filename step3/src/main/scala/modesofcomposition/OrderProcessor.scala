package modesofcomposition

import scala.collection.immutable.SortedSet

import io.chrisdavenport.cats.effect.time.JavaTime
import java.util.UUID

object OrderProcessor {

  def processCustomerOrder[F[_]: Sync: Parallel: Clock: UuidRef: Inventory: Publish](
    order: CustomerOrder): F[Unit] = {

    val nonAvailableSkus: Chain[Sku] = {
      val customerRegion = order.customer.region
      order.items.map(_.sku).filter(_.nonAvailableRegions.contains(customerRegion))
    }

    if (nonAvailableSkus.isEmpty)
      processAvailableOrder[F](order)
    else {
      for {
        currentTime <- JavaTime[F].getInstant //F[Instant]
        message     <- F.pure(Unavailable(NonEmptySet.fromSetUnsafe(SortedSet.from(nonAvailableSkus.iterator)), order, currentTime))
        _           <- F.publish(Topic.Unavailable, message.asJsonBytes)
        } yield ()
    }
  }

  //this is a no-op in step3
  def processAvailableOrder[F[_] : Functor: Sync: Parallel: Clock: UuidRef: Inventory: Publish]
    (order: CustomerOrder): F[Unit] = F.unit
}

