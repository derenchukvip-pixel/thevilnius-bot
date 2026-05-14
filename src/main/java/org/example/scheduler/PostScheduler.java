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

    /** Guards against concurrent runs (startup-publisher vs cron firing simultaneously). */
    private final ReentrantLock runLock = new ReentrantLock();

    /** Ensures storage/ exists at startup so image files can be written. */
    @PostConstruct
    public void init() {
        try {
            Files.createDirectories(STORAGE_DIR);
            log.info("Storage directory ready: {}", STORAGE_DIR.toAbsolutePath());
        } catch (Exception e) {
            log.error("Failed to initialise storage directory", e);
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

            // Supabase query already filters posted_at=is.null — only unposted articles returned
            List<ArticleInfo> newArticles = scraperService.scrapeArticles(10);

            if (newArticles.isEmpty()) {
                log.info("No new articles found — skipping");
                return;
            }

            // Publish only 1 article per run to avoid OOM (Chromium memory pressure)
            ArticleInfo article = newArticles.get(0);
            log.info("Publishing 1 new article ({} unpublished total): {}",
                    newArticles.size(), article.getLink());
            try {
                imageService.generateImage(article);
                imageService.generateStoryImage(article);
                log.info("Generating caption for: {}", article.getTitle());
                String caption = captionService.formatCaption(article.getContent());
                log.info("Caption ready ({} chars), posting to Instagram...", caption.length());
                instagramService.postImage(caption);
                // Mark as posted in Supabase BEFORE anything else so a crash won't cause a double-post
                scraperService.markAsPosted(article.getSlug());
                log.info("Done: {}", article.getLink());
            } catch (Exception e) {
                log.error("Failed to post article '{}' — skipping", article.getLink(), e);
            }

        } catch (Exception e) {
            log.error("Scheduled post failed", e);
        }
    }
}
