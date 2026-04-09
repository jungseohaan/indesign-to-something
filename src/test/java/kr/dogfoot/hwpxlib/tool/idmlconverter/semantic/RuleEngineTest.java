package kr.dogfoot.hwpxlib.tool.idmlconverter.semantic;

import kr.dogfoot.hwpxlib.tool.idmlconverter.semantic.classify.RuleEngine;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.Assert.*;

/**
 * SPEC-018 M2: RuleEngine 단위 테스트.
 *
 * <p>TS rule-classifier 와 동일 동작인지 검증.</p>
 */
public class RuleEngineTest {

    private static StructuralFeatures sample() {
        StructuralFeatures f = new StructuralFeatures();
        f.pageNumber = 5;
        f.regionTag = SemanticTypes.RegionTag.TOP;
        f.textLength = 25;
        f.dominantFontSize = 1800;
        f.paragraphCount = 1;
        f.blockType = "TEXT_FRAME";
        f.isBackgroundOnly = false;
        return f;
    }

    private static SemanticSchema.Condition cond(String field, SemanticTypes.Operator op, Object val) {
        SemanticSchema.Condition c = new SemanticSchema.Condition();
        c.field = field;
        c.operator = op;
        c.value = val;
        return c;
    }

    private static SemanticSchema.ClassificationRule rule(String id, String label,
            int priority, double confidence, SemanticSchema.Condition... conds) {
        SemanticSchema.ClassificationRule r = new SemanticSchema.ClassificationRule();
        r.id = id;
        r.label = label;
        r.priority = priority;
        r.confidence = confidence;
        r.conditions = Arrays.asList(conds);
        return r;
    }

    @Test
    public void eqOperatorMatchesEnum() {
        StructuralFeatures f = sample();
        List<SemanticSchema.ClassificationRule> rules = Arrays.asList(
                rule("r-top", "PAGE_HEADER", 10, 0.85,
                        cond("regionTag", SemanticTypes.Operator.eq, "TOP")));
        SemanticNode n = new SemanticNode();
        n.features = f;
        RuleEngine.ClassificationResult r = RuleEngine.classifyNode(n, rules);
        assertNotNull(r);
        assertEquals("PAGE_HEADER", r.label);
    }

    @Test
    public void numericLtGteOperators() {
        StructuralFeatures f = sample(); // textLength = 25, dominantFontSize = 1800
        List<SemanticSchema.ClassificationRule> rules = Arrays.asList(
                // gte: 1800 >= 1400 OK
                rule("r-title", "SECTION_TITLE", 20, 0.8,
                        cond("regionTag", SemanticTypes.Operator.eq, "TOP"),
                        cond("dominantFontSize", SemanticTypes.Operator.gte, 1400),
                        cond("paragraphCount", SemanticTypes.Operator.lte, 3)));
        SemanticNode n = new SemanticNode();
        n.features = f;
        RuleEngine.ClassificationResult r = RuleEngine.classifyNode(n, rules);
        assertNotNull(r);
        assertEquals("SECTION_TITLE", r.label);

        // textLength < 50 도 매칭
        List<SemanticSchema.ClassificationRule> ltRules = Arrays.asList(
                rule("r-header", "PAGE_HEADER", 10, 0.85,
                        cond("regionTag", SemanticTypes.Operator.eq, "TOP"),
                        cond("textLength", SemanticTypes.Operator.lt, 50)));
        RuleEngine.ClassificationResult r2 = RuleEngine.classifyNode(n, ltRules);
        assertNotNull(r2);
        assertEquals("PAGE_HEADER", r2.label);
    }

    @Test
    public void chooseBestByConfidence() {
        StructuralFeatures f = sample();
        List<SemanticSchema.ClassificationRule> rules = Arrays.asList(
                rule("r-low", "A", 1, 0.5,
                        cond("regionTag", SemanticTypes.Operator.eq, "TOP")),
                rule("r-high", "B", 1, 0.9,
                        cond("regionTag", SemanticTypes.Operator.eq, "TOP")));
        SemanticNode n = new SemanticNode();
        n.features = f;
        RuleEngine.ClassificationResult r = RuleEngine.classifyNode(n, rules);
        assertNotNull(r);
        assertEquals("B", r.label);
    }

    @Test
    public void manualOverrideIsPreserved() {
        StructuralFeatures f = sample();
        SemanticNode n = new SemanticNode();
        n.id = "sn-test";
        n.features = f;
        n.label = "MANUAL_LABEL";
        n.manualOverride = true;
        n.confidence = 1.0;

        List<SemanticSchema.ClassificationRule> rules = Arrays.asList(
                rule("r-top", "PAGE_HEADER", 10, 0.85,
                        cond("regionTag", SemanticTypes.Operator.eq, "TOP")));
        List<SemanticNode> nodes = new ArrayList<>();
        nodes.add(n);
        RuleEngine.classifyNodes(nodes, rules);
        assertEquals("MANUAL_LABEL", n.label);
    }

    @Test
    public void containsOperatorOnString() {
        StructuralFeatures f = sample();
        f.textContent = "1. 우리 몸의 감각 기관";
        SemanticNode n = new SemanticNode();
        n.features = f;
        List<SemanticSchema.ClassificationRule> rules = Arrays.asList(
                rule("r-contains", "BODY_TEXT", 10, 0.9,
                        cond("textContent", SemanticTypes.Operator.contains, "감각")));
        RuleEngine.ClassificationResult r = RuleEngine.classifyNode(n, rules);
        assertNotNull(r);
        assertEquals("BODY_TEXT", r.label);
    }

    @Test
    public void matchesOperatorWithRegex() {
        StructuralFeatures f = sample();
        f.firstLineText = "1. 첫 번째 항목";
        SemanticNode n = new SemanticNode();
        n.features = f;
        List<SemanticSchema.ClassificationRule> rules = Arrays.asList(
                rule("r-matches", "ITEM", 10, 0.9,
                        cond("firstLineText", SemanticTypes.Operator.matches, "^\\d+\\.\\s")));
        RuleEngine.ClassificationResult r = RuleEngine.classifyNode(n, rules);
        assertNotNull(r);
        assertEquals("ITEM", r.label);
    }

    @Test
    public void inOperatorWithList() {
        StructuralFeatures f = sample();
        SemanticNode n = new SemanticNode();
        n.features = f;
        List<Object> values = Arrays.<Object>asList("TOP", "BOTTOM");
        List<SemanticSchema.ClassificationRule> rules = Arrays.asList(
                rule("r-in", "EDGE", 10, 0.7,
                        cond("regionTag", SemanticTypes.Operator.in, values)));
        RuleEngine.ClassificationResult r = RuleEngine.classifyNode(n, rules);
        assertNotNull(r);
        assertEquals("EDGE", r.label);
    }

    @Test
    public void noMatchReturnsNull() {
        StructuralFeatures f = sample();
        SemanticNode n = new SemanticNode();
        n.features = f;
        List<SemanticSchema.ClassificationRule> rules = Arrays.asList(
                rule("r-bottom", "PAGE_FOOTER", 10, 0.85,
                        cond("regionTag", SemanticTypes.Operator.eq, "BOTTOM")));
        RuleEngine.ClassificationResult r = RuleEngine.classifyNode(n, rules);
        assertNull(r);
    }
}
