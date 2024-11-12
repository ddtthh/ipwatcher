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

import scala.concurrent.duration.Duration

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

object Main extends CommandIOApp(
      name = "ipwatcher",
      header = "watches public ip address changes.",
      version = "0.0.1"
    ):

  final case class DyndnsService(
      authorization: Option[(String, String)],
      uri: URI,
      group: Option[String]
  )

  val authorizationUser = Opts.option[String]("user", help = "HTTP basic authorization user.")
  val authorizationPassword = Opts.option[String]("password", help = "HTTP basic authorization password.")
  val authorization = (authorizationUser, authorizationPassword).mapN(_ -> _).orNone
  val dyndnsUri = Opts.option[URI]("uri", help = "dyndns service uri, placeholders: $(IP4), $(IP6)")
  val dyndnsGroup = Opts.option[String]("group", help = "group of services to call sequencially.").orNone

  val dyndnsServices = (authorization, dyndnsUri, dyndnsGroup).mapN(DyndnsService.apply)
  val dyndnsCommand = Command("<dyndns> :=", "dyndns service configuration.", false)(dyndnsServices)

  final case class Config(
      ip6lifetime: FiniteDuration,
      ip6interfaces: NonEmptyList[Regex],
      upnpSearchFor: FiniteDuration,
      debounce: FiniteDuration,
      delay: FiniteDuration,
      retry: FiniteDuration,
      restart: FiniteDuration,
      fail: FiniteDuration,
      dyndnsServices: NonEmptyList[DyndnsService]
  )

  val ip6lifetime = Opts.option[FiniteDuration]("ip6lifetime", help = "minimal preferred lifetime for ip6 address, default 5 min.").withDefault(FiniteDuration(5, "min"))
  val ip6interfaces = Opts.options[String]("ip6interface", help = "network interfaces for ip6 address.").mapValidated: values =>
    values.traverse: value =>
      Try(value.r) match
        case Success(value)     => Validated.valid(value)
        case Failure(exception) => Validated.invalidNel(s"Invalid regular expression: $exception")
  .withDefault(NonEmptyList.one(".*".r))
  val upnpSearchFor = Opts.option[FiniteDuration]("upnpSearchFor", help = "search duration for upnp devices after startup, default 5 s.").withDefault(FiniteDuration(5, "s"))
  val debounce = Opts.option[FiniteDuration]("debounce", help = "debounce updates, default 2 min.").withDefault(FiniteDuration(2, "min"))
  val delay = Opts.option[FiniteDuration]("delay", help = "delay updates, default 5 s.").withDefault(FiniteDuration(5, "s"))
  val retry = Opts.option[FiniteDuration]("retry", help = "retry failed update after, default 10 s.").withDefault(FiniteDuration(10, "s"))
  val restart = Opts.option[FiniteDuration]("restart", help = "restart after failure, default 10 s.").withDefault(FiniteDuration(10, "s"))
  val fail = Opts.option[FiniteDuration]("fail", help = "fail when no ips are detected for that ammount of time, default 30 s.").withDefault(FiniteDuration(30, "s"))
  val dyndns = Opts.options[String]("dyndns", metavar = "dyndns ", help = dyndnsCommand.showHelp).mapValidated: values =>
    values.traverse: value =>
      dyndnsCommand.parse(Util.splitArgs(value)) match
        case Right(config) => Validated.valid(config)
        case Left(help)    => Validated.invalidNel(s"Illegal dyndns service configuration:\n${help.toString.indent(4)}")

  val configOpts = (ip6lifetime, ip6interfaces, upnpSearchFor, debounce, delay, retry, restart, fail, dyndns).mapN(Config.apply)

  val wanIpConnectionServiceType = UDAServiceType("WANIPConnection", 2)

  def watch[F[_]: Async: Logger: Processes](config: Config): F[Unit] =
    val dyndnsServiceGroups = config.dyndnsServices.toList.zipWithIndex.groupBy(x => x._1.group.getOrElse(x)).values.toList
    HttpClientFs2Backend.resource[F]().use: backend =>
      UPNP.upnpService[F].use: upnpService =>
        UPNP.watchServices[F](upnpService, wanIpConnectionServiceType).use: services =>
          UPNP.search[F](upnpService)
            >> services.interruptAfter(config.upnpSearchFor).compile.last.map(_.getOrElse(Map()))
        .flatMap: services =>
          val (udn, (device, wanIpConnectionService)) = services.headOption.getOrElse(throw Exception("No home router found via UPNP."))
          Logger[F].info(s"device found: $udn ${device.getDisplayString()} ${device.getDetails().getPresentationURI()}")
            >> IP.watchLatestGlobalIP6Addresses[F](config.ip6lifetime).evalMap: ip6s =>
              Logger[F].info(s"Discovered ip6 addresses: ${ip6s.map((k, v) => s"$k: $v").mkString(", ")}") >> config.ip6interfaces.toList.view.flatMap(iface => ip6s.view.filterKeys(iface.matches).values).headOption.flatTraverse: ip6 =>
                UPNP.execute[F](upnpService, wanIpConnectionService, "GetExternalIPAddress", Map()).flatMap: result =>
                  val ip4s = result.get("NewExternalIPAddress").map(_.asInstanceOf[String])
                  Logger[F].info(s"Discovered ip4 addresses: ${ip4s.getOrElse("")}").as(
                    ip4s.map(ip6 -> _)
                  )
            .changes.hold1Resource.use: ips =>
              MapRef.inConcurrentHashMap[F, F, Int, (String, String)]().flatMap: previousIps =>
                ips.discrete.evalScan(Async[F].unit): (cancel, ips) =>
                  cancel >> (ips match
                    case (None) =>
                      Logger[F].info(s"no ips") >>
                        (Async[F].sleep(config.fail) >> Async[F].raiseError(new Error(s"no ips detected for ${config.fail}."))).start.map(_.cancel)
                    case Some((ip6, ip4)) =>
                      Logger[F].info(s"ip6: $ip6, ip4: $ip4")
                        >> Ref.of(Instant.MIN).flatMap: terminatedAt =>
                          Async[F].start:
                            Async[F].sleep(config.delay)
                              >> Stream.emits(dyndnsServiceGroups).parEvalMapUnbounded: dyndnsServices =>
                                Stream.emits(dyndnsServices).evalMap: (dyndnsService, index) =>
                                  previousIps(index).get.flatMap: previousIp =>
                                    if previousIp.contains((ip6, ip4)) then
                                      Async[F].unit
                                    else
                                      val user = dyndnsService.authorization.map(x => " " + x._1).getOrElse("")
                                      val partialRequest = dyndnsService.authorization.map: (user, password) =>
                                        basicRequest.auth.basic(user, password)
                                      .getOrElse(basicRequest)
                                      val uri = Uri.unsafeParse(dyndnsService.uri.toString().replace("(IP4)", ip4).replace("(IP6)", ip6))
                                      val request = partialRequest.get(uri)
                                      def doSend: F[Unit] = request.send(backend).flatMap: response =>
                                        if response.isSuccess then
                                          Async[F].uncancelable: _ =>
                                            previousIps(index).set(Some((ip6, ip4)))
                                              >> Logger[F].info(s"update succeeded for$user $ip6, $ip4: $uri")
                                        else
                                          Logger[F].error(s"update failed for$user $ip6, $ip4: $uri")
                                            >> Async[F].sleep(config.retry)
                                            >> doSend
                                      .handleErrorWith(e =>
                                        Logger[F].error(e)(s"update failed with exception for$user $ip6, $ip4: $uri")
                                          >> Async[F].sleep(config.retry)
                                          >> doSend
                                      )
                                      doSend.onCancel(Logger[F].info(s"update canceled for$user $ip6, $ip4: $uri"))
                                .compile.drain
                              .compile.drain
                              >> Async[F].realTimeInstant.flatMap(terminatedAt.set)
                          .map: fibre =>
                            terminatedAt.get.flatMap: terminatedAt =>
                              Async[F].realTimeInstant.flatMap: now =>
                                val delay = java.time.Duration.between(now, terminatedAt.plus(DurationConverters.toJava(config.debounce)))
                                if delay.isPositive() then
                                  Async[F].sleep(DurationConverters.toScala(delay)) >> fibre.cancel
                                else
                                  fibre.cancel
                  )
                .compile.drain
  end watch

  def main: Opts[IO[ExitCode]] = configOpts.map: config =>
    Slf4jLogger.fromName[IO]("ipwatcher").flatMap: logger =>
      given Logger[IO] = logger
      def doRetry: IO[Unit] = watch[IO](config).onCancel(logger.info("shutting down due to signal.")).handleErrorWith: e =>
        logger.error(s"retrying after error: $e") >> IO.sleep(config.restart) >> doRetry
      logger.info(s"started with pid: ${ProcessHandle.current().pid()}")
        >> doRetry
        >> IO.pure(ExitCode.Success)
  end main

end Main
