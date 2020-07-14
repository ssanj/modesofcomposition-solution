package modesofcomposition

import java.nio.charset.StandardCharsets

object OrderProcessor {

  def decodeMsg[F[_]: ApplicativeError[*[_], Throwable]](msg: Array[Byte]): F[OrderMsg] =
    errorValueFromEither[F](parser.decode[OrderMsg](new String(msg, StandardCharsets.UTF_8)))
}

