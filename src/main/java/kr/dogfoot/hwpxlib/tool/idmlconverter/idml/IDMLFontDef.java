package kr.dogfoot.hwpxlib.tool.idmlconverter.idml;

/**
 * IDML 폰트 정의.
 */
public class IDMLFontDef {
    private String selfRef;
    private String fontFamily;
    private String fontStyleName;
    private String postScriptName;
    private String fontType;

    public String selfRef() { return selfRef; }
    public void selfRef(String selfRef) { this.selfRef = selfRef; }

    public String fontFamily() { return fontFamily; }
    public void fontFamily(String fontFamily) { this.fontFamily = fontFamily; }

    public String fontStyleName() { return fontStyleName; }
    public void fontStyleName(String fontStyleName) { this.fontStyleName = fontStyleName; }

    public String postScriptName() { return postScriptName; }
    public void postScriptName(String postScriptName) { this.postScriptName = postScriptName; }

    public String fontType() { return fontType; }
    public void fontType(String fontType) { this.fontType = fontType; }
}
