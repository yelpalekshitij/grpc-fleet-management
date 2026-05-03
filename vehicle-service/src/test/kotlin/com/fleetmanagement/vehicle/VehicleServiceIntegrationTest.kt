package com.fleetmanagement.vehicle

import com.fleetmanagement.proto.common.address
import com.fleetmanagement.proto.common.coordinate
import com.fleetmanagement.proto.vehicle.VehicleServiceGrpcKt
import com.fleetmanagement.proto.vehicle.VehicleStatus
import com.fleetmanagement.proto.vehicle.assignDriverRequest
import com.fleetmanagement.proto.vehicle.createVehicleRequest
import com.fleetmanagement.proto.vehicle.driver
import com.fleetmanagement.proto.vehicle.getVehicleRequest
import com.fleetmanagement.proto.vehicle.listVehiclesRequest
import com.fleetmanagement.proto.vehicle.locationUpdate
import com.fleetmanagement.proto.vehicle.streamLocationRequest
import com.fleetmanagement.proto.vehicle.updateLocationRequest
import com.fleetmanagement.vehicle.grpc.VehicleGrpcService
import com.fleetmanagement.vehicle.repository.VehicleRepository
import com.google.protobuf.Timestamp
import io.grpc.ManagedChannel
import io.grpc.Server
import io.grpc.StatusException
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
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

