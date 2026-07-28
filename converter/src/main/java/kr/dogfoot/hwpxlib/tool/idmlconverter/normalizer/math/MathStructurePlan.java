package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.math;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTInlineItem;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 수식 분류가 끝난 뒤 인접 항목을 하나의 수식 구조로 묶는 불변 계획.
 */
public final class MathStructurePlan {
    public static final class Replacement {
        private final int startInclusive;
        private final int endExclusive;
        private final List<ASTInlineItem> items;
        private final String reason;

        Replacement(int startInclusive, int endExclusive,
                    List<ASTInlineItem> items, String reason) {
            this.startInclusive = startInclusive;
            this.endExclusive = endExclusive;
            this.items = Collections.unmodifiableList(new ArrayList<>(items));
            this.reason = reason;
        }

        public int startInclusive() {
            return startInclusive;
        }

        public int endExclusive() {
            return endExclusive;
        }

        public List<ASTInlineItem> items() {
            return items;
        }

        public String reason() {
            return reason;
        }
    }

    private final List<Replacement> replacements = new ArrayList<>();

    void add(int startInclusive, int endExclusive,
             List<ASTInlineItem> items, String reason) {
        replacements.add(new Replacement(startInclusive, endExclusive, items, reason));
    }

    public List<Replacement> replacements() {
        return Collections.unmodifiableList(replacements);
    }

    public boolean isEmpty() {
        return replacements.isEmpty();
    }
}
