package kr.dogfoot.hwpxlib.tool.idmlconverter.intermediate;

/**
 * 중간 포맷 폰트 정의.
 */
public class IntermediateFontDef {
    private String id;
    private String familyName;
    private String styleName;
    private String fontType;        // "OTF", "TTF" etc.
    private String hwpxMappedName;  // HWPX에서 사용할 폰트명

    public String id() { return id; }
    public void id(String v) { this.id = v; }

    public String familyName() { return familyName; }
    public void familyName(String v) { this.familyName = v; }

    public String styleName() { return styleName; }
    public void styleName(String v) { this.styleName = v; }

    public String fontType() { return fontType; }
    public void fontType(String v) { this.fontType = v; }

    public String hwpxMappedName() { return hwpxMappedName; }
    public void hwpxMappedName(String v) { this.hwpxMappedName = v; }
}
