FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY document-service/build/libs/document-service-1.0.0.jar app.jar
EXPOSE 8083 9093
ENTRYPOINT ["java", "-jar", "app.jar"]
