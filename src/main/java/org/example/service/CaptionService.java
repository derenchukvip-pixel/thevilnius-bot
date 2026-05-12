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

@Slf4j
@Service
public class CaptionService {

    private static final String API_URL_TEMPLATE =
            "https://generativelanguage.googleapis.com/v1/models/gemini-1.5-flash:generateContent?key=%s";

    private static final String CAPTION_SYSTEM = """
            Ты — редактор Instagram-аккаунта «The Vilnius» — русскоязычное городское медиа о Вильнюсе.
            Переформатируй текст новости в Instagram-подпись в профессиональном стиле «The Warsaw».

            Стиль:
            — В начале каждого абзаца ставь 1–2 релевантных эмодзи
            — Тон живой, вовлекающий, профессиональный
            — Между абзацами — пустая строка
            — Используй ПОЛНЫЙ текст новости — без сокращений, без ограничений по длине
            — Сохраняй все ключевые факты, цифры, имена и цитаты из исходника
            — Логичная структура: лид → детали → контекст/последствия

            Строго запрещено:
            — Хэштеги, ссылки, URL
            — Пометки «источник», «читать далее», подписи редакции
            — Любые пояснения от себя — только готовый caption
            """;

    private static final String KEY_PHRASE_SYSTEM = """
            Ты анализируешь русскоязычные заголовки новостей и выделяешь САМУЮ важную, шокирующую
            или интригующую часть, которую нужно подсветить жёлтым маркером в обложке.

            Правила:
            — Верни ТОЛЬКО подстроку из заголовка — без кавычек, без пояснений, без префиксов
            — Подстрока должна точно (буква в букву, регистр и пунктуация) встречаться в заголовке
            — От 1 до 4 идущих подряд слов, без обрывов
            — Выбирай конкретику (имя, цифру, событие, факт), а не служебные слова
              ("в Вильнюсе", "сегодня", "стало известно")
            — Если не уверен — выбери последние 2–3 содержательных слова заголовка

            Примеры:
            Заголовок: Волны краж в Вильнюсе: угнан BMW
            Ответ: угнан BMW

            Заголовок: Цены на жильё снова выросли
            Ответ: снова выросли

            Заголовок: Мэр объявил о массовом ремонте дорог
            Ответ: массовом ремонте дорог
            """;

    @Value("${gemini.api-key:}")
    private String apiKey;

    private final HttpClient    http   = HttpClient.newHttpClient();
    private final ObjectMapper  mapper = new ObjectMapper();

    @PostConstruct
    public void validateApiKey() {
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("⚠️  gemini.api-key is not set — caption/key-phrase generation will be skipped. " +
                     "Set the GEMINI_API_KEY environment variable to enable AI features.");
        } else {
            log.info("Gemini API configured (gemini-1.5-flash)");
        }
    }

    public String formatCaption(String rawContent) {
        if (rawContent == null || rawContent.isBlank()) return "";
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("Gemini API key not set — returning raw content as caption");
            return rawContent;
        }
        try {
            String result = callGemini(CAPTION_SYSTEM, rawContent, 4096);
            log.info("Caption formatted ({} chars)", result.length());
            return result;
        } catch (Exception e) {
            log.error("Caption Gemini call failed — falling back to raw content", e);
            return rawContent;
        }
    }

    public String findKeyPhrase(String title) {
        if (title == null || title.isBlank()) return null;
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("Gemini API key not set — skipping key-phrase detection");
            return null;
        }
        try {
            String raw    = callGemini(KEY_PHRASE_SYSTEM, title, 64).strip();
            String phrase = raw.replaceAll("^[\"'«»\\s]+", "").replaceAll("[\"'«»\\s]+$", "");
            if (phrase.isBlank() || !title.contains(phrase)) {
                log.warn("Key phrase '{}' not a substring of title '{}' — using fallback", phrase, title);
                return null;
            }
            log.info("Key phrase: '{}'", phrase);
            return phrase;
        } catch (Exception e) {
            log.error("Key phrase Gemini call failed — falling back to last words", e);
            return null;
        }
    }

    /**
     * Calls the Gemini REST API (no SDK needed — plain HTTP).
     * Endpoint: POST https://generativelanguage.googleapis.com/v1beta/models/gemini-1.5-flash:generateContent?key=...
     */
    private String callGemini(String systemPrompt, String userContent, int maxOutputTokens) throws Exception {
        // Combine system prompt + user content into a single user message (v1 doesn't support systemInstruction)
        String combinedText = systemPrompt.strip() + "\n\n" + userContent;

        String body = mapper.writeValueAsString(java.util.Map.of(
                "contents", java.util.List.of(java.util.Map.of(
                        "role", "user",
                        "parts", java.util.List.of(java.util.Map.of("text", combinedText))
                )),
                "generationConfig", java.util.Map.of(
                        "maxOutputTokens", maxOutputTokens,
                        "temperature", 0.7
                )
        ));

        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(API_URL_TEMPLATE.formatted(apiKey)))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body))
                .build();

        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        JsonNode json = mapper.readTree(response.body());

        // Surface API errors clearly
        if (json.has("error")) {
            String errorMsg = json.path("error").path("message").asText(response.body());
            throw new RuntimeException("Gemini API error (%d): %s".formatted(response.statusCode(), errorMsg));
        }

        return json.path("candidates").get(0)
                   .path("content").path("parts").get(0)
                   .path("text").asText();
    }
}
