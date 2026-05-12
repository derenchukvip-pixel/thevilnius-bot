# ── Stage 1: Build ────────────────────────────────────────────────────────────
# Uses a full JDK + Maven image so Playwright's CLI can download Chromium
FROM maven:3.9.6-eclipse-temurin-21-jammy AS builder

WORKDIR /app

# Cache dependencies first (improves rebuild speed)
COPY pom.xml .
RUN mvn dependency:go-offline -q

# Build the application JAR
COPY src ./src
RUN mvn clean package -DskipTests

# Download Playwright's Chromium into a fixed directory so it can be copied
ENV PLAYWRIGHT_BROWSERS_PATH=/opt/pw-browsers
RUN mvn exec:java \
      -Dexec.mainClass="com.microsoft.playwright.CLI" \
      -Dexec.args="install --with-deps chromium"


# ── Stage 2: Runtime ──────────────────────────────────────────────────────────
# Must be Jammy (glibc), NOT Alpine (musl) — Playwright bundles a glibc node binary
FROM eclipse-temurin:21-jre-jammy

WORKDIR /app

# Node.js (required by Playwright Java to launch the browser driver at runtime)
# + Chromium system dependencies
RUN apt-get update && apt-get install -y --no-install-recommends \
      nodejs \
      libnss3 libfreetype6 libharfbuzz0b ca-certificates \
      fonts-freefont-ttf fontconfig libasound2 libgbm1 libxshmfence1 \
      libatk1.0-0 libatk-bridge2.0-0 libcups2 libdrm2 libxcomposite1 \
      libxdamage1 libxfixes3 libxrandr2 libxss1 libxtst6 \
    && rm -rf /var/lib/apt/lists/*

# Copy the built JAR
COPY --from=builder /app/target/thevilnius2-instagram-posts-1.0-SNAPSHOT.jar app.jar

# Copy pre-downloaded Playwright browsers
COPY --from=builder /opt/pw-browsers /opt/pw-browsers

# Tell Playwright to use system Node instead of the bundled one from the JAR
ENV PLAYWRIGHT_NODEJS_PATH=/usr/bin/node
ENV PLAYWRIGHT_BROWSERS_PATH=/opt/pw-browsers

# Required environment variables (provide via -e flags or a .env file at runtime):
#   ANTHROPIC_API_KEY
#   SUPABASE_URL
#   SUPABASE_ANON_KEY
#   INSTAGRAM_USER_ID
#   INSTAGRAM_TOKEN
#   APP_PUBLIC_URL

# Persist generated images and history.txt across container restarts
VOLUME /app/storage

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "app.jar"]