// =============================================================================
// VEHICLE SERVICE — INTEGRATION TESTS
//
// Strategy: in-process gRPC (no network, no Spring context, no port conflicts).
// The real VehicleGrpcService + VehicleRepository are wired together directly.
// Tests run in order to share state (vehicle created in test 1 used in test 2+).
//
// What's tested here:
//   ✓ Unary RPC — request/response roundtrip with all data types
//   ✓ Server-streaming — Flow<LocationUpdate> emission
//   ✓ Client-streaming — Flow<UpdateLocationRequest> collection
//   ✓ gRPC Status codes — NOT_FOUND propagation
//   ✓ Data type correctness — string, int32, float, double, bool, enum, Timestamp,
//     repeated, nested messages
// =============================================================================

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class VehicleServiceIntegrationTest {

    private lateinit var server: Server
    private lateinit var channel: ManagedChannel
    private lateinit var stub: VehicleServiceGrpcKt.VehicleServiceCoroutineStub

    private val repository = VehicleRepository()
    private lateinit var vehicleId: String
    private lateinit var driverId: String

    @BeforeAll
    fun startServer() {
        val serverName = InProcessServerBuilder.generateName()

        server = InProcessServerBuilder.forName(serverName)
            .directExecutor()
            .addService(VehicleGrpcService(repository))  // no interceptors — tests pure service logic
            .build()
            .start()

        channel = InProcessChannelBuilder.forName(serverName)
            .directExecutor()
            .build()

        stub = VehicleServiceGrpcKt.VehicleServiceCoroutineStub(channel)
    }

    @AfterAll
    fun stopServer() {
        channel.shutdownNow()
        server.shutdownNow()
    }

    // ─── Tests ───────────────────────────────────────────────────────────────

    @Test
    @Order(1)
    fun `createVehicle — all scalar and nested types round-trip correctly`() = runBlocking {
        val response = stub.createVehicle(createVehicleRequest {
            make  = "Toyota"
            model = "Land Cruiser"
            year  = 2023                          // int32
            licensePlate = "B-FL-001"
            color = "Pearl White"
            fuelCapacityLiters = 87.0             // double
            passengerCapacity  = 7                // int32
            features.addAll(listOf("GPS", "4WD", "DASH_CAM"))  // repeated string

            homeBase = address {                  // nested Address
                street  = "Potsdamer Str. 1"
                city    = "Berlin"
                country = "DE"
                coordinates = coordinate {
                    latitude  = 52.5096           // double — WGS84
                    longitude = 13.3744           // double — WGS84
                    altitudeMeters = 34.0f        // float
                }
            }
        })

        vehicleId = response.id

        // Scalars
        assertThat(response.id).isNotBlank()
        assertThat(response.make).isEqualTo("Toyota")
        assertThat(response.model).isEqualTo("Land Cruiser")
        assertThat(response.year).isEqualTo(2023)                       // int32
        assertThat(response.fuelCapacityLiters).isEqualTo(87.0)         // double
        assertThat(response.passengerCapacity).isEqualTo(7)             // int32
        assertThat(response.color).isEqualTo("Pearl White")

        // Enum
        assertThat(response.status).isEqualTo(VehicleStatus.VEHICLE_STATUS_AVAILABLE)

        // repeated string
        assertThat(response.featuresList).containsExactlyInAnyOrder("GPS", "4WD", "DASH_CAM")

        // Nested message — Address → Coordinate
        assertThat(response.homeBase.city).isEqualTo("Berlin")
        assertThat(response.homeBase.coordinates.latitude).isEqualTo(52.5096)   // double
        assertThat(response.homeBase.coordinates.longitude).isEqualTo(13.3744)  // double
        assertThat(response.homeBase.coordinates.altitudeMeters).isEqualTo(34.0f) // float

        // Timestamp (datetime) — auto-set on creation
        assertThat(response.registeredAt.seconds).isGreaterThan(0L)
    }

    @Test
    @Order(2)
    fun `getVehicle — retrieves the vehicle created in test 1`() = runBlocking {
        val response = stub.getVehicle(getVehicleRequest { id = vehicleId })

        assertThat(response.id).isEqualTo(vehicleId)
        assertThat(response.make).isEqualTo("Toyota")
    }

    @Test
    @Order(3)
    fun `assignDriver — embeds nested Driver with all field types`() = runBlocking {
        driverId = "drv-001"

        val response = stub.assignDriver(assignDriverRequest {
            vehicleId = this@VehicleServiceIntegrationTest.vehicleId
            driver = driver {
                id     = driverId
                firstName = "Hans"
                lastName  = "Müller"
                phone  = "+49-30-12345678"        // string
                email  = "hans@fleet.de"
                licenseNumber = "DE-2019-XY"
                licenseExpiry = Timestamp.newBuilder()  // Timestamp used as date-only
                    .setSeconds(java.time.LocalDate.of(2027, 6, 1)
                        .atStartOfDay(java.time.ZoneOffset.UTC).toEpochSecond())
                    .build()
                rating = 4.8f                     // float
                certifications.addAll(listOf("ADR", "HGV"))  // repeated string
                isActive = true                   // bool
            }
        })

        val d = response.assignedDriver
        assertThat(d.id).isEqualTo("drv-001")
        assertThat(d.firstName).isEqualTo("Hans")
        assertThat(d.rating).isEqualTo(4.8f)                       // float
        assertThat(d.certificationsList).containsExactly("ADR", "HGV")  // repeated string
        assertThat(d.isActive).isTrue()                            // bool
        assertThat(d.licenseExpiry.seconds).isGreaterThan(0L)      // Timestamp
    }

    @Test
    @Order(4)
    fun `listVehicles — returns paginated repeated nested Vehicle objects`() = runBlocking {
        val response = stub.listVehicles(listVehiclesRequest {
            statusFilter = VehicleStatus.VEHICLE_STATUS_UNSPECIFIED  // return all
            page     = 0
            pageSize = 10
        })

        // repeated Vehicle (array of nested objects)
        assertThat(response.vehiclesList).isNotEmpty()
        assertThat(response.pageInfo.totalCount).isGreaterThan(0)   // PageInfo nested message
        assertThat(response.pageInfo.page).isEqualTo(0)
    }

    @Test
    @Order(5)
    fun `listVehicles with status filter — only returns AVAILABLE vehicles`() = runBlocking {
        val response = stub.listVehicles(listVehiclesRequest {
            statusFilter = VehicleStatus.VEHICLE_STATUS_AVAILABLE    // enum filter
            pageSize = 20
        })

        assertThat(response.vehiclesList).allMatch {
            it.status == VehicleStatus.VEHICLE_STATUS_AVAILABLE
        }
    }

    @Test
    @Order(6)
    fun `streamLocationUpdates — server-streaming emits multiple LocationUpdate messages`() = runBlocking {
        // Server-streaming: toList() collects all emitted messages
        val updates = stub.streamLocationUpdates(
            streamLocationRequest { vehicleId = this@VehicleServiceIntegrationTest.vehicleId }
        ).toList()

        assertThat(updates).hasSize(20)

        val first = updates.first()
        assertThat(first.vehicleId).isEqualTo(vehicleId)
        assertThat(first.coordinates.latitude).isBetween(-90.0, 90.0)    // double WGS84
        assertThat(first.coordinates.longitude).isBetween(-180.0, 180.0) // double WGS84
        assertThat(first.coordinates.altitudeMeters).isEqualTo(34.0f)    // float
        assertThat(first.speedKmh).isGreaterThan(0f)                     // float
        assertThat(first.fuelLevelPercent).isBetween(0, 100)             // int32
        assertThat(first.recordedAt.seconds).isGreaterThan(0L)           // Timestamp (datetime)

        // Verify coordinates change across updates (simulated movement)
        assertThat(updates.first().coordinates.latitude)
            .isNotEqualTo(updates.last().coordinates.latitude)
    }

    @Test
    @Order(7)
    fun `batchUpdateLocations — client-streaming processes all streamed messages`() = runBlocking {
        val batchSize = 5
        val pings = (1..batchSize).map { i ->
            updateLocationRequest {
                location = locationUpdate {
                    vehicleId = this@VehicleServiceIntegrationTest.vehicleId
                    coordinates = coordinate {
                        latitude  = 52.52 + i * 0.001   // double
                        longitude = 13.40 + i * 0.001   // double
                    }
                    recordedAt = Timestamp.newBuilder()
                        .setSeconds(Instant.now().epochSecond).build()
                    speedKmh = 60.0f + i            // float
                    fuelLevelPercent = 75 - i       // int32
                }
            }
        }

        // Client-streaming: wrap list in a Flow, stub.collect() processes each message
        val result = stub.batchUpdateLocations(flow { pings.forEach { emit(it) } })

        // Server returns Empty (no body) when all messages processed
        assertThat(result).isNotNull()
    }

    @Test
    @Order(8)
    fun `getVehicle — returns NOT_FOUND status for unknown vehicle ID`() = runBlocking {
        assertThatThrownBy {
            runBlocking {
                stub.getVehicle(getVehicleRequest { id = "this-id-does-not-exist" })
            }
        }.isInstanceOf(StatusException::class.java)
            .satisfies({ ex: Throwable ->
                assertThat((ex as StatusException).status.code)
                    .isEqualTo(io.grpc.Status.Code.NOT_FOUND)
            })
    }
}
