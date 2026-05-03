package com.fleetmanagement.client.runner

import com.fleetmanagement.client.auth.BearerTokenCredentials
import com.fleetmanagement.client.auth.TokenGenerator
import com.fleetmanagement.proto.common.address
import com.fleetmanagement.proto.common.coordinate
import com.fleetmanagement.proto.document.DocumentServiceGrpcKt
import com.fleetmanagement.proto.document.documentMetadata
import com.fleetmanagement.proto.document.downloadRequest
import com.fleetmanagement.proto.document.uploadChunk
import com.fleetmanagement.proto.trip.TripServiceGrpcKt
import com.fleetmanagement.proto.trip.TripStatus
import com.fleetmanagement.proto.trip.createTripRequest
import com.fleetmanagement.proto.trip.getTripRequest
import com.fleetmanagement.proto.trip.positionPing
import com.fleetmanagement.proto.trip.startTripRequest
import com.fleetmanagement.proto.trip.waypoint
import com.fleetmanagement.proto.vehicle.VehicleServiceGrpcKt
import com.fleetmanagement.proto.vehicle.assignDriverRequest
import com.fleetmanagement.proto.vehicle.createVehicleRequest
import com.fleetmanagement.proto.vehicle.driver
import com.fleetmanagement.proto.vehicle.getVehicleRequest
import com.fleetmanagement.proto.vehicle.listVehiclesRequest
import com.fleetmanagement.proto.vehicle.streamLocationRequest
import com.fleetmanagement.proto.vehicle.updateLocationRequest
import com.fleetmanagement.proto.vehicle.VehicleStatus
import com.fleetmanagement.proto.vehicle.locationUpdate
import com.google.protobuf.ByteString
import com.google.protobuf.Timestamp
import io.grpc.Channel
import io.grpc.StatusException
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import net.devh.boot.grpc.client.inject.GrpcClient
import org.springframework.boot.CommandLineRunner
import org.springframework.stereotype.Component
import java.time.Instant
import java.time.ZoneOffset
import java.time.ZonedDateTime

// ANSI color codes for readable console output
private const val RESET  = "[0m"
private const val GREEN  = "[32m"
private const val YELLOW = "[33m"
private const val CYAN   = "[36m"
private const val RED    = "[31m"
private const val BOLD   = "[1m"

