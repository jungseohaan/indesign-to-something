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
    private List<InlineGraphic> inlineGraphics;
    private Double tracking;

    public IDMLCharacterRun() {
        this.inlineFrames = new ArrayList<IDMLTextFrame>();
        this.inlineGraphics = new ArrayList<InlineGraphic>();
    }

    /**
     * IDML 인라인 그래픽 (Rectangle, Polygon, Group 등 텍스트 내 인라인 객체).
     */
    public static class InlineGraphic {
        private String selfId;
        private String type;           // "rectangle", "polygon", "ellipse", "group", etc.
        private double widthPoints;
        private double heightPoints;
        private double[] itemTransform;
        private String embeddedText;
        private String embeddedTextFont;

        public String selfId() { return selfId; }
        public void selfId(String v) { this.selfId = v; }

        public String type() { return type; }
        public void type(String v) { this.type = v; }

        public double widthPoints() { return widthPoints; }
        public void widthPoints(double v) { this.widthPoints = v; }

        public double heightPoints() { return heightPoints; }
        public void heightPoints(double v) { this.heightPoints = v; }

        public double[] itemTransform() { return itemTransform; }
        public void itemTransform(double[] v) { this.itemTransform = v; }

        public String embeddedText() { return embeddedText; }
        public void embeddedText(String v) { this.embeddedText = v; }

        public String embeddedTextFont() { return embeddedTextFont; }
        public void embeddedTextFont(String v) { this.embeddedTextFont = v; }
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

    public List<InlineGraphic> inlineGraphics() { return inlineGraphics; }
    public void addInlineGraphic(InlineGraphic graphic) { this.inlineGraphics.add(graphic); }

    public Double tracking() { return tracking; }
    public void tracking(Double v) { this.tracking = v; }

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
