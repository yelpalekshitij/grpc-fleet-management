import com.google.protobuf.gradle.id

plugins {
    kotlin("jvm")
    id("com.google.protobuf")
}

dependencies {
    api("io.grpc:grpc-protobuf:1.67.1")
    api("io.grpc:grpc-stub:1.67.1")
    api("io.grpc:grpc-kotlin-stub:1.4.1")
    api("com.google.protobuf:protobuf-kotlin:3.25.5")
    api("com.google.protobuf:protobuf-java:3.25.5")
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    // Required for @Generated annotation on generated Java sources
    compileOnly("javax.annotation:javax.annotation-api:1.3.2")
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:3.25.5"
    }
    plugins {
        id("grpc") {
            artifact = "io.grpc:protoc-gen-grpc-java:1.67.1"
        }
        id("grpckt") {
            // jdk8@jar produces a fat JAR with coroutine stubs
            artifact = "io.grpc:protoc-gen-grpc-kotlin:1.4.1:jdk8@jar"
        }
    }
    generateProtoTasks {
        all().forEach { task ->
            task.plugins {
                id("grpc")   // generates Java gRPC service stubs
                id("grpckt") // generates Kotlin coroutine stubs
            }
            task.builtins {
                id("kotlin") // generates Kotlin DSL builders for messages
            }
        }
    }
}

kotlin {
    jvmToolchain(21)
}