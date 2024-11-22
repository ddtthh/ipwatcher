package ipwatcher

import cats.effect.*
import cats.effect.implicits.*
import cats.implicits.*
import fs2.*

import com.monovore.decline.*
import com.monovore.decline.effect.*

import org.jupnp.UpnpServiceImpl
import org.jupnp.model.message.header.STAllHeader
import org.jupnp.registry.DefaultRegistryListener
import org.jupnp.model.types.UDAServiceId
import org.jupnp.model.meta.RemoteDevice
import java.rmi.registry.Registry
import org.jupnp.DefaultUpnpServiceConfiguration
import org.jupnp.model.meta.Device
import org.jupnp.model.types.UDAServiceType
import org.jupnp.util.SpecificationViolationReporter
import org.jupnp.controlpoint.ActionCallback
import org.jupnp.model.action.ActionInvocation
import org.jupnp.model.message.UpnpResponse
import org.jupnp.controlpoint.SubscriptionCallback
import org.jupnp.model.gena.GENASubscription
import org.jupnp.model.gena.CancelReason

import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger

import scala.concurrent.duration.*
import java.time.{Duration => JDuration}

import ipwatcher.UPNP.RegistryEvent
import org.typelevel.log4cats.LoggerName
import scala.concurrent.duration.FiniteDuration
import fs2.io.process.Processes
import java.net.URI
import cats.data.NonEmptyList
import cats.data.Validated
import scala.collection.mutable.ListBuffer
import scala.annotation.tailrec
import scala.util.Try
import scala.util.Success
import scala.util.Failure
import sttp.client4.httpclient.fs2.HttpClientFs2Backend
import scala.util.matching.Regex
import cats.effect.std.MapRef
import sttp.client4.*
import sttp.model.Uri
import java.time.Instant
import scala.jdk.javaapi.DurationConverters
import Util.{flatMapCancel, evalMapCancel, getOrLeft}
import fs2.concurrent.Signal
import sttp.capabilities.fs2.Fs2Streams
import cats.data.Ior
import cats.data.ValidatedNel

