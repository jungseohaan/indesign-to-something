package kr.dogfoot.hwpxlib.tool.idmlconverter.ast;

/**
 * 인라인 수식 — HWP 수식 스크립트를 포함.
 * BT수식M, NP 폰트 등 수식 폰트에서 추출된 수식.
 */
public class ASTEquation extends ASTInlineItem {
    private String hwpScript;
    private String sourceType; // "BT_FONT", "NP_FONT", "EH_FONT", etc.
    private String textColor;  // hex color (e.g., "#FFFFFF"), null이면 기본 검정

    public ASTEquation() {}

    public ASTEquation(String hwpScript, String sourceType) {
        this.hwpScript = hwpScript;
        this.sourceType = sourceType;
    }

    public ItemType itemType() { return ItemType.EQUATION; }

    public String hwpScript() { return hwpScript; }
    public void hwpScript(String v) { this.hwpScript = v; }

    public String sourceType() { return sourceType; }
    public void sourceType(String v) { this.sourceType = v; }

    public String textColor() { return textColor; }
    public void textColor(String v) { this.textColor = v; }
}
