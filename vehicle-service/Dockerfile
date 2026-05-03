FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY vehicle-service/build/libs/vehicle-service-1.0.0.jar app.jar
EXPOSE 8081 9091
ENTRYPOINT ["java", "-jar", "app.jar"]
