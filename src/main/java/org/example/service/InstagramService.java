package org.example.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

@Slf4j
@Service
public class InstagramService {

    private static final String GRAPH_API = "https://graph.facebook.com/v19.0";

    @Value("${instagram.user.id}")
    private String userId;

    @Value("${instagram.access.token}")
    private String accessToken;

    @Value("${app.public-url}")
    private String publicUrl;

    private final HttpClient http = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    public void postImage(String caption) throws Exception {
        String feedImageUrl = publicUrl.stripTrailing() + "/images/" + ImageService.OUTPUT_FILENAME;
        log.info("Posting image from URL: {}", feedImageUrl);

        String creationId = createMediaContainer(feedImageUrl, caption);
        log.info("Media container created: {}", creationId);

        awaitContainerReady(creationId, 20); // 20 attempts × 3s = 60s max for feed
        publishMedia(creationId);
        log.info("Published to feed successfully");

        // Story upload with dedicated 9:16 image — failure is non-fatal
        try {
            String storyImageUrl = publicUrl.stripTrailing() + "/images/" + ImageService.STORY_FILENAME;
            postStory(storyImageUrl);
        } catch (Exception e) {
            log.error("Story upload failed (non-fatal): {}", e.getMessage());
        }
    }

    public void postStoryOnly() throws Exception {
        String storyImageUrl = publicUrl.stripTrailing() + "/images/" + ImageService.STORY_FILENAME;
        log.info("Posting story-only from URL: {}", storyImageUrl);
        postStory(storyImageUrl);
        log.info("Story-only post done");
    }

    // Attempts to update the IG Business Profile "website" field via Graph API.
    // Not documented as writable on FB-Page-linked accounts, so this is best-effort:
    // we log Meta's full response and never throw. Caller decides whether to retry/fallback.
    public void updateProfileWebsite(String articleUrl) {
        if (articleUrl == null || articleUrl.isBlank()) {
            log.warn("Profile website update skipped — empty URL");
            return;
        }
        try {
            String body = "website=" + encode(articleUrl)
                    + "&access_token=" + encode(accessToken);
            HttpResponse<String> response = post(GRAPH_API + "/" + userId, body);
            int status = response.statusCode();
            String snippet = response.body() == null ? "" : response.body();
            if (status >= 200 && status < 300 && !snippet.contains("\"error\"")) {
                log.info("Profile website updated to {} (status={}, body={})", articleUrl, status, snippet);
            } else {
                log.warn("Profile website update rejected by Meta (status={}, body={}). " +
                        "This endpoint is not generally writable on FB-Page-linked tokens — " +
                        "consider the /latest redirect fallback.", status, snippet);
            }
        } catch (Exception e) {
            log.warn("Profile website update failed (non-fatal): {}", e.getMessage());
        }
    }

    private void postStory(String imageUrl) throws Exception {
        log.info("Posting story...");
        String body = "image_url=" + encode(imageUrl)
                + "&media_type=STORIES"
                + "&access_token=" + encode(accessToken);

        HttpResponse<String> response = post(GRAPH_API + "/" + userId + "/media", body);
        log.debug("Create story container response: {}", response.body());

        JsonNode json = mapper.readTree(response.body());
        requireField(json, "id", response.body());
        String storyContainerId = json.get("id").asText();

        awaitContainerReady(storyContainerId, 10); // 10 attempts × 3s = 30s max for story

        String publishBody = "creation_id=" + encode(storyContainerId)
                + "&access_token=" + encode(accessToken);
        HttpResponse<String> publishResponse = post(GRAPH_API + "/" + userId + "/media_publish", publishBody);
        JsonNode publishJson = mapper.readTree(publishResponse.body());
        requireField(publishJson, "id", publishResponse.body());
        log.info("Story published successfully");
    }

    private String createMediaContainer(String imageUrl, String caption) throws Exception {
        String body = "image_url=" + encode(imageUrl)
                + "&caption=" + encode(caption)
                + "&access_token=" + encode(accessToken);

        HttpResponse<String> response = post(GRAPH_API + "/" + userId + "/media", body);
        log.debug("Create media response: {}", response.body());

        JsonNode json = mapper.readTree(response.body());
        requireField(json, "id", response.body());
        return json.get("id").asText();
    }

    private void awaitContainerReady(String creationId, int maxAttempts) throws Exception {
        String url = GRAPH_API + "/" + creationId
                + "?fields=status_code&access_token=" + encode(accessToken);
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).GET().build();

        for (int i = 1; i <= maxAttempts; i++) {
            Thread.sleep(3_000);
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            JsonNode json = mapper.readTree(response.body());
            String statusCode = json.path("status_code").asText("");
            log.info("Container status [{}/{}]: {}", i, maxAttempts, statusCode);

            switch (statusCode) {
                case "FINISHED" -> { return; }
                case "ERROR", "EXPIRED" ->
                    throw new RuntimeException("Container not publishable, status=" + statusCode
                            + " body=" + response.body());
                // IN_PROGRESS or empty → keep polling
            }
        }
        throw new RuntimeException("Container still not ready after " + maxAttempts + " attempts");
    }

    private void publishMedia(String creationId) throws Exception {
        String body = "creation_id=" + encode(creationId)
                + "&access_token=" + encode(accessToken);

        HttpResponse<String> response = post(GRAPH_API + "/" + userId + "/media_publish", body);
        log.debug("Publish response: {}", response.body());

        JsonNode json = mapper.readTree(response.body());
        requireField(json, "id", response.body());
    }

    private HttpResponse<String> post(String url, String formBody) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(formBody))
                .build();
        return http.send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static void requireField(JsonNode json, String field, String rawBody) {
        if (!json.has(field)) {
            throw new RuntimeException("Missing '" + field + "' in response: " + rawBody);
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
