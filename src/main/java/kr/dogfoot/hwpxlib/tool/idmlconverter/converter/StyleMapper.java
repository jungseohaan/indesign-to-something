package kr.dogfoot.hwpxlib.tool.idmlconverter.converter;

import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLStyleDef;
import kr.dogfoot.hwpxlib.tool.idmlconverter.intermediate.IntermediateStyleDef;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * IDML 스타일 → Intermediate 스타일 변환.
 */
public class StyleMapper {

    /**
     * IDML ParagraphStyle → IntermediateStyleDef (paragraph).
     */
    public static IntermediateStyleDef mapParagraphStyle(IDMLStyleDef idmlStyle, String id,
                                                          Map<String, String> colorMap) {
        IntermediateStyleDef style = new IntermediateStyleDef();
        style.id(id);
        style.name(idmlStyle.simpleName());
        style.type("paragraph");
        style.basedOn(idmlStyle.basedOn());
        style.fontFamily(idmlStyle.fontFamily());

        if (idmlStyle.fontSize() != null) {
            style.fontSizeHwpunits(CoordinateConverter.fontSizeToHeight(idmlStyle.fontSize()));
        }

        style.textColor(resolveColor(idmlStyle.fillColor(), colorMap));
        style.bold(idmlStyle.bold());
        style.italic(idmlStyle.italic());
        style.alignment(mapAlignment(idmlStyle.textAlignment()));

        if (idmlStyle.firstLineIndent() != null) {
            style.firstLineIndent(CoordinateConverter.pointsToHwpunits(idmlStyle.firstLineIndent()));
        }
        if (idmlStyle.leftIndent() != null) {
            style.leftMargin(CoordinateConverter.pointsToHwpunits(idmlStyle.leftIndent()));
        }
        if (idmlStyle.rightIndent() != null) {
            style.rightMargin(CoordinateConverter.pointsToHwpunits(idmlStyle.rightIndent()));
        }
        if (idmlStyle.spaceBefore() != null) {
            style.spaceBefore(CoordinateConverter.pointsToHwpunits(idmlStyle.spaceBefore()));
        }
        if (idmlStyle.spaceAfter() != null) {
            style.spaceAfter(CoordinateConverter.pointsToHwpunits(idmlStyle.spaceAfter()));
        }

        // Leading → lineSpacing
        if (idmlStyle.leading() != null) {
            style.lineSpacingType("fixed");
            style.lineSpacingPercent((int) CoordinateConverter.pointsToHwpunits(idmlStyle.leading()));
        } else if ("Auto".equals(idmlStyle.leadingType())) {
            style.lineSpacingType("percent");
            double autoLeading = idmlStyle.autoLeading() != null ? idmlStyle.autoLeading() : 120;
            style.lineSpacingPercent((int) Math.round(autoLeading));
        }

        // Tracking → letterSpacing
        if (idmlStyle.tracking() != null) {
            style.letterSpacing(trackingToLetterSpacing(idmlStyle.tracking()));
        }

        // Word Spacing (DesiredWordSpacing 사용)
        if (idmlStyle.desiredWordSpacing() != null) {
            style.wordSpacingPercent((int) Math.round(idmlStyle.desiredWordSpacing()));
        }

        // Tab Stops
        if (idmlStyle.tabStops() != null && !idmlStyle.tabStops().isEmpty()) {
            for (IDMLStyleDef.TabStop idmlTab : idmlStyle.tabStops()) {
                if (idmlTab.position() != null) {
                    long posHwpunits = CoordinateConverter.pointsToHwpunits(idmlTab.position());
                    String alignment = mapTabAlignment(idmlTab.alignment());
                    style.addTabStop(new IntermediateStyleDef.TabStop(
                            posHwpunits, alignment, idmlTab.leader()));
                }
            }
        }

        return style;
    }

