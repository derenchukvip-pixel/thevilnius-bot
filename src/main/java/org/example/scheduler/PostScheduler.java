package org.example.scheduler;

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
import java.util.List;

@Slf4j
@Component
@RequiredArgsConstructor
public class PostScheduler {

    private static final Path HISTORY = Paths.get("history.txt");

    private final ScraperService scraperService;
    private final ImageService imageService;
    private final InstagramService instagramService;
    private final CaptionService captionService;

    @EventListener(ApplicationReadyEvent.class)
    public void run() {
        try {
            log.info("Scheduler triggered");

            ArticleInfo article = scraperService.scrapeLatestArticle();

            if (article.getLink() == null) {
                log.warn("No article link found — skipping");
                return;
            }

            if (alreadyPosted(article.getLink())) {
                log.info("Already posted '{}' — skipping", article.getLink());
                return;
            }

            imageService.generateImage(article);
            instagramService.postImage(captionService.formatCaption(article.getContent()));
            appendHistory(article.getLink());

            log.info("Done: {}", article.getLink());
        } catch (Exception e) {
            log.error("Scheduled post failed", e);
        }
    }

    private boolean alreadyPosted(String link) throws Exception {
        if (!Files.exists(HISTORY)) return false;
        List<String> lines = Files.readAllLines(HISTORY);
        if (lines.isEmpty()) return false;
        return lines.getLast().strip().equals(link.strip());
    }

    private void appendHistory(String link) throws Exception {
        Files.writeString(HISTORY, link + System.lineSeparator(),
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }
}
