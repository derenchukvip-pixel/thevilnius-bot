# ── Stage 1: Build ────────────────────────────────────────────────────────────
FROM maven:3.9.6-eclipse-temurin-21-jammy AS builder

WORKDIR /app

# Cache dependencies first (improves rebuild speed)
COPY pom.xml .
RUN mvn dependency:go-offline -q

# Build the application JAR
COPY src ./src
RUN mvn clean package -DskipTests

# Download ALL Playwright browsers into a fixed directory so it can be copied
ENV PLAYWRIGHT_BROWSERS_PATH=/opt/pw-browsers
RUN mvn exec:java \
      -Dexec.mainClass="com.microsoft.playwright.CLI" \
      -Dexec.args="install --with-deps"


# ── Stage 2: Runtime ──────────────────────────────────────────────────────────
# Official Playwright image already has ALL Chromium dependencies + Node.js + JDK
FROM mcr.microsoft.com/playwright/java:v1.44.0-jammy

WORKDIR /app

# Copy the built JAR
COPY --from=builder /app/target/thevilnius2-instagram-posts-1.0-SNAPSHOT.jar app.jar

# Copy pre-downloaded Playwright browsers
COPY --from=builder /opt/pw-browsers /opt/pw-browsers

# Point Playwright to the pre-downloaded browsers (override the image default)
ENV PLAYWRIGHT_BROWSERS_PATH=/opt/pw-browsers


# Persist generated images and history.txt across container restarts
VOLUME /app/storage

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]


