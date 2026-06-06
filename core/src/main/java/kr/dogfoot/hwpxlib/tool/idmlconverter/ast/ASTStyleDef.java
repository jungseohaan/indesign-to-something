package kr.dogfoot.hwpxlib.tool.idmlconverter.ast;

/**
 * AST 스타일 정의 — 단락/문자 스타일 속성을 담는다.
 */
public class ASTStyleDef {
    private String styleId;
    private String styleName;
    private String basedOnStyleRef;

    // 단락 속성
    private String alignment;
    private Long firstLineIndent;
    private Long leftMargin;
    private Long rightMargin;
    private Long spaceBefore;
    private Long spaceAfter;
    private Integer lineSpacing;
    private String lineSpacingType; // "percent" or "fixed"

    // 문자 속성
    private String fontFamily;
    private String fontStyle;
    private Integer fontSizeHwpunits;
    private String textColor;
    private Short letterSpacing;
    private Boolean bold;
    private Boolean italic;
    private Short horizontalScale;    // 장평 (%)
    private Double wordSpacing;       // 어간 desired (%)
    private Double autoLeading;       // 자동 줄간격 비율 (%)
    private Boolean underline;        // 밑줄
    private String underlineType;     // 밑줄 타입 ("WAVE" 등)
    private String underlineColor;    // 밑줄 색상
    private Boolean strikeThrough;    // 취소선

    // 두문자 (DropCap)
    private Integer dropCapLines;       // 두문자 줄 수 (0이면 비활성)
    private Integer dropCapCharacters;  // 두문자 글자 수

    // 단락 속성 — 탭 정지점
    private java.util.List<ASTTabStop> tabStops;

    public String styleId() { return styleId; }
    public void styleId(String v) { this.styleId = v; }

    public String styleName() { return styleName; }
    public void styleName(String v) { this.styleName = v; }

    public String basedOnStyleRef() { return basedOnStyleRef; }
    public void basedOnStyleRef(String v) { this.basedOnStyleRef = v; }

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

    public Integer lineSpacing() { return lineSpacing; }
    public void lineSpacing(Integer v) { this.lineSpacing = v; }

    public String lineSpacingType() { return lineSpacingType; }
    public void lineSpacingType(String v) { this.lineSpacingType = v; }

    public String fontFamily() { return fontFamily; }
    public void fontFamily(String v) { this.fontFamily = v; }

    public String fontStyle() { return fontStyle; }
    public void fontStyle(String v) { this.fontStyle = v; }

    public Integer fontSizeHwpunits() { return fontSizeHwpunits; }
    public void fontSizeHwpunits(Integer v) { this.fontSizeHwpunits = v; }

    public String textColor() { return textColor; }
    public void textColor(String v) { this.textColor = v; }

    public Short letterSpacing() { return letterSpacing; }
    public void letterSpacing(Short v) { this.letterSpacing = v; }

    public Boolean bold() { return bold; }
    public void bold(Boolean v) { this.bold = v; }

    public Boolean italic() { return italic; }
    public void italic(Boolean v) { this.italic = v; }

    public Short horizontalScale() { return horizontalScale; }
    public void horizontalScale(Short v) { this.horizontalScale = v; }

    public Double wordSpacing() { return wordSpacing; }
    public void wordSpacing(Double v) { this.wordSpacing = v; }

    public Double autoLeading() { return autoLeading; }
    public void autoLeading(Double v) { this.autoLeading = v; }

    public Boolean underline() { return underline; }
    public void underline(Boolean v) { this.underline = v; }

    public String underlineType() { return underlineType; }
    public void underlineType(String v) { this.underlineType = v; }

    public String underlineColor() { return underlineColor; }
    public void underlineColor(String v) { this.underlineColor = v; }

    public Boolean strikeThrough() { return strikeThrough; }
    public void strikeThrough(Boolean v) { this.strikeThrough = v; }

    public Integer dropCapLines() { return dropCapLines; }
    public void dropCapLines(Integer v) { this.dropCapLines = v; }

    public Integer dropCapCharacters() { return dropCapCharacters; }
    public void dropCapCharacters(Integer v) { this.dropCapCharacters = v; }

    public java.util.List<ASTTabStop> tabStops() { return tabStops; }
    public void tabStops(java.util.List<ASTTabStop> v) { this.tabStops = v; }

    public boolean hasTabStops() {
        return tabStops != null && !tabStops.isEmpty();
    }
}
