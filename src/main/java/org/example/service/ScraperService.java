package org.example.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.example.model.ArticleInfo;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

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

    private final RestTemplate restTemplate = new RestTemplate();
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

        HttpHeaders headers = new HttpHeaders();
        headers.set("apikey", supabaseAnonKey);
        headers.set("Authorization", "Bearer " + supabaseAnonKey);

        ResponseEntity<String> response = restTemplate.exchange(
                url, HttpMethod.GET, new HttpEntity<>(headers), String.class);

        JsonNode root = objectMapper.readTree(response.getBody());
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
