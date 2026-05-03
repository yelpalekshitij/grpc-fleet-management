package com.fleetmanagement.trip.interceptor

import io.grpc.CallOptions
import io.grpc.Channel
import io.grpc.ClientCall
import io.grpc.ClientInterceptor
import io.grpc.ForwardingClientCall
import io.grpc.Metadata
import io.grpc.MethodDescriptor
import net.devh.boot.grpc.client.interceptor.GrpcGlobalClientInterceptor
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value

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
    @Value("\${fleet.auth.service-token}") private val serviceToken: String
) : ClientInterceptor {

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