package kr.dogfoot.hwpxlib.tool.idmlconverter.resolved;

/**
 * resolved.json의 탭스톱 데이터.
 * InDesign DOM에서 paragraph 단위로 수집한 인라인 탭스톱.
 */
public class ResolvedTabStop {
    private Double position;    // pts
    private String alignment;   // "TabStopAlignment.LEFT_ALIGN" 등
    private String leader;

    public Double position() { return position; }
    public void position(Double v) { this.position = v; }

    public String alignment() { return alignment; }
    public void alignment(String v) { this.alignment = v; }

    public String leader() { return leader; }
    public void leader(String v) { this.leader = v; }
}
