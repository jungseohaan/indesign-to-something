package kr.dogfoot.hwpxlib.tool.idmlconverter.semantic.classify;

import kr.dogfoot.hwpxlib.tool.idmlconverter.semantic.SemanticNode;
import kr.dogfoot.hwpxlib.tool.idmlconverter.semantic.SemanticSchema;
import kr.dogfoot.hwpxlib.tool.idmlconverter.semantic.StructuralFeatures;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * RuleEngine — ClassificationRule 평가 엔진.
 *
 * <p>TS {@code packages/semantic-layer/src/core/rule-classifier.ts} 와 1:1 포팅.
 * 같은 룰 + 같은 노드 → 같은 결과.</p>
 *
 * <p>주의: TS의 동작과 정확히 일치하도록 다음 규칙을 지킨다:</p>
 * <ul>
 *   <li>{@code manualOverride == true} 노드는 건드리지 않음.</li>
 *   <li>모든 룰을 평가한 후 {@code confidence} 가 가장 높은 것 선택. 같으면 먼저 매칭된 것.</li>
 *   <li>{@code conditions} 는 모두 AND. 한 조건이라도 false면 룰 미매칭.</li>
 *   <li>field 는 dot-notation 지원 ("spatial.overlappingNodeIds").</li>
 * </ul>
 */
public final class RuleEngine {

    private RuleEngine() {}

    /** 분류 결과. */
    public static class ClassificationResult {
        public final String label;
        public final double confidence;
        public final String ruleId;
        public ClassificationResult(String label, double confidence, String ruleId) {
            this.label = label;
            this.confidence = confidence;
            this.ruleId = ruleId;
        }
    }

    /** 노드 배열에 룰을 적용하여 라벨 부여. manualOverride 보존. */
    public static void classifyNodes(
            List<SemanticNode> nodes,
            List<SemanticSchema.ClassificationRule> rules) {
        for (SemanticNode node : nodes) {
            if (node.manualOverride) continue;
            ClassificationResult r = classifyNode(node, rules);
            if (r == null) continue;
            node.label = r.label;
            node.confidence = r.confidence;
            node.appliedRule = r.ruleId;
        }
    }

    /** 단일 노드에 대해 최적 룰 선택. */
    public static ClassificationResult classifyNode(
            SemanticNode node,
            List<SemanticSchema.ClassificationRule> rules) {
        ClassificationResult best = null;
        for (SemanticSchema.ClassificationRule rule : rules) {
            if (!evaluateConditions(rule.conditions, node.features)) continue;
            if (best == null || rule.confidence > best.confidence) {
                best = new ClassificationResult(rule.label, rule.confidence, rule.id);
            }
        }
        return best;
    }

    /** 모든 조건이 만족되는지 (AND). */
    public static boolean evaluateConditions(
            List<SemanticSchema.Condition> conditions,
            StructuralFeatures features) {
        if (conditions == null) return true;
        for (SemanticSchema.Condition c : conditions) {
            if (!evaluateCondition(c, features)) return false;
        }
        return true;
    }

    // ─── 단일 조건 평가 ────────────────────────────

    private static boolean evaluateCondition(SemanticSchema.Condition c, StructuralFeatures features) {
        Object value = getFieldValue(c.field, features);
        Object expected = c.value;
        switch (c.operator) {
            case eq: return equalsLoose(value, expected);
            case ne: return !equalsLoose(value, expected);
            case gt: return cmp(value, expected) > 0;
            case lt: return cmp(value, expected) < 0;
            case gte: return cmp(value, expected) >= 0;
            case lte: return cmp(value, expected) <= 0;
            case contains:
                if (value instanceof String && expected instanceof String) {
                    return ((String) value).contains((String) expected);
                }
                if (value instanceof List) {
                    return listContainsLoose((List<?>) value, expected);
                }
                return false;
            case startsWith:
                return value instanceof String && expected instanceof String
                        && ((String) value).startsWith((String) expected);
            case matches:
                if (value instanceof String && expected instanceof String) {
                    try {
                        // TS new RegExp(expected).test(value) — 부분 매칭
                        return Pattern.compile((String) expected).matcher((String) value).find();
                    } catch (Exception e) {
                        return false;
                    }
                }
                return false;
            case in:
                return expected instanceof List && listContainsLoose((List<?>) expected, value);
            case notIn:
                return expected instanceof List && !listContainsLoose((List<?>) expected, value);
            default:
                return false;
        }
    }

    /**
     * TS의 {@code ===} 비교에 가까운 동작.
     * - null 양쪽이면 true
     * - 숫자: double 변환 후 비교
     * - 그 외: equals
     */
    private static boolean equalsLoose(Object a, Object b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        if (a instanceof Number && b instanceof Number) {
            return ((Number) a).doubleValue() == ((Number) b).doubleValue();
        }
        if (a instanceof Number && b instanceof String) {
            try { return ((Number) a).doubleValue() == Double.parseDouble((String) b); }
            catch (NumberFormatException e) { return false; }
        }
        if (a instanceof Boolean && b instanceof Boolean) {
            return a.equals(b);
        }
        if (a.getClass().isEnum()) {
            return ((Enum<?>) a).name().equals(b.toString());
        }
        if (b.getClass().isEnum()) {
            return ((Enum<?>) b).name().equals(a.toString());
        }
        return a.equals(b);
    }

    /** 리스트 contains 를 equalsLoose 로 비교 (enum ↔ String 매칭). */
    private static boolean listContainsLoose(List<?> list, Object value) {
        for (Object item : list) {
            if (equalsLoose(item, value)) return true;
        }
        return false;
    }

    /**
     * 숫자 비교. value/expected 둘 다 number 가 아니면 비교 불가 → -2 반환하여
     * 모든 비교 실패. (TS는 typeof 검사로 false 반환)
     */
    private static int cmp(Object a, Object b) {
        if (a instanceof Number && b instanceof Number) {
            double da = ((Number) a).doubleValue();
            double db = ((Number) b).doubleValue();
            return Double.compare(da, db);
        }
        // 비교 불가 — 모든 gt/lt/gte/lte 가 false 가 되도록 안전한 값 반환
        return Integer.MIN_VALUE;
    }

    // ─── 필드 접근 (dot-notation) ──────────────────

    /**
     * StructuralFeatures 의 필드를 dot-notation 으로 접근.
     *
     * <p>TS 는 plain object 라 동적 접근이지만, Java는 reflection 사용.
     * 매번 reflection 호출은 비싸므로 캐시.</p>
     */
    private static final Map<Class<?>, Map<String, Field>> FIELD_CACHE = new HashMap<>();

    static Object getFieldValue(String field, StructuralFeatures features) {
        if (field == null || field.isEmpty()) return null;
        String[] parts = field.split("\\.");
        Object current = features;
        for (String part : parts) {
            if (current == null) return null;
            current = readField(current, part);
        }
        return current;
    }

    private static Object readField(Object obj, String name) {
        Class<?> cls = obj.getClass();
        Map<String, Field> map = FIELD_CACHE.get(cls);
        if (map == null) {
            map = new HashMap<>();
            for (Field f : cls.getFields()) {
                map.put(f.getName(), f);
            }
            FIELD_CACHE.put(cls, map);
        }
        Field f = map.get(name);
        if (f == null) return null;
        try {
            return f.get(obj);
        } catch (IllegalAccessException e) {
            return null;
        }
    }
}
