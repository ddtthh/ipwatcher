package ipwatcher

import cats.effect.*
import cats.implicits.*

import fs2.*

import org.jupnp.UpnpService
import org.jupnp.UpnpServiceImpl
import org.jupnp.DefaultUpnpServiceConfiguration
import org.jupnp.controlpoint.ActionCallback
import org.jupnp.controlpoint.SubscriptionCallback
import org.jupnp.model.action.ActionInvocation
import org.jupnp.model.message.UpnpResponse
import org.jupnp.model.message.header.STAllHeader
import org.jupnp.model.meta.Device
import org.jupnp.model.meta.RemoteDevice
import org.jupnp.model.types.UDAServiceId
import org.jupnp.model.types.UDAServiceType
import org.jupnp.registry.DefaultRegistryListener
import org.jupnp.registry.Registry
import org.jupnp.util.SpecificationViolationReporter
import cats.effect.std.Dispatcher
import cats.effect.std.Queue
import org.jupnp.model.meta.Service
import scala.jdk.CollectionConverters.*
import org.jupnp.model.types.ServiceType
import java.util.UUID
import org.jupnp.model.types.UDN
import org.jupnp.UpnpServiceImpl.Config
import java.lang.annotation.Annotation
import org.typelevel.log4cats.Logger

object UPNP:
  enum RegistryEvent:
    case DeviceAdded(registry: Registry, device: Device[?, ?, ?])
    case DeviceRemoved(registry: Registry, device: Device[?, ?, ?])

  def upnpService[F[_]: Async: Logger]: Resource[F, UpnpService] = Resource(
    Async[F].blocking:
      val upnpService = UpnpServiceImpl(DefaultUpnpServiceConfiguration())
      upnpService.activate(new Config {
        def annotationType(): Class[? <: Annotation] = classOf[Config]
        def initialSearchEnabled(): Boolean = false
      })
      (upnpService, Async[F].blocking(upnpService.shutdown()) >> Logger[F].trace("stopped upnp service."))
    .flatTap(_ => Logger[F].trace("started upnp service."))
  )

  def shutdown[F[_]: Async](service: UpnpService): F[Unit] = Async[F].blocking(service.shutdown())

  def events[F[_]: Async](service: UpnpService): Resource[F, Stream[F, RegistryEvent]] =
    Dispatcher.sequential[F].flatMap: dispatcher =>
      Resource.eval(Queue.unbounded[F, Option[RegistryEvent]]).flatMap: queue =>
        Resource(Async[F].delay:
          val listener = new DefaultRegistryListener():
            override def deviceAdded(registry: Registry, device: Device[?, ?, ?]): Unit =
              dispatcher.unsafeRunAndForget(queue.offer(Some(RegistryEvent.DeviceAdded(registry, device))))
            override def deviceRemoved(registry: Registry, device: Device[?, ?, ?]): Unit =
              dispatcher.unsafeRunAndForget(queue.offer(Some(RegistryEvent.DeviceRemoved(registry, device))))
            override def afterShutdown(): Unit =
              dispatcher.unsafeRunAndForget(queue.offer(None))
          service.getRegistry().addListener(listener)
          ((), Async[F].delay(service.getRegistry().removeListener(listener)))
        ).map(_ => Stream.fromQueueNoneTerminated(queue))

  def execute[F[_]: Async](upnpService: UpnpService, service: Service[?, ?], name: String, arguments: Map[String, Any]): F[Map[String, Any]] =
    Async[F].async_ : callback =>
      val invocation = ActionInvocation(service.getAction("GetExternalIPAddress"))
      for (key, value) <- arguments do
        invocation.setInput(key, value)
      upnpService.getControlPoint().execute(new ActionCallback(invocation) {
        def success(invocation: ActionInvocation[?]): Unit =
          callback(Right(invocation.getOutputMap().asScala.flatMap((k, v) => Option(v.getValue()).map(k -> _)).toMap))

        def failure(invocation: ActionInvocation[?], operation: UpnpResponse, defaultMsg: String): Unit =
          callback(Left(UPNPException(defaultMsg)))
      })

  final case class UPNPException(msg: String) extends Exception(msg)

  def findService(device: Device[?, ?, ?], tpe: ServiceType): Option[Service[?, ?]] =
    Option(device.findService(tpe))

  def search[F[_]: Async](upnpService: UpnpService): F[Unit] =
    Async[F].delay(upnpService.getControlPoint().search())

  def watchServices[F[_]: Async](upnpService: UpnpService, serviceType: UDAServiceType): Resource[F, Stream[F, Map[UDN, (Device[?, ?, ?], Service[?, ?])]]] =
    UPNP.events[F](upnpService).map: events =>
      events.mapFilter:
        case RegistryEvent.DeviceAdded(registry, device) =>
          UPNP.findService(device, serviceType) match
            case Some(service) =>
              Some(Right(device.getIdentity().getUdn() -> (device, service)))
            case None =>
              None
        case RegistryEvent.DeviceRemoved(registry, device) =>
          Some(Left(device.getIdentity().getUdn()))
      .scan(Map()):
        case (map, Right((udn, info))) => map + (udn -> info)
        case (map, Left(udn))          => map - udn
