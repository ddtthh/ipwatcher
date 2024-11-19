package ipwatcher

import cats.*
import cats.implicits.*
import cats.effect.*
import fs2.*
import cats.effect.testing.scalatest.AsyncIOSpec
import org.scalatest.matchers.must.Matchers
import org.scalatest.freespec.AsyncFreeSpec
import scala.concurrent.duration.*
import cats.effect.testkit.TestControl
import cats.effect.std.Queue
import Util.flatMapCancel

class UtilSpec extends AsyncFreeSpec with AsyncIOSpec with Matchers:
  "mapEvalCancel" in:
    TestControl.executeEmbed:
      List.from(0 until 1000).traverse: _ =>
        (Queue.unbounded[IO, Option[(FiniteDuration, String)]], Queue.unbounded[IO, Option[String]]).flatMapN: (input, output) =>
          Stream.fromQueueNoneTerminated(input).flatMapCancel: (delay, value) =>
            Stream.eval(IO.sleep(delay).onCancel(output.offer(Some(s"canceled $value"))).as(value))
          .enqueueNoneTerminated(output).compile.drain.background.use: _ =>
            input.offer(Some((999.millis, "a"))) tick
              input.offer(Some((3.seconds, "b"))) tick
              input.offer(Some((2.seconds, "c"))) tick
              input.offer(Some((1.seconds, "d"))) tick
              IO.unit tick
              input.offer(Some((0.seconds, "e"))) tick
              input.offer(Some((999.millis, "f"))) tick
              input.offer(Some((2.seconds, "g"))) tick
              input.offer(None)
              >> Stream.fromQueueNoneTerminated(output).compile.toList.asserting: result =>
                result mustBe List("a", "canceled b", "canceled c", "d", "e", "f", "g")
    .as(())

  extension [A](self: IO[A])
    infix def tick[B](other: IO[B]): IO[B] = self >> IO.sleep(1.second) >> other
