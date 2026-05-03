FROM gradle:8.12-jdk21-alpine AS builder
WORKDIR /app
COPY . .
RUN ./gradlew :trip-service:bootJar -x test --no-daemon

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=builder /app/trip-service/build/libs/trip-service-1.0.0.jar app.jar
EXPOSE 8082 9092
ENTRYPOINT ["java", "-jar", "app.jar"]
