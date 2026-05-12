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
FROM eclipse-temurin:21-jre-jammy

WORKDIR /app

# Install Node.js 20 (required by Playwright ≥1.44) + Chromium system dependencies
RUN apt-get update && apt-get install -y --no-install-recommends curl ca-certificates && \
    curl -fsSL https://deb.nodesource.com/setup_20.x | bash - && \
    apt-get install -y --no-install-recommends \
      nodejs \
      libxkbcommon0 \
      libnss3 \
      libfreetype6 \
      libharfbuzz0b \
      fonts-freefont-ttf \
      fontconfig \
      libasound2 \
      libgbm1 \
      libxshmfence1 \
      libatk1.0-0 \
      libatk-bridge2.0-0 \
      libcups2 \
      libdrm2 \
      libxcomposite1 \
      libxdamage1 \
      libxfixes3 \
      libxrandr2 \
      libxss1 \
      libxtst6 \
    && rm -rf /var/lib/apt/lists/*

# Copy the built JAR
COPY --from=builder /app/target/thevilnius2-instagram-posts-1.0-SNAPSHOT.jar app.jar

# Copy pre-downloaded Playwright browsers
COPY --from=builder /opt/pw-browsers /opt/pw-browsers

# Point Playwright to the pre-downloaded browsers and system Node.js
ENV PLAYWRIGHT_BROWSERS_PATH=/opt/pw-browsers
ENV PLAYWRIGHT_NODEJS_PATH=/usr/bin/node

# Persist generated images and history.txt across container restarts
VOLUME /app/storage

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
