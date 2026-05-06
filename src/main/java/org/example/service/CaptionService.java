package org.example.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class CaptionService {

    private static final String API_URL = "https://api.anthropic.com/v1/messages";
    private static final String MODEL   = "claude-haiku-4-5-20251001";

    private static final String SYSTEM = """
            Ты — редактор Instagram-аккаунта «The Vilnius» — русскоязычный городской медиа о Вильнюсе.
            Переформатируй текст новости в Instagram-подпись в стиле «The Warsaw».

            Стиль:
            — В начале каждого абзаца ставь 1–2 релевантных эмодзи (🚨 ⚡️ 💬 🏛 💰 👮‍♂️ ⚖️ 🏙 📰 🔍 и другие по смыслу)
            — Внутри текста добавляй эмодзи там, где они усиливают смысл
            — Цитаты оформляй в «кавычках»
            — Тон живой, вовлекающий, как будто рассказываешь другу новость
            — Между абзацами — пустая строка (\\n\\n)
            — Объём: не более 2000 символов

            Строго запрещено:
            — Хэштеги (#...)
            — Ссылки и URL
            — Пометки «источник», «читать далее», «подробнее»
            — Любые пояснения от себя — только готовый caption
            """;

    @Value("${anthropic.api-key}")
    private String apiKey;

    private final HttpClient http    = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    public String formatCaption(String rawContent) {
        if (rawContent == null || rawContent.isBlank()) return "";
        try {
            String result = callClaude(rawContent);
            log.info("Caption formatted ({} chars)", result.length());
            return result;
        } catch (Exception e) {
            log.error("Caption LLM call failed — falling back to raw content", e);
            return rawContent.length() > 2200 ? rawContent.substring(0, 2200) + "…" : rawContent;
        }
    }

    private String callClaude(String content) throws Exception {
        // System prompt wrapped in array with cache_control for prompt caching
        String systemJson = mapper.writeValueAsString(List.of(
                Map.of("type", "text",
                       "text", SYSTEM,
                       "cache_control", Map.of("type", "ephemeral"))
        ));
        String userJson = mapper.writeValueAsString(content);

        String body = """
                {"model":"%s","max_tokens":1500,"system":%s,"messages":[{"role":"user","content":%s}]}
                """.formatted(MODEL, systemJson, userJson).strip();

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(API_URL))
                .header("x-api-key", apiKey)
                .header("anthropic-version", "2023-06-01")
                .header("anthropic-beta", "prompt-caching-2024-07-31")
                .header("content-type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        JsonNode json = mapper.readTree(response.body());

        if (json.has("error")) {
            throw new RuntimeException("Claude API error: " + response.body());
        }
        return json.path("content").get(0).path("text").asText();
    }
}
