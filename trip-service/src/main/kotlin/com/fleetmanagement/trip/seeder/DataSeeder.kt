package com.fleetmanagement.trip.seeder

import com.fleetmanagement.proto.common.address
import com.fleetmanagement.proto.common.coordinate
import com.fleetmanagement.proto.trip.TripStatus
import com.fleetmanagement.proto.trip.trip
import com.fleetmanagement.proto.trip.waypoint
import com.fleetmanagement.trip.repository.TripRepository
import com.google.protobuf.Timestamp
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component
import java.time.LocalDateTime
import java.time.ZoneOffset

@Component
class DataSeeder(private val repository: TripRepository) : ApplicationRunner {

    private val log = LoggerFactory.getLogger(DataSeeder::class.java)

    override fun run(args: ApplicationArguments) {
        seedTrips()
        log.info("Seed data loaded: 3 trips")
    }

    private fun ts(year: Int, month: Int, day: Int, hour: Int = 0, minute: Int = 0): Timestamp {
        val epoch = LocalDateTime.of(year, month, day, hour, minute).toEpochSecond(ZoneOffset.UTC)
        return Timestamp.newBuilder().setSeconds(epoch).build()
    }

    private fun seedTrips() {

        // ── Trip 1 — Berlin → Hamburg, COMPLETED ────────────────────────────
        repository.save(trip {
            id          = "trip-seed-001"
            vehicleId   = "veh-seed-001"
            driverId    = "drv-seed-001"
            description = "Refrigerated cargo delivery — Berlin to Hamburg port"
            status      = TripStatus.TRIP_STATUS_COMPLETED
            scheduledStart = ts(2026, 4, 10, 7, 0)
            scheduledEnd   = ts(2026, 4, 10, 14, 0)
            actualStart    = ts(2026, 4, 10, 7, 15)
            actualEnd      = ts(2026, 4, 10, 13, 45)
            estimatedDistanceKm = 289.0
            actualDistanceKm    = 294.5
            passengerCount = 1
            tags.addAll(listOf("refrigerated", "priority", "port-delivery"))
            metadata["cargo_type"]   = "refrigerated_goods"
            metadata["customer_ref"] = "CUST-HAM-2024"
            metadata["priority"]     = "high"
            origin = address {
                street     = "Potsdamer Str. 1"
                city       = "Berlin"
                country    = "DE"
                postalCode = "10785"
                coordinates = coordinate { latitude = 52.5096; longitude = 13.3744 }
            }
            destination = address {
                street     = "Am Sandtorkai 1"
                city       = "Hamburg"
                country    = "DE"
                postalCode = "20457"
                coordinates = coordinate { latitude = 53.5415; longitude = 9.9863 }
            }
            waypoints.addAll(listOf(
                waypoint {
                    sequence = 0
                    name     = "Cold Storage Warehouse A"
                    coordinates = coordinate { latitude = 52.4800; longitude = 13.4200 }
                    scheduledArrival = ts(2026, 4, 10, 8, 30)
                    actualArrival    = ts(2026, 4, 10, 8, 45)
                    notes       = "Load refrigerated cargo — dock 3"
                    isMandatory = true
                },
                waypoint {
                    sequence = 1
                    name     = "Rest Stop — Autobahn A24"
                    coordinates = coordinate { latitude = 53.0100; longitude = 10.8900 }
                    scheduledArrival = ts(2026, 4, 10, 11, 0)
                    actualArrival    = ts(2026, 4, 10, 11, 10)
                    notes       = "Mandatory driver rest (EU regulation)"
                    isMandatory = true
                }
            ))
        })

        // ── Trip 2 — Munich → Frankfurt, IN_PROGRESS ────────────────────────
        repository.save(trip {
            id          = "trip-seed-002"
            vehicleId   = "veh-seed-002"
            driverId    = "drv-seed-002"
            description = "Express auto parts delivery — Munich to Frankfurt"
            status      = TripStatus.TRIP_STATUS_IN_PROGRESS
            scheduledStart = ts(2026, 5, 3, 6, 0)
            scheduledEnd   = ts(2026, 5, 3, 12, 30)
            actualStart    = ts(2026, 5, 3, 6, 5)
            estimatedDistanceKm = 395.0
            passengerCount = 2
            tags.addAll(listOf("auto-parts", "express", "b2b"))
            metadata["cargo_type"]   = "automotive_parts"
            metadata["customer_ref"] = "CUST-FRA-5678"
            metadata["fragile"]      = "true"
            origin = address {
                street     = "Leopoldstr. 100"
                city       = "Munich"
                country    = "DE"
                postalCode = "80802"
                coordinates = coordinate { latitude = 48.1614; longitude = 11.5839 }
            }
            destination = address {
                street     = "Hanauer Landstr. 200"
                city       = "Frankfurt"
                country    = "DE"
                postalCode = "60314"
                coordinates = coordinate { latitude = 50.1109; longitude = 8.6821 }
            }
            waypoints.addAll(listOf(
                waypoint {
                    sequence = 0
                    name     = "BMW Parts Distribution Center"
                    coordinates = coordinate { latitude = 48.2000; longitude = 11.6200 }
                    scheduledArrival = ts(2026, 5, 3, 7, 0)
                    notes       = "Pick up engine components"
                    isMandatory = true
                },
                waypoint {
                    sequence = 1
                    name     = "Nuremberg Depot"
                    coordinates = coordinate { latitude = 49.4521; longitude = 11.0767 }
                    scheduledArrival = ts(2026, 5, 3, 9, 30)
                    notes       = "Optional drop-off for sub-consignment"
                    isMandatory = false
                }
            ))
        })

        // ── Trip 3 — Berlin → Dresden, PLANNED ──────────────────────────────
        repository.save(trip {
            id          = "trip-seed-003"
            vehicleId   = "veh-seed-001"
            driverId    = "drv-seed-001"
            description = "Construction materials — Berlin to Dresden building site"
            status      = TripStatus.TRIP_STATUS_PLANNED
            scheduledStart = ts(2026, 5, 10, 8, 0)
            scheduledEnd   = ts(2026, 5, 10, 13, 0)
            estimatedDistanceKm = 193.0
            passengerCount = 1
            tags.addAll(listOf("construction", "heavy-load"))
            metadata["cargo_type"]    = "construction_materials"
            metadata["permit_number"] = "PERM-2026-0042"
            origin = address {
                street     = "Potsdamer Str. 1"
                city       = "Berlin"
                country    = "DE"
                postalCode = "10785"
                coordinates = coordinate { latitude = 52.5096; longitude = 13.3744 }
            }
            destination = address {
                street     = "Prager Str. 1"
                city       = "Dresden"
                country    = "DE"
                postalCode = "01069"
                coordinates = coordinate { latitude = 51.0504; longitude = 13.7373 }
            }
            waypoints.addAll(listOf(
                waypoint {
                    sequence = 0
                    name     = "Materials Depot Luckenwalde"
                    coordinates = coordinate { latitude = 52.0870; longitude = 13.1700 }
                    scheduledArrival = ts(2026, 5, 10, 9, 30)
                    notes       = "Load steel beams — crane required"
                    isMandatory = true
                }
            ))
        })
    }
}
