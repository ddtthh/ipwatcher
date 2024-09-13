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
import scala.concurrent.duration.Duration
import ipwatcher.UPNP.RegistryEvent

object Main extends CommandIOApp(
      name = "ipwatcher",
      header = "watches public ip address changes.",
      version = "0.0.1"
    ):
  val flag = Opts.flag("quiet", help = "Don't print any metadata to the console.").orFalse

  def main: Opts[IO[ExitCode]] = flag.map: options =>
    IO.println(s"quiet: $options") >> UPNP.service[IO].use: service =>
      UPNP.events[IO](service).evalTap:
        case RegistryEvent.DeviceAdded(registry, device)   => IO.println(s"$device")
        case RegistryEvent.DeviceRemoved(registry, device) => IO.unit
      .compile.drain.background.use: _ =>
        IO.sleep(Duration(5, "s")).as(ExitCode.Success).onCancel(IO.println("canceled"))

  def run(argv: Array[String]): Unit =
    val upnpService = UpnpServiceImpl(DefaultUpnpServiceConfiguration())
    upnpService.startup()
    val registry = upnpService.getRegistry()
    val serviceType = UDAServiceType("WANIPConnection")
    val myControlPoint = upnpService.getControlPoint()
    registry.addListener(
      new DefaultRegistryListener():

        override def deviceAdded(registry: org.jupnp.registry.Registry, device: Device[?, ?, ?]): Unit =
          Option(device.findService(serviceType)) match
            case Some(wanIpConnectionService) =>
              val getExternalIPAddress = wanIpConnectionService.getAction("GetExternalIPAddress")
              val getExternalIPAddressInvocation = ActionInvocation(getExternalIPAddress)
              myControlPoint.execute(new ActionCallback(getExternalIPAddressInvocation) {
                def success(invocation: ActionInvocation[?]): Unit =
                  val value = invocation.getOutput("NewExternalIPAddress").getValue()
                  println(s"#### ${device.getIdentity().getUdn()} ${device.getDisplayString()} ${wanIpConnectionService.getServiceType()} ExternalIP: $value")

                def failure(invocation: ActionInvocation[?], operation: UpnpResponse, defaultMsg: String): Unit =
                  println("failure")
              })
            case None =>

    )
    myControlPoint.search(STAllHeader())
    Thread.sleep(5000);
    upnpService.shutdown();
