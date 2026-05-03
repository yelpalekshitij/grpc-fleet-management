# gRPC Fleet Management — Learning Demo

A hands-on Kotlin + Spring Boot microservices project that demonstrates **every
major gRPC concept** through a realistic fleet management system.

> **Spring Boot note:** This project uses Spring Boot 3.3.5. Spring Boot 4 had
> not reached stable release at the time this was built. Update the version in
> `build.gradle.kts` when you're ready to migrate.

---

## What You'll Learn

| Concept                          | Where it's shown                            |
|----------------------------------|---------------------------------------------|
| Unary RPC                        | `GetVehicle`, `CreateTrip`, `GetDocument`   |
| Server-streaming RPC             | `StreamLocationUpdates` (vehicle-service)   |
| Client-streaming RPC             | `BatchUpdateLocations`, `UploadDocument`    |
| Bidirectional streaming RPC      | `TrackTrip` (trip-service)                  |
| gRPC status codes & errors       | All services — `StatusException`            |
| JWT auth via Metadata            | `AuthServerInterceptor` in all services     |
| Service-to-service auth          | `AuthClientInterceptor` in trip-service     |
| All proto scalar types           | vehicle.proto — string, int32, float, etc.  |
| GPS coordinates (lat/lng)        | `Coordinate` in common.proto                |
| DateTime                         | `google.protobuf.Timestamp` everywhere      |
| Nested objects                   | `Driver` inside `Vehicle`                   |
| Arrays of nested objects         | `repeated Waypoint` in `Trip`               |
| String arrays                    | `repeated string features` in `Vehicle`     |
| Map type                         | `map<string,string> metadata` in `Trip`     |
| File transfer via `bytes`        | `UploadDocument` / `DownloadDocument`       |
| `oneof` discriminated union      | `UploadChunk` in document.proto             |
| Cross-service gRPC call          | trip-service → vehicle-service              |
| Kotlin DSL proto builders        | All Kotlin service files                    |
| Coroutine stubs (`Flow<T>`)      | All streaming implementations               |

---

## Project Structure

```
grpc-fleet-management/
├── proto/                    # Shared .proto definitions (compiled as a library)
│   └── src/main/proto/
│       ├── common.proto      # Coordinate, Address
│       ├── vehicle.proto     # Vehicle, Driver, LocationUpdate
│       ├── trip.proto        # Trip, Waypoint, TripUpdate
│       └── document.proto    # DocumentMetadata, UploadChunk (bytes)
│
├── vehicle-service/          # gRPC :9091 | REST :8081
│   └── src/test/             # Integration tests (8 service + 4 auth interceptor)
├── trip-service/             # gRPC :9092 | REST :8082  (calls vehicle-service)
│   └── src/test/             # Integration tests (7 tests, incl. cross-service)
├── document-service/         # gRPC :9093 | REST :8083  (file upload/download)
│   └── src/test/             # Integration tests (7 tests, incl. bytes round-trip)
│
├── grpc-client/              # Demo client — calls all 3 services (run via bootRun)
│
├── Makefile                  # Build, test, start/stop, Docker helpers
├── ARCHITECTURE.md           # Deep dive: data types, patterns, auth, testing
├── docker-compose.yml
└── build.gradle.kts          # Multi-module Gradle root
```

---

## Prerequisites

