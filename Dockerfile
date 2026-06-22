# syntax=docker/dockerfile:1

# ---- Build stage ----------------------------------------------------------
# Tests are skipped here: the suite uses Testcontainers (needs a Docker daemon),
# which isn't available inside the image build. CI (.github/workflows/ci.yml)
# is the place that runs `mvn verify`.
FROM maven:3.9-eclipse-temurin-21 AS build
WORKDIR /build

# Cache dependencies first for faster rebuilds.
COPY pom.xml .
RUN mvn -B -q dependency:go-offline

COPY src ./src
RUN mvn -B -q clean package -DskipTests

# ---- Runtime stage --------------------------------------------------------
FROM eclipse-temurin:21-jre AS runtime
WORKDIR /app

# Run as a non-root user.
RUN groupadd --system app && useradd --system --gid app app

COPY --from=build /build/target/velocity-rgs-*.jar app.jar

USER app
EXPOSE 8080

# Container-aware heap sizing; tune the percentage via the deployment if needed.
ENV JAVA_OPTS="-XX:MaxRAMPercentage=75.0"

ENTRYPOINT ["sh", "-c", "java $JAVA_OPTS -jar app.jar"]
