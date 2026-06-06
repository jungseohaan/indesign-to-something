package kr.dogfoot.hwpxlib.tool.idmlconverter.idml;

/**
 * IDML 스타일 정의 (ParagraphStyle 또는 CharacterStyle).
 */
public class IDMLStyleDef {
    private String selfRef;
    private String name;
    private String basedOn;
    private String fontFamily;
    private Double fontSize;
    private String fillColor;
    private String fontStyle;
    private Boolean bold;
    private Boolean italic;
    private String textAlignment;
    private Double firstLineIndent;
    private Double leftIndent;
    private Double rightIndent;
    private Double spaceBefore;
    private Double spaceAfter;
    private Double leading;
    private String leadingType;
    private Double autoLeading;
    private Double horizontalScale;
    private Double tracking;
    private Boolean underline;     // 밑줄
    private String underlineType;  // 밑줄 타입 ("StrokeStyle/$ID/Wavy" 등)
    private String underlineColor; // 밑줄 색상
    private Double underlineWeight; // 밑줄 두께 (points)
    private Double underlineOffset; // 밑줄 오프셋 (points)
    private Boolean strikeThrough; // 취소선
    private Boolean ruleBelowOn;   // 단락 아래선 (RuleBelow)
    private Double ruleAboveLineWeight; // 단락 위선 두께 (points)
    private Double ruleBelowLineWeight; // 단락 아래선 두께 (points)
    private String ruleAboveColor;      // 단락 위선 색상
    private String ruleBelowColor;      // 단락 아래선 색상
    private Double baselineShift;  // 기준선 이동 (points)
    private String capitalization;  // "SmallCaps", "AllCaps", "Normal"

    // 단락 분리 제어
    private Boolean keepWithNext;       // 다음 단락과 같은 페이지 유지
    private Boolean keepLinesTogether;  // 단락 내 줄 분리 방지
    private Boolean pageBreakBefore;    // 이 단락 앞에서 페이지 나눔

    // 어절 간격 (Word Spacing)
    private Double desiredWordSpacing;    // 기본값 100%
    private Double minimumWordSpacing;
    private Double maximumWordSpacing;

    // 두문자 (DropCap)
    private Integer dropCapLines;       // 두문자 줄 수 (0이면 비활성)
    private Integer dropCapCharacters;  // 두문자 글자 수

    // 탭 정지점 목록
    private java.util.List<TabStop> tabStops;

    // GREP 스타일 규칙 (단락 스타일에서만 사용)
    private java.util.List<GrepStyleRule> grepStyles;

    /**
     * GREP 스타일 규칙 — 단락 스타일의 AllGREPStyles에서 파싱.
     * InDesign이 렌더링 시 GREP 패턴에 매칭되는 문자에 문자 스타일을 동적 적용.
     */
    public static class GrepStyleRule {
        private String grepExpression;          // InDesign GREP 정규식
        private String appliedCharacterStyle;   // 적용할 문자 스타일 참조 ID

        public GrepStyleRule() {}
        public GrepStyleRule(String grepExpression, String appliedCharacterStyle) {
            this.grepExpression = grepExpression;
            this.appliedCharacterStyle = appliedCharacterStyle;
        }

        public String grepExpression() { return grepExpression; }
        public void grepExpression(String v) { this.grepExpression = v; }

        public String appliedCharacterStyle() { return appliedCharacterStyle; }
        public void appliedCharacterStyle(String v) { this.appliedCharacterStyle = v; }
    }

    /**
     * 탭 정지점 정보.
     */
    public static class TabStop {
        private Double position;   // 탭 위치 (points)
        private String alignment;  // "LeftAlign", "CenterAlign", "RightAlign", "CharacterAlign"
        private String leader;     // 탭 리더 문자

        public TabStop() {}
        public TabStop(Double position, String alignment, String leader) {
            this.position = position;
            this.alignment = alignment;
            this.leader = leader;
        }

        public Double position() { return position; }
        public void position(Double v) { this.position = v; }

        public String alignment() { return alignment; }
        public void alignment(String v) { this.alignment = v; }

        public String leader() { return leader; }
        public void leader(String v) { this.leader = v; }
    }

    public String selfRef() { return selfRef; }
    public void selfRef(String v) { this.selfRef = v; }

    public String name() { return name; }
    public void name(String v) { this.name = v; }

    public String basedOn() { return basedOn; }
    public void basedOn(String v) { this.basedOn = v; }

    public String fontFamily() { return fontFamily; }
    public void fontFamily(String v) { this.fontFamily = v; }

    public Double fontSize() { return fontSize; }
    public void fontSize(Double v) { this.fontSize = v; }

    public String fillColor() { return fillColor; }
    public void fillColor(String v) { this.fillColor = v; }

    public String fontStyle() { return fontStyle; }
    public void fontStyle(String v) { this.fontStyle = v; }

    public Boolean bold() { return bold; }
    public void bold(Boolean v) { this.bold = v; }

    public Boolean italic() { return italic; }
    public void italic(Boolean v) { this.italic = v; }

    public String textAlignment() { return textAlignment; }
    public void textAlignment(String v) { this.textAlignment = v; }

