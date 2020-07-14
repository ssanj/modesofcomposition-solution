package modesofcomposition

object OrderProcessor {

  //resolveOrderMsg has been broken down into named parts to help understand the pieces of the computation
  def resolveOrderMsg[F[_]: Sync: Parallel: SkuLookup: CustomerLookup](msg: OrderMsg): F[CustomerOrder] =
    msg match { case OrderMsg(custIdStr, items) =>

      val resolveCustomer: F[Customer] =
        F.resolveCustomerId(custIdStr).flatMap(errorValueFromEither[F](_))

      val resolveSkuQuantity: ((String, Int)) => F[SkuQuantity] = {
        case (code, qty) =>
          (
            F.resolveSku(code).flatMap(errorValueFromEither[F](_)),
            PosInt.fromF[F](qty)
          ).parMapN(SkuQuantity)
      }

      val resolveSkus: F[NonEmptyChain[SkuQuantity]] =
        items.parTraverse(resolveSkuQuantity)

      //applicative composition
      (
        resolveCustomer,
        resolveSkus,
        ).parMapN(CustomerOrder(_, _))
    }
}

