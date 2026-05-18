package org.example.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.example.model.ArticleInfo;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@Slf4j
@Service
public class ScraperService {

    private static final String SITE_BASE = "https://thevilnius.lt";

    /** In-memory fallback dedup set — used when posted_at column doesn't exist yet in Supabase. */
    private final Set<String> postedSlugsInMemory = Collections.synchronizedSet(new HashSet<>());

    @Value("${supabase.url}")
    private String supabaseUrl;

    @Value("${supabase.anon-key}")
    private String supabaseAnonKey;

    @Value("${supabase.service-key}")
    private String supabaseServiceKey;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    public ArticleInfo scrapeLatestArticle() throws Exception {
        List<ArticleInfo> articles = scrapeArticles(1);
        return articles.isEmpty() ? new ArticleInfo() : articles.get(0);
    }

    /**
     * Fetches the {@code limit} most-recently-published news articles from Supabase.
     * Returns an ordered list (newest first).
     */
    public List<ArticleInfo> scrapeArticles(int limit) throws Exception {
        // First try with posted_at=is.null filter (requires column to exist in Supabase)
        String urlWithFilter = supabaseUrl + "/rest/v1/articles"
                + "?order=published_at.desc&limit=" + limit
                + "&status=eq.published&category=eq.news"
                + "&posted_at=is.null"
                + "&select=title,slug,image_url,content";

        log.info("Fetching articles from Supabase (with posted_at filter): {}", urlWithFilter);
        HttpResponse<String> response = doGet(urlWithFilter, supabaseAnonKey);
        log.info("Supabase response status: {}", response.statusCode());

        boolean filterWorked = response.statusCode() == 200;
        if (!filterWorked) {
            // Column doesn't exist yet — fall back to query without filter and use in-memory dedup
            log.warn("posted_at filter failed (status={}), falling back to in-memory dedup. " +
                     "Run SQL: ALTER TABLE articles ADD COLUMN IF NOT EXISTS posted_at timestamptz;",
                     response.statusCode());
            String urlNoFilter = supabaseUrl + "/rest/v1/articles"
                    + "?order=published_at.desc&limit=" + limit
                    + "&status=eq.published&category=eq.news"
                    + "&select=title,slug,image_url,content";
            response = doGet(urlNoFilter, supabaseAnonKey);
            log.info("Fallback Supabase response status: {}", response.statusCode());
        }

        JsonNode root = objectMapper.readTree(response.body());
        if (!root.isArray() || root.isEmpty()) {
            log.warn("Supabase returned no news articles");
            return List.of();
        }

        List<ArticleInfo> results = new ArrayList<>();
        for (JsonNode node : root) {
            String slug = node.path("slug").asText(null);
            // When posted_at filter didn't work, use in-memory dedup as fallback
            if (!filterWorked && postedSlugsInMemory.contains(slug)) {
                log.info("Skipping already-posted (in-memory): slug='{}'", slug);
                continue;
            }
            ArticleInfo info = new ArticleInfo();
            info.setTitle(node.path("title").asText(null));
            info.setSlug(slug);
            info.setLink(SITE_BASE + "/articles/" + slug);
            info.setImageUrl(node.path("image_url").asText(null));
            info.setContent(node.path("content").asText(null));
            log.info("Scraped news: title='{}', link='{}'", info.getTitle(), info.getLink());
            results.add(info);
        }
        return results;
    }

    private HttpResponse<String> doGet(String url, String apiKey) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("apikey", apiKey)
                .header("Authorization", "Bearer " + apiKey)
                .GET()
                .build();
        return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
    }

    /**
     * Sets posted_at = NOW() for the given slug so it won't be fetched again.
     */
    public void markAsPosted(String slug) throws Exception {
        if (slug == null || slug.isBlank()) {
            log.warn("markAsPosted called with blank slug — skipping");
            return;
        }
        // Always update in-memory set (works even without DB column)
        postedSlugsInMemory.add(slug);

        String url = supabaseUrl + "/rest/v1/articles?slug=eq." + slug;
        String body = "{\"posted_at\":\"" + java.time.Instant.now() + "\"}";

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(15))
                .header("apikey", supabaseServiceKey)
                .header("Authorization", "Bearer " + supabaseServiceKey)
                .header("Content-Type", "application/json")
                .header("Prefer", "return=minimal")
                .method("PATCH", HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 200 && response.statusCode() < 300) {
            log.info("Marked as posted in Supabase: slug='{}'", slug);
        } else {
            log.warn("Could not mark as posted in Supabase (status={}) — in-memory dedup active. " +
                     "Add column: ALTER TABLE articles ADD COLUMN IF NOT EXISTS posted_at timestamptz;",
                     response.statusCode());
        }
    }
}
