package ipwatcher

import cats.effect.*
import cats.implicits.*

import fs2.*
import fs2.io.process.ProcessBuilder
import fs2.io.process.Processes
import fs2.text.lines
import fs2.text.utf8.decode
import java.time.Instant
import java.time.LocalDateTime

object IP:

  val ipRegEx = raw"(\d+): (\S+) +inet6 ([0-9a-f:/]+) scope (\S+) ((?:\S+ )*)\\.*".r
  val ipMonitorRegEx = raw"\[(\S+)\] (Deleted )?(\d+): (\S+) +inet6 ([0-9a-f:/]+) scope (\S+) ((?:\S+ )*)".r

  final case class IPAddress(id: Int, device: String, ip: String, scope: String, flags: List[String])
  def listIP6Addresses[F[_]: Async: Processes](): F[List[IPAddress]] =
    ProcessBuilder("ip", "-6", "-o", "address").spawn[F].use: process =>
      lines(decode(process.stdout)).mapFilter:
        case ipRegEx(id, device, ip, scope, flags) =>
          Some(IPAddress(id.toInt, device, ip, scope, flags.split(" ").toList))
        case _ => None
      .compile.toList

  def watchIP6Addresses[F[_]: Async: Processes](): Stream[F, Any] =
    Stream.resource(ProcessBuilder("ip", "-6", "-ts", "monitor", "address").spawn[F]).flatMap: process =>
      lines(decode(process.stdout)).mapFilter:
        case ipMonitorRegEx(ts, deleted, id, device, ip, scope, flags) =>
          val ipAddress = IPAddress(id.toInt, device, ip, scope, flags.split(" ").toList)
          val timestamp = LocalDateTime.parse(ts)
          Some((timestamp, if deleted == null then Right(ipAddress) else Left(ipAddress)))
        case _ => None
end IP
