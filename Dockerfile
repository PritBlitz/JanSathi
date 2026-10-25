# One image that serves the PWA and the API on port 8080.
#   docker build -t jansathi .        (or: docker compose up, with PostgreSQL)
# Tests run in CI, not here.

# 1. The PWA, built to call the API on its own origin.
FROM node:24-alpine AS web
WORKDIR /src/frontend
COPY frontend/package.json frontend/package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY frontend/ ./
RUN npm run build:api

# 2. The Spring Boot jar, with the rule bundle, copy rules and the PWA inside.
FROM eclipse-temurin:25-jdk AS api
WORKDIR /src/backend
COPY backend/gradlew backend/settings.gradle.kts backend/build.gradle.kts ./
COPY backend/gradle gradle
RUN ./gradlew --no-daemon -q dependencies > /dev/null
COPY backend/src src
COPY frontend/public/data /src/frontend/public/data
COPY tools/banned-phrases.json /src/tools/banned-phrases.json
COPY --from=web /src/frontend/dist /src/frontend/dist
RUN ./gradlew --no-daemon -q bootJar -x test

# 3. Runtime: JRE only, unprivileged user.
FROM eclipse-temurin:25-jre
RUN useradd --system --uid 10001 --home /app jansathi && mkdir -p /app/data && chown jansathi /app/data
WORKDIR /app
COPY --from=api /src/backend/build/libs/api-0.0.1-SNAPSHOT.jar app.jar
USER jansathi
EXPOSE 8080
ENTRYPOINT ["java", "-XX:MaxRAMPercentage=75", "-jar", "/app/app.jar"]
