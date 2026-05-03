FROM gradle:8.12-jdk21-alpine AS builder
WORKDIR /app
COPY . .
RUN ./gradlew :vehicle-service:bootJar -x test --no-daemon

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=builder /app/vehicle-service/build/libs/vehicle-service-1.0.0.jar app.jar
EXPOSE 8081 9091
ENTRYPOINT ["java", "-jar", "app.jar"]
