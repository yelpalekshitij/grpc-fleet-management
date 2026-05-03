pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
    }
}

plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "grpc-fleet-management"

include("proto")
include("vehicle-service")
include("trip-service")
include("document-service")
include("grpc-client")