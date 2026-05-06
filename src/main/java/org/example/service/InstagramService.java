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
        String imageUrl = publicUrl.stripTrailing() + "/images/" + ImageService.OUTPUT_FILENAME;
        log.info("Posting image from URL: {}", imageUrl);

        String creationId = createMediaContainer(imageUrl, caption);
        log.info("Media container created: {}", creationId);

        awaitContainerReady(creationId);
        publishMedia(creationId);
        log.info("Published successfully");
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

    private void awaitContainerReady(String creationId) throws Exception {
        String url = GRAPH_API + "/" + creationId
                + "?fields=status_code&access_token=" + encode(accessToken);
        HttpRequest request = HttpRequest.newBuilder().uri(URI.create(url)).GET().build();

        int maxAttempts = 20;
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