- JDK 21+
- Gradle 9.2+ (or use `./gradlew`)
- Docker + Docker Compose (optional, for containerised run)
- [grpcurl](https://github.com/fullstorydev/grpcurl) for CLI testing (recommended)
- [BloomRPC](https://github.com/bloomrpc/bloomrpc) or [Postman](https://www.postman.com/) for GUI testing

---

## Running Tests

Tests use **in-process gRPC** — no services need to be running, no ports, no Docker.

```bash
# Run all integration tests across all services
make test
# or: ./gradlew test

# Run tests for a single service
make test-vehicle
make test-trip
make test-document

# Open HTML test reports (macOS)
make test-report
```

**What's covered:**

| Test file | Tests | What it verifies |
|-----------|-------|-----------------|
| `VehicleServiceIntegrationTest` | 8 | All scalar types, nested objects, streaming |
| `AuthInterceptorIntegrationTest` | 4 | JWT validation, UNAUTHENTICATED status |
| `TripServiceIntegrationTest` | 7 | Cross-service call, bidirectional streaming, full lifecycle |
| `DocumentServiceIntegrationTest` | 7 | `bytes` round-trip, `oneof`, 500KB file across 64KB chunks |

---

## Running Locally

### 1. Build the proto module first

```bash
./gradlew :proto:generateProto
# or via Makefile:
make build
```

### 2. Start all services

```bash
# Using Makefile (recommended — manages PID files and logs)
make start-all

# Or individually:
make start-vehicle
make start-trip
make start-document

# Check what's running:
make status

# Stop everything:
make stop-all
```

### 3. Run the demo gRPC client

Once all services are up, the `grpc-client` module walks through every RPC pattern
against the live services:

```bash
make run-client
```

### 4. Or run everything with Docker Compose

```bash
make docker-up
# background:
make docker-up-bg
make docker-logs
make docker-down
```

---

## Browsing the API (Swagger Equivalent)

gRPC has **Server Reflection** — a protocol that lets tools query a running server
for its full service definitions. Combined with **grpcui**, you get a browser-based
UI that works just like Swagger.

### Install

```bash
brew install grpcui   # the browser UI
brew install grpcurl  # the CLI tool
```

### Open the UI

Start services first (`make start-all`), then:

```bash
make grpcui-vehicle   # opens http://localhost:<random-port> for vehicle-service
make grpcui-trip      # for trip-service
make grpcui-document  # for document-service
```

The UI shows every service, every method, and auto-generates the request form
from the proto schema — identical experience to Swagger UI.

### Or use grpcurl as CLI

```bash
# List all services on a port
make grpcurl-list

# Describe all methods of a service
make grpcurl-describe-vehicle
make grpcurl-describe-trip
make grpcurl-describe-document

# Describe a specific message type
grpcurl -plaintext localhost:9091 describe fleetmanagement.vehicle.Vehicle
grpcurl -plaintext localhost:9092 describe fleetmanagement.trip.Trip
```

> **How it works:** Each service has `io.grpc:grpc-services` on its classpath.
> `net.devh:grpc-spring-boot-starter` detects this and automatically registers
> `ProtoReflectionService` alongside your service. No extra config needed.

---

## Testing with grpcurl

grpcurl is a command-line tool for calling gRPC services — like curl but for gRPC.

### Generate a test JWT

The services expect `Authorization: Bearer <jwt>`. For testing, generate a token
signed with the secret `your-256-bit-secret-change-in-production`:

```bash
# Using https://jwt.io — paste this payload:
# { "sub": "test-user", "roles": ["ADMIN"], "iat": 1700000000 }
# Sign with HS256 and secret: your-256-bit-secret-change-in-production
```

Or generate programmatically (Java/Kotlin):
```kotlin
val key = Keys.hmacShaKeyFor("your-256-bit-secret-change-in-production".toByteArray())
val token = Jwts.builder()
    .subject("test-user")
    .claim("roles", listOf("ADMIN"))
    .signWith(key)
    .compact()
```

### List available services

```bash
grpcurl -plaintext localhost:9091 list
# fleetmanagement.vehicle.VehicleService

grpcurl -plaintext localhost:9092 list
# fleetmanagement.trip.TripService

grpcurl -plaintext localhost:9093 list
# fleetmanagement.document.DocumentService
```

### Unary — CreateVehicle

```bash
grpcurl -plaintext \
  -H "authorization: Bearer <your-jwt>" \
  -d '{
    "make": "Toyota",
    "model": "Land Cruiser",
    "year": 2023,
    "licensePlate": "B-FL-123",
    "color": "White",
    "fuelCapacityLiters": 85.0,
    "passengerCapacity": 7,
    "features": ["GPS", "4WD", "DASH_CAM"],
    "homeBase": {
      "street": "Potsdamer Str. 1",
      "city": "Berlin",
      "country": "DE",
      "postalCode": "10785",
      "coordinates": { "latitude": 52.5096, "longitude": 13.3744 }
    }
  }' \
  localhost:9091 fleetmanagement.vehicle.VehicleService/CreateVehicle
```

### Unary — AssignDriver (demonstrates nested object + Timestamp)

```bash
grpcurl -plaintext \
  -H "authorization: Bearer <your-jwt>" \
  -d '{
    "vehicleId": "<id-from-above>",
    "driver": {
      "id": "drv-001",
      "firstName": "Hans",
      "lastName": "Müller",
      "phone": "+49-30-12345678",
      "email": "hans@fleet.de",
      "licenseNumber": "DE-2019-XY",
      "licenseExpiry": "2027-01-15T00:00:00Z",
      "rating": 4.8,
      "certifications": ["ADR", "FORKLIFT"],
      "isActive": true
    }
  }' \
  localhost:9091 fleetmanagement.vehicle.VehicleService/AssignDriver
```

### Server-Streaming — StreamLocationUpdates

```bash
# Streams 20 location updates, 500ms apart
grpcurl -plaintext \
  -H "authorization: Bearer <your-jwt>" \
  -d '{"vehicleId": "<id>"}' \
  localhost:9091 fleetmanagement.vehicle.VehicleService/StreamLocationUpdates
```

### Unary — CreateTrip (demonstrates repeated Waypoints + map + Timestamps)

```bash
grpcurl -plaintext \
  -H "authorization: Bearer <your-jwt>" \
  -d '{
    "vehicleId": "<vehicle-id>",
    "driverId": "drv-001",
    "description": "Berlin to Hamburg delivery",
    "origin": {
      "street": "Potsdamer Str. 1", "city": "Berlin", "country": "DE",
      "coordinates": { "latitude": 52.5096, "longitude": 13.3744 }
    },
    "destination": {
      "street": "Speicherstadt 1", "city": "Hamburg", "country": "DE",
      "coordinates": { "latitude": 53.5415, "longitude": 9.9863 }
    },
    "waypoints": [
      {
        "sequence": 0,
        "name": "Warehouse A",
        "coordinates": { "latitude": 52.4800, "longitude": 13.4200 },
        "scheduledArrival": "2024-06-01T10:00:00Z",
        "notes": "Pick up refrigerated cargo",
        "isMandatory": true
      },
      {
        "sequence": 1,
        "name": "Rest Stop",
        "coordinates": { "latitude": 53.0000, "longitude": 10.5000 },
        "scheduledArrival": "2024-06-01T13:00:00Z",
        "isMandatory": false
      }
    ],
    "scheduledStart": "2024-06-01T09:00:00Z",
    "scheduledEnd": "2024-06-01T16:00:00Z",
    "passengerCount": 1,
    "tags": ["urgent", "refrigerated"],
    "metadata": {
      "cargo_type": "refrigerated",
      "priority": "high",
      "customer_ref": "CUST-987"
    }
  }' \
  localhost:9092 fleetmanagement.trip.TripService/CreateTrip
```

### Client-Streaming — UploadDocument (file via bytes)

```bash
# grpcurl doesn't support client streaming interactively.
# Use a gRPC client in code, or BloomRPC/Postman.
# See document-service/src/test/ for a Kotlin test client example.
```

---

## Key Code Locations

### gRPC Data Types

| What to see                     | File                                                    | Line |
|---------------------------------|---------------------------------------------------------|------|
| All scalar types in one message | `proto/src/main/proto/vehicle.proto`                    | ~60  |
| lat/lng as double               | `proto/src/main/proto/common.proto`                     | ~14  |
| Timestamp (datetime)            | `proto/src/main/proto/vehicle.proto` — `registeredAt`   | ~47  |
| Nested object (Driver)          | `proto/src/main/proto/vehicle.proto` — `Driver` message | ~30  |
| repeated (array)                | `proto/src/main/proto/trip.proto` — `waypoints`         | ~38  |
| map<string,string>              | `proto/src/main/proto/trip.proto` — `metadata`          | ~48  |
| bytes + oneof                   | `proto/src/main/proto/document.proto`                   | ~55  |
| int64 (large numbers)           | `proto/src/main/proto/document.proto` — `size_bytes`    | ~30  |

### gRPC Patterns

| Pattern                 | File                                                          |
|-------------------------|---------------------------------------------------------------|
| Unary RPC               | `vehicle-service/.../grpc/VehicleGrpcService.kt` — `createVehicle` |
| Server-streaming        | `vehicle-service/.../grpc/VehicleGrpcService.kt` — `streamLocationUpdates` |
| Client-streaming        | `document-service/.../grpc/DocumentGrpcService.kt` — `uploadDocument` |
| Bidirectional streaming | `trip-service/.../grpc/TripGrpcService.kt` — `trackTrip`     |
| Cross-service gRPC call | `trip-service/.../grpc/TripGrpcService.kt` — `createTrip`    |

### Authentication

| What to see              | File                                                            |
|--------------------------|-----------------------------------------------------------------|
| JWT validation on server | `vehicle-service/.../interceptor/AuthServerInterceptor.kt`      |
| Token injection on client | `trip-service/.../interceptor/AuthClientInterceptor.kt`        |
| Reading user from context | `vehicle-service/.../grpc/VehicleGrpcService.kt` — `AUTHENTICATED_USER_KEY.get()` |

### Testing

| What to see                        | File                                                              |
|------------------------------------|-------------------------------------------------------------------|
| In-process server setup            | `VehicleServiceIntegrationTest.kt` — `@BeforeAll startServer()`  |
| Auth interceptor wired in-process  | `AuthInterceptorIntegrationTest.kt` — `ServerInterceptors.intercept()` |
| Cross-service test (two in-process servers) | `TripServiceIntegrationTest.kt` — `startServers()`      |
| Bytes round-trip test              | `DocumentServiceIntegrationTest.kt` — `bytes round-trip` test    |
| Full trip lifecycle test           | `TripServiceIntegrationTest.kt` — orders 1–7                     |

### Demo Client

| What to see                   | File                                                          |
|-------------------------------|---------------------------------------------------------------|
| Live calls to all 3 services  | `grpc-client/.../runner/FleetDemoRunner.kt`                  |
| JWT token generation          | `grpc-client/.../auth/TokenGenerator.kt`                     |
| `CallCredentials` per-call auth | `grpc-client/.../auth/BearerTokenCredentials.kt`           |

---

## Understanding the Generated Code

When you run `./gradlew :proto:generateProto`, the following is generated
in `proto/build/generated/source/proto/`:

```
main/
├── grpc/                    # Java gRPC service stubs
│   └── VehicleServiceGrpc.java
├── grpckt/                  # Kotlin coroutine stubs ← we use these
│   └── VehicleServiceGrpcKt.kt
│       ├── VehicleServiceCoroutineImplBase  ← extend this in server
│       └── VehicleServiceCoroutineStub      ← inject this in client
└── kotlin/                  # Kotlin DSL message builders
    └── VehicleKt.kt
        ├── fun vehicle { }        ← builds a Vehicle
        ├── fun driver { }         ← builds a Driver
        └── fun coordinate { }     ← builds a Coordinate
```

### Kotlin DSL builders vs Java builders

```kotlin
// Kotlin DSL (idiomatic — uses generated extension functions)
val v = vehicle {
    id = "veh-001"
    make = "Toyota"
    year = 2023
    registeredAt = Timestamp.newBuilder().setSeconds(Instant.now().epochSecond).build()
    features.addAll(listOf("GPS", "AC"))     // repeated string
    assignedDriver = driver {                 // nested object
        firstName = "Hans"
        rating = 4.8f
    }
}

// Java builder (also works in Kotlin, more verbose)
val v = Vehicle.newBuilder()
    .setId("veh-001")
    .setMake("Toyota")
    .build()
```

---

## Common Pitfalls

1. **Default values aren't serialized in proto3.**
   `int32` field = `0`, `string` = `""`, `bool` = `false` — these are
   indistinguishable from "not set". Use `google.protobuf.Int32Value` wrappers
   if you need to distinguish "zero" from "absent".

2. **Timestamp stores UTC seconds.** Always convert your local datetime to UTC
   before building a `Timestamp`. Use `Instant.now()` or `ZonedDateTime.toInstant()`.

3. **`repeated` fields in Kotlin use `addAll()`, not assignment.**
   ```kotlin
   // Wrong:  features = listOf("GPS")
   // Right:  features.addAll(listOf("GPS"))
   ```

4. **gRPC metadata keys must be lowercase** (HTTP/2 header rules).
   `Metadata.Key.of("Authorization", ...)` will fail — use `"authorization"`.

5. **`oneof` field access.**
   ```kotlin
   when (chunk.dataCase) {
       UploadChunk.DataCase.METADATA -> { /* chunk.metadata */ }
       UploadChunk.DataCase.CONTENT  -> { /* chunk.content  */ }
       else -> { /* DATA_NOT_SET */ }
   }
   ```

6. **File upload chunk size vs message size limit.**
   Default gRPC message limit is 4MB. Keep each chunk below this.
   Our `CHUNK_SIZE = 64KB` is safe.
