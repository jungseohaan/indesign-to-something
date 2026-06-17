package kr.dogfoot.hwpxlib.tool.idmlconverter.llm;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.stream.JsonReader;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTDocument;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.io.StringReader;

/**
 * IDML AST → LLM → Teaching Semantic JSON 오케스트레이터.
 */
public final class TeachingMaterialGenerator {

    private TeachingMaterialGenerator() {}

    /**
     * @param doc           변환된 ASTDocument
     * @param systemPrompt  이미 머지된 시스템 프롬프트 (TeachingPromptLoader로 조합)
     * @param config        LLM 설정 (API 키 포함)
     * @return {"semantic_blocks":[...],"teaching_blocks":[...]} JSON 객체
     */
    public static JsonObject generate(
            ASTDocument doc,
            String systemPrompt,
            LLMConfig config) throws Exception {
        return generate(doc, new TeachingPromptLoader.AgentPrompts(systemPrompt, systemPrompt), config);
    }

    /**
     * Agent A: AST chunk → Raw Semantic Blocks
     * Agent B: Raw Semantic Blocks → Teaching Blocks
     */
    public static JsonObject generate(
            ASTDocument doc,
            TeachingPromptLoader.AgentPrompts prompts,
            LLMConfig config) throws Exception {
        throw new LLMException("LLM 호출 기반 semantic/teaching block 생성은 비활성화되었습니다. "
                + "HWPX 변환의 .semantic-blocks.json은 로컬 SemanticBlockDetector만 사용하세요.");
    }

    // -------------------------------------------------------
    // LLM 응답 파싱
    // -------------------------------------------------------

    private static JsonObject parseSemanticResult(String raw) throws LLMException {
        String json = extractJsonObject(raw);
        if (json == null) throw new LLMException("JSON 객체 없음: " + safePreview(raw));

        try {
            JsonElement parsed = parseJson(json);
            if (!parsed.isJsonObject()) {
                throw new LLMException("응답 최상위가 JSON 객체가 아님");
            }
            JsonObject root = parsed.getAsJsonObject();
            JsonArray semanticBlocks = findArray(root,
                    "semantic_blocks",
                    "raw_semantic_blocks",
                    "Raw Semantic Blocks",
                    "Semantic Blocks",
                    "semanticBlocks",
                    "blocks");
            if (semanticBlocks == null && root.has("block_id")) {
                semanticBlocks = new JsonArray();
                semanticBlocks.add(root.deepCopy());
            }
            if (semanticBlocks == null) {
                throw new LLMException("응답에 semantic_blocks 배열 없음: " + safePreview(raw));
            }

            JsonObject normalized = new JsonObject();
            normalized.add("semantic_blocks", semanticBlocks.deepCopy());
            return normalized;
        } catch (LLMException e) {
            throw e;
        } catch (Exception e) {
            JsonObject fallback = parseLooseMultipleObjects(json);
            if (fallback != null
                    && fallback.has("semantic_blocks")
                    && fallback.get("semantic_blocks").isJsonArray()
                    && fallback.getAsJsonArray("semantic_blocks").size() > 0) {
                return fallback;
            }
            throw new LLMException("Raw Semantic JSON 파싱 실패: " + e.getMessage()
                    + " / raw=" + safePreview(raw), e);
        }
    }

    private static JsonObject parseTeachingResult(String raw) throws LLMException {
        String json = extractJsonObject(raw);
        if (json == null) throw new LLMException("JSON 객체 없음: " + safePreview(raw));

        try {
            JsonElement parsed = parseJson(json);
            if (!parsed.isJsonObject()) {
                throw new LLMException("응답 최상위가 JSON 객체가 아님");
            }
            JsonObject root = parsed.getAsJsonObject();
            JsonArray teachingBlocks = findArray(root,
                    "teaching_blocks",
                    "Teaching Blocks",
                    "teachingBlocks");
            if (teachingBlocks == null && root.has("teaching_block_id")) {
                teachingBlocks = new JsonArray();
                teachingBlocks.add(root.deepCopy());
            }
            if (teachingBlocks == null) {
                throw new LLMException("응답에 teaching_blocks 배열 없음: " + safePreview(raw));
            }

            JsonObject normalized = new JsonObject();
            normalized.add("teaching_blocks", teachingBlocks.deepCopy());
            return normalized;
        } catch (LLMException e) {
            throw e;
        } catch (Exception e) {
            JsonObject fallback = parseLooseMultipleObjects(json);
            if (fallback != null
                    && fallback.has("teaching_blocks")
                    && fallback.get("teaching_blocks").isJsonArray()
                    && fallback.getAsJsonArray("teaching_blocks").size() > 0) {
                return fallback;
            }
            throw new LLMException("Teaching Block JSON 파싱 실패: " + e.getMessage()
                    + " / raw=" + safePreview(raw), e);
        }
    }

