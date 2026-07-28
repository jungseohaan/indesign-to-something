package kr.dogfoot.hwpxlib.tool.idmlconverter.ast;

/**
 * 인라인 수식 — HWP 수식 스크립트를 포함.
 * BT수식M, NP 폰트 등 수식 폰트에서 추출된 수식.
 */
public class ASTEquation extends ASTInlineItem {
    private String hwpScript;
    private String sourceType; // "BT_FONT", "NP_FONT", "EH_FONT", etc.
    private String textColor;  // hex color (e.g., "#FFFFFF"), null이면 기본 검정
    private Integer preferredBaseUnit; // 본문형 수식이 따라야 할 목표 크기 (hwpunit)
    private String preferredFontFamily; // 본문형 수식이 따라야 할 목표 폰트 패밀리
    private boolean sourceItalic; // 원본 런이 이탤릭이었는지 (수학 변수 vs 이니셜 구분, SPEC-079)
    private boolean sourceUpright; // 원본이 명시적 직립(GREP 상부자 비이탤릭 규칙) — rm 방출 (SPEC-085)
    private Integer sourceObjectId; // 이 수식을 materialize한 source ownership object

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

    public Integer preferredBaseUnit() { return preferredBaseUnit; }
    public void preferredBaseUnit(Integer v) { this.preferredBaseUnit = v; }

    public String preferredFontFamily() { return preferredFontFamily; }
    public void preferredFontFamily(String v) { this.preferredFontFamily = v; }

    public boolean sourceItalic() { return sourceItalic; }
    public void sourceItalic(boolean v) { this.sourceItalic = v; }
    public boolean sourceUpright() { return sourceUpright; }
    public void sourceUpright(boolean v) { this.sourceUpright = v; }

    public Integer sourceObjectId() { return sourceObjectId; }
    public void sourceObjectId(Integer v) { this.sourceObjectId = v; }
}
