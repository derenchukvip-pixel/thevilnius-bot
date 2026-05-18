package org.example.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;

/**
 * Persists the set of already-posted article slugs in Supabase Storage.
 * No SQL / schema changes required — uses the Storage REST API with the service-role key.
 *
 * Bucket: "instagram-bot"
 * File:   "posted_slugs.json"  ← JSON array of slug strings
 */
@Slf4j
@Service
public class HistoryService {

    @Value("${supabase.url}")
    private String supabaseUrl;

    @Value("${supabase.service-key}")
    private String serviceKey;

    private static final String BUCKET    = "instagram-bot";
    private static final String FILE_PATH = "posted_slugs.json";

    private final HttpClient   http   = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(15))
            .build();
    private final ObjectMapper mapper = new ObjectMapper();

    /** In-memory cache — loaded from Supabase Storage on startup. */
    private final Set<String> posted = Collections.synchronizedSet(new HashSet<>());

    @PostConstruct
    public void init() {
        ensureBucketExists();
        loadFromStorage();
    }

    /** Returns true if this slug was already published to Instagram. */
    public boolean isPosted(String slug) {
        return slug != null && posted.contains(slug);
    }

    /** Records the slug as posted in memory AND persists to Supabase Storage. */
    public void markAsPosted(String slug) {
        if (slug == null || slug.isBlank()) return;
        posted.add(slug);
        saveToStorage();
        log.info("Marked as posted: slug='{}'  (total history: {})", slug, posted.size());
    }

    // ────────────────────────────────────────────────────────────────────────────
    // Private helpers
    // ────────────────────────────────────────────────────────────────────────────

    private void ensureBucketExists() {
        try {
            String url  = supabaseUrl + "/storage/v1/bucket";
            String body = "{\"id\":\"" + BUCKET + "\",\"name\":\"" + BUCKET + "\",\"public\":false}";

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .header("apikey", serviceKey)
                    .header("Authorization", "Bearer " + serviceKey)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() == 200 || res.statusCode() == 201) {
                log.info("Supabase Storage bucket '{}' created", BUCKET);
            } else {
                // 409 = already exists — that's fine
                log.debug("Bucket '{}' check: status={}", BUCKET, res.statusCode());
            }
        } catch (Exception e) {
            log.warn("Could not ensure storage bucket '{}' exists: {}", BUCKET, e.getMessage());
        }
    }

    private void loadFromStorage() {
        try {
            String url = supabaseUrl + "/storage/v1/object/" + BUCKET + "/" + FILE_PATH;

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .header("apikey", serviceKey)
                    .header("Authorization", "Bearer " + serviceKey)
                    .GET()
                    .build();

            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());

            if (res.statusCode() == 200) {
                String[] slugs = mapper.readValue(res.body(), String[].class);
                for (String s : slugs) posted.add(s);
                log.info("Loaded {} posted slugs from Supabase Storage", posted.size());
            } else if (res.statusCode() == 400 || res.statusCode() == 404) {
                log.info("No history file yet in Supabase Storage — starting fresh");
            } else {
                log.warn("Unexpected status loading history: {} — {}", res.statusCode(), res.body());
            }
        } catch (Exception e) {
            log.warn("Could not load history from Supabase Storage: {}", e.getMessage());
        }
    }

    private void saveToStorage() {
        try {
            String url  = supabaseUrl + "/storage/v1/object/" + BUCKET + "/" + FILE_PATH;
            String body = mapper.writeValueAsString(posted);

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .header("apikey", serviceKey)
                    .header("Authorization", "Bearer " + serviceKey)
                    .header("Content-Type", "application/json")
                    .header("x-upsert", "true")   // create-or-replace
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            HttpResponse<String> res = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (res.statusCode() >= 200 && res.statusCode() < 300) {
                log.info("History saved to Supabase Storage ({} slugs total)", posted.size());
            } else {
                log.warn("Failed to save history to Storage: status={}, body={}", res.statusCode(), res.body());
            }
        } catch (Exception e) {
            log.warn("Could not save history to Supabase Storage: {}", e.getMessage());
        }
    }
}

