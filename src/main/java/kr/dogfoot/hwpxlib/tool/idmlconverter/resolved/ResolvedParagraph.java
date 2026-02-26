package kr.dogfoot.hwpxlib.tool.idmlconverter.resolved;

import java.util.ArrayList;
import java.util.List;

/**
 * resolved.json의 문단 단위 데이터.
 * InDesign DOM에서 paragraph 단위로 수집한 최종 계산값.
 */
public class ResolvedParagraph {
    private String styleName;
    private Object leading;       // Double (fixed pt) 또는 String "Auto"
    private Double autoLeading;   // auto-leading percentage (예: 120)
    private String justification;
    private Double spaceBefore;
    private Double spaceAfter;
    private Double firstLineIndent;
    private Double leftIndent;
    private final List<ResolvedRun> runs = new ArrayList<>();

    public String styleName() { return styleName; }
    public void styleName(String v) { this.styleName = v; }

    /** fixed leading이면 Double, auto이면 null */
    public Double fixedLeading() {
        if (leading instanceof Number) {
            return ((Number) leading).doubleValue();
        }
        return null;
    }

    /** auto-leading이면 true */
    public boolean isAutoLeading() {
        return leading instanceof String && "Auto".equalsIgnoreCase((String) leading);
    }

    public void leading(Object v) { this.leading = v; }

    public Double autoLeading() { return autoLeading; }
    public void autoLeading(Double v) { this.autoLeading = v; }

    public String justification() { return justification; }
    public void justification(String v) { this.justification = v; }

    public Double spaceBefore() { return spaceBefore; }
    public void spaceBefore(Double v) { this.spaceBefore = v; }

    public Double spaceAfter() { return spaceAfter; }
    public void spaceAfter(Double v) { this.spaceAfter = v; }

    public Double firstLineIndent() { return firstLineIndent; }
    public void firstLineIndent(Double v) { this.firstLineIndent = v; }

    public Double leftIndent() { return leftIndent; }
    public void leftIndent(Double v) { this.leftIndent = v; }

    public List<ResolvedRun> runs() { return runs; }
    public void addRun(ResolvedRun r) { runs.add(r); }
}
