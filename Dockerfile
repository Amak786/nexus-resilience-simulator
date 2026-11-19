# NEXUS - single container: React front end bundled into the Spring Boot API.
# Build:  docker build -t nexus .
# Run:    docker run -p 8080:8080 nexus      (then open http://localhost:8080)

# 1) Front end (React + TypeScript + Vite)
FROM node:20-alpine AS web
WORKDIR /web
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY frontend/ ./
RUN npm run build

# 2) Back end (Java 21 + Spring Boot), with the built site as static resources
FROM maven:3.9-eclipse-temurin-21 AS api
WORKDIR /api
COPY backend/pom.xml ./
RUN mvn -q -B dependency:go-offline
COPY backend/src ./src
COPY --from=web /web/dist ./src/main/resources/static
# Tests run in CI (GitHub Actions); skipping them here keeps hosted builds fast.
RUN mvn -q -B -DskipTests package

# 3) Runtime image
FROM eclipse-temurin:21-jre
WORKDIR /app
RUN groupadd --system nexus && useradd --system --gid nexus nexus
COPY --from=api /api/target/nexus-api.jar app.jar
USER nexus
ENV JAVA_TOOL_OPTIONS="-XX:MaxRAMPercentage=75 -XX:+UseSerialGC -Xss4m"
EXPOSE 8080
CMD ["java", "-jar", "app.jar"]
