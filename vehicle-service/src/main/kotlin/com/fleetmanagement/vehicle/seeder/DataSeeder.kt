package com.fleetmanagement.vehicle.seeder

import com.fleetmanagement.proto.common.address
import com.fleetmanagement.proto.common.coordinate
import com.fleetmanagement.proto.vehicle.Driver
import com.fleetmanagement.proto.vehicle.VehicleStatus
import com.fleetmanagement.proto.vehicle.driver
import com.fleetmanagement.proto.vehicle.vehicle
import com.fleetmanagement.vehicle.repository.VehicleRepository
import com.google.protobuf.Timestamp
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component
import java.time.LocalDate
import java.time.ZoneOffset

@Component
class DataSeeder(private val repository: VehicleRepository) : ApplicationRunner {

    private val log = LoggerFactory.getLogger(DataSeeder::class.java)

    override fun run(args: ApplicationArguments) {
        seedVehicles()
        log.info("Seed data loaded: 3 vehicles")
    }

    private fun ts(year: Int, month: Int, day: Int): Timestamp {
        val epoch = LocalDate.of(year, month, day).atStartOfDay().toEpochSecond(ZoneOffset.UTC)
        return Timestamp.newBuilder().setSeconds(epoch).build()
    }

    private fun seedVehicles() {

        // ── Vehicle 1 — Toyota Land Cruiser, AVAILABLE ──────────────────────
        repository.save(vehicle {
            id           = "veh-seed-001"
            make         = "Toyota"
            model        = "Land Cruiser"
            year         = 2022
            licensePlate = "B-FL-001"
            color        = "White"
            fuelCapacityLiters  = 93.0
            passengerCapacity   = 7
            status       = VehicleStatus.VEHICLE_STATUS_AVAILABLE
            registeredAt = ts(2022, 3, 15)
            features.addAll(listOf("GPS", "4WD", "DASH_CAM", "REAR_CAMERA", "BLUETOOTH"))
            assignedDriver = driver {
                id            = "drv-seed-001"
                firstName     = "Hans"
                lastName      = "Müller"
                phone         = "+49-30-11111111"
                email         = "hans.mueller@fleet.de"
                licenseNumber = "DE-2019-HM001"
                licenseExpiry = ts(2029, 6, 30)
                rating        = 4.8f
                certifications.addAll(listOf("ADR", "HGV", "FORKLIFT"))
                isActive      = true
            }
            homeBase = address {
                street     = "Potsdamer Str. 1"
                city       = "Berlin"
                country    = "DE"
                postalCode = "10785"
                coordinates = coordinate {
                    latitude  = 52.5096
                    longitude = 13.3744
                }
            }
        })

        // ── Vehicle 2 — Mercedes Sprinter, ON_TRIP ───────────────────────────
        repository.save(vehicle {
            id           = "veh-seed-002"
            make         = "Mercedes-Benz"
            model        = "Sprinter 316"
            year         = 2021
            licensePlate = "HH-FL-002"
            color        = "Silver"
            fuelCapacityLiters  = 75.0
            passengerCapacity   = 3
            status       = VehicleStatus.VEHICLE_STATUS_ON_TRIP
            registeredAt = ts(2021, 7, 20)
            features.addAll(listOf("GPS", "CLIMATE_CONTROL", "CARGO_NET", "BACKUP_CAMERA"))
            assignedDriver = driver {
                id            = "drv-seed-002"
                firstName     = "Anna"
                lastName      = "Schmidt"
                phone         = "+49-40-22222222"
                email         = "anna.schmidt@fleet.de"
                licenseNumber = "HH-2020-AS002"
                licenseExpiry = ts(2028, 12, 31)
                rating        = 4.9f
                certifications.addAll(listOf("HGV", "HAZMAT"))
                isActive      = true
            }
            homeBase = address {
                street     = "Speicherstadt 1"
                city       = "Hamburg"
                country    = "DE"
                postalCode = "20457"
                coordinates = coordinate {
                    latitude  = 53.5415
                    longitude = 9.9863
                }
            }
        })

        // ── Vehicle 3 — Volkswagen Crafter, MAINTENANCE ─────────────────────
        repository.save(vehicle {
            id           = "veh-seed-003"
            make         = "Volkswagen"
            model        = "Crafter 35"
            year         = 2020
            licensePlate = "M-FL-003"
            color        = "Blue"
            fuelCapacityLiters  = 80.0
            passengerCapacity   = 2
            status       = VehicleStatus.VEHICLE_STATUS_MAINTENANCE
            registeredAt = ts(2020, 11, 5)
            features.addAll(listOf("GPS", "DASH_CAM", "TACHOGRAPH"))
            homeBase = address {
                street     = "Leopoldstr. 100"
                city       = "Munich"
                country    = "DE"
                postalCode = "80802"
                coordinates = coordinate {
                    latitude  = 48.1614
                    longitude = 11.5839
                }
            }
        })
    }
}
