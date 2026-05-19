package org.example.scheduler;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.model.ArticleInfo;
import org.example.service.CaptionService;
import org.example.service.HistoryService;
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
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

@Slf4j
@Component
@RequiredArgsConstructor
public class PostScheduler {

    private static final Path STORAGE_DIR = Paths.get("storage");

    private final ScraperService   scraperService;
    private final ImageService     imageService;
    private final InstagramService instagramService;
    private final CaptionService   captionService;
    private final HistoryService   historyService;

    private final ReentrantLock runLock = new ReentrantLock();

    @PostConstruct
    public void init() {
        try {
            Files.createDirectories(STORAGE_DIR);
            log.info("Storage directory ready: {}", STORAGE_DIR.toAbsolutePath());
        } catch (Exception e) {
            log.error("Failed to initialise storage directory", e);
        }
    }

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

            List<ArticleInfo> allArticles = scraperService.scrapeArticles(10);
            if (allArticles.isEmpty()) {
                log.info("No articles returned from Supabase — skipping");
                return;
            }

            // Filter out already-posted articles using HistoryService (backed by GitHub)
            List<ArticleInfo> newArticles = allArticles.stream()
                    .filter(a -> !historyService.isPosted(a.getSlug()))
                    .toList();

            if (newArticles.isEmpty()) {
                log.info("All {} fetched articles already posted — skipping", allArticles.size());
                return;
            }

            // Publish only 1 article per run to avoid OOM (Chromium memory pressure)
            ArticleInfo article = newArticles.get(0);
            log.info("Publishing article ({} new out of {} total): {}",
                    newArticles.size(), allArticles.size(), article.getLink());

            try {
                // Theme: 3 LIGHT → 3 DARK → 3 LIGHT … derived from persistent post count
                boolean isDark = (historyService.getPostedCount() % 6) >= 3;

                // 1. Generate BOTH images in ONE Chromium instance (saves ~250 MB RAM)
                imageService.generateBothImages(article, isDark);

                // 2. Generate caption
                log.info("Generating caption for: {}", article.getTitle());
                String caption = captionService.formatCaption(article.getContent());
                log.info("Caption ready ({} chars), posting feed to Instagram...", caption.length());

                // 3. Publish feed post
                instagramService.postFeed(caption);

                // 4. Mark as posted IMMEDIATELY after feed — even if story fails, no duplicate on next run
                historyService.markAsPosted(article.getSlug());
                log.info("Done (feed): {}", article.getLink());

                // 5. Publish story (best-effort, non-fatal)
                try {
                    instagramService.postStoryOnly();
                    log.info("Done (story): {}", article.getLink());
                } catch (Exception e) {
                    log.warn("Story failed (non-fatal, feed was posted): {}", e.getMessage());
                }

            } catch (Exception e) {
                log.error("Failed to post article '{}' — skipping", article.getLink(), e);
            }

        } catch (Exception e) {
            log.error("Scheduled post failed", e);
        }
    }
}
