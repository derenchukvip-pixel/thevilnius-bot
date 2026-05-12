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
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Slf4j
@Component
@RequiredArgsConstructor
public class PostScheduler {

    private static final Path HISTORY     = Paths.get("history.txt");
    private static final Path STORAGE_DIR = Paths.get("storage");

    private final ScraperService    scraperService;
    private final ImageService      imageService;
    private final InstagramService  instagramService;
    private final CaptionService    captionService;

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

    // Fires daily at 10:00 (server timezone): second 0, minute 0, hour 10, every day.
    @Scheduled(cron = "0 0 10 * * *")
    public void run() {
        try {
            log.info("Scheduler triggered");

            Set<String> posted  = loadHistory();
            List<ArticleInfo> allArticles = scraperService.scrapeArticles(3);

            // Keep only articles that have not been posted yet, preserving newest-first order
            List<ArticleInfo> newArticles = allArticles.stream()
                    .filter(a -> a.getLink() != null && !posted.contains(a.getLink().strip()))
                    .toList();

            if (newArticles.isEmpty()) {
                log.info("No new articles found — skipping");
                return;
            }

            log.info("Found {} new article(s) to post", newArticles.size());

            for (int i = 0; i < newArticles.size(); i++) {
                ArticleInfo article = newArticles.get(i);
                try {
                    log.info("Processing [{}/{}]: {}", i + 1, newArticles.size(), article.getLink());

                    imageService.generateImage(article);
                    imageService.generateStoryImage(article);
                    instagramService.postImage(captionService.formatCaption(article.getContent()));
                    instagramService.updateProfileWebsite(article.getLink());
                    appendHistory(article.getLink());

                    log.info("Done: {}", article.getLink());
                } catch (Exception e) {
                    log.error("Failed to post article '{}' — skipping", article.getLink(), e);
                }

                // Protective delay between posts to avoid Instagram spam-block
                if (i < newArticles.size() - 1) {
                    log.info("Waiting 60 s before next article…");
                    Thread.sleep(60_000);
                }
            }

        } catch (Exception e) {
            log.error("Scheduled post failed", e);
        }
    }

    /** Loads every URL that has already been posted into a Set for O(1) lookup. */
    private Set<String> loadHistory() throws Exception {
        if (!Files.exists(HISTORY)) return new HashSet<>();
        Set<String> seen = new HashSet<>();
        for (String line : Files.readAllLines(HISTORY)) {
            String trimmed = line.strip();
            if (!trimmed.isEmpty()) seen.add(trimmed);
        }
        return seen;
    }

    private void appendHistory(String link) throws Exception {
        Files.writeString(HISTORY, link + System.lineSeparator(),
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
}
