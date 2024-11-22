package ipwatcher

import scala.annotation.tailrec
import scala.collection.mutable.ListBuffer

import cats.*
import cats.implicits.*
import cats.effect.*
import cats.effect.implicits.*
import fs2.*
import cats.effect.std.Queue

object Util:
  def splitArgs(s: String): List[String] =
    @tailrec
    def rec(i: Int, args: ListBuffer[String], current: Option[(StringBuffer, Boolean, Option[Char])]): List[String] =
      if i < s.length then
        current match
          case None =>
            if s(i).isSpaceChar then
              rec(i + 1, args, None)
            else if s(i) == '"' then
              rec(i + 1, args, Some((StringBuffer(), false, Some('"'))))
            else if s(i) == '\'' then
              rec(i + 1, args, Some((StringBuffer(), false, Some('\''))))
            else if s(i) == '\\' then
              rec(i + 1, args, Some((StringBuffer(), true, None)))
            else
              rec(i + 1, args, Some((StringBuffer(s(i).toString), false, None)))
          case Some((current, true, quote)) =>
            current.append(s(i))
            rec(i + 1, args, Some((current, false, quote)))
          case Some((current, false, None)) =>
            if s(i).isSpaceChar then
              args += current.toString
              rec(i + 1, args, None)
            else if s(i) == '\\' then
              rec(i + 1, args, Some((current, true, None)))
            else
              current.append(s(i))
              rec(i + 1, args, Some((current, false, None)))
          case Some((current, false, Some(quote))) =>
            if s(i) == quote then
              args += current.toString
              rec(i + 1, args, None)
            else if s(i) == '\\' then
              rec(i + 1, args, Some((current, true, Some(quote))))
            else
              current.append(s(i))
              rec(i + 1, args, Some((current, false, Some(quote))))
      else
        current match
          case None => args.toList
          case Some((value, _, _)) =>
            args += value.toString()
            args.toList
    rec(0, ListBuffer(), None)

  extension [F[_], O](self: Stream[F, O])
    def evalMapCancel[F2[x] >: F[x]: Async, O2](f: O => F2[O2]): Stream[F2, O2] =
      self.flatMapCancel(o => Stream.eval(f(o)))
    def flatMapCancel[F2[x] >: F[x]: Async, O2](f: O => Stream[F2, O2]): Stream[F2, O2] =
      Stream.eval((Queue.bounded[F2, Option[Either[Throwable, O2]]](1), Ref.of[F2, Option[Fiber[F2, Throwable, Unit]]](None)).tupled).flatMap: (outputs, fiber) =>
        Stream.fromQueueNoneTerminated(outputs).rethrow.concurrently:
          self.foreach: input =>
            val evaluator = f(input).attempt.foreach: output =>
              outputs.offer(Some(output))
            .compile.drain
            Async[F2].uncancelable: _ =>
              fiber.getAndSet(None).flatMap(_.traverse(_.cancel))
                >> evaluator.start.flatMap: newFiber =>
                  fiber.set(Some(newFiber))
          .onComplete(Stream.eval(fiber.getAndSet(None).flatMap(_.traverse(_.join).void) >> outputs.offer(None)))
            .onFinalize:
              Async[F2].uncancelable: _ =>
                fiber.get.flatMap(_.traverse(_.cancel).void)

  extension [A, B](self: Either[A, B])
    def getOrLeft: A | B = self match
      case Left(a)  => a
      case Right(b) => b
