package com.hortonworks.orendainx.trucking.simulator.actors

import java.sql.Timestamp
import java.util.Date

import akka.actor.{Actor, ActorLogging, ActorRef, Props}
import com.hortonworks.orendainx.trucking.shared.models.{TruckingEvent, TruckingEventTypes}
import com.hortonworks.orendainx.trucking.simulator.collectors.EventCollector.CollectEvent
import com.hortonworks.orendainx.trucking.simulator.models.{Driver, Location, Route, Truck}
import com.typesafe.config.Config

import scala.collection.mutable
import scala.util.Random

/**
  * @author Edgar Orendain <edgar@orendainx.com>
  */
object DriverActor {
  case object Drive

  case class NewRoute(route: Route)
  case class NewTruck(truck: Truck)

  def props(driver: Driver, depot: ActorRef, eventCollector: ActorRef)(implicit config: Config) =
    Props(new DriverActor(driver, depot, eventCollector))
}

class DriverActor(driver: Driver, depot: ActorRef, eventCollector: ActorRef)(implicit config: Config) extends Actor with ActorLogging {

  import DriverActor._

  // Current and previous truck/routes
  var truck: Option[Truck] = None
  var route: Option[Route] = None
  var previousTruck: Option[Truck] = None
  var previousRoute: Option[Route] = None

  // Locations this driver visits
  var locations = List.empty[Location]
  var locationsLeft = mutable.Buffer.empty[Location]

  // TODO: config only being used for 2 options, and they're shared among all users. Consider factoring out.
  val SpeedingThreshold = config.getInt("simulator.speeding-threshold")
  val MaxRouteCompletedCount = config.getInt("simulator.max-route-completed-count")

  var driveCount = 0
  var routeCompletedCount = 0

  depot ! TruckAndRouteDepot.RequestRoute(previousRoute)
  depot ! TruckAndRouteDepot.RequestTruck(previousTruck)

  context become waitingOndepot

  def receive = {
    case _ => log.error("This message should never be seen.")
  }

  def driverActive: Receive = {
    case Drive =>
      driveCount += 1
      log.info(s"Processing drive event #$driveCount")

      val currentLoc = locationsLeft.remove(0)
      val speed =
        driver.drivingPattern.minSpeed + Random.nextInt(driver.drivingPattern.maxSpeed - driver.drivingPattern.minSpeed + 1)

      val eventType =
        if (speed >= SpeedingThreshold || driveCount % driver.drivingPattern.riskFrequency == 0)
          TruckingEventTypes.AllTypes(Random.nextInt(TruckingEventTypes.AllTypes.length))
        else
          TruckingEventTypes.Normal

      // Create event and emit it
      val eventTime = new Timestamp(new Date().getTime)
      val event = TruckingEvent(eventTime, truck.get.id, driver.id, driver.name, route.get.id, route.get.name, currentLoc.latitude, currentLoc.longitude, speed, eventType)
      eventCollector ! CollectEvent(event)

      // If driver completed the route, switch trucks
      if (locationsLeft.isEmpty) {
        previousTruck = truck
        truck = None
        depot ! TruckAndRouteDepot.ReturnTruck(previousTruck.get)
        depot ! TruckAndRouteDepot.RequestTruck(previousTruck)

        // If route traveled too many times, switch routes
        routeCompletedCount += 1
        if (routeCompletedCount > MaxRouteCompletedCount) {
          previousRoute = route
          route = None
          depot ! TruckAndRouteDepot.ReturnRoute(previousRoute.get)
          depot ! TruckAndRouteDepot.RequestRoute(previousRoute)
        } else {
          locations = locations.reverse
          locationsLeft = locations.toBuffer
        }

        log.info("Changing context to waitingOnDepot")
        context become waitingOndepot
      }
  }

  def waitingOndepot: Receive = {
    case NewTruck(newTruck) =>
      truck = Some(newTruck)
      inspectState()
      log.info(s"Received new truck with id ${newTruck.id}")
    case NewRoute(newRoute) =>
      if (route.nonEmpty) depot ! TruckAndRouteDepot.ReturnRoute(route.get)
      route = Some(newRoute)
      locations = route.get.locations
      locationsLeft = locations.toBuffer
      inspectState()
      log.info(s"Received new route: ${newRoute.name}")
    case Drive =>
      // TODO: should not requeue because then all same timestamp, but need to make sure all events are generated for driver
      // TODO: implement exactly-n-times in coordinator.
      log.debug("Received Drive command while waiting on resources. Ignoring command, lost generated event.")
  }

  def inspectState(): Unit = if (truck.nonEmpty && route.nonEmpty) context become driverActive
}