@Component
class FleetDemoRunner(
    private val tokenGenerator: TokenGenerator
) : CommandLineRunner {

    @GrpcClient("vehicle-service") private lateinit var vehicleChannel: Channel
    @GrpcClient("trip-service")    private lateinit var tripChannel: Channel
    @GrpcClient("document-service") private lateinit var documentChannel: Channel

    override fun run(vararg args: String) = runBlocking {
        banner("Fleet Management gRPC Demo Client")

        val creds = BearerTokenCredentials(tokenGenerator.generateToken())

        val vehicleStub  = VehicleServiceGrpcKt.VehicleServiceCoroutineStub(vehicleChannel).withCallCredentials(creds)
        val tripStub     = TripServiceGrpcKt.TripServiceCoroutineStub(tripChannel).withCallCredentials(creds)
        val documentStub = DocumentServiceGrpcKt.DocumentServiceCoroutineStub(documentChannel).withCallCredentials(creds)

        try {
            // ── PHASE 1: Vehicle Service ──────────────────────────────────────
            section("PHASE 1 — Vehicle Service (vehicle-service :9091)")
            val vehicleId = runVehicleDemo(vehicleStub)

            // ── PHASE 2: Trip Service (calls vehicle-service internally) ──────
            section("PHASE 2 — Trip Service (trip-service :9092 → vehicle-service :9091)")
            runTripDemo(tripStub, vehicleId)

            // ── PHASE 3: Document Service ─────────────────────────────────────
            section("PHASE 3 — Document Service (document-service :9093)")
            runDocumentDemo(documentStub, vehicleId)

            banner("All demos completed successfully ✓")

        } catch (e: StatusException) {
            println("${RED}[FAILED] gRPC call failed: ${e.status}${RESET}")
            println("${RED}Make sure all three services are running. See README for instructions.${RESET}")
        }
    }

    // =========================================================================
    // VEHICLE DEMO
    // =========================================================================
    private suspend fun runVehicleDemo(stub: VehicleServiceGrpcKt.VehicleServiceCoroutineStub): String {

        // --- Unary: CreateVehicle ---
        step("CreateVehicle (Unary) — all scalar + nested types")
        val vehicle = stub.createVehicle(createVehicleRequest {
            make  = "Toyota"
            model = "Land Cruiser"
            year  = 2023                          // int32
            licensePlate = "B-FL-2024"
            color = "Pearl White"
            fuelCapacityLiters  = 87.0            // double
            passengerCapacity   = 7               // int32
            features.addAll(listOf("GPS", "4WD", "DASH_CAM", "REAR_CAMERA"))  // repeated string
            homeBase = address {                  // nested Address → nested Coordinate
                street = "Potsdamer Str. 1"
                city   = "Berlin"
                country = "DE"
                postalCode = "10785"
                coordinates = coordinate {
                    latitude  = 52.5096           // double — WGS84
                    longitude = 13.3744           // double — WGS84
                    altitudeMeters = 34.0f        // float
                }
            }
        })
        ok("Vehicle created: id=${vehicle.id}, make=${vehicle.make}, year=${vehicle.year}")
        ok("  → home base: ${vehicle.homeBase.city} (${vehicle.homeBase.coordinates.latitude}, ${vehicle.homeBase.coordinates.longitude})")
        ok("  → status enum: ${vehicle.status}")
        ok("  → features: ${vehicle.featuresList}")
        ok("  → registeredAt (Timestamp): ${tsToString(vehicle.registeredAt)}")

        // --- Unary: AssignDriver (nested object with all field types) ---
        step("AssignDriver (Unary) — nested Driver with float, bool, Timestamp, repeated")
        val withDriver = stub.assignDriver(assignDriverRequest {
            vehicleId = vehicle.id
            driver = driver {
                id     = "drv-001"
                firstName = "Hans"
                lastName  = "Müller"
                phone     = "+49-30-12345678"     // string
                email     = "hans@fleet.de"
                licenseNumber = "DE-2019-XY"
                licenseExpiry = epochTs(ZonedDateTime.of(2027, 6, 1, 0, 0, 0, 0, ZoneOffset.UTC))
                rating   = 4.8f                   // float
                certifications.addAll(listOf("ADR", "FORKLIFT", "HGV"))  // repeated string
                isActive = true                   // bool
            }
        })
        ok("Driver assigned: ${withDriver.assignedDriver.firstName} ${withDriver.assignedDriver.lastName}")
        ok("  → rating (float): ${withDriver.assignedDriver.rating}")
        ok("  → certifications: ${withDriver.assignedDriver.certificationsList}")
        ok("  → licenseExpiry (Timestamp/date): ${tsToString(withDriver.assignedDriver.licenseExpiry)}")

        // --- Unary: ListVehicles (repeated nested objects) ---
        step("ListVehicles (Unary) — response contains repeated Vehicle (nested object array)")
        val list = stub.listVehicles(listVehiclesRequest {
            statusFilter = VehicleStatus.VEHICLE_STATUS_UNSPECIFIED
            page = 0
            pageSize = 10
        })
        ok("Found ${list.vehiclesList.size} vehicle(s), total=${list.pageInfo.totalCount}")

        // --- Server-Streaming: StreamLocationUpdates ---
        step("StreamLocationUpdates (Server-Streaming) — server pushes 20 updates")
        val updates = stub.streamLocationUpdates(streamLocationRequest {
            vehicleId = vehicle.id
        }).toList()
        val last = updates.last()
        ok("Received ${updates.size} location updates:")
        ok("  → lat (double): ${last.coordinates.latitude}")
        ok("  → lng (double): ${last.coordinates.longitude}")
        ok("  → altitude (float): ${last.coordinates.altitudeMeters}m")
        ok("  → speed (float): ${last.speedKmh} km/h")
        ok("  → fuel (int32): ${last.fuelLevelPercent}%")
        ok("  → recordedAt (Timestamp): ${tsToString(last.recordedAt)}")

        // --- Client-Streaming: BatchUpdateLocations ---
        step("BatchUpdateLocations (Client-Streaming) — client streams 5 location pings")
        val pings = (1..5).map { i ->
            updateLocationRequest {
                location = locationUpdate {
                    vehicleId = vehicle.id
                    coordinates = coordinate {
                        latitude  = 52.52 + i * 0.001
                        longitude = 13.40 + i * 0.001
                    }
                    recordedAt = nowTs()
                    speedKmh = 50.0f + i
                    fuelLevelPercent = 80 - i
                }
            }
        }
        stub.batchUpdateLocations(flow { pings.forEach { emit(it) } })
        ok("Streamed 5 location pings to server (client-streaming pattern)")

        return vehicle.id
    }

    // =========================================================================
    // TRIP DEMO
    // =========================================================================
    private suspend fun runTripDemo(stub: TripServiceGrpcKt.TripServiceCoroutineStub, vehicleId: String) {

        // --- Unary: CreateTrip (trip-service calls vehicle-service internally) ---
        step("CreateTrip (Unary) — trip-service validates vehicle via cross-service gRPC call")
        step("  + repeated Waypoint, map<string,string>, multiple Timestamps")
        val trip = stub.createTrip(createTripRequest {
            this.vehicleId = vehicleId
            driverId    = "drv-001"
            description = "Berlin to Hamburg — refrigerated cargo delivery"

            origin = address {
                street = "Potsdamer Str. 1"; city = "Berlin"; country = "DE"
                coordinates = coordinate { latitude = 52.5096; longitude = 13.3744 }
            }
            destination = address {
                street = "Speicherstadt 1"; city = "Hamburg"; country = "DE"
                coordinates = coordinate { latitude = 53.5415; longitude = 9.9863 }
            }

            // repeated Waypoint — array of nested objects, each with mixed types
            waypoints.addAll(listOf(
                waypoint {
                    sequence = 0                    // int32
                    name     = "Warehouse A"
                    coordinates = coordinate { latitude = 52.48; longitude = 13.42 }
                    scheduledArrival = epochTs(ZonedDateTime.of(2024, 6, 1, 10, 0, 0, 0, ZoneOffset.UTC))
                    notes       = "Pick up refrigerated cargo"
                    isMandatory = true              // bool
                },
                waypoint {
                    sequence = 1
                    name     = "Rest Stop Fischbek"
                    coordinates = coordinate { latitude = 53.0; longitude = 10.5 }
                    scheduledArrival = epochTs(ZonedDateTime.of(2024, 6, 1, 13, 0, 0, 0, ZoneOffset.UTC))
                    isMandatory = false
                }
            ))

            scheduledStart = epochTs(ZonedDateTime.of(2024, 6, 1, 9, 0, 0, 0, ZoneOffset.UTC))
            scheduledEnd   = epochTs(ZonedDateTime.of(2024, 6, 1, 16, 0, 0, 0, ZoneOffset.UTC))
            passengerCount = 1                      // int32

            tags.addAll(listOf("urgent", "refrigerated", "B2B"))  // repeated string

            // map<string, string> — arbitrary metadata
            metadata.putAll(mapOf(
                "cargo_type"   to "refrigerated",
                "priority"     to "HIGH",
                "customer_ref" to "CUST-987",
                "temperature"  to "-18C"
            ))
        })
        ok("Trip created: id=${trip.id}")
        ok("  → status enum: ${trip.status}")
        ok("  → waypoints count (repeated): ${trip.waypointsList.size}")
        ok("  → tags (repeated string): ${trip.tagsList}")
        ok("  → metadata (map): ${trip.metadataMap}")
        ok("  → scheduledStart (Timestamp): ${tsToString(trip.scheduledStart)}")
        ok("  → waypoint[0].isMandatory (bool): ${trip.waypointsList[0].isMandatory}")

        // --- Unary: StartTrip ---
        step("StartTrip (Unary)")
        val started = stub.startTrip(startTripRequest { tripId = trip.id })
        ok("Trip started: status=${started.status}, actualStart=${tsToString(started.actualStart)}")

        // --- Bidirectional Streaming: TrackTrip ---
        step("TrackTrip (Bidirectional Streaming) — client sends pings, server echoes enriched updates")
        val positions = (1..5).map { i ->
            positionPing {
                tripId = trip.id
                coordinates = coordinate {
                    latitude  = 52.5096 + i * 0.05
                    longitude = 13.3744 + i * 0.08
                }
                timestamp = nowTs()
                speedKmh  = 80.0f + i * 2
            }
        }
        val tripUpdates = stub.trackTrip(flow {
            positions.forEach { ping ->
                emit(ping)
                delay(100L)
            }
        }).toList()
        ok("Sent ${positions.size} position pings, received ${tripUpdates.size} trip updates")
        tripUpdates.lastOrNull()?.let { upd ->
            ok("  → last update: tripId=${upd.tripId}, speed=${upd.speedKmh} km/h, status=${upd.status}")
        }
    }

    // =========================================================================
    // DOCUMENT DEMO
    // =========================================================================
    private suspend fun runDocumentDemo(stub: DocumentServiceGrpcKt.DocumentServiceCoroutineStub, vehicleId: String) {

        // --- Client-Streaming: UploadDocument (bytes type) ---
        step("UploadDocument (Client-Streaming) — transfers file as bytes chunks via gRPC")
        val fakeContent = "FAKE PDF CONTENT — %s bytes of data for demo purposes".format("x".repeat(2000))
        val contentBytes = fakeContent.toByteArray()
        val chunkSize = 512

        val chunks = buildList {
            // First message: metadata (oneof.metadata)
            add(uploadChunk {
                metadata = documentMetadata {
                    filename   = "vehicle-registration-${vehicleId}.pdf"
                    mimeType   = "application/pdf"
                    entityType = "vehicle"
                    entityId   = vehicleId
                    tags.addAll(listOf("registration", "legal"))          // repeated string
                    customAttributes.putAll(mapOf("year" to "2024"))       // map<string,string>
                }
            })
            // Subsequent messages: byte chunks (oneof.content)
            contentBytes.toList().chunked(chunkSize).forEach { chunk ->
                add(uploadChunk {
                    content = ByteString.copyFrom(chunk.toByteArray())    // bytes type
                })
            }
        }

        val meta = stub.uploadDocument(flow { chunks.forEach { emit(it) } })
        ok("File uploaded: id=${meta.id}, filename=${meta.filename}")
        ok("  → sizeBytes (int64): ${meta.sizeBytes}")
        ok("  → mimeType: ${meta.mimeType}")
        ok("  → tags: ${meta.tagsList}")
        ok("  → createdAt (Timestamp): ${tsToString(meta.createdAt)}")
        ok("  → sent in ${chunks.size} chunks (1 metadata + ${chunks.size - 1} byte chunks)")

        // --- Server-Streaming: DownloadDocument (bytes type) ---
        step("DownloadDocument (Server-Streaming) — server streams file bytes back in chunks")
        val downloadChunks = stub.downloadDocument(downloadRequest { documentId = meta.id }).toList()

        val metaChunk    = downloadChunks.first()
        val contentChunks = downloadChunks.drop(1)
        val reassembled  = contentChunks.fold(ByteArray(0)) { acc, chunk ->
            acc + chunk.content.toByteArray()
        }

        ok("Downloaded: ${downloadChunks.size} chunks (1 metadata + ${contentChunks.size} content chunks)")
        ok("  → filename: ${metaChunk.metadata.filename}")
        ok("  → reassembled size: ${reassembled.size} bytes")
        ok("  → bytes integrity check: ${if (reassembled.contentEquals(contentBytes)) "PASS ✓" else "FAIL ✗"}")
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun banner(msg: String) {
        val line = "═".repeat(msg.length + 4)
        println("\n$BOLD$CYAN╔$line╗")
        println("║  $msg  ║")
        println("╚$line╝$RESET\n")
    }

    private fun section(title: String) {
        println("\n$BOLD$YELLOW▶ $title$RESET")
        println("$YELLOW${"─".repeat(60)}$RESET")
    }

    private fun step(msg: String) = println("  $CYAN→ $msg$RESET")
    private fun ok(msg: String)   = println("  $GREEN✓ $msg$RESET")

    private fun nowTs(): Timestamp = Timestamp.newBuilder()
        .setSeconds(Instant.now().epochSecond).setNanos(0).build()

    private fun epochTs(zdt: ZonedDateTime): Timestamp = Timestamp.newBuilder()
        .setSeconds(zdt.toEpochSecond()).setNanos(0).build()

    private fun tsToString(ts: Timestamp): String =
        Instant.ofEpochSecond(ts.seconds).toString()
}
