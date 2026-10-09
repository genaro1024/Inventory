# syntax=docker/dockerfile:1
FROM maven:3.9.11-eclipse-temurin-21 AS build
WORKDIR /workspace
COPY pom.xml ./
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp dependency:go-offline
COPY src ./src
# CI executes the complete test suite before building this image.
RUN --mount=type=cache,target=/root/.m2 mvn -B -ntp -Dmaven.test.skip=true package

FROM eclipse-temurin:21-jre-jammy AS runtime
RUN groupadd --gid 10001 inventory \
    && useradd --uid 10001 --gid inventory --no-create-home --shell /usr/sbin/nologin inventory
WORKDIR /app
COPY --from=build --chown=10001:10001 /workspace/target/inventory-reservations-*.jar /app/inventory.jar
USER 10001:10001
ENV SERVER_PORT=8080 \
    JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=65.0 -XX:+ExitOnOutOfMemoryError"
EXPOSE 8080
STOPSIGNAL SIGTERM
ENTRYPOINT ["java", "-jar", "/app/inventory.jar"]
