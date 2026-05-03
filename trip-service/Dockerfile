FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY trip-service/build/libs/trip-service-1.0.0.jar app.jar
EXPOSE 8082 9092
ENTRYPOINT ["java", "-jar", "app.jar"]
