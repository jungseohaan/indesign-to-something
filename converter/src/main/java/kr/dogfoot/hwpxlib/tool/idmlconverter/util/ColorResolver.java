package kr.dogfoot.hwpxlib.tool.idmlconverter.util;

import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLDocument;

/**
 * IDML 색상 참조를 HEX 색상으로 변환하는 유틸리티.
 */
public class ColorResolver {

    private final IDMLDocument idmlDoc;

    public ColorResolver(IDMLDocument idmlDoc) {
        this.idmlDoc = idmlDoc;
    }

    /**
     * 색상 참조를 HEX 색상으로 변환한다.
     *
     * @param colorRef IDML 색상 참조 (예: "Color/C=0 M=0 Y=0 K=100")
     * @return HEX 색상 (예: "#000000") 또는 null
     */
    public String resolve(String colorRef) {
        if (colorRef == null) return null;
        if (isHexColor(colorRef)) return colorRef;
        if (colorRef.contains("None") || colorRef.contains("[None]")) return null;
        if (colorRef.startsWith("Tint/")) {
            return resolveTintReference(colorRef.substring("Tint/".length()));
        }

        // IDML Color 정의에서 색상 조회
        if (idmlDoc != null) {
            String hexColor = idmlDoc.getColor(colorRef);
            if (hexColor != null) {
                return hexColor;
            }
        }

        // 기본 색상 처리
        if (colorRef.contains("Paper")) return "#FFFFFF";  // Paper = 용지 배경색 (흰색)
        if (colorRef.contains("Black")) return "#000000";
        if (colorRef.contains("White")) return "#FFFFFF";
        if (colorRef.contains("Red")) return "#FF0000";
        if (colorRef.contains("Green")) return "#00FF00";
        if (colorRef.contains("Blue")) return "#0000FF";
        if (colorRef.contains("Yellow")) return "#FFFF00";
        if (colorRef.contains("Cyan")) return "#00FFFF";
        if (colorRef.contains("Magenta")) return "#FF00FF";

        // 변환 실패 시 null 반환
        return null;
    }

    /**
     * 색상 참조에 tint를 적용해 HEX 색상으로 변환한다.
     *
     * <p>InDesign FillTint/StrokeTint는 투명도가 아니라 원색과 흰색의 혼합 비율이다.
     * tint=100은 원색, tint=0은 흰색이며, -1/NaN 같은 누락 값은 100으로 보정한다.</p>
     */
    public String resolveTinted(String colorRef, double tint) {
        return applyTintToHex(resolve(colorRef), tint);
    }

    /**
     * IDMLDocument 없이 기본 색상만 처리하는 정적 메서드.
     */
    public static String resolveBasicColor(String colorRef) {
        if (colorRef == null) return null;
        if (colorRef.startsWith("#")) return colorRef;
        if (colorRef.contains("None") || colorRef.contains("[None]")) return null;

        if (colorRef.contains("Black")) return "#000000";
        if (colorRef.contains("White")) return "#FFFFFF";

        return null;
    }

    private String resolveTintReference(String tintRef) {
        if (tintRef == null) return null;
        String normalized = tintRef.replace("%25", "%").trim();
        java.util.regex.Matcher m = java.util.regex.Pattern
                .compile("^\\[?([^\\]]+)\\]?\\s+(\\d+\\.?\\d*)%$")
                .matcher(normalized);
        if (!m.find()) return null;
        String base = m.group(1).trim();
        double tint;
        try {
            tint = Double.parseDouble(m.group(2));
        } catch (NumberFormatException e) {
            return null;
        }
        String baseHex = resolve(base);
        return applyTintToHex(baseHex, tint);
    }

    private static boolean isHexColor(String colorRef) {
        // #RGB(4) / #RRGGBB(7) / #RRGGBBAA(9) 모두 hex passthrough로 인정
        if (colorRef == null) return false;
        int len = colorRef.length();
        if (len != 4 && len != 7 && len != 9) return false;
        if (colorRef.charAt(0) != '#') return false;
        for (int i = 1; i < len; i++) {
            char ch = colorRef.charAt(i);
            boolean hex = (ch >= '0' && ch <= '9')
                    || (ch >= 'a' && ch <= 'f')
                    || (ch >= 'A' && ch <= 'F');
            if (!hex) return false;
        }
        return true;
    }

    public static double normalizeTint(double tint) {
        if (Double.isNaN(tint) || tint < 0) return 100.0;
        if (tint > 100) return 100.0;
        return tint;
    }

    public static String applyTintToHex(String hex, double tint) {
        return blendColorWithWhite(hex, normalizeTint(tint) / 100.0);
    }

    /**
     * 색상 hex를 fraction 비율로 흰색과 블렌딩.
     * fraction=1.0 → 원래 색상, fraction=0.0 → 흰색.
     */
    public static String blendColorWithWhite(String hex, double fraction) {
        if (hex == null || !hex.startsWith("#") || hex.length() < 7) return hex;
        double f = Math.max(0.0, Math.min(1.0, fraction));
        try {
            int rgb = Integer.parseInt(hex.substring(1, 7), 16);
            int r = (rgb >> 16) & 0xFF;
            int g = (rgb >> 8) & 0xFF;
            int b = rgb & 0xFF;
            r = (int) Math.round(255 + (r - 255) * f);
            g = (int) Math.round(255 + (g - 255) * f);
            b = (int) Math.round(255 + (b - 255) * f);
            return String.format("#%02X%02X%02X",
                    Math.max(0, Math.min(255, r)),
                    Math.max(0, Math.min(255, g)),
                    Math.max(0, Math.min(255, b)));
        } catch (Exception e) {
            return hex;
        }
    }
}
