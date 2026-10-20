FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /app
COPY pom.xml .
RUN mvn -B dependency:go-offline
COPY src src
RUN mvn -B verify
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN addgroup -S farmacia && adduser -S farmacia -G farmacia
COPY --from=build /app/target/farmacia-api-0.1.0.jar app.jar
USER farmacia
EXPOSE 8080
ENTRYPOINT ["java","-jar","app.jar"]
