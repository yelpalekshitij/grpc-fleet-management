package com.fleetmanagement.trip.config

import com.fleetmanagement.proto.vehicle.VehicleServiceGrpcKt
import io.grpc.Channel
import net.devh.boot.grpc.client.inject.GrpcClient
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

// Wraps the @GrpcClient channel in a Spring bean so TripGrpcService can
// receive VehicleServiceCoroutineStub via constructor injection.
// This makes TripGrpcService testable: integration tests inject an in-process stub.
@Configuration
class GrpcClientConfig {

    @GrpcClient("vehicle-service")
    private lateinit var vehicleServiceChannel: Channel

    @Bean
    fun vehicleServiceStub(): VehicleServiceGrpcKt.VehicleServiceCoroutineStub =
        VehicleServiceGrpcKt.VehicleServiceCoroutineStub(vehicleServiceChannel)
}
