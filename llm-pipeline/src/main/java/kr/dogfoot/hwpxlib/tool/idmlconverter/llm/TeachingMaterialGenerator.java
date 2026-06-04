package kr.dogfoot.hwpxlib.tool.idmlconverter.llm;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTDocument;

import java.time.Instant;
import java.util.*;

/**
 * IDML → AST → 텍스트 청크 → LLM → TeachingMaterial 오케스트레이터.
 */
public final class TeachingMaterialGenerator {

    private TeachingMaterialGenerator() {}

    /**
     * @param doc           변환된 ASTDocument
     * @param systemPrompt  이미 머지된 시스템 프롬프트 (TeachingPromptLoader로 조합)
     * @param config        LLM 설정 (API 키 포함)
     * @return TeachingMaterial (슬라이드 콘텐츠)
     */
    public static TeachingMaterial generate(
            ASTDocument doc,
            String systemPrompt,
            LLMConfig config) throws Exception {

        if (!config.hasAnyKey()) {
            throw new LLMException("API 키 없음. .env에 GROQ_API_KEY 또는 ANTHROPIC_API_KEY를 설정하세요.");
        }

        // 2. AST → 청크
        List<DocumentChunker.DocumentChunk> chunks = DocumentChunker.chunk(doc);
        System.out.println("[LLM] 총 " + chunks.size() + "개 단원 청크 감지");

        // 3. LLM 클라이언트
        GroqClient client = new GroqClient(config);

        // 4. 결과 조립
        TeachingMaterial material = new TeachingMaterial();
        material.source      = deriveSource(doc);
        material.generatedAt = Instant.now().toString();
        material.model       = config.activeProvider();
        // promptFiles는 호출자(CLI)가 실제 파일명을 채운다

        for (int i = 0; i < chunks.size(); i++) {
            DocumentChunker.DocumentChunk chunk = chunks.get(i);
            System.out.println("[LLM] 단원 " + (i + 1) + "/" + chunks.size()
                    + " 처리 중: " + chunk.unitTitle);

            TeachingUnit unit = processChunk(chunk, systemPrompt, client, i + 1);
            material.units.add(unit);

            // rate-limit 대비 딜레이 (마지막 청크 제외)
            if (i < chunks.size() - 1 && config.chunkDelayMs() > 0) {
                try { Thread.sleep(config.chunkDelayMs()); } catch (InterruptedException ignored) {}
            }
        }

        return material;
    }

    // -------------------------------------------------------
    // 청크 처리
    // -------------------------------------------------------

    private static TeachingUnit processChunk(
            DocumentChunker.DocumentChunk chunk,
            String systemPrompt,
            GroqClient client,
            int unitIdx) {

        TeachingUnit unit = new TeachingUnit();
        unit.unitIndex = unitIdx;
        unit.unitTitle = chunk.unitTitle;
        unit.sourcePages = chunk.pages;

        String userContent = buildUserContent(chunk);

        String raw = null;
        try {
            raw = client.complete(systemPrompt, userContent);
            unit.slides = parseSlides(raw);
        } catch (LLMException e) {
            System.err.println("[LLM] 단원 " + unitIdx + " API 오류: " + e.getMessage());
            // 폴백: 빈 슬라이드 1개
            unit.slides = new ArrayList<SlideContent>();
            unit.slides.add(SlideContent.fallback(1, raw != null ? raw : e.getMessage()));
        }

        return unit;
    }

    private static String buildUserContent(DocumentChunker.DocumentChunk chunk) {
        StringBuilder sb = new StringBuilder();
        sb.append("단원 제목: ").append(chunk.unitTitle).append("\n");
        sb.append("페이지: ").append(chunk.pages).append("\n\n");
        sb.append("--- 내용 시작 ---\n");
        // 너무 길면 자름 (GROQ 컨텍스트 제한)
        String text = chunk.textContent;
        if (text != null && text.length() > 8000) {
            text = text.substring(0, 8000) + "\n\n... (이하 생략)";
        }
        sb.append(text != null ? text : "");
        sb.append("\n--- 내용 끝 ---\n\n");
        sb.append("위 내용을 기반으로 교수자료 슬라이드를 JSON으로 생성해주세요.");
        return sb.toString();
    }

    // -------------------------------------------------------
    // LLM 응답 파싱
    // -------------------------------------------------------

    private static List<SlideContent> parseSlides(String raw) throws LLMException {
        // JSON 블록 추출 (LLM이 마크다운 코드블록 안에 넣는 경우 처리)
        String json = extractJson(raw);
        if (json == null) throw new LLMException("JSON 블록 없음: " + safePreview(raw));

        try {
            JsonObject root = JsonParser.parseString(json).getAsJsonObject();
            // {"slides": [...]} 또는 {"units": [{"slides": [...]}]} 형태 허용
            JsonArray arr = null;
            if (root.has("slides")) {
                arr = root.getAsJsonArray("slides");
            } else if (root.has("units")) {
                JsonArray units = root.getAsJsonArray("units");
                if (units.size() > 0) {
                    JsonObject first = units.get(0).getAsJsonObject();
                    if (first.has("slides")) arr = first.getAsJsonArray("slides");
                }
            }
            if (arr == null) throw new LLMException("응답에 slides 배열 없음");

            Gson gson = new Gson();
            List<SlideContent> result = new ArrayList<SlideContent>();
            for (JsonElement el : arr) {
                SlideContent s = gson.fromJson(el, SlideContent.class);
                if (s.index <= 0) s.index = result.size() + 1;
                if (s.layout == null) s.layout = "custom";
                result.add(s);
            }
            return result;
        } catch (LLMException e) {
            throw e;
        } catch (Exception e) {
            throw new LLMException("슬라이드 JSON 파싱 실패: " + e.getMessage(), e);
        }
    }

    /** { ... } 블록 추출 */
    private static String extractJson(String raw) {
        if (raw == null) return null;
        // 코드 블록 안 JSON 우선
        int codeStart = raw.indexOf("```json");
        if (codeStart >= 0) {
            int start = raw.indexOf('\n', codeStart) + 1;
            int end   = raw.indexOf("```", start);
            if (end > start) return raw.substring(start, end).trim();
        }
        // 첫 번째 { 부터 마지막 } 까지
        int first = raw.indexOf('{');
        int last  = raw.lastIndexOf('}');
        if (first >= 0 && last > first) return raw.substring(first, last + 1);
        return null;
    }

    // -------------------------------------------------------
    // 유틸
    // -------------------------------------------------------

    private static String deriveSource(ASTDocument doc) {
        if (doc == null) return "unknown";
        // 파일명 기반 (ASTDocument에 sourceFile이 없으면 generic)
        return "document";
    }

    private static String safePreview(String s) {
        if (s == null) return "(null)";
        return s.length() > 200 ? s.substring(0, 200) + "..." : s;
    }
}
