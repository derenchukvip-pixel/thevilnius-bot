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
import java.util.List;

@Slf4j
@Service
public class ScraperService {

    private static final String SITE_BASE = "https://thevilnius.lt";

    @Value("${supabase.url}")
    private String supabaseUrl;

    @Value("${supabase.anon-key}")
    private String supabaseAnonKey;

    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();
    private final ObjectMapper objectMapper = new ObjectMapper();
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
        String url = supabaseUrl + "/rest/v1/articles"
                + "?order=published_at.desc&limit=" + limit
                + "&status=eq.published&category=eq.news"
                + "&select=title,slug,image_url,content";

        log.info("Fetching articles from Supabase: {}", url);

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(30))
                .header("apikey", supabaseAnonKey)
                .header("Authorization", "Bearer " + supabaseAnonKey)
                .GET()
                .build();

        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        log.info("Supabase response status: {}", response.statusCode());

        JsonNode root = objectMapper.readTree(response.body());
        if (!root.isArray() || root.isEmpty()) {
            log.warn("Supabase returned no news articles");
            return List.of();
        }

        List<ArticleInfo> results = new ArrayList<>();
        for (JsonNode node : root) {
            ArticleInfo info = new ArticleInfo();
            info.setTitle(node.path("title").asText(null));
            info.setLink(SITE_BASE + "/articles/" + node.path("slug").asText());
            info.setImageUrl(node.path("image_url").asText(null));
            info.setContent(node.path("content").asText(null));
            log.info("Scraped news: title='{}', link='{}'", info.getTitle(), info.getLink());
            results.add(info);
        }
        return results;
    }
}
