# Build context is the repository root (see docker-compose.yml) so this
# Dockerfile can pull in both cpp-engine/ and backend/.

FROM debian:bookworm-slim AS cpp-builder
RUN apt-get update && apt-get install -y --no-install-recommends g++ && rm -rf /var/lib/apt/lists/*
WORKDIR /engine
COPY cpp-engine/pricer_cli.cpp .
RUN g++ -std=c++17 -O2 -o pricer_cli pricer_cli.cpp

FROM maven:3.9-eclipse-temurin-21 AS java-builder
WORKDIR /build
COPY backend/pom.xml .
RUN mvn -q -B dependency:go-offline
COPY backend/src ./src
RUN mvn -q -B package -DskipTests

FROM eclipse-temurin:21-jre-noble
RUN apt-get update && apt-get install -y --no-install-recommends curl && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY --from=java-builder /build/target/pricer-api-*.jar app.jar
COPY --from=cpp-builder /engine/pricer_cli /app/pricer_cli
ENV PRICING_ENGINE_BINARY_PATH=/app/pricer_cli
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
