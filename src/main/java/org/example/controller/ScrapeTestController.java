package org.example.controller;

import lombok.RequiredArgsConstructor;
import org.example.model.ArticleInfo;
import org.example.service.CaptionService;
import org.example.service.ImageService;
import org.example.service.InstagramService;
import org.example.service.ScraperService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class ScrapeTestController {

    private final ScraperService scraperService;
    private final ImageService imageService;
    private final CaptionService captionService;
    private final InstagramService instagramService;

    @GetMapping("/test/scrape")
    public ArticleInfo testScrape() throws Exception {
        return scraperService.scrapeLatestArticle();
    }

    @GetMapping("/test/generate")
    public String testGenerate() throws Exception {
        ArticleInfo article = scraperService.scrapeLatestArticle();
        imageService.generateImage(article);
        return "Generated: " + article.getTitle();
    }

    // Force-publishes the latest news article, bypassing the duplicate check
    @GetMapping("/test/force-post")
    public String forcePost() throws Exception {
        ArticleInfo article = scraperService.scrapeLatestArticle();
        if (article.getLink() == null) return "No article found";
        imageService.generateImage(article);
        imageService.generateStoryImage(article);
        String caption = captionService.formatCaption(article.getContent());
        instagramService.postImage(caption);
        return "Force-posted: " + article.getTitle();
    }

    // Posts only a Story (9:16) — bypasses feed and duplicate check
    @GetMapping("/test/force-story")
    public String forceStory() throws Exception {
        ArticleInfo article = scraperService.scrapeLatestArticle();
        if (article.getLink() == null) return "No article found";
        imageService.generateImage(article);
        imageService.generateStoryImage(article);
        instagramService.postStoryOnly();
        return "Story posted: " + article.getTitle();
    }
}