    public Double firstLineIndent() { return firstLineIndent; }
    public void firstLineIndent(Double v) { this.firstLineIndent = v; }

    public Double leftIndent() { return leftIndent; }
    public void leftIndent(Double v) { this.leftIndent = v; }

    public Double rightIndent() { return rightIndent; }
    public void rightIndent(Double v) { this.rightIndent = v; }

    public Double spaceBefore() { return spaceBefore; }
    public void spaceBefore(Double v) { this.spaceBefore = v; }

    public Double spaceAfter() { return spaceAfter; }
    public void spaceAfter(Double v) { this.spaceAfter = v; }

    public Double leading() { return leading; }
    public void leading(Double v) { this.leading = v; }

    public String leadingType() { return leadingType; }
    public void leadingType(String v) { this.leadingType = v; }

    public Double autoLeading() { return autoLeading; }
    public void autoLeading(Double v) { this.autoLeading = v; }

    public Double horizontalScale() { return horizontalScale; }
    public void horizontalScale(Double v) { this.horizontalScale = v; }

    public Double tracking() { return tracking; }
    public void tracking(Double v) { this.tracking = v; }

    public Boolean underline() { return underline; }
    public void underline(Boolean v) { this.underline = v; }

    public String underlineType() { return underlineType; }
    public void underlineType(String v) { this.underlineType = v; }

    public String underlineColor() { return underlineColor; }
    public void underlineColor(String v) { this.underlineColor = v; }

    public Double underlineWeight() { return underlineWeight; }
    public void underlineWeight(Double v) { this.underlineWeight = v; }

    public Double underlineOffset() { return underlineOffset; }
    public void underlineOffset(Double v) { this.underlineOffset = v; }

    public Boolean strikeThrough() { return strikeThrough; }
    public void strikeThrough(Boolean v) { this.strikeThrough = v; }

    public Boolean ruleBelowOn() { return ruleBelowOn; }
    public void ruleBelowOn(Boolean v) { this.ruleBelowOn = v; }

    public Double ruleAboveLineWeight() { return ruleAboveLineWeight; }
    public void ruleAboveLineWeight(Double v) { this.ruleAboveLineWeight = v; }

    public Double ruleBelowLineWeight() { return ruleBelowLineWeight; }
    public void ruleBelowLineWeight(Double v) { this.ruleBelowLineWeight = v; }

    public String ruleAboveColor() { return ruleAboveColor; }
    public void ruleAboveColor(String v) { this.ruleAboveColor = v; }

    public String ruleBelowColor() { return ruleBelowColor; }
    public void ruleBelowColor(String v) { this.ruleBelowColor = v; }

    public Double baselineShift() { return baselineShift; }
    public void baselineShift(Double v) { this.baselineShift = v; }

    public String capitalization() { return capitalization; }
    public void capitalization(String v) { this.capitalization = v; }

    public Boolean keepWithNext() { return keepWithNext; }
    public void keepWithNext(Boolean v) { this.keepWithNext = v; }

    public Boolean keepLinesTogether() { return keepLinesTogether; }
    public void keepLinesTogether(Boolean v) { this.keepLinesTogether = v; }

    public Boolean pageBreakBefore() { return pageBreakBefore; }
    public void pageBreakBefore(Boolean v) { this.pageBreakBefore = v; }

    public Integer dropCapLines() { return dropCapLines; }
    public void dropCapLines(Integer v) { this.dropCapLines = v; }

    public Integer dropCapCharacters() { return dropCapCharacters; }
    public void dropCapCharacters(Integer v) { this.dropCapCharacters = v; }

    public Double desiredWordSpacing() { return desiredWordSpacing; }
    public void desiredWordSpacing(Double v) { this.desiredWordSpacing = v; }

    public Double minimumWordSpacing() { return minimumWordSpacing; }
    public void minimumWordSpacing(Double v) { this.minimumWordSpacing = v; }

    public Double maximumWordSpacing() { return maximumWordSpacing; }
    public void maximumWordSpacing(Double v) { this.maximumWordSpacing = v; }

    public java.util.List<TabStop> tabStops() { return tabStops; }
    public void tabStops(java.util.List<TabStop> v) { this.tabStops = v; }

    public void addTabStop(TabStop ts) {
        if (this.tabStops == null) {
            this.tabStops = new java.util.ArrayList<>();
        }
        this.tabStops.add(ts);
    }

    public java.util.List<GrepStyleRule> grepStyles() { return grepStyles; }
    public void grepStyles(java.util.List<GrepStyleRule> v) { this.grepStyles = v; }

    public void addGrepStyle(GrepStyleRule rule) {
        if (this.grepStyles == null) {
            this.grepStyles = new java.util.ArrayList<>();
        }
        this.grepStyles.add(rule);
    }

    /**
     * selfRef에서 간단한 이름 추출.
     * "ParagraphStyle/Body" -> "Body"
     */
    public String simpleName() {
        if (selfRef == null) return name;
        int idx = selfRef.lastIndexOf('/');
        return idx >= 0 ? selfRef.substring(idx + 1) : selfRef;
    }
}
