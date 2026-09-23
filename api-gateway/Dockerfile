# syntax=docker/dockerfile:1
# Contexto de construcción: la raíz del repositorio (ver docker-compose.yml).

# ---- Build: compila solo este módulo y los que necesita (-am) ----
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY . .
# La caché de ~/.m2 se comparte entre las construcciones de todos los servicios
RUN --mount=type=cache,target=/root/.m2,sharing=locked \
    mvn -B -q -DskipTests -pl api-gateway -am package \
    && cp api-gateway/target/api-gateway-*.jar /workspace/app.jar

# ---- Runtime: solo JRE, usuario sin privilegios ----
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
RUN addgroup -S app && adduser -S app -G app
USER app
COPY --from=build --chown=app:app /workspace/app.jar app.jar

ENV SPRING_PROFILES_ACTIVE=docker \
    SERVER_PORT=8090
EXPOSE 8090

HEALTHCHECK --interval=10s --timeout=3s --start-period=40s --retries=6 \
    CMD wget -qO- http://localhost:8090/actuator/health/readiness || exit 1

ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75.0", "-jar", "/app/app.jar"]
