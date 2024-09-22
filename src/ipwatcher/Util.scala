package ipwatcher

import scala.annotation.tailrec
import scala.collection.mutable.ListBuffer

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
