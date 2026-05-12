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

    // Fires every 30 minutes to pick up new unpublished articles one by one
    @Scheduled(cron = "0 0/30 * * * *")
    public void run() {
        try {
            log.info("Scheduler triggered");

            Set<String> posted  = loadHistory();
            List<ArticleInfo> allArticles = scraperService.scrapeArticles(10);

            // Keep only articles that have not been posted yet, preserving newest-first order
            List<ArticleInfo> newArticles = allArticles.stream()
                    .filter(a -> a.getLink() != null && !posted.contains(a.getLink().strip()))
                    .toList();

            if (newArticles.isEmpty()) {
                log.info("No new articles found — skipping");
                return;
            }

            // Publish only 1 article per run to avoid OOM (Chromium memory pressure)
            ArticleInfo article = newArticles.get(0);
            log.info("Publishing 1 new article ({}  unpublished total): {}", newArticles.size(), article.getLink());
            try {
                imageService.generateImage(article);
                imageService.generateStoryImage(article);
                instagramService.postImage(captionService.formatCaption(article.getContent()));
                instagramService.updateProfileWebsite(article.getLink());
                appendHistory(article.getLink());
                log.info("Done: {}", article.getLink());
            } catch (Exception e) {
                log.error("Failed to post article '{}' — skipping", article.getLink(), e);
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
