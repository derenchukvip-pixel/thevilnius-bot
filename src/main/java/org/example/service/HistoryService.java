package org.example.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Persists posted article slugs with two layers:
 *  1. LOCAL  — storage/posted_slugs.json  (fast, survives restarts within same Render instance)
 *  2. GITHUB — data/posted_slugs.json in the repo (survives re-deploys)
 *
 * On startup: loads from GitHub first, then merges with local file.
 * On save:    writes local file first (fast), then uploads to GitHub (best-effort).
 */
@Slf4j
@Service
public class HistoryService {

    @Value("${github.token}")
    private String githubToken;

    @Value("${github.repo.owner}")
    private String repoOwner;

    @Value("${github.repo.name}")
    private String repoName;

    private static final String   GITHUB_FILE_PATH = "data/posted_slugs.json";
    private static final String   API_BASE         = "https://api.github.com";
    private static final Path     LOCAL_FILE       = Paths.get("storage/posted_slugs.json");

    private final HttpClient   http   = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();
    private final ObjectMapper mapper = new ObjectMapper();

    private final Set<String> posted = Collections.synchronizedSet(new HashSet<>());
    private String currentSha = null;

    @PostConstruct
    public void init() {
        loadFromGitHub();   // primary source (persistent across redeploys)
        loadFromLocal();    // merge with local (survives restarts within same instance)
        log.info("HistoryService ready — {} slugs loaded", posted.size());
    }

    public boolean isPosted(String slug) {
        return slug != null && posted.contains(slug);
    }

    /** Total number of articles posted so far — used to determine LIGHT/DARK theme rotation. */
    public int getPostedCount() {
        return posted.size();
    }

    public void markAsPosted(String slug) {
        if (slug == null || slug.isBlank()) return;
        posted.add(slug);
        saveToLocal();      // fast, always works
        saveToGitHub();     // persistent across redeploys (requires token write access)
        log.info("Marked as posted: slug='{}'  (total history: {})", slug, posted.size());
    }

    // ────────────────────────────────────────────────────────────────────────────
    // Local file (storage/posted_slugs.json)
    // ────────────────────────────────────────────────────────────────────────────

    private void loadFromLocal() {
        try {
            if (Files.exists(LOCAL_FILE)) {
                String[] slugs = mapper.readValue(LOCAL_FILE.toFile(), String[].class);
                int before = posted.size();
                for (String s : slugs) posted.add(s);
                log.info("Merged {} slugs from local file (total: {})", posted.size() - before, posted.size());
            }
        } catch (Exception e) {
            log.warn("Could not load local history file: {}", e.getMessage());
        }
    }

    private void saveToLocal() {
        try {
            Files.createDirectories(LOCAL_FILE.getParent());
            mapper.writeValue(LOCAL_FILE.toFile(), posted);
        } catch (Exception e) {
            log.warn("Could not save local history file: {}", e.getMessage());
        }
    }

    // ────────────────────────────────────────────────────────────────────────────
    // GitHub Contents API (data/posted_slugs.json in repo)
    // ────────────────────────────────────────────────────────────────────────────

    private void loadFromGitHub() {
        try {
            String url = API_BASE + "/repos/" + repoOwner + "/" + repoName
                    + "/contents/" + GITHUB_FILE_PATH;

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .header("Authorization", "Bearer " + githubToken)
                    .header("Accept", "application/vnd.github+json")
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .GET()
                    .build();

            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());

            if (res.statusCode() == 200) {
                JsonNode json = mapper.readTree(res.body());
                currentSha = json.path("sha").asText(null);
                String content = json.path("content").asText("").replaceAll("\\s", "");
                String decoded = new String(Base64.getDecoder().decode(content));
                String[] slugs = mapper.readValue(decoded, String[].class);
                for (String s : slugs) posted.add(s);
                log.info("Loaded {} posted slugs from GitHub (sha={})", posted.size(), currentSha);
            } else if (res.statusCode() == 404) {
                log.info("No history file yet in GitHub — starting fresh");
            } else if (res.statusCode() == 403 || res.statusCode() == 401) {
                log.warn("GitHub token has no READ access to repo contents (status={}). " +
                         "Fix: GitHub → token → Repository permissions → Contents → Read and Write",
                         res.statusCode());
            } else {
                log.warn("Unexpected status loading history from GitHub: {} — {}",
                        res.statusCode(), res.body());
            }
        } catch (Exception e) {
            log.warn("Could not load history from GitHub: {}", e.getMessage());
        }
    }

    private void saveToGitHub() {
        try {
            String url = API_BASE + "/repos/" + repoOwner + "/" + repoName
                    + "/contents/" + GITHUB_FILE_PATH;

            String jsonContent = mapper.writeValueAsString(posted);
            String encoded     = Base64.getEncoder().encodeToString(jsonContent.getBytes());

            StringBuilder body = new StringBuilder();
            body.append("{")
                .append("\"message\":\"chore: update posted slugs history\",")
                .append("\"content\":\"").append(encoded).append("\"");
            if (currentSha != null) {
                body.append(",\"sha\":\"").append(currentSha).append("\"");
            }
            body.append("}");

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(15))
                    .header("Authorization", "Bearer " + githubToken)
                    .header("Accept", "application/vnd.github+json")
                    .header("X-GitHub-Api-Version", "2022-11-28")
                    .header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(body.toString()))
                    .build();

            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());

            if (res.statusCode() == 200 || res.statusCode() == 201) {
                JsonNode json = mapper.readTree(res.body());
                currentSha = json.path("content").path("sha").asText(currentSha);
                log.info("History saved to GitHub ({} slugs, sha={})", posted.size(), currentSha);
            } else if (res.statusCode() == 403 || res.statusCode() == 401) {
                log.warn("GitHub token has no WRITE access (status={}). History saved locally only. " +
                         "Fix: GitHub → token → Repository permissions → Contents → Read and Write",
                         res.statusCode());
            } else {
                log.warn("Failed to save history to GitHub: status={}, body={}",
                        res.statusCode(), res.body());
            }
        } catch (Exception e) {
            log.warn("Could not save history to GitHub: {}", e.getMessage());
        }
    }
}


