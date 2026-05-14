package org.example.scheduler;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.model.ArticleInfo;
import org.example.service.CaptionService;
import org.example.service.ImageService;
import org.example.service.InstagramService;
import org.example.service.ScraperService;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.locks.ReentrantLock;

@Slf4j
@Component
@RequiredArgsConstructor
public class PostScheduler {

    private static final Path STORAGE_DIR   = Paths.get("storage");
    private static final Path HISTORY       = STORAGE_DIR.resolve("history.txt");
    private static final int  MAX_PER_DAY   = 3;

    private final ScraperService    scraperService;
    private final ImageService      imageService;
    private final InstagramService  instagramService;
    private final CaptionService    captionService;

    /** Guards against concurrent runs (startup-publisher vs cron firing simultaneously). */
    private final ReentrantLock runLock = new ReentrantLock();

    /** Ensures storage/ and history.txt exist at startup so the app never fails on first run. */
    @PostConstruct
    public void init() {
        try {
            Files.createDirectories(STORAGE_DIR);
            if (!Files.exists(HISTORY)) {
                Files.createFile(HISTORY);
                log.info("Created empty history.txt");
            }
            log.info("Storage directory ready: {}", STORAGE_DIR.toAbsolutePath());
        } catch (Exception e) {
            log.error("Failed to initialise storage directory / history.txt", e);
        }
    }

    /** Triggered once immediately when the application is fully started — runs in background thread. */
    @EventListener(ApplicationReadyEvent.class)
    public void runOnStartup() {
        log.info("Application ready — running startup publication check");
        Thread thread = new Thread(() -> {
            try {
                run();
            } catch (Exception e) {
                log.error("Startup publication check failed", e);
            }
        }, "startup-publisher");
        thread.setDaemon(false);
        thread.start();
    }

    // Fires every 30 minutes to pick up new unpublished articles one by one
    @Scheduled(cron = "0 0/30 * * * *")
    public void run() {
        if (!runLock.tryLock()) {
            log.info("Scheduler skipped — another run is already in progress");
            return;
        }
        try {
            doRun();
        } finally {
            runLock.unlock();
        }
    }

    private void doRun() {
        try {
            log.info("Scheduler triggered");

            List<String> historyLines = Files.exists(HISTORY)
                    ? Files.readAllLines(HISTORY) : List.of();
            Set<String> posted = new HashSet<>();
            long todayCount = 0;
            String todayPrefix = "# " + LocalDate.now();
            for (String line : historyLines) {
                String t = line.strip();
                if (t.isEmpty()) continue;
                if (t.startsWith("# ")) {
                    // dated marker line — count today's posts
                    if (t.startsWith(todayPrefix)) todayCount++;
                } else {
                    posted.add(t);
                }
            }

            if (todayCount >= MAX_PER_DAY) {
                log.info("Daily limit reached ({}/{} posts today) — skipping", todayCount, MAX_PER_DAY);
                return;
            }

            List<ArticleInfo> allArticles = scraperService.scrapeArticles(10);
            List<ArticleInfo> newArticles = allArticles.stream()
                    .filter(a -> a.getLink() != null && !posted.contains(a.getLink().strip()))
                    .toList();

            if (newArticles.isEmpty()) {
                log.info("No new articles found — skipping");
                return;
            }

            // Publish only 1 article per run to avoid OOM (Chromium memory pressure)
            ArticleInfo article = newArticles.get(0);
            log.info("Publishing 1 new article ({} unpublished total, {}/{} today): {}",
                    newArticles.size(), todayCount, MAX_PER_DAY, article.getLink());
            try {
                imageService.generateImage(article);
                imageService.generateStoryImage(article);
                log.info("Generating caption for: {}", article.getTitle());
                String caption = captionService.formatCaption(article.getContent());
                log.info("Caption ready ({} chars), posting to Instagram...", caption.length());
                instagramService.postImage(caption);
                // Write history BEFORE profile-update so a crash there won't cause a double-post
                appendHistory(article.getLink());
                log.info("Saved to history: {}", article.getLink());
                instagramService.updateProfileWebsite(article.getLink());
                log.info("Done: {}", article.getLink());
            } catch (Exception e) {
                log.error("Failed to post article '{}' — skipping", article.getLink(), e);
            }

        } catch (Exception e) {
            log.error("Scheduled post failed", e);
        }
    }

    private void appendHistory(String link) throws Exception {
        // Write a dated marker on a separate line, then the URL
        String entry = "# " + LocalDate.now() + System.lineSeparator()
                + link + System.lineSeparator();
        Files.writeString(HISTORY, entry, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
}
