plugins {
    kotlin("jvm")
    kotlin("plugin.spring")
    id("org.springframework.boot")
    id("io.spring.dependency-management")
}

dependencies {
    implementation(project(":proto"))

    implementation("org.springframework.boot:spring-boot-starter")

    // gRPC client only — this module makes calls, does not serve
    implementation("net.devh:grpc-client-spring-boot-starter:3.1.0.RELEASE")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    implementation("org.jetbrains.kotlin:kotlin-reflect")

    // JWT to generate demo tokens on the fly
    implementation("io.jsonwebtoken:jjwt-api:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-impl:0.12.6")
    runtimeOnly("io.jsonwebtoken:jjwt-jackson:0.12.6")
}

kotlin {
    jvmToolchain(21)
}
