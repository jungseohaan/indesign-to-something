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
        if (colorRef.startsWith("#")) return colorRef;
        if (colorRef.contains("None") || colorRef.contains("[None]")) return null;

        // IDML Color 정의에서 색상 조회
        if (idmlDoc != null) {
            String hexColor = idmlDoc.getColor(colorRef);
            if (hexColor != null) {
                return hexColor;
            }
        }

        // 기본 색상 처리
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
}
