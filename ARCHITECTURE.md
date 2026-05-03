# Fleet Management — gRPC Architecture

## Problem Statement

A logistics company manages a fleet of vehicles. Operators need to:
- Register and track vehicles and drivers
- Plan and monitor trips with multiple waypoints
- Store vehicle registration documents and driver licenses

This system demonstrates gRPC-based microservice communication covering every
major data type and all four RPC communication patterns.

---

## Services Overview

```
                         ┌────────────────────────────────────────┐
                         │          External Clients               │
                         │  (grpcurl, BloomRPC, custom app, etc.) │
                         └───────┬──────────────┬──────────┬───────┘
                                 │              │          │
                           gRPC :9091     gRPC :9092  gRPC :9093
                                 │              │          │
                    ┌────────────▼─┐   ┌────────▼──┐  ┌───▼────────────┐
                    │   vehicle-   │   │   trip-   │  │   document-    │
                    │   service    │◄──│   service │  │    service     │
                    │  port 9091   │   │ port 9092 │  │   port 9093    │
                    └──────────────┘   └───────────┘  └────────────────┘
                                              │
                              calls vehicle-service
                              via gRPC client stub
                              to validate vehicle
                              availability
```

### Ports

| Service          | REST Port | gRPC Port |
|------------------|-----------|-----------|
| vehicle-service  | 8081      | 9091      |
| trip-service     | 8082      | 9092      |
| document-service | 8083      | 9093      |

---

## Module Structure

```
grpc-fleet-management/
├── proto/                          # Shared protobuf definitions
│   └── src/main/proto/
│       ├── common.proto            # Coordinate, Address, PageInfo
│       ├── vehicle.proto           # Vehicle, Driver, LocationUpdate + service
│       ├── trip.proto              # Trip, Waypoint, TripUpdate + service
│       └── document.proto          # DocumentMetadata, UploadChunk + service
│
├── vehicle-service/
│   └── src/main/kotlin/com/fleetmanagement/vehicle/
│       ├── grpc/VehicleGrpcService.kt      # All 4 RPC patterns implemented
│       ├── interceptor/AuthServerInterceptor.kt
│       └── repository/VehicleRepository.kt
│
├── trip-service/
│   └── src/main/kotlin/com/fleetmanagement/trip/
│       ├── grpc/TripGrpcService.kt         # Calls vehicle-service via @GrpcClient
│       ├── interceptor/AuthServerInterceptor.kt
│       ├── interceptor/AuthClientInterceptor.kt  # Adds JWT to outbound calls
│       └── repository/TripRepository.kt
│
└── document-service/
    └── src/main/kotlin/com/fleetmanagement/document/
        ├── grpc/DocumentGrpcService.kt     # File upload/download via bytes
        ├── interceptor/AuthServerInterceptor.kt
        └── storage/DocumentStorage.kt
```

---

## Data Types Reference

| Proto Type             | Kotlin Type      | Used In                          | Example                          |
|------------------------|------------------|----------------------------------|----------------------------------|
| `string`               | `String`         | IDs, names, plates               | `"Toyota"`, `"veh-001"`          |
| `int32`                | `Int`            | Year, counts, percentages        | `2023`, `85` (fuel%)             |
| `int64`                | `Long`           | File sizes (can exceed 2GB)      | `10485760L` (10MB)               |
| `float`                | `Float`          | Rating, speed, heading           | `4.8f`, `65.5f`                  |
| `double`               | `Double`         | Lat/lng, distances, fuel_liters  | `52.5200`, `13.4050`             |
| `bool`                 | `Boolean`        | Active flags, mandatory waypoint | `true`, `false`                  |
| `bytes`                | `ByteString`     | File content                     | Binary PDF/image data            |
| `enum`                 | Generated enum   | Status fields                    | `VehicleStatus.AVAILABLE`        |
| `google.protobuf.Timestamp` | `Timestamp` | All datetime fields            | registered_at, trip start/end    |
| Nested `message`       | Generated class  | Driver in Vehicle, Coordinate    | `vehicle.assignedDriver.rating`  |
| `repeated string`      | `List<String>`   | Features, tags, certifications   | `["GPS", "AC", "DASH_CAM"]`      |
| `repeated <Message>`   | `List<T>`        | Waypoints array in Trip          | List of Waypoint objects         |
| `map<string, string>`  | `Map<String,String>` | Trip metadata                | `{"cargo": "refrigerated"}`      |
| `oneof`                | Sealed-like union | Upload/download chunks         | Either metadata OR bytes          |

### Date vs DateTime

- **DateTime** → `google.protobuf.Timestamp` (seconds + nanos since Unix epoch)
  - Used for: registered_at, trip start/end, location recorded_at
  - Kotlin: `Timestamp.newBuilder().setSeconds(Instant.now().epochSecond).build()`

