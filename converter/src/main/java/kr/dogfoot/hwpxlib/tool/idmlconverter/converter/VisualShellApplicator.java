package kr.dogfoot.hwpxlib.tool.idmlconverter.converter;

import kr.dogfoot.hwpxlib.object.content.header_xml.enumtype.LineType2;
import kr.dogfoot.hwpxlib.object.content.header_xml.enumtype.LineWidth;
import kr.dogfoot.hwpxlib.object.content.header_xml.references.borderfill.Border;
import kr.dogfoot.hwpxlib.object.content.header_xml.references.borderfill.FillBrush;

/**
 * 박스 셸(fill/stroke)의 HWPX 출력 생성 공용 헬퍼.
 *
 * <p>drawText 박스(Rectangle)와 테이블 셀/단일컬럼 프레임(BorderFill)이 같은
 * WinBrush 단색 채우기·테두리 매핑 로직을 공유하도록 추출. Rectangle과 BorderFill의
 * {@code FillBrush}는 동일 타입이므로 한 헬퍼로 통합한다. 단, hatchColor 관습이
 * 구조별로 다르므로(Rectangle "#000000" vs BorderFill "#FF000000") 호출별 인자로 둔다.</p>
 */
final class VisualShellApplicator {
    private VisualShellApplicator() {}

    /**
     * WinBrush 단색 채우기. {@code fillBrush}는 호출부에서 createFillBrush() 후 전달.
     * @param hatchColor Rectangle은 "#000000", BorderFill은 "#FF000000"
     */
    static void applyWinBrushFill(FillBrush fillBrush, String faceColor, String hatchColor) {
        fillBrush.createWinBrush();
        fillBrush.winBrush()
                .faceColorAnd(faceColor)
                .hatchColorAnd(hatchColor)
                .alphaAnd(0f);
    }

    /**
     * BorderFill 한 변의 stroke 매핑. weight가 0 이하면 NONE/기본값.
     */
    static void applyBorderEdge(Border border, String strokeType, double weightHwp, String color) {
        if (weightHwp <= 0) {
            border.typeAnd(LineType2.NONE).widthAnd(LineWidth.MM_0_1).color("#000000");
            return;
        }
        LineType2 lineType = HwpxParagraphBuilder.strokeTypeToLineType(strokeType);
        LineWidth lineWidth = HwpxParagraphBuilder.hwpunitToLineWidth(weightHwp);
        String c = (color == null || color.isEmpty() || !color.startsWith("#")) ? "#000000" : color;
        border.typeAnd(lineType).widthAnd(lineWidth).color(c);
    }
}
