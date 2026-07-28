package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.math;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTEquation;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTInlineItem;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTParagraph;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 수식 서브 파이프라인 3단계 검증기.
 *
 * <p>수식 여부를 다시 판단하거나 AST를 수정하지 않고, materialize된 ASTEquation의
 * 구조적 계약만 검사한다. {@code MATH_VALIDATE=strict} 환경에서는 위반을 예외로
 * 보고하며, 기본 변환에서는 기존 문서 호환성을 위해 진단만 제공한다.</p>
 */
public final class MathMaterializationValidator {
    public static final String EMPTY_SCRIPT = "EMPTY_SCRIPT";
    public static final String MISSING_SOURCE_TYPE = "MISSING_SOURCE_TYPE";
    public static final String SCRIPT_CONTROL_CHARACTER = "SCRIPT_CONTROL_CHARACTER";

    public static final class Violation {
        private final String code;
        private final int itemIndex;
        private final String message;

        Violation(String code, int itemIndex, String message) {
            this.code = code;
            this.itemIndex = itemIndex;
            this.message = message;
        }

        public String code() {
            return code;
        }

        public int itemIndex() {
            return itemIndex;
        }

        public String message() {
            return message;
        }
    }

    private MathMaterializationValidator() {
    }

    public static List<Violation> validate(ASTParagraph paragraph) {
        if (paragraph == null || paragraph.items() == null) {
            return Collections.emptyList();
        }
        List<Violation> violations = new ArrayList<>();
        List<ASTInlineItem> items = paragraph.items();
        for (int i = 0; i < items.size(); i++) {
            ASTInlineItem item = items.get(i);
            if (!(item instanceof ASTEquation)) continue;
            ASTEquation equation = (ASTEquation) item;
            String script = equation.hwpScript();
            if (script == null || script.trim().isEmpty()) {
                violations.add(new Violation(
                        EMPTY_SCRIPT, i, "materialized equation has no HWP script"));
            } else if (containsControlCharacter(script)) {
                violations.add(new Violation(
                        SCRIPT_CONTROL_CHARACTER,
                        i,
                        "materialized equation script contains a tab or line break"));
            }
            String sourceType = equation.sourceType();
            if (sourceType == null || sourceType.trim().isEmpty()) {
                violations.add(new Violation(
                        MISSING_SOURCE_TYPE, i, "materialized equation has no source type"));
            }
        }
        return Collections.unmodifiableList(violations);
    }

    public static void validateIfEnabled(ASTParagraph paragraph) {
        String mode = System.getenv("MATH_VALIDATE");
        if (mode == null || mode.trim().isEmpty()) return;
        List<Violation> violations = validate(paragraph);
        if (violations.isEmpty()) return;
        String message = format(violations);
        if ("strict".equalsIgnoreCase(mode.trim())) {
            throw new IllegalStateException(message);
        }
        System.err.println(message);
    }

    private static boolean containsControlCharacter(String script) {
        for (int i = 0; i < script.length(); i++) {
            char c = script.charAt(i);
            if (c == '\t' || c == '\n' || c == '\r' || c == '\u2028') return true;
        }
        return false;
    }

    private static String format(List<Violation> violations) {
        StringBuilder out = new StringBuilder("Math materialization violations:");
        for (Violation violation : violations) {
            out.append(' ')
                    .append(violation.code())
                    .append('@')
                    .append(violation.itemIndex())
                    .append('(')
                    .append(violation.message())
                    .append(')');
        }
        return out.toString();
    }
}