object Main extends CommandIOApp(
      name = "ipwatcher",
      header = "watches public ip address changes.",
      version = "0.0.1"
    ):

  final case class DyndnsService(
      authorization: Option[(String, String)],
      uris: Ior[URI, URI], // left ip6 only, right ip6 and ip4
      group: Option[String]
  ):
    def info: String = s"uri: ${uris.toEither.getOrLeft}, user: ${authorization.map(_._1).getOrElse("n/a")}"

  val authorizationUser = Opts.option[String]("user", help = "HTTP basic authorization user.")
  val authorizationPassword = Opts.option[String]("password", help = "HTTP basic authorization password.")
  val authorization = (authorizationUser, authorizationPassword).mapN(_ -> _).orNone
  val dyndnsUri = Opts.option[URI]("uri", help = "dyndns service uri, placeholders: $(IP4), $(IP6)").orNone
  val dyndnsUriIp6 = Opts.option[URI]("uriIp6", help = "dyndns service uri to set only ip6 when ip4 is unavailable, placeholders: $(IP6)").orNone
  val dyndnsGroup = Opts.option[String]("group", help = "group of services to call sequencially.").orNone

  val dyndnsUris: Opts[Ior[URI, URI]] = (dyndnsUriIp6, dyndnsUri).mapN(Ior.fromOptions).mapValidated:
    case Some(uris) => Validated.valid(uris)
    case None       => Validated.invalidNel("at least one of --uri or --uriIp6 is required.")
  val dyndnsServices = (authorization, dyndnsUris, dyndnsGroup).mapN(DyndnsService.apply)
  val dyndnsCommand = Command("<dyndns> :=", "dyndns service configuration.", false)(dyndnsServices)

  final case class Config(
      ip6lifetime: FiniteDuration,
      ip6interfaces: NonEmptyList[Regex],
      upnpSearchFor: FiniteDuration,
      debounce: FiniteDuration,
      delay: FiniteDuration,
      retryUpnp: FiniteDuration,
      retryUpnpLimit: Int,
      retryUpdate: FiniteDuration,
      retryUpdateLimit: Int,
      restart: FiniteDuration,
      restartLimit: Int,
      dyndnsServices: NonEmptyList[DyndnsService]
  ):
    def isIp6Only: Boolean = dyndnsServices.forall(_.uris.isLeft)

  val ip6lifetime = Opts.option[FiniteDuration]("ip6lifetime", help = "minimal preferred lifetime for ip6 address, default 5 min.").withDefault(5.minutes)
  val ip6interfaces = Opts.options[String]("ip6interface", help = "network interfaces for ip6 address.").mapValidated: values =>
    values.traverse: value =>
      Try(value.r) match
        case Success(value)     => Validated.valid(value)
        case Failure(exception) => Validated.invalidNel(s"Invalid regular expression: $exception")
  .withDefault(NonEmptyList.one(".*".r))
  val upnpSearchFor = Opts.option[FiniteDuration]("upnpSearchFor", help = "search duration for upnp devices after startup, default 5 s.").withDefault(5.seconds)
  val debounce = Opts.option[FiniteDuration]("debounce", help = "debounce updates, default 2 min.").withDefault(10.minutes)
  val delay = Opts.option[FiniteDuration]("delay", help = "delay updates, default 5 s.").withDefault(5.seconds)
  val retryUpnp = Opts.option[FiniteDuration]("retryUpnp", help = "retry failed upnp request after, default 10 s.").withDefault(10.seconds)
  val retryUpnpLimit = Opts.option[Int]("retryUpnpLimit", help = "number of retries after failed upnp request, default 100.").withDefault(100)
  val retryUpdate = Opts.option[FiniteDuration]("retryUpdate", help = "retry failed update after, default 5 min.").withDefault(5.minutes)
  val retryUpdateLimit = Opts.option[Int]("retryUpdateLimit", help = "number of retries after failed update, default 100.").withDefault(100)
  val restart = Opts.option[FiniteDuration]("restart", help = "restart after failure, default 30 s.").withDefault(30.seconds)
  val restartLimit = Opts.option[Int]("restartLimit", help = "number of restarts, default 100.").withDefault(100)
  val dyndns = Opts.options[String]("dyndns", metavar = "dyndns ", help = dyndnsCommand.showHelp).mapValidated: values =>
    values.traverse: value =>
      dyndnsCommand.parse(Util.splitArgs(value)) match
        case Right(config) => Validated.valid(config)
        case Left(help)    => Validated.invalidNel(s"Illegal dyndns service configuration:\n${help.toString.indent(4)}")

  val configOpts = (ip6lifetime, ip6interfaces, upnpSearchFor, debounce, delay, retryUpnp, retryUpnpLimit, retryUpdate, retryUpdateLimit, restart, restartLimit, dyndns).mapN(Config.apply)

  val wanIpConnectionServiceType = UDAServiceType("WANIPConnection", 2)

  def watch[F[_]: Async: Logger: Processes](config: Config): Stream[F, Option[(String, Option[String])]] =
    if config.isIp6Only then
      watchIp6(config).map(_.map(_ -> None))
    else
      val dyndnsServiceGroups = config.dyndnsServices.toList.zipWithIndex.groupBy(x => x._1.group.getOrElse(x)).values.toList
      Stream.resource:
        UPNP.upnpService[F].evalMap: upnpService =>
          UPNP.watchServices[F](upnpService, wanIpConnectionServiceType).use: services =>
            UPNP.search[F](upnpService) >> services.interruptAfter(config.upnpSearchFor).compile.last.map(_.getOrElse(Map()))
          .flatMap: services =>
            val (udn, (device, wanIpConnectionService)) = services.headOption.getOrElse(throw Exception("No home router found via UPNP."))
            Logger[F].info(s"device found: $udn ${device.getDisplayString()} ${device.getDetails().getPresentationURI()}").as:
              IP.watchLatestGlobalIP6Addresses[F](config.ip6lifetime).evalTap: ip6s =>
                Logger[F].info(s"Discovered ip6 addresses: ${ip6s.map((k, v) => s"$k: $v").mkString(", ")}")
              watchIp6(config).flatMapCancel:
                case None => Stream(Option.empty[(String, Option[String])])
                case Some(ip6) => Stream(Some(ip6 -> Option.empty[String])) ++
                    Stream.retry(
                      UPNP.execute[F](upnpService, wanIpConnectionService, "GetExternalIPAddress", Map()).flatMap: result =>
                        val ip4 = result.get("NewExternalIPAddress").getOrElse(throw new ApplicationException("Empty reply for upnp request for ip4 address.")).asInstanceOf[String]
                        Logger[F].info(s"Discovered ip4 addresses: $ip4").as(Some(ip6 -> Some(ip4)))
                      .onError:
                        case ApplicationException(msg) =>
                          Logger[F].error(s"Failed to fetch ip4 address from $udn ${device.getDisplayString()} ${device.getDetails().getPresentationURI()} due to: $msg")
                        case UPNP.UPNPException(msg) =>
                          Logger[F].error(s"Failed to fetch ip4 address from $udn ${device.getDisplayString()} ${device.getDetails().getPresentationURI()} due to: $msg")
                      ,
                      config.retryUpnp,
                      identity,
                      config.retryUpdateLimit
                    )
      .flatten.changes
  end watch

  def watchIp6[F[_]: Async: Logger: Processes](config: Config): Stream[F, Option[String]] =
    IP.watchLatestGlobalIP6Addresses[F](config.ip6lifetime).evalTap: ip6s =>
      Logger[F].info(s"Discovered ip6 addresses: ${ip6s.map((k, v) => s"$k: $v").mkString(", ")}")
    .map: ip6s =>
      config.ip6interfaces.toList.view.flatMap(iface => ip6s.view.filterKeys(iface.matches).values).headOption
  end watchIp6

  def scheduleUpdates[F[_]: Async: Logger: Processes](config: Config, ips: Stream[F, Option[(String, Option[String])]]): F[Unit] =
    val dyndnsServiceGroups = config.dyndnsServices.toList.zipWithIndex.groupBy(x => x._1.group.getOrElse(x)).values.toList
    HttpClientFs2Backend.resource[F]().use: backend =>
      (MapRef.inConcurrentHashMap[F, F, Int, Instant](), MapRef.inConcurrentHashMap[F, F, Int, (String, Option[String])]()).flatMapN: (terminatedAt, previousIps) =>
        ips.evalMapCancel:
          case Some((ip6, ip4)) =>
            Async[F].sleep(config.delay) >>
              Stream.emits(dyndnsServiceGroups).parEvalMapUnbounded: dyndnsServices =>
                Stream.emits(dyndnsServices).evalMap: (dyndnsService, index) =>
                  previousIps(index).get.flatMap: previousIp =>
                    if previousIp.contains((ip6, ip4)) then
                      Async[F].unit
                    else
                      ((Async[F].realTimeInstant.flatMap: now =>
                        terminatedAt(index).get.flatMap: terminatedAt =>
                          val delay = JDuration.between(now, terminatedAt.getOrElse(Instant.MIN).plus(DurationConverters.toJava(config.debounce)))
                          (if JDuration.ZERO.compareTo(delay) < 0 then
                             Logger[F].info(s"update scheduled in $delay for ${dyndnsService.info} with ip6: $ip6, ip4: $ip4") >>
                               Async[F].sleep(DurationConverters.toScala(delay))
                           else
                             Async[F].unit
                          ) >> update(config, dyndnsService, backend, ip6, ip4)
                      ) >> previousIps(index).set(Some((ip6, ip4)))).guarantee:
                        Async[F].realTimeInstant.flatMap: now =>
                          terminatedAt(index).set(Some(now))
                .compile.drain
              .compile.drain
          case None =>
            Logger[F].info(s"no ips")
        .compile.drain
  end scheduleUpdates

  def update[F[_]: Async: Logger](config: Config, dyndnsService: DyndnsService, backend: WebSocketStreamBackend[F, Fs2Streams[F]], ip6: String, ip4: Option[String]): F[Unit] =
    val partialRequest = dyndnsService.authorization.map: (user, password) =>
      basicRequest.auth.basic(user, password)
    .getOrElse(basicRequest)
    def doIp6Only(uriIp6Only: URI) =
      val uri = Uri.unsafeParse(uriIp6Only.toString().replace("(IP6)", ip6))
      val request = partialRequest.get(uri)
      request.send(backend).flatMap: response =>
        response.body match
          case Right(_)  => Logger[F].info(s"update for ip6 succeeded for ${dyndnsService.info} with ip6: $ip6")
          case Left(msg) => Async[F].raiseError(ApplicationException(s"Request failed for uri $uri with $msg"))
    def doBoth(uriBoth: URI, ip4: String) =
      val uri = Uri.unsafeParse(uriBoth.toString().replace("(IP4)", ip4).replace("(IP6)", ip6))
      val request = partialRequest.get(uri)
      request.send(backend).flatMap: response =>
        response.body match
          case Right(_)  => Logger[F].info(s"update succeeded for ${dyndnsService.info} with ip6: $ip6, ip4: $ip4")
          case Left(msg) => Async[F].raiseError(ApplicationException(s"Request failed for uri $uri with $msg"))
    val doSend = dyndnsService.uris match
      case Ior.Right(uriBoth) =>
        ip4 match
          case None      => Logger[F].warn(s"update ignored as no ip4 is available for ${dyndnsService.info} with ip6: $ip6")
          case Some(ip4) => doBoth(uriBoth, ip6)
      case Ior.Left(uriIp6only) => doIp6Only(uriIp6only)
      case Ior.Both(uriIp6only, uriBoth) =>
        ip4 match
          case None      => doIp6Only(uriIp6only)
          case Some(ip4) => doBoth(uriBoth, ip6)
    Stream.retry(
      doSend.onError:
        case ApplicationException(msg) =>
          Logger[F].error(s"update failed for ${dyndnsService.info} with ip6: $ip6, ip4: ${ip4.getOrElse("n/a")} due to: $msg")
        case e: SttpClientException =>
          Logger[F].error(s"update failed for ${dyndnsService.info} with ip6: $ip6, ip4: ${ip4.getOrElse("n/a")} due to: ${e.getMessage()} caused by ${Option(e.cause).map(e => e.getMessage()).getOrElse("n/a")}")
      .onCancel:
        Logger[F].error(s"update cancled for ${dyndnsService.info} with ip6: $ip6, ip4: ${ip4.getOrElse("n/a")}")
      ,
      config.retryUpdate,
      identity,
      config.retryUpdateLimit,
      e => e.isInstanceOf[ApplicationException] || e.isInstanceOf[SttpClientException]
    ).compile.drain
  end update

  def main: Opts[IO[ExitCode]] = configOpts.map: config =>
    Slf4jLogger.fromName[IO]("ipwatcher").flatMap: logger =>
      given Logger[IO] = logger
      val doUpdates = logger.info(s"starting updates") >> scheduleUpdates[IO](config, watch[IO](config)).onCancel(logger.info("shutting down due to signal.")).onError: e =>
        logger.error(e)(s"failed with unexpected error: $e")
      logger.info(s"started with pid: ${ProcessHandle.current().pid()}")
        >> Stream.retry(
          doUpdates,
          config.restart,
          identity,
          config.restartLimit
        ).compile.drain.as(ExitCode.Success)
  end main

  case class ApplicationException(msg: String) extends Exception(msg)

end Main
