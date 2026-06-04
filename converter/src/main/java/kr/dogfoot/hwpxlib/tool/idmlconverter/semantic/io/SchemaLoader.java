package kr.dogfoot.hwpxlib.tool.idmlconverter.semantic.io;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import kr.dogfoot.hwpxlib.tool.idmlconverter.semantic.SemanticSchema;
import kr.dogfoot.hwpxlib.tool.idmlconverter.semantic.SemanticTypes;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SchemaLoader — JSON 스키마 파일을 읽어 {@link SemanticSchema} 로 파싱하고
 * extends 상속을 해석한다.
 *
 * <p>TypeScript {@code packages/semantic-layer/src/core/schema-loader.ts} 와
 * 동일한 동작을 하도록 1:1 포팅. 같은 JSON → 같은 SemanticSchema (필드 단위
 * 비교 가능).</p>
 *
 * <p>classpath 리소스 {@code semantic-schemas/*.json} 을 직접 로드하는
 * 헬퍼도 제공 ({@link #loadResource(String)}).</p>
 */
public class SchemaLoader {

    private final Map<String, SemanticSchema> schemas = new LinkedHashMap<>();

    /** 스키마를 직접 등록. */
    public void register(SemanticSchema schema) {
        if (schema == null || schema.schemaId == null || schema.schemaId.isEmpty()) return;
        schemas.put(schema.schemaId, schema);
    }

    /** JSON 문자열에서 스키마 파싱 + 등록. */
    public SemanticSchema loadFromJson(String json) {
        SemanticSchema schema = parseSchema(json);
        register(schema);
        return schema;
    }

    /** 파일에서 스키마 로드 + 등록. */
    public SemanticSchema loadFromFile(Path path) throws IOException {
        String json = new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
        return loadFromJson(json);
    }

    /**
     * classpath 리소스에서 스키마 로드 + 등록.
     *
     * <p>예: {@code loadResource("semantic-schemas/common.schema.json")}</p>
     */
    public SemanticSchema loadResource(String resourcePath) throws IOException {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null) cl = SchemaLoader.class.getClassLoader();
        InputStream is = cl.getResourceAsStream(resourcePath);
        if (is == null) {
            throw new IOException("스키마 리소스를 찾을 수 없음: " + resourcePath);
        }
        try (Reader reader = new InputStreamReader(is, StandardCharsets.UTF_8)) {
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[4096];
            int n;
            while ((n = reader.read(buf)) != -1) sb.append(buf, 0, n);
            return loadFromJson(sb.toString());
        }
    }

    /** 등록된 모든 스키마 ID. */
    public List<String> listIds() {
        return new ArrayList<>(schemas.keySet());
    }

    /**
     * 스키마 가져오기 (extends 상속 해석 포함).
     *
     * <p>{@code extends} 가 지정되어 있으면 부모 스키마와 머지한 결과를 반환.</p>
     */
    public SemanticSchema get(String schemaId) {
        SemanticSchema schema = schemas.get(schemaId);
        if (schema == null) return null;
        return resolve(schema);
    }

    /** 상속 체인을 따라가며 부모와 머지. */
    private SemanticSchema resolve(SemanticSchema schema) {
        if (schema.extendsSchema == null || schema.extendsSchema.isEmpty()) return schema;
        SemanticSchema parent = schemas.get(schema.extendsSchema);
        if (parent == null) return schema;
        SemanticSchema resolvedParent = resolve(parent);
        return mergeSchemas(resolvedParent, schema);
    }

    /**
     * 부모 + 자식 스키마 머지.
     *
     * <p>TS {@code mergeSchemas} 와 동일:</p>
     * <ul>
     *   <li>labels: 자식이 부모 덮어씀 (id 기준)</li>
     *   <li>rules: 부모 + 자식 합쳐서 priority 오름차순 정렬</li>
     *   <li>relationRules: 부모 + 자식 머지 (id 기준)</li>
     *   <li>layoutHints: 부모 + 자식 머지 (label 기준)</li>
     * </ul>
     */
    static SemanticSchema mergeSchemas(SemanticSchema parent, SemanticSchema child) {
        SemanticSchema out = new SemanticSchema();
        out.schemaId = child.schemaId;
        out.schemaName = child.schemaName;
        out.version = child.version;
        out.subject = child.subject;
        out.documentType = child.documentType;
        out.extendsSchema = null; // 이미 해석됨

        // labels
        Map<String, SemanticSchema.LabelDef> labelMap = new LinkedHashMap<>();
        for (SemanticSchema.LabelDef l : parent.labels) labelMap.put(l.id, l);
        for (SemanticSchema.LabelDef l : child.labels) labelMap.put(l.id, l);
        out.labels = new ArrayList<>(labelMap.values());

        // rules: 합쳐서 priority 오름차순 (안정 정렬)
        List<SemanticSchema.ClassificationRule> allRules = new ArrayList<>();
        allRules.addAll(parent.rules);
        allRules.addAll(child.rules);
        // Java Collections.sort 는 안정 정렬 — TS Array.sort 와 호환
        Collections.sort(allRules, (a, b) -> Integer.compare(a.priority, b.priority));
        out.rules = allRules;

        // relationRules
        Map<String, SemanticSchema.RelationRule> relMap = new LinkedHashMap<>();
        for (SemanticSchema.RelationRule r : parent.relationRules) relMap.put(r.id, r);
        for (SemanticSchema.RelationRule r : child.relationRules) relMap.put(r.id, r);
        out.relationRules = new ArrayList<>(relMap.values());

        // layoutHints
        Map<String, SemanticSchema.LayoutHint> hintMap = new LinkedHashMap<>();
        for (SemanticSchema.LayoutHint h : parent.layoutHints) hintMap.put(h.label, h);
        for (SemanticSchema.LayoutHint h : child.layoutHints) hintMap.put(h.label, h);
        out.layoutHints = new ArrayList<>(hintMap.values());

        return out;
    }

    // ─────────────────────────────────────────────────────────
    // JSON 파싱 (수동) — extends 키워드 처리 + 기본값 보장
    // ─────────────────────────────────────────────────────────

    /**
     * JSON → SemanticSchema. TS {@code validateSchema} 와 동일한 기본값 정책.
     */
    public static SemanticSchema parseSchema(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        SemanticSchema s = new SemanticSchema();
        s.schemaId = optString(root, "schemaId", "");
        s.schemaName = optString(root, "schemaName", "");
        s.version = optString(root, "version", "1.0.0");
        s.subject = optString(root, "subject", "");
        s.documentType = optString(root, "documentType", "");
        if (root.has("extends") && !root.get("extends").isJsonNull()) {
            s.extendsSchema = root.get("extends").getAsString();
        }

        s.labels = new ArrayList<>();
        if (root.has("labels") && root.get("labels").isJsonArray()) {
            for (JsonElement el : root.getAsJsonArray("labels")) {
                s.labels.add(parseLabel(el.getAsJsonObject()));
            }
        }
        s.rules = new ArrayList<>();
        if (root.has("rules") && root.get("rules").isJsonArray()) {
            for (JsonElement el : root.getAsJsonArray("rules")) {
                s.rules.add(parseRule(el.getAsJsonObject()));
            }
        }
        s.relationRules = new ArrayList<>();
        if (root.has("relationRules") && root.get("relationRules").isJsonArray()) {
            for (JsonElement el : root.getAsJsonArray("relationRules")) {
                s.relationRules.add(parseRelationRule(el.getAsJsonObject()));
            }
        }
        s.layoutHints = new ArrayList<>();
        if (root.has("layoutHints") && root.get("layoutHints").isJsonArray()) {
            for (JsonElement el : root.getAsJsonArray("layoutHints")) {
                s.layoutHints.add(parseLayoutHint(el.getAsJsonObject()));
            }
        }
        return s;
    }

    private static SemanticSchema.LabelDef parseLabel(JsonObject o) {
        SemanticSchema.LabelDef l = new SemanticSchema.LabelDef();
        l.id = optString(o, "id", "");
        l.name = optString(o, "name", "");
        l.description = optString(o, "description", "");
        l.color = optString(o, "color", "#888888");
        l.icon = optString(o, "icon", "");
        String cat = optString(o, "category", "content");
        try {
            l.category = SemanticTypes.LabelCategory.valueOf(cat);
        } catch (IllegalArgumentException e) {
            l.category = SemanticTypes.LabelCategory.content;
        }
        l.allowedChildren = new ArrayList<>();
        if (o.has("allowedChildren") && o.get("allowedChildren").isJsonArray()) {
            for (JsonElement el : o.getAsJsonArray("allowedChildren")) {
                l.allowedChildren.add(el.getAsString());
            }
        }
        return l;
    }

    private static SemanticSchema.ClassificationRule parseRule(JsonObject o) {
        SemanticSchema.ClassificationRule r = new SemanticSchema.ClassificationRule();
        r.id = optString(o, "id", "");
        r.label = optString(o, "label", "");
        r.priority = optInt(o, "priority", 999);
        r.confidence = optDouble(o, "confidence", 0.5);
        r.conditions = parseConditions(o);
        return r;
    }

    private static SemanticSchema.RelationRule parseRelationRule(JsonObject o) {
        SemanticSchema.RelationRule r = new SemanticSchema.RelationRule();
        r.id = optString(o, "id", "");
        if (o.has("type") && !o.get("type").isJsonNull()) {
            try {
                r.type = SemanticTypes.RelationType.valueOf(o.get("type").getAsString());
            } catch (IllegalArgumentException e) {
                r.type = null;
            }
        }
        r.sourceLabel = optString(o, "sourceLabel", "");
        r.targetLabel = optString(o, "targetLabel", "");
        r.conditions = parseConditions(o);
        return r;
    }

    private static SemanticSchema.LayoutHint parseLayoutHint(JsonObject o) {
        SemanticSchema.LayoutHint h = new SemanticSchema.LayoutHint();
        h.label = optString(o, "label", "");
        h.expectedRegions = new ArrayList<>();
        if (o.has("expectedRegions") && o.get("expectedRegions").isJsonArray()) {
            for (JsonElement el : o.getAsJsonArray("expectedRegions")) {
                try {
                    h.expectedRegions.add(SemanticTypes.RegionTag.valueOf(el.getAsString()));
                } catch (IllegalArgumentException ignore) {}
            }
        }
        return h;
    }

    private static List<SemanticSchema.Condition> parseConditions(JsonObject o) {
        List<SemanticSchema.Condition> list = new ArrayList<>();
        if (!o.has("conditions") || !o.get("conditions").isJsonArray()) return list;
        for (JsonElement el : o.getAsJsonArray("conditions")) {
            JsonObject co = el.getAsJsonObject();
            SemanticSchema.Condition c = new SemanticSchema.Condition();
            c.field = optString(co, "field", "");
            String op = optString(co, "operator", "eq");
            try {
                c.operator = SemanticTypes.Operator.valueOf(op);
            } catch (IllegalArgumentException e) {
                c.operator = SemanticTypes.Operator.eq;
            }
            if (co.has("value")) {
                c.value = jsonToObject(co.get("value"));
            }
            list.add(c);
        }
        return list;
    }

    /** JsonElement → Java Object (string/double/boolean/list). */
    private static Object jsonToObject(JsonElement el) {
        if (el == null || el.isJsonNull()) return null;
        if (el.isJsonPrimitive()) {
            JsonPrimitive p = el.getAsJsonPrimitive();
            if (p.isString()) return p.getAsString();
            if (p.isBoolean()) return p.getAsBoolean();
            if (p.isNumber()) return p.getAsDouble();
        }
        if (el.isJsonArray()) {
            List<Object> list = new ArrayList<>();
            for (JsonElement e : el.getAsJsonArray()) list.add(jsonToObject(e));
            return list;
        }
        // 객체는 일단 무시 (룰 조건 value로 객체는 사용 안 함)
        return null;
    }

    private static String optString(JsonObject o, String key, String def) {
        if (!o.has(key) || o.get(key).isJsonNull()) return def;
        return o.get(key).getAsString();
    }

    private static int optInt(JsonObject o, String key, int def) {
        if (!o.has(key) || o.get(key).isJsonNull()) return def;
        return o.get(key).getAsInt();
    }

    private static double optDouble(JsonObject o, String key, double def) {
        if (!o.has(key) || o.get(key).isJsonNull()) return def;
        return o.get(key).getAsDouble();
    }
}
