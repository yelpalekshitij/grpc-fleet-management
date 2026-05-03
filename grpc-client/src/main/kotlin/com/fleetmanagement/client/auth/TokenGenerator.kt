package com.fleetmanagement.client.auth

import io.grpc.CallCredentials
import io.grpc.Metadata
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.util.Date
import java.util.concurrent.Executor

@Component
class TokenGenerator(
    @Value("\${fleet.auth.jwt-secret}") private val jwtSecret: String,
    @Value("\${fleet.auth.client-subject}") private val clientSubject: String
) {
    fun generateToken(roles: List<String> = listOf("ADMIN")): String {
        val key = Keys.hmacShaKeyFor(jwtSecret.toByteArray(StandardCharsets.UTF_8))
        return Jwts.builder()
            .subject(clientSubject)
            .claim("roles", roles)
            .issuedAt(Date())
            .expiration(Date(System.currentTimeMillis() + 3_600_000))  // 1 hour
            .signWith(key)
            .compact()
    }
}

// gRPC CallCredentials — injects the Bearer token into every outbound call's Metadata.
// Used as: stub.withCallCredentials(BearerTokenCredentials(token))
class BearerTokenCredentials(private val token: String) : CallCredentials() {

    private val authKey = Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER)

    override fun applyRequestMetadata(
        requestInfo: RequestInfo,
        appExecutor: Executor,
        applier: MetadataApplier
    ) {
        appExecutor.execute {
            val headers = Metadata()
            headers.put(authKey, "Bearer $token")
            applier.apply(headers)
        }
    }
}
