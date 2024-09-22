package ipwatcher

import cats.*
import cats.effect.*
import cats.implicits.*

import fs2.*
import fs2.io.process.ProcessBuilder
import fs2.io.process.Processes
import fs2.text.lines
import fs2.text.utf8.decode
import java.time.Instant
import java.time.LocalDateTime
import fs2.Pull.Timed
import scala.concurrent.duration.FiniteDuration
import scala.concurrent.duration.Duration

object IP:

  given Eq[LocalDateTime] = Eq.fromUniversalEquals

  val ipRegEx = raw"(\d+): (\S+) +inet6 ([0-9a-f:]+)/64 scope (\S+) ((?:\S+ )*)\\ +valid_lft (\S+) preferred_lft (\S+)".r
  val ipMonitorRegEx1 = raw"\[(\S+)\] (Deleted )?(\d+): (\S+) +inet6 ([0-9a-f:]+)/64 scope (\S+) ((?:\S+ )*)".r
  val ipMonitorRegEx2 = raw" +valid_lft (\S+) preferred_lft (\S+)".r
  val secRegEx = raw"(\d+)sec".r

  private def toDuration(s: String): Duration =
    if s == "forever" then Duration.Inf
    else
      val secRegEx(sec) = s: @unchecked
      Duration(sec.toInt, "s")

  final case class IP6Address(id: Int, device: String, ip: String, scope: String, flags: List[String], validLft: Duration, preferredLft: Duration):
    def isRelevant: Boolean = scope == "global" && !flags.contains("temporary") && !flags.contains("deprecated") && flags.contains("dynamic") && preferredLft > FiniteDuration(5, "min") && !ip.startsWith("fd0")

  def listIP6Addresses[F[_]: Async: Processes](): F[List[IP6Address]] =
    ProcessBuilder("ip", "-6", "-o", "address").spawn[F].use: process =>
      lines(decode(process.stdout)).mapFilter:
        case ipRegEx(id, device, ip, scope, flags, validLft, preferredLft) =>
          Some(IP6Address(id.toInt, device, ip, scope, flags.split(" ").toList, toDuration(validLft), toDuration(preferredLft)))
        case _ => None
      .compile.toList

  def watchIP6Addresses[F[_]: Async: Processes](): Stream[F, (LocalDateTime, Either[IP6Address, IP6Address])] =
    Stream.resource(ProcessBuilder("ip", "-6", "-ts", "monitor", "address").spawn[F]).flatMap: process =>
      lines(decode(process.stdout)).chunkN(2, false).map: chunks =>
        val ipMonitorRegEx1(ts, deleted, id, device, ip, scope, flags) = chunks(0): @unchecked
        val ipMonitorRegEx2(validLft, preferredLft) = chunks(1): @unchecked
        val addr = IP6Address(id.toInt, device, ip, scope, flags.split(" ").toList, toDuration(validLft), toDuration(preferredLft))
        val timestamp = LocalDateTime.parse(ts)
        (timestamp, if deleted == null then Right(addr) else Left(addr))

  def watchGlobalIP6Addresses[F[_]: Async: Processes](): Stream[F, Map[(String, String), LocalDateTime]] =
    watchIP6Addresses[F]().pull.timed: timed =>
      def await(input: Timed[F, (LocalDateTime, Either[IP6Address, IP6Address])]): Pull[F, Map[(String, String), LocalDateTime], Unit] =
        Pull.eval(listIP6Addresses[F]()).flatMap: ip6addresses =>
          input.timeout(FiniteDuration(1, "s")) >> input.uncons.flatMap:
            case None                   => Pull.done
            case Some((Right(_), tail)) => await(tail)
            case Some((Left(_), tail)) =>
              val time = LocalDateTime.now()
              val map = ip6addresses.flatMap(ip => Option.when(ip.isRelevant)((ip.device, ip.ip) -> time)).toMap
              Pull.output1(map) >> forward(tail, map)
      def forward(input: Timed[F, (LocalDateTime, Either[IP6Address, IP6Address])], map: Map[(String, String), LocalDateTime]): Pull[F, Map[(String, String), LocalDateTime], Unit] =
        input.uncons.flatMap:
          case None => Pull.done
          case Some((Right(events), tail)) =>
            val newMap = events.foldLeft(map):
              case (map, (time, Right(ip))) if ip.isRelevant                   => map + ((ip.device, ip.ip) -> time)
              case (map, (time, Right(ip))) if ip.flags.contains("deprecated") => map - (ip.device -> ip.ip)
              case (map, (_, Left(ip)))                                        => map - (ip.device -> ip.ip)
              case _                                                           => map
            Pull.output1(newMap) >> forward(tail, newMap)
          case Some((Left(_), tail)) => forward(tail, map)
      await(timed)
    .stream.changes

  def watchLatestGlobalIP6Addresses[F[_]: Async: Processes](): Stream[F, Map[String, String]] =
    watchGlobalIP6Addresses[F]().map: ips =>
      ips.groupMapReduce(_._1._1)(entry => entry._2 -> entry._1._2)((a, b) => if a._1.isBefore(b._1) then b else a).view.mapValues(_._2).toMap
    .changes

end IP
