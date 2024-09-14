package ipwatcher

import cats.effect.*
import cats.implicits.*

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

object Main extends CommandIOApp(
      name = "ipwatcher",
      header = "watches public ip address changes.",
      version = "0.0.1"
    ):
  val flag = Opts.flag("quiet", help = "Don't print any metadata to the console.").orFalse

  val wanIpConnectionServiceType = UDAServiceType("WANIPConnection")
  def main: Opts[IO[ExitCode]] = flag.map: options =>
    Slf4jLogger.fromName[IO]("ipwatcher").flatMap: logger =>
      IO.println(s"quiet: $options") >> UPNP.upnpService[IO].use: service =>
        UPNP.events[IO](service).evalTap:
          case RegistryEvent.DeviceAdded(registry, device) =>
            UPNP.findService(device, wanIpConnectionServiceType) match
              case Some(wanIpConnectionService) =>
                UPNP.execute[IO](service, wanIpConnectionService, "GetExternalIPAddress", Map()).flatMap: result =>
                  val value = result("NewExternalIPAddress")
                  logger.info(s"${device.getIdentity().getUdn()} ${device.getDisplayString()} ${wanIpConnectionService.getServiceType()} ExternalIP: $value")
              case None => IO.unit
          case RegistryEvent.DeviceRemoved(registry, device) => IO.unit
        .compile.drain.background.use_ >> IO.sleep(Duration(5, "s")).as(ExitCode.Success).onCancel(logger.info("canceled"))
  end main

end Main
