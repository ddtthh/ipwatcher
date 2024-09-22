package ipwatcher

import cats.effect.*
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

object Main extends CommandIOApp(
      name = "ipwatcher",
      header = "watches public ip address changes.",
      version = "0.0.1"
    ):

  final case class DyndnsService(
      authorization: Option[(String, String)],
      uri: URI
  )

  val authorizationUser = Opts.option[String]("user", help = "HTTP basic authorization user.")
  val authorizationPassword = Opts.option[String]("password", help = "HTTP basic authorization password.")
  val authorization = (authorizationUser, authorizationPassword).mapN(_ -> _).orNone
  val dyndnsUri = Opts.option[URI]("uri", help = "dyndns service uri, placeholders: $(IP4), $(IP6)")
    

  val dyndnsServices = (authorization, dyndnsUri).mapN(DyndnsService.apply)
  val dyndnsCommand = Command("<dyndns> :=", "dyndns service configuration.", false)(dyndnsServices)

  final case class Config(
      ip6lifetime: FiniteDuration,
      ip6interfaces: List[String],
      dyndnsServices: NonEmptyList[DyndnsService]
  )

  val ip6lifetime = Opts.option[FiniteDuration]("ip6lifetime", help = "minimal preferred lifetime for ip6 address, default 5 min.").withDefault(FiniteDuration(5, "min"))
  val ip6interfaces = Opts.options[String]("ip6interface", help = "network interfaces for ip6 address.").orEmpty
  val dyndns = Opts.options[String]("dyndns", metavar = "dyndns ", help = dyndnsCommand.showHelp).mapValidated: values =>
    values.traverse: value =>
      dyndnsCommand.parse(Util.splitArgs(value)) match
        case Right(config) => Validated.valid(config)
        case Left(help)    => Validated.invalidNel(s"Illegal dyndns service configuration:\n${help.toString.indent(4)}")

  val configOpts = (ip6lifetime, ip6interfaces, dyndns).mapN(Config.apply)

  val wanIpConnectionServiceType = UDAServiceType("WANIPConnection", 2)

  def watch[F[_]: Async: Logger: Processes]: F[Unit] =
    UPNP.upnpService[F].use: upnpService =>
      UPNP.watchServices[F](upnpService, wanIpConnectionServiceType).use: services =>
        UPNP.search[F](upnpService)
          >> services.interruptAfter(FiniteDuration(10, "s")).compile.last.map(_.getOrElse(Map()))
      .flatMap: services =>
        val (udn, (device, wanIpConnectionService)) = services.headOption.getOrElse(throw Exception("No home router found via UPNP."))
        Logger[F].info(s"device found: $udn ${device.getDisplayString()} ${device.getDetails().getPresentationURI()}")
          >> IP.watchLatestGlobalIP6Addresses[F]().hold1Resource.use: ips =>
            ips.discrete.evalMap: ips =>
              UPNP.execute[F](upnpService, wanIpConnectionService, "GetExternalIPAddress", Map()).flatMap: result =>
                val value = result.get("NewExternalIPAddress")
                Logger[F].info(s"global v6 ips: $ips, external v4 ip: ${value.getOrElse("n/a")}")
            .compile.drain

  def main: Opts[IO[ExitCode]] = configOpts.map: config =>
    Slf4jLogger.fromName[IO]("ipwatcher").flatMap: logger =>
      given Logger[IO] = logger
      def doRetry: IO[Unit] = watch[IO].onCancel(logger.info("shutting down due to signal.")).onError: e =>
        logger.error(s"retrying after error: $e") >> IO.sleep(FiniteDuration(10, "s")) >> doRetry
      logger.info(s"started with pid: ${ProcessHandle.current().pid()}")
        >> IO.println(config.toString())
        >> doRetry
        >> IO.pure(ExitCode.Success)
  end main

end Main
