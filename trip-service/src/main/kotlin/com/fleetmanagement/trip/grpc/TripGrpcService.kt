

package com.fleetmanagement.trip.grpc

import com.fleetmanagement.proto.common.pageInfo
import com.fleetmanagement.proto.trip.CompleteTripRequest
import com.fleetmanagement.proto.trip.CreateTripRequest
import com.fleetmanagement.proto.trip.GetTripRequest
import com.fleetmanagement.proto.trip.ListTripsRequest
import com.fleetmanagement.proto.trip.ListTripsResponse
import com.fleetmanagement.proto.trip.PositionPing
import com.fleetmanagement.proto.trip.StartTripRequest
import com.fleetmanagement.proto.trip.Trip
import com.fleetmanagement.proto.trip.TripServiceGrpcKt
import com.fleetmanagement.proto.trip.TripStatus
import com.fleetmanagement.proto.trip.TripUpdate
import com.fleetmanagement.proto.trip.listTripsResponse
import com.fleetmanagement.proto.trip.trip
import com.fleetmanagement.proto.trip.tripUpdate
import com.fleetmanagement.proto.vehicle.GetVehicleRequest
import com.fleetmanagement.proto.vehicle.UpdateVehicleStatusRequest
import com.fleetmanagement.proto.vehicle.VehicleServiceGrpcKt
import com.fleetmanagement.proto.vehicle.VehicleStatus
import com.fleetmanagement.proto.vehicle.getVehicleRequest
import com.fleetmanagement.proto.vehicle.updateVehicleStatusRequest
import com.fleetmanagement.trip.repository.TripRepository
import com.google.protobuf.Timestamp
import io.grpc.Status
import io.grpc.StatusException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import net.devh.boot.grpc.server.service.GrpcService
import org.slf4j.LoggerFactory
import java.time.Instant
import java.util.UUID

// =============================================================================
// TRIP gRPC SERVICE IMPLEMENTATION
//
// This service demonstrates:
//   1. Calling another service (vehicle-service) via gRPC client stub
//   2. Bidirectional streaming with Flow<in> → Flow<out>
//   3. Accessing JWT context set by the server interceptor
//   4. map<string,string> and repeated Waypoint in proto messages
// =============================================================================

