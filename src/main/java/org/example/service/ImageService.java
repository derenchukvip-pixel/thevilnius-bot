package org.example.service;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.ScreenshotType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.example.model.ArticleInfo;
import org.springframework.stereotype.Service;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.Arrays;

@Slf4j
@Service
@RequiredArgsConstructor
public class ImageService {

    private static final String STORAGE_DIR = "storage";
    public static final String OUTPUT_FILENAME = "upload.png";
    public static final String STORY_FILENAME = "story_upload.png";
    private static final Path COUNTER_FILE = Paths.get(STORAGE_DIR, "theme_counter.txt");

    private enum Theme { LIGHT, DARK }

    private Theme lastTheme = Theme.LIGHT;

    private final CaptionService captionService;

    /**
     * Generates BOTH feed (1080×1350) and story (1080×1920) images using a SINGLE
     * Chromium instance to keep peak memory within Render's 512 MB free-tier limit.
     *
     * @param isDark true → DARK theme, false → LIGHT theme
     */
    public void generateBothImages(ArticleInfo article, boolean isDark) throws Exception {
        Path storageDir = Paths.get(STORAGE_DIR);
        Files.createDirectories(storageDir);

        ensureKeyPhrase(article);

        Theme theme = isDark ? Theme.DARK : Theme.LIGHT;
        lastTheme = theme;
        log.info("Using {} theme", theme);

        String feedHtml  = theme == Theme.DARK ? buildDarkHtml(article)      : buildLightHtml(article);
        String storyHtml = theme == Theme.DARK ? buildDarkStoryHtml(article)  : buildLightStoryHtml(article);

        Path feedPath    = storageDir.resolve(OUTPUT_FILENAME);
        Path previewPath = storageDir.resolve("grid_preview.png");
        Path storyPath   = storageDir.resolve(STORY_FILENAME);

        Files.deleteIfExists(feedPath);
        Files.deleteIfExists(previewPath);
        Files.deleteIfExists(storyPath);

        // One Playwright / one Chromium for both renders — ~250 MB instead of ~500 MB
        try (Playwright playwright = Playwright.create()) {
            try (Browser browser = playwright.chromium().launch()) {

                // ── Feed 1080×1350 ──────────────────────────────────────────────
                BrowserContext feedCtx = browser.newContext(
                        new Browser.NewContextOptions().setViewportSize(1080, 1350));
                Page feedPage = feedCtx.newPage();
                feedPage.setContent(feedHtml);
                feedPage.waitForLoadState(LoadState.NETWORKIDLE,
                        new Page.WaitForLoadStateOptions().setTimeout(10_000));
                feedPage.screenshot(new Page.ScreenshotOptions()
                        .setPath(feedPath).setType(ScreenshotType.PNG));
                feedPage.screenshot(new Page.ScreenshotOptions()
                        .setPath(previewPath).setClip(0, 135, 1080, 1080)
                        .setType(ScreenshotType.PNG));
                feedCtx.close();
                log.info("Feed image saved: {} ({})", feedPath.toAbsolutePath(), theme);

                // ── Story 1080×1920 ─────────────────────────────────────────────
                BrowserContext storyCtx = browser.newContext(
                        new Browser.NewContextOptions().setViewportSize(1080, 1920));
                Page storyPage = storyCtx.newPage();
                storyPage.setContent(storyHtml);
                storyPage.waitForLoadState(LoadState.NETWORKIDLE,
                        new Page.WaitForLoadStateOptions().setTimeout(10_000));
                storyPage.screenshot(new Page.ScreenshotOptions()
                        .setPath(storyPath).setType(ScreenshotType.PNG));
                storyCtx.close();
                log.info("Story image saved: {} ({})", storyPath.toAbsolutePath(), theme);
            }
        }
    }

    /** @deprecated Use {@link #generateBothImages(ArticleInfo, boolean)} instead. */
    public Path generateImage(ArticleInfo article) throws Exception {
        generateBothImages(article, false);
        return Paths.get(STORAGE_DIR, OUTPUT_FILENAME);
    }

