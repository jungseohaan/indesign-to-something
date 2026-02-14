package kr.dogfoot.hwpxlib.tool.idmlconverter.ast;

/**
 * 폰트 정의.
 */
public class ASTFontDef {
    private String fontId;
    private String fontFamily;
    private String fontType;

    public String fontId() { return fontId; }
    public void fontId(String v) { this.fontId = v; }

    public String fontFamily() { return fontFamily; }
    public void fontFamily(String v) { this.fontFamily = v; }

    public String fontType() { return fontType; }
    public void fontType(String v) { this.fontType = v; }
}
