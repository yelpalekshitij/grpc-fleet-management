package com.fleetmanagement.vehicle

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class VehicleServiceApplication

fun main(args: Array<String>) {
    runApplication<VehicleServiceApplication>(*args)
}