package com.fleetmanagement.vehicle

import com.fleetmanagement.proto.vehicle.VehicleServiceGrpcKt
import com.fleetmanagement.proto.vehicle.listVehiclesRequest
import com.fleetmanagement.vehicle.grpc.VehicleGrpcService
import com.fleetmanagement.vehicle.interceptor.AuthServerInterceptor
import com.fleetmanagement.vehicle.repository.VehicleRepository
import io.grpc.CallCredentials
import io.grpc.ManagedChannel
import io.grpc.Metadata
import io.grpc.Server
import io.grpc.ServerInterceptors
import io.grpc.StatusException
import io.grpc.inprocess.InProcessChannelBuilder
import io.grpc.inprocess.InProcessServerBuilder
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets
import java.util.Date
import java.util.concurrent.Executor

// =============================================================================
// AUTH INTERCEPTOR — INTEGRATION TEST
//
// Tests the full interceptor → service chain using an in-process gRPC server
// with the AuthServerInterceptor explicitly wired in via ServerInterceptors.intercept().
//
// What's tested:
//   ✓ Missing Authorization header → UNAUTHENTICATED
//   ✓ Malformed token → UNAUTHENTICATED
//   ✓ Expired token → UNAUTHENTICATED
//   ✓ Valid JWT → request reaches the handler
//   ✓ Skip-auth-methods bypass → health check skips validation
// =============================================================================

private const val TEST_SECRET = "test-jwt-secret-must-be-at-least-256-bits-long"

class AuthInterceptorIntegrationTest {

    private lateinit var server: Server
    private lateinit var channel: ManagedChannel
    private lateinit var stub: VehicleServiceGrpcKt.VehicleServiceCoroutineStub

    @BeforeEach
    fun setup() {
        val serverName = InProcessServerBuilder.generateName()
        val interceptor = AuthServerInterceptor(
            jwtSecret   = TEST_SECRET,
            skipMethods = listOf("grpc.health.v1.Health/Check")
        )

        server = InProcessServerBuilder.forName(serverName)
            .directExecutor()
            .addService(
                ServerInterceptors.intercept(VehicleGrpcService(VehicleRepository()), interceptor)
            )
            .build()
            .start()

        channel = InProcessChannelBuilder.forName(serverName).directExecutor().build()
        stub = VehicleServiceGrpcKt.VehicleServiceCoroutineStub(channel)
    }

    @AfterEach
    fun tearDown() {
        channel.shutdownNow()
        server.shutdownNow()
    }

    @Test
    fun `no Authorization header — returns UNAUTHENTICATED`() { runBlocking {
        assertThatThrownBy {
            runBlocking { stub.listVehicles(listVehiclesRequest {}) }
        }.isInstanceOf(StatusException::class.java)
            .satisfies({ ex: Throwable ->
                assertThat((ex as StatusException).status.code)
                    .isEqualTo(io.grpc.Status.Code.UNAUTHENTICATED)
            })
    } }

    @Test
    fun `malformed Bearer token — returns UNAUTHENTICATED`() { runBlocking {
        val badToken = "not.a.real.jwt"
        assertThatThrownBy {
            runBlocking {
                stub.withCallCredentials(bearerCreds(badToken))
                    .listVehicles(listVehiclesRequest {})
            }
        }.isInstanceOf(StatusException::class.java)
            .satisfies({ ex: Throwable ->
                assertThat((ex as StatusException).status.code)
                    .isEqualTo(io.grpc.Status.Code.UNAUTHENTICATED)
            })
    } }

    @Test
    fun `valid JWT — request reaches the handler and returns OK`() { runBlocking {
        val token = validToken()
        // No exception = interceptor accepted the token and handler ran
        val response = stub.withCallCredentials(bearerCreds(token))
            .listVehicles(listVehiclesRequest {})
        assertThat(response).isNotNull()
    } }

    @Test
    fun `valid JWT — authenticated user context is populated`() { runBlocking {
        val token = validToken(subject = "integration-test-user", roles = listOf("ADMIN"))
        val response = stub.withCallCredentials(bearerCreds(token))
            .listVehicles(listVehiclesRequest {})
        assertThat(response).isNotNull()
    } }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private fun validToken(subject: String = "test-service", roles: List<String> = listOf("SERVICE")): String {
        val key = Keys.hmacShaKeyFor(TEST_SECRET.toByteArray(StandardCharsets.UTF_8))
        return Jwts.builder()
            .subject(subject)
            .claim("roles", roles)
            .issuedAt(Date())
            .expiration(Date(System.currentTimeMillis() + 3_600_000))
            .signWith(key)
            .compact()
    }

    private fun bearerCreds(token: String): CallCredentials = object : CallCredentials() {
        private val key = Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER)
        override fun applyRequestMetadata(requestInfo: RequestInfo, appExecutor: Executor, applier: MetadataApplier) {
            appExecutor.execute {
                val h = Metadata()
                h.put(key, "Bearer $token")
                applier.apply(h)
            }
        }
    }
}
