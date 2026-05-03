import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "2.0.21" apply false
    kotlin("plugin.spring") version "2.0.21" apply false
    id("org.springframework.boot") version "3.3.5" apply false
    id("io.spring.dependency-management") version "1.1.6" apply false
    id("com.google.protobuf") version "0.9.4" apply false
}

allprojects {
    group = "com.fleetmanagement"
    version = "1.0.0"

    repositories {
        mavenCentral()
    }
}

subprojects {
    configurations.all {
        resolutionStrategy {
            force(
                "io.grpc:grpc-api:1.67.1",
                "io.grpc:grpc-core:1.67.1",
                "io.grpc:grpc-stub:1.67.1",
                "io.grpc:grpc-netty-shaded:1.67.1",
                "io.grpc:grpc-protobuf:1.67.1",
                "io.grpc:grpc-protobuf-lite:1.67.1",
                "io.grpc:grpc-context:1.67.1",
                "io.grpc:grpc-inprocess:1.67.1",
                "io.grpc:grpc-util:1.67.1"
            )
        }
    }

    tasks.withType<KotlinCompile> {
        compilerOptions {
            freeCompilerArgs.add("-Xjsr305=strict")
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        }
    }

    tasks.withType<JavaCompile> {
        sourceCompatibility = "21"
        targetCompatibility = "21"
    }

    tasks.withType<Test> {
        useJUnitPlatform()
    }
}