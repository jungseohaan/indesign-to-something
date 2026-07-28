package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.math;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 수식 서브 파이프라인의 1단계 결과.
 *
 * <p>원본 AST 항목을 실제로 변경하지 않고, 어떤 항목을 어떤 근거로 수식화할지 기록한다.
 * 이후 변환 단계는 이 계획을 실행하고 HWPX 출력 단계는 판정을 다시 하지 않는다.</p>
 */
public final class MathSpanPlan {
    public enum Classification {
        MATH,
        TEXT
    }

    public static final class Span {
        private final int itemIndex;
        private final Classification classification;
        private final String reason;

        Span(int itemIndex, Classification classification, String reason) {
            this.itemIndex = itemIndex;
            this.classification = classification;
            this.reason = reason;
        }

        public int itemIndex() {
            return itemIndex;
        }

        public Classification classification() {
            return classification;
        }

        public String reason() {
            return reason;
        }
    }

    private final List<Span> spans = new ArrayList<>();

    void add(int itemIndex, Classification classification, String reason) {
        spans.add(new Span(itemIndex, classification, reason));
    }

    public List<Span> spans() {
        return Collections.unmodifiableList(spans);
    }

    public boolean isEmpty() {
        return spans.isEmpty();
    }
}