    /**
     * IDML CharacterStyle → IntermediateStyleDef (character).
     */
    public static IntermediateStyleDef mapCharacterStyle(IDMLStyleDef idmlStyle, String id,
                                                          Map<String, String> colorMap) {
        IntermediateStyleDef style = new IntermediateStyleDef();
        style.id(id);
        style.name(idmlStyle.simpleName());
        style.type("character");
        style.basedOn(idmlStyle.basedOn());
        style.fontFamily(idmlStyle.fontFamily());

        if (idmlStyle.fontSize() != null) {
            style.fontSizeHwpunits(CoordinateConverter.fontSizeToHeight(idmlStyle.fontSize()));
        }

        style.textColor(resolveColor(idmlStyle.fillColor(), colorMap));
        style.bold(idmlStyle.bold());
        style.italic(idmlStyle.italic());

        // Tracking → letterSpacing
        if (idmlStyle.tracking() != null) {
            style.letterSpacing(trackingToLetterSpacing(idmlStyle.tracking()));
        }

        return style;
    }

    /**
     * IDML ParagraphStyle/CharacterStyle 맵을 Intermediate 맵으로 변환한다.
     * @return IDML selfRef → Intermediate id 매핑
     */
    public static Map<String, String> buildParagraphStyleMap(
            Map<String, IDMLStyleDef> idmlStyles, Map<String, String> colorMap,
            java.util.List<IntermediateStyleDef> outStyles) {
        Map<String, String> refToId = new LinkedHashMap<String, String>();
        int idx = 0;
        for (Map.Entry<String, IDMLStyleDef> entry : idmlStyles.entrySet()) {
            String id = "pstyle_" + idx;
            // 스타일 상속 해결 후 매핑
            IDMLStyleDef resolved = resolveStyleInheritance(entry.getValue(), idmlStyles);
            IntermediateStyleDef mapped = mapParagraphStyle(resolved, id, colorMap);
            outStyles.add(mapped);
            refToId.put(entry.getKey(), id);
            idx++;
        }
        return refToId;
    }

    public static Map<String, String> buildCharacterStyleMap(
            Map<String, IDMLStyleDef> idmlStyles, Map<String, String> colorMap,
            java.util.List<IntermediateStyleDef> outStyles) {
        Map<String, String> refToId = new LinkedHashMap<String, String>();
        int idx = 0;
        for (Map.Entry<String, IDMLStyleDef> entry : idmlStyles.entrySet()) {
            String id = "cstyle_" + idx;
            // 스타일 상속 해결 후 매핑
            IDMLStyleDef resolved = resolveStyleInheritance(entry.getValue(), idmlStyles);
            IntermediateStyleDef mapped = mapCharacterStyle(resolved, id, colorMap);
            outStyles.add(mapped);
            refToId.put(entry.getKey(), id);
            idx++;
        }
        return refToId;
    }

    /**
     * 부모 스타일(basedOn)에서 상속된 속성을 해결한다.
     */
    private static IDMLStyleDef resolveStyleInheritance(IDMLStyleDef style, Map<String, IDMLStyleDef> allStyles) {
        if (style.basedOn() == null || style.basedOn().isEmpty()) {
            return style;
        }

        // 부모 스타일 찾기
        IDMLStyleDef parent = allStyles.get(style.basedOn());
        if (parent == null) {
            return style;
        }

        // 부모 스타일도 상속 해결 (재귀)
        parent = resolveStyleInheritance(parent, allStyles);

        // 새 스타일 객체 생성하여 병합
        IDMLStyleDef merged = new IDMLStyleDef();
        merged.selfRef(style.selfRef());
        merged.name(style.name());
        merged.basedOn(style.basedOn());

        // 부모 속성 먼저 복사, 자식 속성으로 덮어쓰기
        merged.fontFamily(style.fontFamily() != null ? style.fontFamily() : parent.fontFamily());
        merged.fontSize(style.fontSize() != null ? style.fontSize() : parent.fontSize());
        merged.fillColor(style.fillColor() != null ? style.fillColor() : parent.fillColor());
        merged.fontStyle(style.fontStyle() != null ? style.fontStyle() : parent.fontStyle());
        merged.bold(style.bold() != null ? style.bold() : parent.bold());
        merged.italic(style.italic() != null ? style.italic() : parent.italic());
        merged.textAlignment(style.textAlignment() != null ? style.textAlignment() : parent.textAlignment());
        merged.firstLineIndent(style.firstLineIndent() != null ? style.firstLineIndent() : parent.firstLineIndent());
        merged.leftIndent(style.leftIndent() != null ? style.leftIndent() : parent.leftIndent());
        merged.rightIndent(style.rightIndent() != null ? style.rightIndent() : parent.rightIndent());
        merged.spaceBefore(style.spaceBefore() != null ? style.spaceBefore() : parent.spaceBefore());
        merged.spaceAfter(style.spaceAfter() != null ? style.spaceAfter() : parent.spaceAfter());
        merged.leading(style.leading() != null ? style.leading() : parent.leading());
        merged.leadingType(style.leadingType() != null ? style.leadingType() : parent.leadingType());
        merged.autoLeading(style.autoLeading() != null ? style.autoLeading() : parent.autoLeading());
        merged.tracking(style.tracking() != null ? style.tracking() : parent.tracking());
        merged.desiredWordSpacing(style.desiredWordSpacing() != null ? style.desiredWordSpacing() : parent.desiredWordSpacing());

        // 탭 정지점 상속
        if (style.tabStops() != null && !style.tabStops().isEmpty()) {
            merged.tabStops(style.tabStops());
        } else if (parent.tabStops() != null) {
            merged.tabStops(parent.tabStops());
        }

        return merged;
    }

