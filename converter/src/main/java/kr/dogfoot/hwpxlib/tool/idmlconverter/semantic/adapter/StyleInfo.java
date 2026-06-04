package kr.dogfoot.hwpxlib.tool.idmlconverter.semantic.adapter;

/**
 * 스타일 정보 (TS {@code StyleInfo} 와 1:1).
 */
public class StyleInfo {
    public enum StyleType { paragraph, character }

    public String styleId = "";
    public String styleName;
    public StyleType type = StyleType.paragraph;
    public String basedOnStyleRef;
    public String fontFamily;
    public String fontStyle;
    public Integer fontSize;
    public Boolean bold;
    public Boolean italic;
    public String alignment;
    public String textColor;
}