    /** { ... } 객체 추출 */
    private static String extractJsonObject(String raw) {
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

    private static String buildAgentAUserContent(DocumentChunker.DocumentChunk chunk) {
        JsonObject input = new JsonObject();
        input.addProperty("type", "SemanticExtractionChunk");
        input.addProperty("chunk_index", chunk.unitIndex);
        input.addProperty("chunk_title", chunk.unitTitle);
        input.addProperty("contents", chunk.textContent != null ? chunk.textContent : "");

        JsonArray pages = new JsonArray();
        for (Integer page : chunk.pages) pages.add(page);
        input.add("pages", pages);

        JsonArray imageRefs = new JsonArray();
        for (String ref : chunk.imageRefs) imageRefs.add(ref);
        input.add("image_refs", imageRefs);

        return "InDesign AST chunk:\n" + new Gson().toJson(input);
    }

    private static String buildAgentBUserContent(JsonArray semanticBlocks) {
        JsonObject input = new JsonObject();
        input.addProperty("type", "TeachingBlockCompositionInput");
        input.add("semantic_blocks", semanticBlocks.deepCopy());
        return "Semantic Block 목록:\n" + new Gson().toJson(input);
    }

    private static void appendSemanticBlocks(
            JsonArray mergedSemanticBlocks,
            JsonObject parsed,
            DocumentChunker.DocumentChunk chunk) {
        JsonArray semanticBlocks = parsed.getAsJsonArray("semantic_blocks");
        for (JsonElement element : semanticBlocks) {
            if (!element.isJsonObject()) continue;
            JsonObject block = element.getAsJsonObject().deepCopy();
            int blockIndex = mergedSemanticBlocks.size() + 1;
            String newId = String.format("SB-%03d", blockIndex);
            block.addProperty("block_id", newId);

            if (!block.has("page_start") && !chunk.pages.isEmpty()) {
                block.addProperty("page_start", chunk.pages.get(0));
            }
            if (!block.has("page_end") && !chunk.pages.isEmpty()) {
                block.addProperty("page_end", chunk.pages.get(chunk.pages.size() - 1));
            }

            mergedSemanticBlocks.add(block);
        }
    }

    private static JsonArray renumberTeachingBlocks(JsonObject parsed) {
        JsonArray mergedTeachingBlocks = new JsonArray();
        JsonArray teachingBlocks = parsed.getAsJsonArray("teaching_blocks");
        for (JsonElement element : teachingBlocks) {
            if (!element.isJsonObject()) continue;
            JsonObject block = element.getAsJsonObject().deepCopy();
            int blockIndex = mergedTeachingBlocks.size() + 1;
            block.addProperty("teaching_block_id", String.format("TB-%03d", blockIndex));
            mergedTeachingBlocks.add(block);
        }
        return mergedTeachingBlocks;
    }

    private static void validateTeachingCoverage(JsonArray semanticBlocks, JsonArray teachingBlocks) throws LLMException {
        Map<String, Boolean> semanticIds = new HashMap<String, Boolean>();
        for (JsonElement element : semanticBlocks) {
            if (!element.isJsonObject()) continue;
            JsonObject block = element.getAsJsonObject();
            if (block.has("block_id")) semanticIds.put(block.get("block_id").getAsString(), Boolean.FALSE);
        }

        java.util.HashSet<String> used = new java.util.HashSet<String>();
        java.util.ArrayList<String> duplicates = new java.util.ArrayList<String>();
        java.util.ArrayList<String> unknown = new java.util.ArrayList<String>();

        for (JsonElement element : teachingBlocks) {
            if (!element.isJsonObject()) continue;
            JsonObject block = element.getAsJsonObject();
            if (!block.has("semantic_blocks") || !block.get("semantic_blocks").isJsonArray()) {
                continue;
            }
            for (JsonElement idElement : block.getAsJsonArray("semantic_blocks")) {
                String id = idElement.getAsString();
                if (!semanticIds.containsKey(id)) {
                    unknown.add(id);
                    continue;
                }
                if (!used.add(id)) {
                    duplicates.add(id);
                }
                semanticIds.put(id, Boolean.TRUE);
            }
        }

        java.util.ArrayList<String> missing = new java.util.ArrayList<String>();
        for (Map.Entry<String, Boolean> entry : semanticIds.entrySet()) {
            if (!entry.getValue()) missing.add(entry.getKey());
        }

        if (!duplicates.isEmpty() || !unknown.isEmpty() || !missing.isEmpty()) {
            throw new LLMException("Teaching Block 검증 실패"
                    + " / duplicate=" + duplicates
                    + " / unknown=" + unknown
                    + " / missing=" + missing);
        }
    }

    private static JsonArray findArray(JsonObject root, String... keys) {
        for (String key : keys) {
            if (root.has(key) && root.get(key).isJsonArray()) {
                return root.getAsJsonArray(key);
            }
        }
        return null;
    }

    private static JsonElement parseJson(String json) throws Exception {
        JsonReader reader = new JsonReader(new StringReader(json));
        reader.setLenient(true);
        return JsonParser.parseReader(reader);
    }

    private static JsonObject parseLooseMultipleObjects(String json) {
        try {
            List<String> objects = extractTopLevelObjects(json);
            if (objects.isEmpty()) return null;

            JsonArray semanticBlocks = new JsonArray();
            JsonArray teachingBlocks = new JsonArray();
            for (String objectJson : objects) {
                JsonElement parsed = parseJson(objectJson);
                if (!parsed.isJsonObject()) continue;
                JsonObject object = parsed.getAsJsonObject();
                if (object.has("block_id")) {
                    semanticBlocks.add(object.deepCopy());
                } else if (object.has("teaching_block_id")) {
                    teachingBlocks.add(object.deepCopy());
                } else {
                    JsonArray nestedSemantic = findArray(object,
                            "semantic_blocks",
                            "raw_semantic_blocks",
                            "Raw Semantic Blocks",
                            "Semantic Blocks",
                            "semanticBlocks",
                            "blocks");
                    if (nestedSemantic != null) {
                        for (JsonElement el : nestedSemantic) semanticBlocks.add(el.deepCopy());
                    }
                    JsonArray nestedTeaching = findArray(object,
                            "teaching_blocks",
                            "Teaching Blocks",
                            "teachingBlocks");
                    if (nestedTeaching != null) {
                        for (JsonElement el : nestedTeaching) teachingBlocks.add(el.deepCopy());
                    }
                }
            }

            if (semanticBlocks.size() == 0 && teachingBlocks.size() == 0) return null;
            JsonObject root = new JsonObject();
            root.add("semantic_blocks", semanticBlocks);
            root.add("teaching_blocks", teachingBlocks);
            return root;
        } catch (Exception ignored) {
            return null;
        }
    }

    private static List<String> extractTopLevelObjects(String json) {
        java.util.ArrayList<String> objects = new java.util.ArrayList<String>();
        int depth = 0;
        int start = -1;
        boolean inString = false;
        boolean escaped = false;

        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (c == '\\') {
                    escaped = true;
                } else if (c == '"') {
                    inString = false;
                }
                continue;
            }

            if (c == '"') {
                inString = true;
            } else if (c == '{') {
                if (depth == 0) start = i;
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0 && start >= 0) {
                    objects.add(json.substring(start, i + 1));
                    start = -1;
                }
            }
        }
        return objects;
    }

    private static JsonObject emptyResult() {
        JsonObject result = new JsonObject();
        result.add("semantic_blocks", new JsonArray());
        result.add("teaching_blocks", new JsonArray());
        return result;
    }

    // -------------------------------------------------------
    // 유틸
    // -------------------------------------------------------

    private static String safePreview(String s) {
        if (s == null) return "(null)";
        return s.length() > 200 ? s.substring(0, 200) + "..." : s;
    }
}
