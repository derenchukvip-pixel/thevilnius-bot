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

    /** Instagram hard limit for caption length. */
    private static final int INSTAGRAM_MAX_CAPTION = 2200;

    private static final String API_URL_TEMPLATE =
            "https://generativelanguage.googleapis.com/v1/models/gemini-2.5-flash-lite:generateContent?key=%s";

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
            Ты анализируешь русскоязычные заголовки новостей и выделяешь САМУЮ шокирующую,
            сенсационную или неожиданную часть — то, от чего читатель остановится при скролле.

            ГЛАВНОЕ ПРАВИЛО: выбирай СУЩЕСТВО события — конкретный предмет, явление, лицо,
            которые делают новость сенсационной. НЕ выбирай место ("в Вильнюсе", "на территории")
            и не выбирай действие ("обсуждает", "рассматривает"), если можно выделить ЧТО именно.

            Иерархия выбора (от лучшего к худшему):
            1. Оружие, угроза, катастрофа, смерть → ВСЕГДА приоритет ("ядерное оружие", "взрыв бомбы")
            2. Конкретное имя известной персоны или организации
            3. Крупная сумма денег или цифра
            4. Неожиданное действие или событие
            5. Прилагательное + существо если вместе дают удар ("массовый арест")

            Технические правила:
            — Верни ТОЛЬКО подстроку из заголовка — без кавычек, без пояснений
            — Подстрока должна точно (буква в букву, включая регистр) встречаться в заголовке
            — От 1 до 4 идущих подряд слов, без обрывов на середине
            — Никогда не выбирай: "в Вильнюсе", "сегодня", "стало известно", "на своей территории",
              "обсуждает", "рассматривает" и другие локативы/глаголы без существа

            Примеры:
            Заголовок: Литва обсуждает размещение ядерного оружия на своей территории
            Ответ: ядерного оружия

            Заголовок: Волны краж в Вильнюсе: угнан BMW
            Ответ: угнан BMW

            Заголовок: Мэр Вильнюса потратил 2 млн евро на ремонт дорог
            Ответ: 2 млн евро

            Заголовок: В Литве арестован лидер преступной группировки
            Ответ: лидер преступной группировки

            Заголовок: Цены на жильё выросли на 30%
            Ответ: на 30%
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
            log.info("Gemini API configured (gemini-2.5-flash-lite)");
        }
    }

    public String formatCaption(String rawContent) {
        if (rawContent == null || rawContent.isBlank()) return "";
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("Gemini API key not set — returning raw content as caption");
            return truncate(rawContent);
        }
        try {
            String result = callGemini(CAPTION_SYSTEM, rawContent, 4096);
            result = truncate(result);
            log.info("Caption formatted ({} chars)", result.length());
            return result;
        } catch (Exception e) {
            log.error("Caption Gemini call failed — falling back to raw content", e);
            return truncate(rawContent);
        }
    }

    /** Hard-trims caption to Instagram's 2200-char limit, cutting at the last newline before the limit. */
    private static String truncate(String text) {
        if (text == null || text.length() <= INSTAGRAM_MAX_CAPTION) return text;
        String cut = text.substring(0, INSTAGRAM_MAX_CAPTION);
        int lastNl = cut.lastIndexOf('\n');
        return (lastNl > INSTAGRAM_MAX_CAPTION / 2 ? cut.substring(0, lastNl) : cut).stripTrailing();
    }

    public String findKeyPhrase(String title) {
        if (title == null || title.isBlank()) return null;
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("Gemini API key not set — skipping key-phrase detection");
            return null;
        }
        try {
            String raw     = callGemini(KEY_PHRASE_SYSTEM, title, 64).strip();
            String phrase  = cleanPhrase(raw);
            String matched = matchInTitle(title, phrase);
            if (matched == null) {
                log.warn("Key phrase '{}' not found in title '{}' — using fallback", phrase, title);
                return null;
            }
            log.info("Key phrase: '{}'", matched);
            return matched;
        } catch (Exception e) {
            log.error("Key phrase Gemini call failed — falling back to last words", e);
            return null;
        }
    }

    /** Strips surrounding quotes, guillemets and stray punctuation/whitespace from the LLM reply. */
    private static String cleanPhrase(String raw) {
        if (raw == null) return "";
        return raw.replaceAll("^[\"'«»“”\\s.,:;—–-]+", "")
                  .replaceAll("[\"'«»“”\\s.,:;—–-]+$", "")
                  .strip();
    }

    /**
     * Locates {@code phrase} inside {@code title} and returns the substring exactly as it
     * appears in the title (preserving the title's original casing). Falls back to a
     * case-insensitive search so a capitalization difference from the LLM doesn't break the
     * highlight. Returns {@code null} when there is no match.
     */
    private static String matchInTitle(String title, String phrase) {
        if (phrase == null || phrase.isBlank()) return null;
        int idx = title.indexOf(phrase);
        if (idx >= 0) return title.substring(idx, idx + phrase.length());
        idx = title.toLowerCase().indexOf(phrase.toLowerCase());
        if (idx >= 0) return title.substring(idx, idx + phrase.length());
        return null;
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