// vehicleStub is injected via constructor (from GrpcClientConfig bean) so it can be
// replaced with an in-process stub in integration tests without Spring overhead.
@GrpcService
class TripGrpcService(
    private val repository: TripRepository,
    private val vehicleStub: VehicleServiceGrpcKt.VehicleServiceCoroutineStub
) : TripServiceGrpcKt.TripServiceCoroutineImplBase() {

    private val log = LoggerFactory.getLogger(TripGrpcService::class.java)

    // =========================================================================
    // UNARY RPC — CreateTrip
    //
    // Before creating a trip, we call vehicle-service via gRPC to:
    //   1. Verify the vehicle exists
    //   2. Check it's available (not already on a trip)
    // This is inter-service gRPC communication in action.
    // =========================================================================
    override suspend fun createTrip(request: CreateTripRequest): Trip {
        log.info("CreateTrip: vehicle={}, waypoints={}", request.vehicleId, request.waypointsCount)

        // ---- Cross-service gRPC call ----------------------------------------
        // vehicleStub.getVehicle() is a suspend fun — we await it with no
        // thread blocking. If vehicle-service is down, this throws a StatusException.
        val vehicle = try {
            vehicleStub.getVehicle(
                getVehicleRequest { id = request.vehicleId }
            )
        } catch (e: StatusException) {
            throw StatusException(
                Status.FAILED_PRECONDITION.withDescription(
                    "Could not verify vehicle '${request.vehicleId}': ${e.status.description}"
                )
            )
        }

        if (vehicle.status == VehicleStatus.VEHICLE_STATUS_ON_TRIP) {
            throw StatusException(
                Status.FAILED_PRECONDITION.withDescription(
                    "Vehicle '${request.vehicleId}' is already on a trip"
                )
            )
        }

        if (vehicle.status == VehicleStatus.VEHICLE_STATUS_MAINTENANCE ||
            vehicle.status == VehicleStatus.VEHICLE_STATUS_INACTIVE) {
            throw StatusException(
                Status.FAILED_PRECONDITION.withDescription(
                    "Vehicle '${request.vehicleId}' is not available (status: ${vehicle.status})"
                )
            )
        }

        val id = UUID.randomUUID().toString()

        // Kotlin DSL builder for Trip — demonstrates all rich data types
        val created = trip {
            this.id = id
            vehicleId = request.vehicleId           // string
            driverId = request.driverId             // string
            description = request.description       // string

            if (request.hasOrigin()) origin = request.origin           // nested Address
            if (request.hasDestination()) destination = request.destination // nested Address

            // repeated Waypoint — array of nested complex objects
            // Each Waypoint itself contains: int32, string, Coordinate, Timestamp, bool
            waypoints.addAll(request.waypointsList)

            scheduledStart = request.scheduledStart    // Timestamp (datetime)
            scheduledEnd = request.scheduledEnd        // Timestamp (datetime)
            passengerCount = request.passengerCount    // int32
            estimatedDistanceKm = 0.0                  // double (calculated later)

            // repeated string — simple string array for tags/categories
            tags.addAll(request.tagsList)

            // map<string, string> — flexible key-value metadata
            // e.g. {"cargo_type": "refrigerated", "priority": "high"}
            metadata.putAll(request.metadataMap)

            status = TripStatus.TRIP_STATUS_PLANNED   // enum
        }

        repository.save(created)

        // Update vehicle status via another gRPC call
        vehicleStub.updateVehicleStatus(
            updateVehicleStatusRequest {
                vehicleId = request.vehicleId
                status = VehicleStatus.VEHICLE_STATUS_ON_TRIP
            }
        )

        return created
    }

    // =========================================================================
    // UNARY RPC — GetTrip
    // =========================================================================
    override suspend fun getTrip(request: GetTripRequest): Trip {
        return repository.findById(request.id)
            ?: throw StatusException(
                Status.NOT_FOUND.withDescription("Trip '${request.id}' not found")
            )
    }

    // =========================================================================
    // UNARY RPC — ListTrips
    // Demonstrates: listTripsResponse with repeated Trip (array of nested objects)
    // =========================================================================
    override suspend fun listTrips(request: ListTripsRequest): ListTripsResponse {
        val page = if (request.page < 0) 0 else request.page
        val pageSize = if (request.pageSize <= 0) 20 else request.pageSize

        val result = repository.findAll(
            vehicleId = request.vehicleId.ifBlank { null },
            statusFilter = request.statusFilter,
            page = page,
            pageSize = pageSize
        )

        return listTripsResponse {
            trips.addAll(result.items)
            pageInfo = pageInfo {
                this.page = page
                this.pageSize = pageSize
                totalCount = result.total
                hasNext = (page + 1) * pageSize < result.total
            }
        }
    }

    // =========================================================================
    // UNARY RPC — StartTrip
    // =========================================================================
    override suspend fun startTrip(request: StartTripRequest): Trip {
        val existing = repository.findById(request.tripId)
            ?: throw StatusException(Status.NOT_FOUND.withDescription("Trip not found"))

        if (existing.status != TripStatus.TRIP_STATUS_PLANNED) {
            throw StatusException(
                Status.FAILED_PRECONDITION.withDescription(
                    "Trip must be in PLANNED state to start. Current: ${existing.status}"
                )
            )
        }

        val updated = existing.toBuilder()
            .setStatus(TripStatus.TRIP_STATUS_IN_PROGRESS)
            .setActualStart(nowTimestamp())
            .build()

        repository.save(updated)
        return updated
    }

    // =========================================================================
    // UNARY RPC — CompleteTrip
    // =========================================================================
    override suspend fun completeTrip(request: CompleteTripRequest): Trip {
        val existing = repository.findById(request.tripId)
            ?: throw StatusException(Status.NOT_FOUND.withDescription("Trip not found"))

        val updated = existing.toBuilder()
            .setStatus(TripStatus.TRIP_STATUS_COMPLETED)
            .setActualEnd(nowTimestamp())
            .setActualDistanceKm(request.actualDistanceKm) // double
            .build()

        repository.save(updated)

        // Free up the vehicle via gRPC call to vehicle-service
        vehicleStub.updateVehicleStatus(
            updateVehicleStatusRequest {
                vehicleId = existing.vehicleId
                status = VehicleStatus.VEHICLE_STATUS_AVAILABLE
            }
        )

        return updated
    }

    // =========================================================================
    // BIDIRECTIONAL STREAMING RPC — TrackTrip
    //
    // Client streams PositionPings (GPS coordinates); server streams TripUpdates.
    // Both sides are independent — this is true full-duplex communication.
    //
    // Implementation pattern: use flow { } to emit responses while collect { }
    // processes incoming pings. In practice, a Channel<TripUpdate> bridges them.
    //
    // Simplified approach here: transform each incoming ping into one TripUpdate.
    // =========================================================================
    override fun trackTrip(requests: Flow<PositionPing>): Flow<TripUpdate> {
        return requests.map { ping ->
            log.debug("Received position ping for trip: {}, lat={}, lng={}",
                ping.tripId, ping.coordinates.latitude, ping.coordinates.longitude)

            val trip = repository.findById(ping.tripId)
                ?: throw StatusException(Status.NOT_FOUND.withDescription("Trip '${ping.tripId}' not found"))

            val waypointsCompleted = trip.waypointsList.count { waypoint ->
                waypoint.hasActualArrival()
            }

            // Emit an enriched TripUpdate back to the client for each ping
            tripUpdate {
                tripId = ping.tripId
                currentPosition = ping.coordinates   // Coordinate (double lat/lng)
                timestamp = ping.timestamp           // Timestamp (datetime)
                speedKmh = ping.speedKmh             // float
                this.waypointsCompleted = waypointsCompleted  // int32
                distanceCoveredKm = trip.actualDistanceKm     // double
                status = trip.status                 // enum
            }
        }
    }

    private fun nowTimestamp(): Timestamp {
        val now = Instant.now()
        return Timestamp.newBuilder()
            .setSeconds(now.epochSecond)
            .setNanos(now.nano)
            .build()
    }
}
