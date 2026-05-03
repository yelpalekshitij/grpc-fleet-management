package com.fleetmanagement.trip.interceptor

import io.grpc.CallOptions
import io.grpc.Channel
import io.grpc.ClientCall
import io.grpc.ClientInterceptor
import io.grpc.ForwardingClientCall
import io.grpc.Metadata
import io.grpc.MethodDescriptor
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import net.devh.boot.grpc.client.interceptor.GrpcGlobalClientInterceptor
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import java.nio.charset.StandardCharsets
import java.util.Date

// =============================================================================
// gRPC AUTHENTICATION — CLIENT INTERCEPTOR
//
// When trip-service calls vehicle-service, it must attach a JWT token so that
// vehicle-service's AuthServerInterceptor accepts the request.
//
// This interceptor runs before every outbound gRPC call and injects the
// service-to-service bearer token into the Metadata (HTTP/2 headers).
//
// @GrpcGlobalClientInterceptor applies to ALL outbound gRPC calls from
// this service. To target a specific client, use:
//   @GrpcClient("vehicle-service", interceptors = [AuthClientInterceptor::class])
// =============================================================================

@GrpcGlobalClientInterceptor
class AuthClientInterceptor(
    @Value("\${fleet.auth.jwt-secret}") private val jwtSecret: String
) : ClientInterceptor {

    // Generated at startup — signed with the same secret vehicle-service uses to validate
    private val serviceToken: String by lazy {
        val key = Keys.hmacShaKeyFor(jwtSecret.toByteArray(StandardCharsets.UTF_8))
        Jwts.builder()
            .subject("trip-service")
            .claim("roles", listOf("SERVICE"))
            .issuedAt(Date())
            .signWith(key)
            .compact()
    }

    private val log = LoggerFactory.getLogger(AuthClientInterceptor::class.java)

    companion object {
        val AUTHORIZATION_KEY: Metadata.Key<String> =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER)
    }

    override fun <ReqT, RespT> interceptCall(
        method: MethodDescriptor<ReqT, RespT>,
        callOptions: CallOptions,
        next: Channel
    ): ClientCall<ReqT, RespT> {

        // Wrap the underlying call to inject auth header before it leaves
        return object : ForwardingClientCall.SimpleForwardingClientCall<ReqT, RespT>(
            next.newCall(method, callOptions)
        ) {
            override fun start(responseListener: Listener<RespT>, headers: Metadata) {
                // Inject "Authorization: Bearer <token>" into outbound metadata
                headers.put(AUTHORIZATION_KEY, "Bearer $serviceToken")
                log.debug("Injected service token for outbound call: {}", method.fullMethodName)
                super.start(responseListener, headers)
            }
        }
    }
}