package com.hortonworks.orendainx.trucking.simulator.models

import com.hortonworks.orendainx.trucking.simulator.patterns.{DrivingPattern, WeatherPattern}

/**
  * The model for each Driver.
  *
  * @author Edgar Orendain <edgar@orendainx.com>
  */
class Driver(id: Int, name: String, drivingPattern: DrivingPattern, weatherPattern: WeatherPattern) {

  val route = ???
}