    // Counter cycles 0–5: 0,1,2 → LIGHT; 3,4,5 → DARK
    private Theme nextTheme() throws Exception {
        int count = 0;
        if (Files.exists(COUNTER_FILE)) {
            String s = Files.readString(COUNTER_FILE).trim();
            if (!s.isEmpty()) count = Integer.parseInt(s);
        }
        Theme theme = (count % 6) < 3 ? Theme.LIGHT : Theme.DARK;
        Files.writeString(COUNTER_FILE, String.valueOf((count + 1) % 6),
                StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
        return theme;
    }


    // Computes the key phrase once per article (LLM call) and stores it on the model.
    private void ensureKeyPhrase(ArticleInfo article) {
        if (article.getKeyPhrase() != null) return;
        article.setKeyPhrase(captionService.findKeyPhrase(article.getTitle()));
    }

    // Wraps the LLM-identified key phrase in a yellow highlight span.
    // Falls back to the last 3 words if no phrase is provided.
    private String buildTitleHtml(String title, String keyPhrase) {
        String safeTitle = escapeHtml(title);
        if (keyPhrase != null && !keyPhrase.isBlank()) {
            String safePhrase = escapeHtml(keyPhrase);
            int idx = safeTitle.indexOf(safePhrase);
            if (idx >= 0) {
                return safeTitle.substring(0, idx)
                        + "<span class=\"hl\">" + safePhrase + "</span>"
                        + safeTitle.substring(idx + safePhrase.length());
            }
        }
        // Fallback (no LLM phrase): highlight a trailing window, but never lead with a
        // preposition/locative so we don't end up emphasising things like "на своей территории".
        String[] words = safeTitle.trim().split("\\s+");
        if (words.length <= 3) {
            return "<span class=\"hl\">" + safeTitle + "</span>";
        }
        int start = words.length - 3;
        while (start < words.length - 1 && STOPWORDS.contains(words[start].toLowerCase())) {
            start++;
        }
        String normal = String.join(" ", Arrays.copyOf(words, start));
        String highlighted = String.join(" ", Arrays.copyOfRange(words, start, words.length));
        return (normal.isEmpty() ? "" : normal + " ")
                + "<span class=\"hl\">" + highlighted + "</span>";
    }

    // Russian prepositions / pronoun-locatives we never want to lead a highlight with.
    private static final java.util.Set<String> STOPWORDS = java.util.Set.of(
            "на", "в", "во", "с", "со", "по", "за", "от", "до", "из", "у", "о", "об", "обо",
            "для", "при", "под", "над", "к", "ко", "и", "а", "но", "же", "бы", "ли",
            "своей", "свою", "своих", "своего", "этой", "этого", "эту", "его", "их");

    private static String escapeHtml(String s) {
        return s == null ? "" : s
                .replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
    }

    private String buildDarkHtml(ArticleInfo article) {
        String safeImage = safeImage(article.getImageUrl());
        return """
                <!DOCTYPE html>
                <html>
                <head>
                <meta charset="UTF-8">
                <link rel="preconnect" href="https://fonts.googleapis.com">
                <link href="https://fonts.googleapis.com/css2?family=League+Spartan:wght@400;700&display=swap" rel="stylesheet">
                <style>
                  * { margin: 0; padding: 0; box-sizing: border-box; }
                  body {
                    width: 1080px; height: 1350px;
                    overflow: hidden;
                    display: flex; flex-direction: column;
                  }

                  /* ── top 50 %% (675 of 1350) ──
                     padding-top=200 keeps the logo inside Instagram's centered
                     1080×1080 grid crop (visible band starts at Y=135) */
                  .top {
                    flex-shrink: 0;
                    width: 1080px; height: 675px;
                    background: #111111;
                    display: flex; flex-direction: column;
                    padding: 200px 120px 80px;
                  }

                  /* logo — single line: "the [VILNIUS]" */
                  .logo { display: inline-flex; flex-direction: row; align-items: center; gap: 8px; }
                  .logo-the {
                    font-family: 'League Spartan', sans-serif;
                    font-weight: 700; font-size: 22px;
                    color: rgba(255,255,255,0.9);
                    text-transform: lowercase;
                  }
                  .logo-vilnius {
                    font-family: 'League Spartan', sans-serif;
                    font-weight: 700; font-size: 22px; letter-spacing: 2px;
                    text-transform: uppercase;
                    color: #111111; background: #FFD700;
                    display: inline-block; padding: 3px 10px 5px; line-height: 1;
                  }

                  /* title — vertically centered in remaining space */
                  .title-wrap { flex: 1; display: flex; align-items: center; }
                  .title {
                    width: 100%%;
                    font-family: 'League Spartan', sans-serif;
                    font-weight: 700; font-size: 58px; line-height: 0.95;
                    color: #ffffff;
                    display: -webkit-box;
                    -webkit-line-clamp: 5; -webkit-box-orient: vertical;
                    overflow: hidden;
                  }
                  .hl {
                    background: #FFD700; color: #111111;
                    padding: 4px 10px;
                    border-radius: 3px;
                    -webkit-box-decoration-break: clone;
                    box-decoration-break: clone;
                  }

                  /* ── bottom 50 %% ── */
                  .bottom {
                    flex: 1;
                    background-image: url('%s');
                    background-size: cover; background-position: center;
                    background-color: #1c1c1c;
                  }
                </style>
                </head>
                <body>
                  <div class="top">
                    <div class="logo">
                      <span class="logo-the">the</span><span class="logo-vilnius">VILNIUS</span>
                    </div>
                    <div class="title-wrap">
                      <div class="title">%s</div>
                    </div>
                  </div>
                  <div class="bottom"></div>
                </body>
                </html>
                """.formatted(safeImage, buildTitleHtml(article.getTitle(), article.getKeyPhrase()));
    }

    private String buildDarkStoryHtml(ArticleInfo article) {
        String safeImage = safeImage(article.getImageUrl());
        return """
                <!DOCTYPE html>
                <html>
                <head>
                <meta charset="UTF-8">
                <link rel="preconnect" href="https://fonts.googleapis.com">
                <link href="https://fonts.googleapis.com/css2?family=League+Spartan:wght@400;700&display=swap" rel="stylesheet">
                <style>
                  * { margin: 0; padding: 0; box-sizing: border-box; }
                  body {
                    width: 1080px; height: 1920px;
                    overflow: hidden;
                    position: relative;
                    display: flex; flex-direction: column;
                  }

                  /* top 60%% — text area; padding-top=250 pushes content below Instagram UI */
                  .top {
                    flex-shrink: 0;
                    width: 1080px; height: 1152px;
                    background: #111111;
                    display: flex; flex-direction: column;
                    padding: 250px 120px 60px;
                  }

                  .logo { display: inline-flex; flex-direction: row; align-items: center; gap: 10px; margin-bottom: 40px; }
                  .logo-the {
                    font-family: 'League Spartan', sans-serif;
                    font-weight: 700; font-size: 28px;
                    color: rgba(255,255,255,0.9);
                    text-transform: lowercase;
                  }
                  .logo-vilnius {
                    font-family: 'League Spartan', sans-serif;
                    font-weight: 700; font-size: 28px; letter-spacing: 2px;
                    text-transform: uppercase;
                    color: #111111; background: #FFD700;
                    display: inline-block; padding: 4px 12px 6px; line-height: 1;
                  }

                  .title {
                    font-family: 'League Spartan', sans-serif;
                    font-weight: 700; font-size: 96px; line-height: 0.95;
                    color: #ffffff;
                    word-break: break-word;
                  }
                  .hl {
                    background: #FFD700; color: #111111;
                    padding: 4px 12px;
                    border-radius: 3px;
                    -webkit-box-decoration-break: clone;
                    box-decoration-break: clone;
                  }

                  /* bottom 40%% — news photo; crisp edge, no blur */
                  .bottom {
                    flex: 1;
                    background-image: url('%s');
                    background-size: cover; background-position: center top;
                    background-color: #1c1c1c;
                  }

                  /* CTA sticker — bottom-center, above IG's reply bar (~250px reserved) */
                  .cta {
                    position: absolute;
                    left: 50%%;
                    bottom: 300px;
                    transform: translateX(-50%%);
                    display: flex; flex-direction: column; align-items: center; gap: 14px;
                    z-index: 10;
                  }
                  .cta-button {
                    background: #FFD700;
                    color: #111111;
                    font-family: 'League Spartan', sans-serif;
                    font-weight: 700;
                    font-size: 30px;
                    letter-spacing: 2px;
                    text-transform: uppercase;
                    padding: 22px 52px 24px;
                    border-radius: 60px;
                    white-space: nowrap;
                    display: inline-flex; align-items: center; gap: 14px;
                    box-shadow: 0 14px 44px rgba(0,0,0,0.55);
                    line-height: 1;
                  }
                  .cta-arrow { font-size: 26px; line-height: 1; }
                </style>
                </head>
                <body>
                  <div class="top">
                    <div class="logo">
                      <span class="logo-the">the</span><span class="logo-vilnius">VILNIUS</span>
                    </div>
                    <div class="title" id="storyTitle">%s</div>
                  </div>
                  <div class="bottom"></div>
                  <div class="cta">
                    <div class="cta-button">Ссылка в шапке профиля <span class="cta-arrow">↑</span></div>
                  </div>
                  <script>
                    (function() {
                      var titleEl = document.getElementById('storyTitle');
                      var topEl = titleEl.parentElement;
                      var logoEl = topEl.querySelector('.logo');
                      var cs = getComputedStyle(topEl);
                      var available = topEl.clientHeight
                        - parseFloat(cs.paddingTop)
                        - parseFloat(cs.paddingBottom)
                        - logoEl.offsetHeight
                        - parseFloat(getComputedStyle(logoEl).marginBottom);
                      var fontSize = 96;
                      titleEl.style.fontSize = fontSize + 'px';
                      while (titleEl.scrollHeight > available && fontSize > 44) {
                        fontSize -= 2;
                        titleEl.style.fontSize = fontSize + 'px';
                      }
                    })();
                  </script>
                </body>
                </html>
                """.formatted(safeImage, buildTitleHtml(article.getTitle(), article.getKeyPhrase()));
    }

    private String buildLightStoryHtml(ArticleInfo article) {
        String safeImage = safeImage(article.getImageUrl());
        return """
                <!DOCTYPE html>
                <html>
                <head>
                <meta charset="UTF-8">
                <link rel="preconnect" href="https://fonts.googleapis.com">
                <link href="https://fonts.googleapis.com/css2?family=League+Spartan:wght@400;700&display=swap" rel="stylesheet">
                <style>
                  * { margin: 0; padding: 0; box-sizing: border-box; }
                  body {
                    width: 1080px; height: 1920px;
                    overflow: hidden;
                    position: relative;
                    display: flex; flex-direction: column;
                  }

                  /* top 60%% — text area; padding-top=250 pushes content below Instagram UI */
                  .top {
                    flex-shrink: 0;
                    width: 1080px; height: 1152px;
                    background: #F4F0E6;
                    display: flex; flex-direction: column;
                    padding: 250px 120px 60px;
                  }

                  .logo { display: inline-flex; flex-direction: row; align-items: center; gap: 10px; margin-bottom: 40px; }
                  .logo-the {
                    font-family: 'League Spartan', sans-serif;
                    font-weight: 700; font-size: 28px;
                    color: #111111;
                    text-transform: lowercase;
                  }
                  .logo-vilnius {
                    font-family: 'League Spartan', sans-serif;
                    font-weight: 700; font-size: 28px; letter-spacing: 2px;
                    text-transform: uppercase;
                    color: #111111; background: #FFD700;
                    display: inline-block; padding: 4px 12px 6px; line-height: 1;
                  }

                  .title {
                    font-family: 'League Spartan', sans-serif;
                    font-weight: 700; font-size: 96px; line-height: 0.95;
                    color: #111111;
                    word-break: break-word;
                  }
                  .hl {
                    background: #FFD700; color: #111111;
                    padding: 4px 12px;
                    border-radius: 3px;
                    -webkit-box-decoration-break: clone;
                    box-decoration-break: clone;
                  }

                  /* bottom 40%% — news photo; crisp edge, no blur */
                  .bottom {
                    flex: 1;
                    background-image: url('%s');
                    background-size: cover; background-position: center top;
                    background-color: #DDD9CE;
                  }

                  /* CTA sticker — bottom-center, above IG's reply bar (~250px reserved) */
                  .cta {
                    position: absolute;
                    left: 50%%;
                    bottom: 300px;
                    transform: translateX(-50%%);
                    display: flex; flex-direction: column; align-items: center; gap: 14px;
                    z-index: 10;
                  }
                  .cta-button {
                    background: #FFD700;
                    color: #111111;
                    font-family: 'League Spartan', sans-serif;
                    font-weight: 700;
                    font-size: 30px;
                    letter-spacing: 2px;
                    text-transform: uppercase;
                    padding: 22px 52px 24px;
                    border-radius: 60px;
                    white-space: nowrap;
                    display: inline-flex; align-items: center; gap: 14px;
                    box-shadow: 0 14px 44px rgba(0,0,0,0.45);
                    line-height: 1;
                  }
                  .cta-arrow { font-size: 26px; line-height: 1; }
                </style>
                </head>
                <body>
                  <div class="top">
                    <div class="logo">
                      <span class="logo-the">the</span><span class="logo-vilnius">VILNIUS</span>
                    </div>
                    <div class="title" id="storyTitle">%s</div>
                  </div>
                  <div class="bottom"></div>
                  <div class="cta">
                    <div class="cta-button">Ссылка в шапке профиля <span class="cta-arrow">↑</span></div>
                  </div>
                  <script>
                    (function() {
                      var titleEl = document.getElementById('storyTitle');
                      var topEl = titleEl.parentElement;
                      var logoEl = topEl.querySelector('.logo');
                      var cs = getComputedStyle(topEl);
                      var available = topEl.clientHeight
                        - parseFloat(cs.paddingTop)
                        - parseFloat(cs.paddingBottom)
                        - logoEl.offsetHeight
                        - parseFloat(getComputedStyle(logoEl).marginBottom);
                      var fontSize = 96;
                      titleEl.style.fontSize = fontSize + 'px';
                      while (titleEl.scrollHeight > available && fontSize > 44) {
                        fontSize -= 2;
                        titleEl.style.fontSize = fontSize + 'px';
                      }
                    })();
                  </script>
                </body>
                </html>
                """.formatted(safeImage, buildTitleHtml(article.getTitle(), article.getKeyPhrase()));
    }

    private String buildLightHtml(ArticleInfo article) {
        String safeImage = safeImage(article.getImageUrl());
        return """
                <!DOCTYPE html>
                <html>
                <head>
                <meta charset="UTF-8">
                <link rel="preconnect" href="https://fonts.googleapis.com">
                <link href="https://fonts.googleapis.com/css2?family=League+Spartan:wght@400;700&display=swap" rel="stylesheet">
                <style>
                  * { margin: 0; padding: 0; box-sizing: border-box; }
                  body {
                    width: 1080px; height: 1350px;
                    overflow: hidden;
                    display: flex; flex-direction: column;
                  }

                  /* ── top 50 %% (675 of 1350) ──
                     padding-top=200 keeps the logo inside Instagram's centered
                     1080×1080 grid crop (visible band starts at Y=135) */
                  .top {
                    flex-shrink: 0;
                    width: 1080px; height: 675px;
                    background: #F4F0E6;
                    display: flex; flex-direction: column;
                    padding: 200px 120px 80px;
                  }

                  /* logo — single line: "the [VILNIUS]" */
                  .logo { display: inline-flex; flex-direction: row; align-items: center; gap: 8px; }
                  .logo-the {
                    font-family: 'League Spartan', sans-serif;
                    font-weight: 700; font-size: 22px;
                    color: #111111;
                    text-transform: lowercase;
                  }
                  .logo-vilnius {
                    font-family: 'League Spartan', sans-serif;
                    font-weight: 700; font-size: 22px; letter-spacing: 2px;
                    text-transform: uppercase;
                    color: #111111; background: #FFD700;
                    display: inline-block; padding: 3px 10px 5px; line-height: 1;
                  }

                  /* title — vertically centered in remaining space */
                  .title-wrap { flex: 1; display: flex; align-items: center; }
                  .title {
                    width: 100%%;
                    font-family: 'League Spartan', sans-serif;
                    font-weight: 700; font-size: 58px; line-height: 0.95;
                    color: #111111;
                    display: -webkit-box;
                    -webkit-line-clamp: 5; -webkit-box-orient: vertical;
                    overflow: hidden;
                  }
                  .hl {
                    background: #FFD700; color: #111111;
                    padding: 4px 10px;
                    border-radius: 3px;
                    -webkit-box-decoration-break: clone;
                    box-decoration-break: clone;
                  }

                  /* ── bottom 50 %% ── */
                  .bottom {
                    flex: 1;
                    background-image: url('%s');
                    background-size: cover; background-position: center;
                    background-color: #DDD9CE;
                  }
                </style>
                </head>
                <body>
                  <div class="top">
                    <div class="logo">
                      <span class="logo-the">the</span><span class="logo-vilnius">VILNIUS</span>
                    </div>
                    <div class="title-wrap">
                      <div class="title">%s</div>
                    </div>
                  </div>
                  <div class="bottom"></div>
                </body>
                </html>
                """.formatted(safeImage, buildTitleHtml(article.getTitle(), article.getKeyPhrase()));
    }

    private static String safeImage(String imageUrl) {
        return imageUrl == null ? "" : imageUrl.replace("'", "\\'");
    }
}
