package kr.dogfoot.hwpxlib.tool.idmlconverter.llm;

import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/**
 * GROQ / Anthropic LLM HTTP 클라이언트.
 *
 * 우선순위: GROQ → Anthropic. 외부 의존성 없음 (HttpURLConnection).
 * API 키는 로그에 절대 출력하지 않는다.
 */
public class GroqClient {

    private static final String GROQ_URL  = "https://api.groq.com/openai/v1/chat/completions";
    private static final String CLAUDE_URL = "https://api.anthropic.com/v1/messages";

    private final LLMConfig config;

    public GroqClient(LLMConfig config) {
        this.config = config;
    }

    /**
     * JSON 응답 텍스트 반환. 실패 시 LLMException.
     *
     * @param systemPrompt  시스템 프롬프트
     * @param userContent   유저 메시지
     * @return LLM이 반환한 JSON 문자열 (content 필드)
     */
    public String complete(String systemPrompt, String userContent) throws LLMException {
        if (config.hasGroq()) {
            return callGroq(systemPrompt, userContent);
        } else if (config.hasAnthropic()) {
            return callAnthropic(systemPrompt, userContent);
        } else {
            throw new LLMException("API 키 없음. .env에 GROQ_API_KEY 또는 ANTHROPIC_API_KEY를 설정하세요.");
        }
    }

    /** ping 테스트: 짧은 "hello" 호출로 API 연결 확인 */
    public String ping() throws LLMException {
        return complete("Respond with exactly: {\"ok\":true}", "ping");
    }

    // -------------------------------------------------------
    // GROQ (OpenAI 호환 엔드포인트)
    // -------------------------------------------------------

    private String callGroq(String systemPrompt, String userContent) throws LLMException {
        String body = buildOpenAIBody(LLMConfig.MODEL_GROQ_LLAMA, systemPrompt, userContent);
        String raw = post(GROQ_URL, body,
                "Authorization", "Bearer " + config.groqApiKey(),
                "Content-Type", "application/json");
        return extractOpenAIContent(raw);
    }

    private String buildOpenAIBody(String model, String system, String user) {
        return "{"
                + "\"model\":" + jsonStr(model) + ","
                + "\"temperature\":0.3,"
                + "\"response_format\":{\"type\":\"json_object\"},"
                + "\"messages\":["
                +   "{\"role\":\"system\",\"content\":" + jsonStr(system) + "},"
                +   "{\"role\":\"user\",\"content\":" + jsonStr(user) + "}"
                + "]}";
    }

    private String extractOpenAIContent(String raw) throws LLMException {
        // "content":"..." 필드 추출 (Gson 없이 간단 파싱)
        int idx = raw.indexOf("\"content\":");
        if (idx < 0) throw new LLMException("GROQ 응답에 content 필드 없음: " + safePreview(raw));
        int start = raw.indexOf('"', idx + 10);
        if (start < 0) throw new LLMException("GROQ content 파싱 실패");
        // JSON 문자열 파싱 (이스케이프 처리)
        StringBuilder sb = new StringBuilder();
        int i = start + 1;
        while (i < raw.length()) {
            char c = raw.charAt(i);
            if (c == '\\' && i + 1 < raw.length()) {
                char next = raw.charAt(i + 1);
                switch (next) {
                    case '"':  sb.append('"');  i += 2; continue;
                    case '\\': sb.append('\\'); i += 2; continue;
                    case 'n':  sb.append('\n'); i += 2; continue;
                    case 'r':  sb.append('\r'); i += 2; continue;
                    case 't':  sb.append('\t'); i += 2; continue;
                    default:   sb.append(next); i += 2; continue;
                }
            }
            if (c == '"') break;
            sb.append(c);
            i++;
        }
        return sb.toString();
    }

    // -------------------------------------------------------
    // Anthropic
    // -------------------------------------------------------

