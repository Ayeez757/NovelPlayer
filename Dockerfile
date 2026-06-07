# syntax=docker/dockerfile:1.7

# First stage: build the backend executable jar.
FROM maven:3.9.10-eclipse-temurin-21 AS backend-build
WORKDIR /workspace

# Keep Maven downloads in a BuildKit cache and retry interrupted HTTP transfers.
# This makes Docker builds much more tolerant of unstable Maven Central connections.
ARG MAVEN_CLI_OPTS="-B -ntp -Dmaven.wagon.http.retryHandler.count=5 -Dmaven.wagon.http.pool=false"
COPY backend/pom.xml backend/pom.xml
RUN --mount=type=cache,target=/root/.m2 \
    mvn ${MAVEN_CLI_OPTS} -f backend/pom.xml -DskipTests dependency:go-offline

COPY backend/src backend/src
RUN --mount=type=cache,target=/root/.m2 \
    mvn ${MAVEN_CLI_OPTS} -f backend/pom.xml -DskipTests package

# Second stage: keep only the runtime and the final executable jar.
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY --from=backend-build /workspace/backend/target/novel-player-backend.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "/app/app.jar"]
