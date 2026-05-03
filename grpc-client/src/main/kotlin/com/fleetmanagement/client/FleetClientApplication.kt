package com.fleetmanagement.client

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

@SpringBootApplication
class FleetClientApplication

fun main(args: Array<String>) {
    runApplication<FleetClientApplication>(*args)
}
