FROM gradle:8.12-jdk21-alpine AS builder
WORKDIR /app
COPY . .
RUN ./gradlew :document-service:bootJar -x test --no-daemon

FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=builder /app/document-service/build/libs/document-service-1.0.0.jar app.jar
EXPOSE 8083 9093
ENTRYPOINT ["java", "-jar", "app.jar"]
