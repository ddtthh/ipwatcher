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

object UPNP:
  enum RegistryEvent:
    case DeviceAdded(registry: Registry, device: Device[?, ?, ?])
    case DeviceRemoved(registry: Registry, device: Device[?, ?, ?])

  def upnpService[F[_]: Async]: Resource[F, UpnpService] = Resource(Async[F].blocking {
    val upnpService = UpnpServiceImpl(DefaultUpnpServiceConfiguration())
    upnpService.startup()
    (upnpService, Async[F].blocking(upnpService.shutdown()))
  })

  def shutdown[F[_]: Async](service: UpnpService): F[Unit] = Async[F].blocking(service.shutdown())

  def events[F[_]: Async](service: UpnpService): Stream[F, RegistryEvent] =
    Stream.resource(Dispatcher.sequential[F]).flatMap: dispatcher =>
      Stream.eval(Queue.unbounded[F, Option[RegistryEvent]]).flatMap: queue =>
        Stream.resource(
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
          )
        ) >> Stream.fromQueueNoneTerminated(queue)

  def execute[F[_]: Async](upnpService: UpnpService, service: Service[?, ?], name: String, arguments: Map[String, Any]): F[Map[String, Any]] =
    Async[F].async_ : callback =>
      val invocation = ActionInvocation(service.getAction("GetExternalIPAddress"))
      for (key, value) <- arguments do
        invocation.setInput(key, value)
      upnpService.getControlPoint().execute(new ActionCallback(invocation) {
        def success(invocation: ActionInvocation[?]): Unit =
          callback(Right(invocation.getOutputMap().asScala.map((k, v) => (k, v.getValue())).toMap))

        def failure(invocation: ActionInvocation[?], operation: UpnpResponse, defaultMsg: String): Unit =
          callback(Left(Error(defaultMsg)))
      })

  def findService(device: Device[?, ?, ?], tpe: ServiceType): Option[Service[?, ?]] =
    Option(device.findService(tpe))
