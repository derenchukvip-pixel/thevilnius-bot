# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

Spring Boot 3.3 / Java 21 app that scrapes the latest article from https://thevilnius.lt/, renders a 1080×1080 PNG via Playwright, and posts it to Instagram via the Graph API. Runs on a daily cron (`0 0 10 * * *`).

## Build & Run

```bash
mvn clean package -DskipTests
mvn spring-boot:run
```

## Install Playwright browsers (one-time)

```bash
mvn exec:java -Dexec.mainClass="com.microsoft.playwright.CLI" -Dexec.args="install chromium"
```

## Configuration (`src/main/resources/application.properties`)

| Property | Description |
|---|---|
| `instagram.user.id` | Instagram Business account ID |
| `instagram.access.token` | Long-lived Graph API token |
| `app.public-url` | Public URL (e.g. ngrok) so Instagram can fetch the image |
| `server.port` | Default 8080 |

## Architecture

```
ScraperService      — Jsoup scrape of thevilnius.lt, returns ArticleInfo (title, imageUrl, link)
ImageService        — Builds an HTML template, renders with Playwright to storage/upload.png
InstagramService    — Two-step Graph API POST: /media (create container) → /media_publish
StorageController   — GET /images/{filename} serves storage/ as PNG for Instagram to pull
PostScheduler       — @Scheduled daily driver; deduplicates via last line of history.txt
```

### Flow

1. `PostScheduler.run()` fires at 10:00 AM.
2. Checks `history.txt` — skips if the latest scraped link matches the last line.
3. `ScraperService` fetches the homepage, selects the first `<article>` element.
4. `ImageService` renders HTML (background image + 60 % dark gradient + bold white title) to `storage/upload.png`.
5. `InstagramService` POSTs `{app.public-url}/images/upload.png` to `/media`, then POSTs the returned `creation_id` to `/media_publish`.
6. The article link is appended to `history.txt`.

### Key files

| File | Role |
|---|---|
| `src/main/java/org/example/Main.java` | `@SpringBootApplication` + `@EnableScheduling` entry point |
| `src/main/java/org/example/model/ArticleInfo.java` | DTO |
| `storage/upload.png` | Generated image (git-ignored) |
| `history.txt` | Append-only log of posted article links |

## ngrok setup

```bash
ngrok http 8080
# copy the https URL into app.public-url in application.properties
```