    private String callAnthropic(String systemPrompt, String userContent) throws LLMException {
        String body = "{"
                + "\"model\":" + jsonStr(LLMConfig.MODEL_CLAUDE_HAIKU) + ","
                + "\"max_tokens\":4096,"
                + "\"system\":" + jsonStr(systemPrompt) + ","
                + "\"messages\":[{\"role\":\"user\",\"content\":" + jsonStr(userContent) + "}]"
                + "}";
        String raw = post(CLAUDE_URL, body,
                "x-api-key", config.anthropicApiKey(),
                "anthropic-version", "2023-06-01",
                "Content-Type", "application/json");
        return extractAnthropicContent(raw);
    }

    private String extractAnthropicContent(String raw) throws LLMException {
        // {"content":[{"type":"text","text":"..."}],...}
        int idx = raw.indexOf("\"text\":");
        if (idx < 0) throw new LLMException("Anthropic 응답에 text 필드 없음: " + safePreview(raw));
        int start = raw.indexOf('"', idx + 7);
        if (start < 0) throw new LLMException("Anthropic text 파싱 실패");
        StringBuilder sb = new StringBuilder();
        int i = start + 1;
        while (i < raw.length()) {
            char c = raw.charAt(i);
            if (c == '\\' && i + 1 < raw.length()) {
                char next = raw.charAt(i + 1);
                switch (next) {
                    case '"':  sb.append('"');  i += 2; continue;
                    case '\\': sb.append('\\'); i += 2; continue;
                    case 'n':  sb.append('\n'); i += 2; continue;
                    case 'r':  sb.append('\r'); i += 2; continue;
                    case 't':  sb.append('\t'); i += 2; continue;
                    default:   sb.append(next); i += 2; continue;
                }
            }
            if (c == '"') break;
            sb.append(c);
            i++;
        }
        return sb.toString();
    }

    // -------------------------------------------------------
    // HTTP
    // -------------------------------------------------------

    /** 가변 header 쌍(key, value, key, value, ...) POST */
    private String post(String urlStr, String body, String... headers) throws LLMException {
        try {
            URL url = new URL(urlStr);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(config.connectTimeoutMs());
            conn.setReadTimeout(config.readTimeoutMs());
            conn.setDoOutput(true);
            for (int i = 0; i + 1 < headers.length; i += 2) {
                conn.setRequestProperty(headers[i], headers[i + 1]);
            }
            byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
            conn.setRequestProperty("Content-Length", String.valueOf(bodyBytes.length));
            try (OutputStream os = conn.getOutputStream()) {
                os.write(bodyBytes);
            }
            int status = conn.getResponseCode();
            InputStream is = status >= 400 ? conn.getErrorStream() : conn.getInputStream();
            String response = readAll(is);
            if (status >= 400) {
                throw new LLMException("HTTP " + status + " from " + urlStr + ": " + safePreview(response));
            }
            return response;
        } catch (LLMException e) {
            throw e;
        } catch (Exception e) {
            throw new LLMException("HTTP 요청 실패 (" + urlStr + "): " + e.getMessage(), e);
        }
    }

    private static String readAll(InputStream is) throws IOException {
        if (is == null) return "";
        try (BufferedReader r = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
            StringBuilder sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) sb.append(line).append('\n');
            return sb.toString();
        }
    }

    // -------------------------------------------------------
    // 유틸
    // -------------------------------------------------------

    /** JSON 문자열 이스케이프 + 쌍따옴표 감싸기 */
    static String jsonStr(String s) {
        if (s == null) return "null";
        StringBuilder sb = new StringBuilder(s.length() + 10);
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n");  break;
                case '\r': sb.append("\\r");  break;
                case '\t': sb.append("\\t");  break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
        return sb.toString();
    }

    /** 로그용 미리보기 (앞 200자, API 키 포함 금지) */
    private static String safePreview(String s) {
        if (s == null) return "(null)";
        String p = s.length() > 200 ? s.substring(0, 200) + "..." : s;
        return p.replaceAll("(sk-|gsk_)[A-Za-z0-9]{8}[A-Za-z0-9]*", "[MASKED_KEY]");
    }
}
