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

    @Value("${anthropic.api-key}")
    private String apiKey;

    private final HttpClient http    = HttpClient.newHttpClient();
    private final ObjectMapper mapper = new ObjectMapper();

    public String formatCaption(String rawContent) {
        if (rawContent == null || rawContent.isBlank()) return "";
        try {
            String result = callClaude(CAPTION_SYSTEM, rawContent, 4096);
            log.info("Caption formatted ({} chars)", result.length());
            return result;
        } catch (Exception e) {
            log.error("Caption LLM call failed — falling back to raw content", e);
            return rawContent;
        }
    }

    public String findKeyPhrase(String title) {
        if (title == null || title.isBlank()) return null;
        try {
            String raw = callClaude(KEY_PHRASE_SYSTEM, title, 64).strip();
            String phrase = raw.replaceAll("^[\"'«»\\s]+", "").replaceAll("[\"'«»\\s]+$", "");
            if (phrase.isBlank() || !title.contains(phrase)) {
                log.warn("Key phrase '{}' not a substring of title '{}' — using fallback", phrase, title);
                return null;
            }
            log.info("Key phrase: '{}'", phrase);
            return phrase;
        } catch (Exception e) {
            log.error("Key phrase LLM call failed — falling back to last words", e);
            return null;
        }
    }

    private String callClaude(String system, String userContent, int maxTokens) throws Exception {
        String systemJson = mapper.writeValueAsString(List.of(
                Map.of("type", "text",
                       "text", system,
                       "cache_control", Map.of("type", "ephemeral"))
        ));
        String userJson = mapper.writeValueAsString(userContent);

        String body = """
                {"model":"%s","max_tokens":%d,"system":%s,"messages":[{"role":"user","content":%s}]}
                """.formatted(MODEL, maxTokens, systemJson, userJson).strip();

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
