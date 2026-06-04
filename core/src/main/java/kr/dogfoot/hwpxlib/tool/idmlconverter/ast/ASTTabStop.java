package kr.dogfoot.hwpxlib.tool.idmlconverter.ast;

/**
 * 탭 정지점.
 * 좌표 단위: HWPUNIT.
 */
public class ASTTabStop {
    private long position;
    private String alignment;  // "left", "center", "right", "decimal"
    private String leader;     // null, ".", "-", "_"

    public ASTTabStop() {}

    public ASTTabStop(long position, String alignment, String leader) {
        this.position = position;
        this.alignment = alignment;
        this.leader = leader;
    }

    public long position() { return position; }
    public void position(long v) { this.position = v; }

    public String alignment() { return alignment; }
    public void alignment(String v) { this.alignment = v; }

    public String leader() { return leader; }
    public void leader(String v) { this.leader = v; }
}
