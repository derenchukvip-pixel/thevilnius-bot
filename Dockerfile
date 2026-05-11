# ── Stage 1: Build ────────────────────────────────────────────────────────────
# Uses a full JDK + Maven image so Playwright's CLI can download Chromium
FROM maven:3.9-eclipse-temurin-21 AS builder

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
FROM eclipse-temurin:21-jre-alpine

WORKDIR /app

# System libraries required by Playwright Chromium on Alpine
RUN apk add --no-cache \
      chromium \
      nss \
      freetype \
      harfbuzz \
      ca-certificates \
      ttf-freefont \
      fontconfig \
      udev \
      font-noto

# Copy the built JAR
COPY --from=builder /app/target/thevilnius2-instagram-posts-1.0-SNAPSHOT.jar app.jar

# Copy pre-downloaded Playwright browsers
COPY --from=builder /opt/pw-browsers /opt/pw-browsers

# Point Playwright to the pre-downloaded browsers
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