    /**
     * IDML Justification → intermediate alignment string.
     */
    public static String mapAlignment(String idmlJustification) {
        if (idmlJustification == null) return null;
        switch (idmlJustification) {
            case "LeftAlign": return "left";
            case "CenterAlign": return "center";
            case "RightAlign": return "right";
            case "LeftJustified":
            case "CenterJustified":
            case "RightJustified":
            case "FullyJustified": return "justify";
            default: return "left";
        }
    }

    /**
     * IDML 색상 참조를 #RRGGBB로 변환.
     */
    public static String resolveColor(String fillColor, Map<String, String> colorMap) {
        if (fillColor == null || fillColor.isEmpty()) return null;
        if (fillColor.contains("None") || fillColor.contains("[None]")) return null;
        if (fillColor.startsWith("#")) {
            return fillColor;
        }

        // 직접 조회
        String hex = colorMap.get(fillColor);
        if (hex != null) {
            return hex;
        }

        // "Color/..." 형식에서 키만 추출하여 재시도
        if (fillColor.startsWith("Color/")) {
            String colorKey = fillColor.substring(6);  // "Color/" 제거
            for (Map.Entry<String, String> entry : colorMap.entrySet()) {
                if (entry.getKey().endsWith("/" + colorKey) || entry.getKey().equals(colorKey)) {
                    return entry.getValue();
                }
            }
        }

        // 기본 색상 이름으로 처리
        String upper = fillColor.toUpperCase();
        if (upper.contains("BLACK")) return "#000000";
        if (upper.contains("WHITE")) return "#FFFFFF";
        if (upper.contains("RED")) return "#FF0000";
        if (upper.contains("GREEN")) return "#00FF00";
        if (upper.contains("BLUE")) return "#0000FF";
        if (upper.contains("YELLOW")) return "#FFFF00";
        if (upper.contains("CYAN")) return "#00FFFF";
        if (upper.contains("MAGENTA")) return "#FF00FF";

        return null;
    }

    /**
     * IDML tab alignment → intermediate alignment string.
     */
    private static String mapTabAlignment(String idmlAlignment) {
        if (idmlAlignment == null) return "left";
        switch (idmlAlignment) {
            case "LeftAlign": return "left";
            case "CenterAlign": return "center";
            case "RightAlign": return "right";
            case "CharacterAlign": return "decimal";
            default: return "left";
        }
    }

    /**
     * IDML tracking 값을 HWPX letterSpacing(%)으로 변환한다.
     * IDML tracking: 1/1000 em 단위. 예: 100 = 0.1em = 10%
     * HWPX spacing: % 단위 (-50 ~ 50).
     */
    private static short trackingToLetterSpacing(double tracking) {
        // tracking 100 = 0.1em = 10%
        int percent = (int) Math.round(tracking / 10.0);
        // HWPX spacing 범위 제한 (-50 ~ 50)
        percent = Math.max(-50, Math.min(50, percent));
        return (short) percent;
    }
}
