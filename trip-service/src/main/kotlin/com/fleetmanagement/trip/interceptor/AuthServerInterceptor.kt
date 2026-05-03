package com.fleetmanagement.trip.interceptor

import io.grpc.Context
import io.grpc.Contexts
import io.grpc.Metadata
import io.grpc.ServerCall
import io.grpc.ServerCallHandler
import io.grpc.ServerInterceptor
import io.grpc.Status
import io.jsonwebtoken.JwtException
import io.jsonwebtoken.Jwts
import io.jsonwebtoken.security.Keys
import net.devh.boot.grpc.server.interceptor.GrpcGlobalServerInterceptor
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import java.nio.charset.StandardCharsets

val AUTHENTICATED_USER_KEY: Context.Key<AuthenticatedUser> =
    Context.key("authenticatedUser")

data class AuthenticatedUser(val subject: String, val roles: List<String>)

@GrpcGlobalServerInterceptor
class AuthServerInterceptor(
    @Value("\${fleet.auth.jwt-secret}") private val jwtSecret: String,
    @Value("\${fleet.auth.skip-auth-methods}") private val skipMethods: List<String>
) : ServerInterceptor {

    private val log = LoggerFactory.getLogger(AuthServerInterceptor::class.java)

    companion object {
        val AUTHORIZATION_KEY: Metadata.Key<String> =
            Metadata.Key.of("authorization", Metadata.ASCII_STRING_MARSHALLER)
    }

    override fun <ReqT, RespT> interceptCall(
        call: ServerCall<ReqT, RespT>,
        headers: Metadata,
        next: ServerCallHandler<ReqT, RespT>
    ): ServerCall.Listener<ReqT> {
        val methodName = call.methodDescriptor.fullMethodName

        if (skipMethods.any { methodName.contains(it) }) {
            return next.startCall(call, headers)
        }

        val authHeader = headers.get(AUTHORIZATION_KEY)
        if (authHeader == null || !authHeader.startsWith("Bearer ")) {
            call.close(Status.UNAUTHENTICATED.withDescription("Missing Authorization header"), Metadata())
            return object : ServerCall.Listener<ReqT>() {}
        }

        return try {
            val user = validateToken(authHeader.removePrefix("Bearer "))
            val ctx = Context.current().withValue(AUTHENTICATED_USER_KEY, user)
            Contexts.interceptCall(ctx, call, headers, next)
        } catch (e: JwtException) {
            call.close(Status.UNAUTHENTICATED.withDescription("Invalid token: ${e.message}"), Metadata())
            object : ServerCall.Listener<ReqT>() {}
        }
    }

    private fun validateToken(token: String): AuthenticatedUser {
        val key = Keys.hmacShaKeyFor(jwtSecret.toByteArray(StandardCharsets.UTF_8))
        val claims = Jwts.parser().verifyWith(key).build().parseSignedClaims(token).payload
        @Suppress("UNCHECKED_CAST")
        val roles = (claims["roles"] as? List<*>)?.filterIsInstance<String>() ?: emptyList()
        return AuthenticatedUser(subject = claims.subject ?: "anonymous", roles = roles)
    }
}