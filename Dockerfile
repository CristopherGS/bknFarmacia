FROM maven:3.9-eclipse-temurin-21 AS build
# Las pruebas corren en GitHub Actions. RUN_TESTS=true (variable de build) las repite aquí,
# más lento: PostgreSQL temporal de las pruebas no se ejecuta como root.
ARG RUN_TESTS=false
RUN groupadd --gid 10001 builder && useradd --create-home --uid 10001 --gid 10001 builder
WORKDIR /app
RUN chown builder:builder /app
USER builder
COPY --chown=builder:builder pom.xml .
COPY --chown=builder:builder src src
# Caché de BuildKit para ~/.m2: las dependencias se reutilizan entre builds aunque cambie el pom.
RUN --mount=type=cache,target=/home/builder/.m2,uid=10001,gid=10001 \
    if [ "$RUN_TESTS" = "true" ]; then mvn -B verify; \
    else mvn -B package -Dmaven.test.skip=true; fi
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN addgroup -S farmacia && adduser -S farmacia -G farmacia
COPY --from=build /app/target/farmacia-api-0.1.0.jar app.jar
USER farmacia
EXPOSE 8080
HEALTHCHECK --interval=30s --timeout=3s --start-period=60s --retries=3 CMD wget -q -O /dev/null "http://127.0.0.1:${PORT:-8080}/actuator/health/readiness" || exit 1
ENTRYPOINT ["java","-jar","app.jar"]
