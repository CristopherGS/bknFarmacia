FROM maven:3.9-eclipse-temurin-21 AS build
RUN useradd --create-home --uid 10001 builder
WORKDIR /app
RUN chown builder:builder /app
USER builder
COPY --chown=builder:builder pom.xml .
RUN mvn -B dependency:go-offline
COPY --chown=builder:builder src src
RUN mvn -B verify
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN addgroup -S farmacia && adduser -S farmacia -G farmacia
COPY --from=build /app/target/farmacia-api-0.1.0.jar app.jar
USER farmacia
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=3s --start-period=45s --retries=3 CMD wget -q -O /dev/null "http://127.0.0.1:${PORT:-8080}/actuator/health/readiness" || exit 1
ENTRYPOINT ["java","-jar","app.jar"]
