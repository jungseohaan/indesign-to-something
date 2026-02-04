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
            IntermediateStyleDef mapped = mapParagraphStyle(entry.getValue(), id, colorMap);
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
            IntermediateStyleDef mapped = mapCharacterStyle(entry.getValue(), id, colorMap);
            outStyles.add(mapped);
            refToId.put(entry.getKey(), id);
            idx++;
        }
        return refToId;
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
     * 흰색(#FFFFFF 또는 유사)은 배경 이미지 없이 안 보이므로 30% 회색으로 대체한다.
     */
    public static String resolveColor(String fillColor, Map<String, String> colorMap) {
        if (fillColor == null || fillColor.isEmpty()) return null;
        String hex;
        if (fillColor.startsWith("#")) {
            hex = fillColor;
        } else {
            // "Color/..." 형식
            hex = colorMap.get(fillColor);
            if (hex == null) {
                // "Color/Black" 같은 이름
                if (fillColor.contains("Black")) return "#000000";
                if (fillColor.contains("White")) return "#4D4D4D";
                return null;
            }
        }
        // 흰색 또는 거의 흰색 → 30% 회색 (#4D4D4D)
        if (isNearWhite(hex)) {
            return "#4D4D4D";
        }
        return hex;
    }

    /**
     * 색상이 흰색 또는 거의 흰색인지 확인한다 (R, G, B 모두 240 이상).
     */
    private static boolean isNearWhite(String hex) {
        if (hex == null || !hex.startsWith("#") || hex.length() < 7) return false;
        try {
            int r = Integer.parseInt(hex.substring(1, 3), 16);
            int g = Integer.parseInt(hex.substring(3, 5), 16);
            int b = Integer.parseInt(hex.substring(5, 7), 16);
            return r >= 240 && g >= 240 && b >= 240;
        } catch (NumberFormatException e) {
            return false;
        }
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
