package com.fleetmanagement.trip

import com.fleetmanagement.proto.common.address
import com.fleetmanagement.proto.common.coordinate
import com.fleetmanagement.proto.trip.TripServiceGrpcKt
import com.fleetmanagement.proto.trip.TripStatus
import com.fleetmanagement.proto.trip.completeTripRequest
import com.fleetmanagement.proto.trip.createTripRequest
import com.fleetmanagement.proto.trip.getTripRequest
import com.fleetmanagement.proto.trip.positionPing
import com.fleetmanagement.proto.trip.startTripRequest
import com.fleetmanagement.proto.trip.waypoint
import com.fleetmanagement.proto.vehicle.VehicleServiceGrpcKt
import com.fleetmanagement.proto.vehicle.createVehicleRequest
import com.fleetmanagement.trip.grpc.TripGrpcService
import com.fleetmanagement.trip.repository.TripRepository
import com.fleetmanagement.vehicle.grpc.VehicleGrpcService
import com.fleetmanagement.vehicle.repository.VehicleRepository
import com.google.protobuf.Timestamp
import io.grpc.ManagedChannel
import io.grpc.Server
import io.grpc.StatusException
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.junit.jupiter.api.TestMethodOrder
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime

// =============================================================================
// TRIP SERVICE — INTEGRATION TEST
//
// This test wires BOTH vehicle-service and trip-service as in-process gRPC servers.
// TripGrpcService receives a real VehicleServiceCoroutineStub pointing at the
// in-process vehicle server — this is the cross-service gRPC call under test.
//
// What's tested:
//   ✓ Cross-service gRPC call (trip validates vehicle existence)
//   ✓ FAILED_PRECONDITION when vehicle is already on a trip
//   ✓ repeated Waypoint (array of nested objects) serialization
//   ✓ map<string,string> serialization
//   ✓ multiple Timestamp fields (scheduledStart, scheduledEnd, actualStart)
//   ✓ Bidirectional streaming — TrackTrip
//   ✓ Full trip lifecycle: create → start → track → complete
// =============================================================================

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class TripServiceIntegrationTest {

    private lateinit var vehicleServer: Server
    private lateinit var vehicleChannel: ManagedChannel

    private lateinit var tripServer: Server
    private lateinit var tripChannel: ManagedChannel
    private lateinit var tripStub: TripServiceGrpcKt.TripServiceCoroutineStub

    private lateinit var vehicleId: String
    private lateinit var tripId: String

    @BeforeAll
    fun startServers() {
        // 1. Start vehicle-service in-process
        val vehicleServerName = InProcessServerBuilder.generateName()
        val vehicleRepo = VehicleRepository()

        vehicleServer = InProcessServerBuilder.forName(vehicleServerName)
            .directExecutor()
            .addService(VehicleGrpcService(vehicleRepo))
            .build()
            .start()

        vehicleChannel = InProcessChannelBuilder.forName(vehicleServerName)
            .directExecutor().build()

        // 2. Wire the vehicleStub to point at the in-process vehicle server
        val vehicleStub = VehicleServiceGrpcKt.VehicleServiceCoroutineStub(vehicleChannel)

        // 3. Start trip-service in-process, injecting the real vehicleStub
        val tripServerName = InProcessServerBuilder.generateName()

        tripServer = InProcessServerBuilder.forName(tripServerName)
            .directExecutor()
            .addService(TripGrpcService(TripRepository(), vehicleStub))
            .build()
            .start()

        tripChannel = InProcessChannelBuilder.forName(tripServerName)
            .directExecutor().build()

        tripStub = TripServiceGrpcKt.TripServiceCoroutineStub(tripChannel)
    }

    @AfterAll
    fun stopServers() {
        tripChannel.shutdownNow(); tripServer.shutdownNow()
        vehicleChannel.shutdownNow(); vehicleServer.shutdownNow()
    }

    @Test
    @Order(1)
    fun `setup — create a vehicle in vehicle-service for trip tests`() { runBlocking {
        val vehicleStubDirect = VehicleServiceGrpcKt.VehicleServiceCoroutineStub(vehicleChannel)
        val vehicle = vehicleStubDirect.createVehicle(createVehicleRequest {
            make = "Mercedes"; model = "Actros"; year = 2022
            licensePlate = "HH-TRK-99"; color = "Red"
            fuelCapacityLiters = 400.0; passengerCapacity = 2
        })
        vehicleId = vehicle.id
        assertThat(vehicleId).isNotBlank()
    } }

    @Test
    @Order(2)
    fun `createTrip — cross-service call validates vehicle, repeated Waypoints and map serialized`() { runBlocking {
        val trip = tripStub.createTrip(createTripRequest {
            vehicleId   = this@TripServiceIntegrationTest.vehicleId
            driverId    = "drv-001"
            description = "Berlin to Hamburg refrigerated delivery"  // string

            origin = address {
                street = "Potsdamer Str. 1"; city = "Berlin"; country = "DE"
                coordinates = coordinate { latitude = 52.5096; longitude = 13.3744 }
            }
            destination = address {
                city = "Hamburg"; country = "DE"
                coordinates = coordinate { latitude = 53.5415; longitude = 9.9863 }
            }

            // repeated Waypoint — array of nested objects
            waypoints.addAll(listOf(
                waypoint {
                    sequence = 0                              // int32
                    name     = "Warehouse A"
                    coordinates = coordinate { latitude = 52.48; longitude = 13.42 }
                    scheduledArrival = epochTs(ZonedDateTime.of(2024, 6, 1, 10, 0, 0, 0, ZoneOffset.UTC))
                    isMandatory = true                        // bool
                    notes = "Cold chain handover"
                },
                waypoint {
                    sequence = 1
                    name = "Rest Area"
                    coordinates = coordinate { latitude = 53.0; longitude = 10.5 }
                    scheduledArrival = epochTs(ZonedDateTime.of(2024, 6, 1, 13, 0, 0, 0, ZoneOffset.UTC))
                    isMandatory = false
                }
            ))

            scheduledStart = epochTs(ZonedDateTime.of(2024, 6, 1, 9, 0, 0, 0, ZoneOffset.UTC))
            scheduledEnd   = epochTs(ZonedDateTime.of(2024, 6, 1, 16, 0, 0, 0, ZoneOffset.UTC))
            passengerCount = 1                                // int32

            tags.addAll(listOf("urgent", "refrigerated"))    // repeated string

            // map<string, string>
            metadata.putAll(mapOf(
                "cargo_type" to "refrigerated",
                "priority"   to "HIGH",
                "customer"   to "ACME GmbH"
            ))
        })

        tripId = trip.id

        assertThat(trip.id).isNotBlank()
        assertThat(trip.vehicleId).isEqualTo(vehicleId)
        assertThat(trip.status).isEqualTo(TripStatus.TRIP_STATUS_PLANNED)     // enum

        // repeated Waypoint (array of nested objects)
        assertThat(trip.waypointsList).hasSize(2)
        assertThat(trip.waypointsList[0].name).isEqualTo("Warehouse A")
        assertThat(trip.waypointsList[0].sequence).isEqualTo(0)               // int32
        assertThat(trip.waypointsList[0].isMandatory).isTrue()                // bool
        assertThat(trip.waypointsList[0].coordinates.latitude).isEqualTo(52.48) // double

        // repeated string
        assertThat(trip.tagsList).containsExactlyInAnyOrder("urgent", "refrigerated")

        // map<string, string>
        assertThat(trip.metadataMap).containsEntry("cargo_type", "refrigerated")
        assertThat(trip.metadataMap).containsEntry("priority", "HIGH")

        // Timestamps (datetime)
        assertThat(trip.scheduledStart.seconds).isGreaterThan(0L)
        assertThat(trip.scheduledEnd.seconds).isGreaterThan(trip.scheduledStart.seconds)
    } }

    @Test
    @Order(3)
    fun `createTrip — FAILED_PRECONDITION when vehicle is already on a trip`() {
        assertThatThrownBy {
            runBlocking {
                tripStub.createTrip(createTripRequest {
                    vehicleId = this@TripServiceIntegrationTest.vehicleId  // same vehicle
                    driverId  = "drv-002"
                    scheduledStart = nowTs()
                    scheduledEnd   = nowTs()
                })
            }
        }.isInstanceOf(StatusException::class.java)
            .satisfies({ ex: Throwable ->
                assertThat((ex as StatusException).status.code)
                    .isEqualTo(io.grpc.Status.Code.FAILED_PRECONDITION)
            })
    }

    @Test
    @Order(4)
    fun `startTrip — transitions status to IN_PROGRESS and sets actualStart Timestamp`() { runBlocking {
        val started = tripStub.startTrip(startTripRequest { tripId = this@TripServiceIntegrationTest.tripId })

        assertThat(started.status).isEqualTo(TripStatus.TRIP_STATUS_IN_PROGRESS)
        assertThat(started.actualStart.seconds).isGreaterThan(0L)  // Timestamp auto-set
    } }

    @Test
    @Order(5)
    fun `trackTrip — bidirectional streaming, each ping produces one TripUpdate`() { runBlocking {
        val pings = (1..4).map { i ->
            positionPing {
                tripId = this@TripServiceIntegrationTest.tripId
                coordinates = coordinate {
                    latitude  = 52.5096 + i * 0.05
                    longitude = 13.3744 + i * 0.08
                }
                timestamp = nowTs()
                speedKmh  = 80.0f + i * 2
            }
        }

        // Bidirectional: Flow<PositionPing> → Flow<TripUpdate>
        val updates = tripStub.trackTrip(flow {
            pings.forEach { ping -> emit(ping); delay(50L) }
        }).toList()

        assertThat(updates).hasSize(pings.size)

        val first = updates.first()
        assertThat(first.tripId).isEqualTo(tripId)
        assertThat(first.status).isEqualTo(TripStatus.TRIP_STATUS_IN_PROGRESS)  // enum
        assertThat(first.speedKmh).isGreaterThan(0f)                             // float
        assertThat(first.currentPosition.latitude).isGreaterThan(0.0)           // double
        assertThat(first.timestamp.seconds).isGreaterThan(0L)                   // Timestamp
    } }

    @Test
    @Order(6)
    fun `completeTrip — transitions status and records actual distance`() { runBlocking {
        val completed = tripStub.completeTrip(completeTripRequest {
            tripId = this@TripServiceIntegrationTest.tripId
            actualDistanceKm = 289.5  // double
        })

        assertThat(completed.status).isEqualTo(TripStatus.TRIP_STATUS_COMPLETED)
        assertThat(completed.actualDistanceKm).isEqualTo(289.5)  // double preserved
        assertThat(completed.actualEnd.seconds).isGreaterThan(0L) // Timestamp set
    } }

    @Test
    @Order(7)
    fun `getTrip — returns NOT_FOUND for unknown trip ID`() {
        assertThatThrownBy {
            runBlocking { tripStub.getTrip(getTripRequest { id = "non-existent" }) }
        }.isInstanceOf(StatusException::class.java)
            .satisfies({ ex: Throwable ->
                assertThat((ex as StatusException).status.code).isEqualTo(io.grpc.Status.Code.NOT_FOUND)
            })
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────
    private fun nowTs(): Timestamp = Timestamp.newBuilder()
        .setSeconds(Instant.now().epochSecond).build()

    private fun epochTs(zdt: ZonedDateTime): Timestamp = Timestamp.newBuilder()
        .setSeconds(zdt.toEpochSecond()).build()
}
