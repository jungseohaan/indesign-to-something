package kr.dogfoot.hwpxlib.tool.idmlconverter.converter;

import kr.dogfoot.hwpxlib.object.content.header_xml.enumtype.HorizontalAlign2;
import kr.dogfoot.hwpxlib.object.content.header_xml.enumtype.LineType2;
import kr.dogfoot.hwpxlib.object.content.header_xml.enumtype.LineWidth;
import kr.dogfoot.hwpxlib.object.content.section_xml.enumtype.TextFlowSide;
import kr.dogfoot.hwpxlib.object.content.section_xml.enumtype.TextWrapMethod;
import kr.dogfoot.hwpxlib.object.content.section_xml.enumtype.VerticalAlign2;

/**
 * IDML 속성값 → HWPX enum 매핑 유틸리티.
 */
public final class HwpxEnumMapper {
    private HwpxEnumMapper() {}

    /**
     * IDML 정렬 → HWPX HorizontalAlign2.
     */
    public static HorizontalAlign2 mapAlignment(String alignment) {
        if (alignment == null) return HorizontalAlign2.JUSTIFY;
        switch (alignment.toLowerCase()) {
            case "left": case "leftalign": case "left_align":
                return HorizontalAlign2.LEFT;
            case "center": case "centeralign": case "center_align":
                return HorizontalAlign2.CENTER;
            case "right": case "rightalign": case "right_align":
                return HorizontalAlign2.RIGHT;
            // IDML LEFT_JUSTIFIED / CENTER_JUSTIFIED / RIGHT_JUSTIFIED / FULLY_JUSTIFIED 는
            // 모두 양끝맞춤. 마지막 줄 정렬 차이만 있을 뿐 HWPX 에선 단일 JUSTIFY.
            case "leftjustify": case "left_justified": case "leftjustified":
            case "centerjustify": case "center_justified": case "centerjustified":
            case "rightjustify": case "right_justified": case "rightjustified":
            case "justify": case "fulljustify": case "fullyjustified": case "fully_justified":
                return HorizontalAlign2.JUSTIFY;
            default:
                return HorizontalAlign2.JUSTIFY;
        }
    }

    /**
     * IDML VerticalJustification → HWPX VerticalAlign2.
     */
    public static VerticalAlign2 mapVerticalJustification(String vj) {
        if (vj == null) return VerticalAlign2.TOP;
        switch (vj.toLowerCase()) {
            case "centeralign": case "center": case "center_align": return VerticalAlign2.CENTER;
            case "bottomalign": case "bottom": case "bottom_align": return VerticalAlign2.BOTTOM;
            default: return VerticalAlign2.TOP;
        }
    }

    /**
     * IDML TextWrapMode → HWPX TextWrapMethod.
     */
    public static TextWrapMethod mapTextWrapMethod(String idmlMode) {
        if (idmlMode == null) return TextWrapMethod.TOP_AND_BOTTOM;
        switch (idmlMode) {
            case "BoundingBoxTextWrap": return TextWrapMethod.SQUARE;
            case "JumpObjectTextWrap": return TextWrapMethod.TOP_AND_BOTTOM;
            case "Contour": return TextWrapMethod.TIGHT;
            default: return TextWrapMethod.TOP_AND_BOTTOM;
        }
    }

    /**
     * IDML TextWrapSide → HWPX TextFlowSide.
     */
    public static TextFlowSide mapTextFlowSide(String idmlSide) {
        if (idmlSide == null) return TextFlowSide.BOTH_SIDES;
        switch (idmlSide) {
            case "LeftSide": return TextFlowSide.LEFT_ONLY;
            case "RightSide": return TextFlowSide.RIGHT_ONLY;
            case "LargestArea": return TextFlowSide.LARGEST_ONLY;
            default: return TextFlowSide.BOTH_SIDES;
        }
    }

    /**
     * IDML StrokeType → HWPX LineType2.
     */
    public static LineType2 strokeTypeToLineType(String strokeType) {
        if (strokeType == null) return LineType2.SOLID;
        switch (strokeType.toLowerCase()) {
            case "solid": return LineType2.SOLID;
            case "dashed": case "dash": return LineType2.DASH;
            case "dotted": case "dot": return LineType2.DOT;
            case "none": return LineType2.NONE;
            default: return LineType2.SOLID;
        }
    }

    /**
     * HWPUNIT 획 두께 → HWPX LineWidth enum.
     */
    public static LineWidth hwpunitToLineWidth(double hwpunit) {
        double mm = hwpunit * 25.4 / 7200.0;
        if (mm <= 0.1) return LineWidth.MM_0_1;
        if (mm <= 0.12) return LineWidth.MM_0_12;
        if (mm <= 0.15) return LineWidth.MM_0_15;
        if (mm <= 0.2) return LineWidth.MM_0_2;
        if (mm <= 0.25) return LineWidth.MM_0_25;
        if (mm <= 0.3) return LineWidth.MM_0_3;
        if (mm <= 0.4) return LineWidth.MM_0_4;
        if (mm <= 0.5) return LineWidth.MM_0_5;
        if (mm <= 0.6) return LineWidth.MM_0_6;
        if (mm <= 0.7) return LineWidth.MM_0_7;
        if (mm <= 1.0) return LineWidth.MM_1_0;
        if (mm <= 1.5) return LineWidth.MM_1_5;
        if (mm <= 2.0) return LineWidth.MM_2_0;
        if (mm <= 3.0) return LineWidth.MM_3_0;
        if (mm <= 4.0) return LineWidth.MM_4_0;
        return LineWidth.MM_5_0;
    }
}
