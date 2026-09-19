# ─── Stage 1: Build ─────────────────────────────────────────────────────────
FROM maven:3.9.6-eclipse-temurin-21-alpine AS builder
WORKDIR /app

COPY pom.xml .
RUN mvn dependency:go-offline -B

COPY src ./src
RUN mvn clean package -DskipTests

# ─── Stage 2: Runtime ───────────────────────────────────────────────────────
FROM eclipse-temurin:21-jre-alpine
LABEL org.opencontainers.image.title="poptalk-rag" \
      org.opencontainers.image.description="MCP server for retrieval-augmented knowledge — plugs into the PopTalk chat backend" \
      org.opencontainers.image.source="https://github.com/pandaind/poptalk"

WORKDIR /app

RUN mkdir -p /app/knowledge

# Copy built JAR (finalName is fixed in pom.xml, so no version suffix to track here)
COPY --from=builder /app/target/poptalk-rag.jar app.jar

EXPOSE 8090

ENTRYPOINT ["java", "-jar", "app.jar"]
