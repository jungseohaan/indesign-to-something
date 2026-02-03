package kr.dogfoot.hwpxlib.tool.idmlconverter.idml;

import kr.dogfoot.hwpxlib.tool.equationconverter.idml.NPFontGlyphMap;

import java.util.ArrayList;
import java.util.List;

/**
 * IDML CharacterStyleRange — 동일 스타일의 문자 런.
 */
public class IDMLCharacterRun {
    private String appliedCharacterStyle;
    private String fontFamily;
    private Double fontSize;
    private String fillColor;
    private String fontStyle;
    private String position;
    private String content;
    private List<IDMLTextFrame> inlineFrames;

    public IDMLCharacterRun() {
        this.inlineFrames = new ArrayList<IDMLTextFrame>();
    }

    public String appliedCharacterStyle() { return appliedCharacterStyle; }
    public void appliedCharacterStyle(String v) { this.appliedCharacterStyle = v; }

    public String fontFamily() { return fontFamily; }
    public void fontFamily(String v) { this.fontFamily = v; }

    public Double fontSize() { return fontSize; }
    public void fontSize(Double v) { this.fontSize = v; }

    public String fillColor() { return fillColor; }
    public void fillColor(String v) { this.fillColor = v; }

    public String fontStyle() { return fontStyle; }
    public void fontStyle(String v) { this.fontStyle = v; }

    public String position() { return position; }
    public void position(String v) { this.position = v; }

    public String content() { return content; }
    public void content(String v) { this.content = v; }

    public List<IDMLTextFrame> inlineFrames() { return inlineFrames; }
    public void addInlineFrame(IDMLTextFrame frame) { this.inlineFrames.add(frame); }

    /**
     * NP 폰트인지 확인.
     */
    public boolean isNPFont() {
        return NPFontGlyphMap.extractNPFontName(appliedCharacterStyle) != null;
    }

    /**
     * NP 폰트 이름 추출.
     */
    public String npFontName() {
        return NPFontGlyphMap.extractNPFontName(appliedCharacterStyle);
    }

    public boolean isSubscript() {
        return "Subscript".equals(position);
    }

    public boolean isSuperscript() {
        return "Superscript".equals(position);
    }
}
