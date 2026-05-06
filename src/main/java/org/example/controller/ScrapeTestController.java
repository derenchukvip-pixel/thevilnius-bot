package org.example.controller;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.ScreenshotType;
import lombok.RequiredArgsConstructor;
import org.example.model.ArticleInfo;
import org.example.service.ImageService;
import org.example.service.ScraperService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.file.Path;
import java.nio.file.Paths;

@RestController
@RequiredArgsConstructor
public class ScrapeTestController {

    private final ScraperService scraperService;
    private final ImageService imageService;

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

    // Renders arbitrary HTML via Playwright and saves PNG to storage/preview_<name>.png
    @GetMapping("/test/render")
    public String renderHtml(@RequestParam String html, @RequestParam(defaultValue = "preview") String name) throws Exception {
        Path out = Paths.get("storage", name + ".png");
        try (Playwright pw = Playwright.create()) {
            try (Browser browser = pw.chromium().launch()) {
                BrowserContext ctx = browser.newContext(
                        new Browser.NewContextOptions().setViewportSize(1080, 1080));
                Page page = ctx.newPage();
                page.setContent(html);
                page.waitForLoadState(LoadState.NETWORKIDLE,
                        new Page.WaitForLoadStateOptions().setTimeout(10_000));
                page.screenshot(new Page.ScreenshotOptions()
                        .setPath(out).setType(ScreenshotType.PNG));
            }
        }
        return "Saved to " + out.toAbsolutePath();
    }
}
