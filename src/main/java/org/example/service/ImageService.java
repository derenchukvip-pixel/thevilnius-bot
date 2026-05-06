package org.example.service;

import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.options.LoadState;
import com.microsoft.playwright.options.ScreenshotType;
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
public class ImageService {

    private static final String STORAGE_DIR = "storage";
    public static final String OUTPUT_FILENAME = "upload.png";
    private static final Path COUNTER_FILE = Paths.get(STORAGE_DIR, "theme_counter.txt");

    private enum Theme { LIGHT, DARK }

    public Path generateImage(ArticleInfo article) throws Exception {
        Path storageDir = Paths.get(STORAGE_DIR);
        Files.createDirectories(storageDir);

        Theme theme = nextTheme();
        log.info("Using {} theme", theme);

        String title = article.getTitle() != null ? article.getTitle() : "";
        String imageUrl = article.getImageUrl() != null ? article.getImageUrl() : "";
        String html = theme == Theme.DARK ? buildDarkHtml(title, imageUrl) : buildLightHtml(title, imageUrl);

        Path outputPath = storageDir.resolve(OUTPUT_FILENAME);

        try (Playwright playwright = Playwright.create()) {
            try (Browser browser = playwright.chromium().launch()) {
                BrowserContext ctx = browser.newContext(
                        new Browser.NewContextOptions().setViewportSize(1080, 1080));
                Page page = ctx.newPage();
                page.setContent(html);
                page.waitForLoadState(LoadState.NETWORKIDLE,
                        new Page.WaitForLoadStateOptions().setTimeout(10_000));
                page.screenshot(new Page.ScreenshotOptions()
                        .setPath(outputPath)
                        .setType(ScreenshotType.PNG));
            }
        }

        log.info("Image saved: {} ({})", outputPath.toAbsolutePath(), theme);
        return outputPath;
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

    // Escapes title and wraps the last 3 words in a yellow highlight span
    private String buildTitleHtml(String title) {
        String safe = title
                .replace("&", "&amp;").replace("<", "&lt;")
                .replace(">", "&gt;").replace("\"", "&quot;");
        String[] words = safe.split(" ");
        if (words.length <= 3) {
            return "<span class=\"hl\">" + safe + "</span>";
        }
        String normal = String.join(" ", Arrays.copyOf(words, words.length - 3));
        String highlighted = String.join(" ", Arrays.copyOfRange(words, words.length - 3, words.length));
        return normal + " <span class=\"hl\">" + highlighted + "</span>";
    }

    private String buildDarkHtml(String title, String imageUrl) {
        String safeImage = imageUrl.replace("'", "\\'");
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
                    width: 1080px; height: 1080px;
                    overflow: hidden;
                    display: flex; flex-direction: column;
                  }

                  /* ── top 50 %% ── */
                  .top {
                    flex-shrink: 0;
                    width: 1080px; height: 540px;
                    background: #111111;
                    display: flex; flex-direction: column;
                    padding: 80px;
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
                    font-weight: 700; font-size: 72px; line-height: 1.1;
                    color: #ffffff;
                    display: -webkit-box;
                    -webkit-line-clamp: 4; -webkit-box-orient: vertical;
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
                """.formatted(safeImage, buildTitleHtml(title));
    }

    private String buildLightHtml(String title, String imageUrl) {
        String safeImage = imageUrl.replace("'", "\\'");
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
                    width: 1080px; height: 1080px;
                    overflow: hidden;
                    display: flex; flex-direction: column;
                  }

                  /* ── top 50 %% ── */
                  .top {
                    flex-shrink: 0;
                    width: 1080px; height: 540px;
                    background: #F4F0E6;
                    display: flex; flex-direction: column;
                    padding: 80px;
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
                    font-weight: 700; font-size: 72px; line-height: 1.1;
                    color: #111111;
                    display: -webkit-box;
                    -webkit-line-clamp: 4; -webkit-box-orient: vertical;
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
                """.formatted(safeImage, buildTitleHtml(title));
    }
}
