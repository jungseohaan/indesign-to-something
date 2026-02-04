package kr.dogfoot.hwpxlib.tool.idmlconverter.intermediate;

import java.util.ArrayList;
import java.util.List;

/**
 * 중간 포맷 스타일 정의 (paragraph 또는 character).
 * HWPUNIT 단위.
 */
public class IntermediateStyleDef {
    /**
     * 탭 정지점 정보 (HWPUNIT 단위).
     */
    public static class TabStop {
        private long position;       // 탭 위치 (HWPUNIT)
        private String alignment;    // "left", "center", "right", "decimal"
        private String leader;       // 탭 리더 문자

        public TabStop() {}
        public TabStop(long position, String alignment, String leader) {
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

    private String id;
    private String name;
    private String type;        // "paragraph" or "character"
    private String basedOn;
    private String fontFamily;
    private Integer fontSizeHwpunits;
    private String textColor;
    private Boolean bold;
    private Boolean italic;
    private String alignment;   // "left", "center", "right", "justify"
    private Long firstLineIndent;
    private Long leftMargin;
    private Long rightMargin;
    private Long spaceBefore;
    private Long spaceAfter;
    private Integer lineSpacingPercent;
    private String lineSpacingType;  // "percent", "fixed"
    private Short letterSpacing;     // 자간 (HWPX spacing, -50 ~ 50 범위)
    private Integer wordSpacingPercent; // 어절 간격 (퍼센트, 기본값 100)
    private List<TabStop> tabStops;     // 탭 정지점 목록

    public String id() { return id; }
    public void id(String v) { this.id = v; }

    public String name() { return name; }
    public void name(String v) { this.name = v; }

    public String type() { return type; }
    public void type(String v) { this.type = v; }

    public String basedOn() { return basedOn; }
    public void basedOn(String v) { this.basedOn = v; }

    public String fontFamily() { return fontFamily; }
    public void fontFamily(String v) { this.fontFamily = v; }

    public Integer fontSizeHwpunits() { return fontSizeHwpunits; }
    public void fontSizeHwpunits(Integer v) { this.fontSizeHwpunits = v; }

    public String textColor() { return textColor; }
    public void textColor(String v) { this.textColor = v; }

    public Boolean bold() { return bold; }
    public void bold(Boolean v) { this.bold = v; }

    public Boolean italic() { return italic; }
    public void italic(Boolean v) { this.italic = v; }

    public String alignment() { return alignment; }
    public void alignment(String v) { this.alignment = v; }

    public Long firstLineIndent() { return firstLineIndent; }
    public void firstLineIndent(Long v) { this.firstLineIndent = v; }

    public Long leftMargin() { return leftMargin; }
    public void leftMargin(Long v) { this.leftMargin = v; }

    public Long rightMargin() { return rightMargin; }
    public void rightMargin(Long v) { this.rightMargin = v; }

    public Long spaceBefore() { return spaceBefore; }
    public void spaceBefore(Long v) { this.spaceBefore = v; }

    public Long spaceAfter() { return spaceAfter; }
    public void spaceAfter(Long v) { this.spaceAfter = v; }

    public Integer lineSpacingPercent() { return lineSpacingPercent; }
    public void lineSpacingPercent(Integer v) { this.lineSpacingPercent = v; }

    public String lineSpacingType() { return lineSpacingType; }
    public void lineSpacingType(String v) { this.lineSpacingType = v; }

    public Short letterSpacing() { return letterSpacing; }
    public void letterSpacing(Short v) { this.letterSpacing = v; }

    public Integer wordSpacingPercent() { return wordSpacingPercent; }
    public void wordSpacingPercent(Integer v) { this.wordSpacingPercent = v; }

    public List<TabStop> tabStops() { return tabStops; }
    public void tabStops(List<TabStop> v) { this.tabStops = v; }

    public void addTabStop(TabStop ts) {
        if (this.tabStops == null) {
            this.tabStops = new ArrayList<>();
        }
        this.tabStops.add(ts);
    }
}