- **Date only** (e.g. license expiry) → also uses `Timestamp` interpreted as date-only
  - Alternative: `google.type.Date` (requires `proto-google-common-protos` dep)
  - We use Timestamp for simplicity and show the trade-off in comments

### GPS Coordinates

```protobuf
message Coordinate {
  double latitude = 1;       // WGS84, -90.0 to 90.0  (double for precision)
  double longitude = 2;      // WGS84, -180.0 to 180.0
  float altitude_meters = 3; // float is sufficient for altitude
}
```

`double` is used for lat/lng because GPS coordinates need ~7 decimal places of
precision (centimeter accuracy). `float` only gives ~5 decimal places (~1m accuracy).

---

## gRPC Communication Patterns

### 1. Unary RPC (most common)

```
Client ──── request ────► Server
Client ◄─── response ─── Server
```

```kotlin
// Proto
rpc GetVehicle(GetVehicleRequest) returns (Vehicle);

// Server implementation
override suspend fun getVehicle(request: GetVehicleRequest): Vehicle {
    return repository.findById(request.id) ?: throw StatusException(Status.NOT_FOUND)
}

// Client call
val vehicle = vehicleStub.getVehicle(getVehicleRequest { id = "veh-001" })
```

### 2. Server-Streaming RPC

```
Client ──── request ────► Server
Client ◄─── response 1 ──┐
Client ◄─── response 2 ──┤ Server streams multiple
Client ◄─── response 3 ──┘
```

Used in: `VehicleService.StreamLocationUpdates`

```kotlin
// Proto
rpc StreamLocationUpdates(StreamLocationRequest) returns (stream LocationUpdate);

// Server: returns a Flow — emit() sends each message
override fun streamLocationUpdates(request: StreamLocationRequest): Flow<LocationUpdate> = flow {
    repeat(20) { i ->
        delay(500L)
        emit(locationUpdate { ... })
    }
}

// Client: collect the flow
vehicleStub.streamLocationUpdates(request).collect { update ->
    println("Vehicle at: ${update.coordinates.latitude}, ${update.coordinates.longitude}")
}
```

### 3. Client-Streaming RPC

```
Client ──── request 1 ──► Server
Client ──── request 2 ──► Server
Client ──── request 3 ──► Server
Client ◄─── response ─── Server (after all requests received)
```

Used in: `VehicleService.BatchUpdateLocations`, `DocumentService.UploadDocument`

```kotlin
// Proto
rpc UploadDocument(stream UploadChunk) returns (DocumentMetadata);

// Server: receives a Flow, collects it, returns one response
override suspend fun uploadDocument(requests: Flow<UploadChunk>): DocumentMetadata {
    val buffer = ByteArrayOutputStream()
    requests.collect { chunk ->
        when (chunk.dataCase) {
            METADATA -> { /* store metadata */ }
            CONTENT  -> buffer.write(chunk.content.toByteArray())
        }
    }
    return storage.save(metadata, buffer.toByteArray())
}
```

### 4. Bidirectional Streaming RPC

```
Client ──── ping 1 ───► Server ──── update 1 ──► Client
Client ──── ping 2 ───► Server ──── update 2 ──► Client
Client ──── ping 3 ───► Server ──── update 3 ──► Client
```

Used in: `TripService.TrackTrip`

```kotlin
// Proto
rpc TrackTrip(stream PositionPing) returns (stream TripUpdate);

// Server: Flow<in> → Flow<out>  (map each input to one output)
override fun trackTrip(requests: Flow<PositionPing>): Flow<TripUpdate> {
    return requests.map { ping ->
        tripUpdate {
            tripId = ping.tripId
            currentPosition = ping.coordinates
            // ... enrich with trip data
        }
    }
}
```

---

## Authentication

gRPC uses **Metadata** (equivalent to HTTP/2 headers) for authentication.

### Flow

```
Client                              Server
  │                                    │
  │  Metadata: authorization: Bearer   │
  │  <jwt-token>                       │
  ├─────────────── RPC call ──────────►│
  │                                    │ AuthServerInterceptor validates token
  │                                    │ Stores AuthenticatedUser in gRPC Context
  │                                    │ Calls the actual RPC handler
  │◄─────────────── response ──────────┤
```

### Server Interceptor (vehicle-service, trip-service, document-service)

`@GrpcGlobalServerInterceptor` — runs before every incoming RPC:
1. Reads `authorization` header from `Metadata`
2. Validates the JWT signature using HMAC-SHA256
3. Puts the `AuthenticatedUser` into `Context` for handlers to read
4. Returns `UNAUTHENTICATED` status if token is missing/invalid

