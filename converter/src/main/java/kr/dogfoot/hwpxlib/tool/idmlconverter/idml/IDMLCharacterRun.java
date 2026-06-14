package kr.dogfoot.hwpxlib.tool.idmlconverter.idml;

import kr.dogfoot.hwpxlib.tool.equationconverter.idml.BTFontGlyphMap;
import kr.dogfoot.hwpxlib.tool.equationconverter.idml.EHFontGlyphMap;
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
    private Double fillTint;
    private String fontStyle;
    private String position;
    private String content;
    private List<IDMLTextFrame> inlineFrames;
    private List<InlineGraphic> inlineGraphics;
    private List<InlineAnchor> inlineAnchors; // FFFC 위치 기반 인터리빙 순서
    private Double tracking;
    private boolean grepMathFont;  // GREP 스타일에서 BT수식M이 동적 적용된 런
    private String grepFillColor;  // GREP 스타일에서 동적 적용된 FillColor (IDML 색상 참조명)
    private String grepAppliedCharStyle;  // GREP 스타일에서 동적 적용된 문자 스타일 참조 ID
    private Boolean underline;     // 밑줄 (IDML Underline="true")
    private String underlineType;  // 밑줄 타입 (IDML UnderlineType: "StrokeStyle/$ID/Wavy" 등)
    private Double underlineTint;  // 밑줄 틴트 % (IDML UnderlineTint)
    private String shadeColor;     // 문자 배경색/강조색 (IDML CharacterShading/Shading 계열)
    private Double shadeTint;      // 문자 배경색 틴트 %
    private Boolean strikeThrough; // 취소선 (IDML StrikeThru="true")
    private Double baselineShift;  // 기준선 이동 (points, 양수=위)
    private Double horizontalScale; // 장평 (%, 100=normal)
    private String capitalization;  // "SmallCaps", "AllCaps", "Normal" 등

    /**
     * 인라인 앵커 타입 — FFFC 위치에 TextFrame 또는 InlineGraphic 중 어느 것이 오는지 구분.
     */
    public enum InlineAnchorType { FRAME, GRAPHIC }

    /**
     * 인라인 앵커 — CharacterStyleRange 내 인라인 항목의 문서 순서를 추적한다.
     * TextFrame과 InlineGraphic 모두 FFFC 위치 기반 인터리빙에 참여하도록 한다.
     */
    public static class InlineAnchor {
        private final InlineAnchorType type;
        private final int index; // inlineFrames 또는 inlineGraphics 내 인덱스

        public InlineAnchor(InlineAnchorType type, int index) {
            this.type = type;
            this.index = index;
        }

        public InlineAnchorType type() { return type; }
        public int index() { return index; }
    }

    public IDMLCharacterRun() {
        this.inlineFrames = new ArrayList<IDMLTextFrame>();
        this.inlineGraphics = new ArrayList<InlineGraphic>();
        this.inlineAnchors = new ArrayList<InlineAnchor>();
    }

    /**
     * IDML 인라인 그래픽 (Rectangle, Polygon, Group 등 텍스트 내 인라인 객체).
     */
    public static class InlineGraphic {
        private String selfId;
        private String type;           // "rectangle", "polygon", "ellipse", "group", etc.
        private double widthPoints;
        private double heightPoints;
        private double[] geometricBounds;  // [top, left, bottom, right] — 프레임의 로컬 좌표
        private double[] itemTransform;
        private String embeddedText;
        private String embeddedTextFont;
        private List<InlineGraphic> childGraphics;       // Group 내 자식 그래픽
        private List<IDMLTextFrame> childTextFrames;     // Group 내 자식 텍스트프레임

        // 벡터 도형 정보 (Polygon/Rectangle 글리프 아웃라인 등, 래스터화용)
        private IDMLVectorShape vectorShape;

        // 앵커/래핑 속성 (AnchoredObjectSetting, TextWrapPreference)
        private String anchoredPosition;    // "InlinePosition", "AboveLine", "Anchored"
        private String textWrapMode;        // "None", "BoundingBoxTextWrap", "JumpObjectTextWrap"
        private String textWrapSide;        // "BothSides", "LeftSide", "RightSide", "LargestArea"
        private double textWrapTop, textWrapLeft, textWrapBottom, textWrapRight; // TextWrapOffset (points)

        // 이미지 링크 정보 (Rectangle/Polygon/Oval 내부에 Image가 있는 경우)
        private String linkResourceURI;
        private String linkResourceFormat;
        private String linkStoredState;
        private double[] imageTransform;    // 이미지의 transform (클리핑용)
        private double[] graphicBounds;     // 원본 이미지 크기
        private String embeddedContents;    // 내장 이미지 base64 데이터

        public InlineGraphic() {
            this.childGraphics = new ArrayList<>();
            this.childTextFrames = new ArrayList<>();
        }

        public String selfId() { return selfId; }
        public void selfId(String v) { this.selfId = v; }

        public String type() { return type; }
        public void type(String v) { this.type = v; }

        public double widthPoints() { return widthPoints; }
        public void widthPoints(double v) { this.widthPoints = v; }

        public double heightPoints() { return heightPoints; }
        public void heightPoints(double v) { this.heightPoints = v; }

        public double[] geometricBounds() { return geometricBounds; }
        public void geometricBounds(double[] v) { this.geometricBounds = v; }

        public double[] itemTransform() { return itemTransform; }
        public void itemTransform(double[] v) { this.itemTransform = v; }

        public String embeddedText() { return embeddedText; }
        public void embeddedText(String v) { this.embeddedText = v; }

        public String embeddedTextFont() { return embeddedTextFont; }
        public void embeddedTextFont(String v) { this.embeddedTextFont = v; }

        public List<InlineGraphic> childGraphics() { return childGraphics; }
        public void addChildGraphic(InlineGraphic g) { this.childGraphics.add(g); }

        public List<IDMLTextFrame> childTextFrames() { return childTextFrames; }
        public void addChildTextFrame(IDMLTextFrame tf) { this.childTextFrames.add(tf); }

        public String linkResourceURI() { return linkResourceURI; }
        public void linkResourceURI(String v) { this.linkResourceURI = v; }

        public String linkResourceFormat() { return linkResourceFormat; }
        public void linkResourceFormat(String v) { this.linkResourceFormat = v; }

        public String linkStoredState() { return linkStoredState; }
        public void linkStoredState(String v) { this.linkStoredState = v; }

        public double[] imageTransform() { return imageTransform; }
        public void imageTransform(double[] v) { this.imageTransform = v; }

        public double[] graphicBounds() { return graphicBounds; }
        public void graphicBounds(double[] v) { this.graphicBounds = v; }

        public String embeddedContents() { return embeddedContents; }
        public void embeddedContents(String v) { this.embeddedContents = v; }

        public boolean hasEmbeddedContents() {
            return embeddedContents != null && !embeddedContents.isEmpty();
        }

        public boolean hasImage() {
            return (linkResourceURI != null && !linkResourceURI.isEmpty())
                    || hasEmbeddedContents();
        }

        public String anchoredPosition() { return anchoredPosition; }
        public void anchoredPosition(String v) { this.anchoredPosition = v; }

        public String textWrapMode() { return textWrapMode; }
        public void textWrapMode(String v) { this.textWrapMode = v; }

        public String textWrapSide() { return textWrapSide; }
        public void textWrapSide(String v) { this.textWrapSide = v; }

        public double textWrapTop() { return textWrapTop; }
        public void textWrapTop(double v) { this.textWrapTop = v; }
        public double textWrapLeft() { return textWrapLeft; }
        public void textWrapLeft(double v) { this.textWrapLeft = v; }
        public double textWrapBottom() { return textWrapBottom; }
        public void textWrapBottom(double v) { this.textWrapBottom = v; }
        public double textWrapRight() { return textWrapRight; }
        public void textWrapRight(double v) { this.textWrapRight = v; }

        public IDMLVectorShape vectorShape() { return vectorShape; }
        public void vectorShape(IDMLVectorShape v) { this.vectorShape = v; }
        public boolean hasVectorShape() { return vectorShape != null; }

        private String appliedObjectStyle;
        public String appliedObjectStyle() { return appliedObjectStyle; }
        public void appliedObjectStyle(String v) { this.appliedObjectStyle = v; }

        // Group 레벨 색상 (자식 도형이 명시적 색상 없을 때 폴백용)
        private String groupStrokeColor;
        private String groupFillColor;
        private double groupFillTint = 100;
        private double groupStrokeTint = 100;
        private double groupStrokeWeight = 0;
        public String groupStrokeColor() { return groupStrokeColor; }
        public void groupStrokeColor(String v) { this.groupStrokeColor = v; }
        public String groupFillColor() { return groupFillColor; }
        public void groupFillColor(String v) { this.groupFillColor = v; }
        public double groupFillTint() { return groupFillTint; }
        public void groupFillTint(double v) { this.groupFillTint = v; }
        public double groupStrokeTint() { return groupStrokeTint; }
        public void groupStrokeTint(double v) { this.groupStrokeTint = v; }
        public double groupStrokeWeight() { return groupStrokeWeight; }
        public void groupStrokeWeight(double v) { this.groupStrokeWeight = v; }
    }

    public String appliedCharacterStyle() { return appliedCharacterStyle; }
    public void appliedCharacterStyle(String v) { this.appliedCharacterStyle = v; }

    public String fontFamily() { return fontFamily; }
    public void fontFamily(String v) { this.fontFamily = v; }

    public Double fontSize() { return fontSize; }
    public void fontSize(Double v) { this.fontSize = v; }

    public String fillColor() { return fillColor; }
    public void fillColor(String v) { this.fillColor = v; }

    public Double fillTint() { return fillTint; }
    public void fillTint(Double v) { this.fillTint = v; }

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

    public List<InlineAnchor> inlineAnchors() { return inlineAnchors; }
    public void addInlineAnchor(InlineAnchorType type, int index) {
        this.inlineAnchors.add(new InlineAnchor(type, index));
    }

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

    /**
     * BT수식M 폰트인지 확인 (CharacterStyle 또는 fontFamily 기반).
     */
    public boolean isBTFont() {
        return BTFontGlyphMap.isBTFontStyle(appliedCharacterStyle)
                || BTFontGlyphMap.isBTFontFamily(fontFamily);
    }

    /**
     * EH 수식 폰트인지 확인 (fontFamily 또는 CharacterStyle 기반).
     */
    public boolean isEHFont() {
        return EHFontGlyphMap.isEHFontFamily(fontFamily)
                || EHFontGlyphMap.isEHFontStyle(appliedCharacterStyle);
    }

    /**
     * 수식 폰트인지 확인 (NP, BT, EH).
     */
    public boolean isMathFont() {
        return isNPFont() || isBTFont() || isEHFont();
    }

    public boolean isSubscript() {
        return "Subscript".equals(position);
    }

    public boolean isSuperscript() {
        return "Superscript".equals(position);
    }

    public boolean grepMathFont() { return grepMathFont; }
    public void grepMathFont(boolean v) { this.grepMathFont = v; }
    public String grepFillColor() { return grepFillColor; }
    public void grepFillColor(String v) { this.grepFillColor = v; }

    public String grepAppliedCharStyle() { return grepAppliedCharStyle; }
    public void grepAppliedCharStyle(String v) { this.grepAppliedCharStyle = v; }

    public Boolean underline() { return underline; }
    public void underline(Boolean v) { this.underline = v; }

    public String underlineType() { return underlineType; }
    public void underlineType(String v) { this.underlineType = v; }

    public Double underlineTint() { return underlineTint; }
    public void underlineTint(Double v) { this.underlineTint = v; }

    public String shadeColor() { return shadeColor; }
    public void shadeColor(String v) { this.shadeColor = v; }

    public Double shadeTint() { return shadeTint; }
    public void shadeTint(Double v) { this.shadeTint = v; }

    public Boolean strikeThrough() { return strikeThrough; }
    public void strikeThrough(Boolean v) { this.strikeThrough = v; }

    public Double baselineShift() { return baselineShift; }
    public void baselineShift(Double v) { this.baselineShift = v; }

    public Double horizontalScale() { return horizontalScale; }
    public void horizontalScale(Double v) { this.horizontalScale = v; }

    public String capitalization() { return capitalization; }
    public void capitalization(String v) { this.capitalization = v; }
}