```kotlin
// In any RPC handler — reading the caller's identity:
val caller = AUTHENTICATED_USER_KEY.get()  // from Context
log.info("Called by: ${caller.subject}, roles: ${caller.roles}")
```

### Client Interceptor (trip-service only)

`@GrpcGlobalClientInterceptor` — runs before every outbound gRPC call:
- Injects `"Authorization: Bearer <service-token>"` into outbound `Metadata`

```kotlin
// ForwardingClientCall — wraps every outbound call transparently
override fun start(responseListener: Listener<RespT>, headers: Metadata) {
    headers.put(AUTHORIZATION_KEY, "Bearer $serviceToken")
    super.start(responseListener, headers)
}
```

### Production Authentication Options

| Option             | Description                                      | When to use               |
|--------------------|--------------------------------------------------|---------------------------|
| JWT in metadata    | HS256/RS256 token in `authorization` header      | User-facing services      |
| mTLS               | Client certificate verified at TLS layer         | Service-to-service only   |
| mTLS + JWT         | Both: network-level + application-level auth     | High security deployments |
| Service mesh (Istio/Linkerd) | mTLS auto-managed by sidecar proxy     | Kubernetes deployments    |

**mTLS config (add to application.yml):**
```yaml
grpc:
  server:
    security:
      certificate-chain: classpath:certs/server.crt
      private-key: classpath:certs/server.key
      trust-cert-collection: classpath:certs/ca.crt
      client-auth: REQUIRE
  client:
    vehicle-service:
      security:
        certificate-chain: classpath:certs/client.crt
        private-key: classpath:certs/client.key
        trust-cert-collection: classpath:certs/ca.crt
```

---

## File Transfer via gRPC (`bytes` type)

**Yes — gRPC can transfer files.** The key constraint is the 4MB default message
size limit. The solution is chunked streaming.

### Upload sequence

```
Client                                   document-service
  │                                           │
  │── UploadChunk { metadata: {...} } ───────►│  (1st: announce the file)
  │── UploadChunk { content: <64KB> } ───────►│  (2nd: first byte chunk)
  │── UploadChunk { content: <64KB> } ───────►│  (3rd: second byte chunk)
  │── UploadChunk { content: <rest> } ───────►│  (last chunk)
  │                    ──stream end──          │
  │◄───────────── DocumentMetadata ───────────│  (response: stored file info)
```

### `oneof` — discriminated union in proto

```protobuf
message UploadChunk {
  oneof data {
    DocumentMetadata metadata = 1;  // first message
    bytes content             = 2;  // all subsequent messages
  }
}
```

`oneof` guarantees exactly one field is set — impossible to accidentally send
both metadata and bytes in the same message.

---

## Technology Stack

| Component          | Technology                                |
|--------------------|-------------------------------------------|
| Language           | Kotlin 2.0.21                             |
| Framework          | Spring Boot 3.3.5                         |
| gRPC framework     | `net.devh:grpc-spring-boot-starter:3.1.0` |
| Protobuf           | Protocol Buffers 3.25.5                   |
| gRPC Kotlin stubs  | `grpc-kotlin-stub:1.4.1`                  |
| Coroutines         | `kotlinx-coroutines-core:1.8.1`           |
| Authentication     | JJWT 0.12.6 (HMAC-SHA256)                |
| Build              | Gradle 9.2.1 (Kotlin DSL, multi-module)   |

---

## Error Handling

gRPC uses **Status codes** instead of HTTP codes. Key codes:

| Status Code           | Meaning                              | HTTP equiv |
|-----------------------|--------------------------------------|------------|
| `OK`                  | Success                              | 200        |
| `NOT_FOUND`           | Resource doesn't exist               | 404        |
| `INVALID_ARGUMENT`    | Bad request data                     | 400        |
| `UNAUTHENTICATED`     | Missing/invalid token                | 401        |
| `PERMISSION_DENIED`   | Authenticated but not authorized     | 403        |
| `ALREADY_EXISTS`      | Duplicate creation                   | 409        |
| `FAILED_PRECONDITION` | Business rule violated               | 422        |
| `RESOURCE_EXHAUSTED`  | Quota/size exceeded                  | 429        |
| `INTERNAL`            | Unexpected server error              | 500        |
| `UNAVAILABLE`         | Service down/overloaded              | 503        |

```kotlin
// Throwing a gRPC error from a handler:
throw StatusException(
    Status.NOT_FOUND.withDescription("Vehicle 'veh-001' not found")
)

// With additional details (optional, for machine-readable errors):
throw StatusException(
    Status.FAILED_PRECONDITION
        .withDescription("Vehicle is not available")
        .augmentDescription("current_status=ON_TRIP")
)
```
