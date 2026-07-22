package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer;

import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLCharacterRun;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLVectorShape;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedPageItem;

/**
 * 괄호 빈칸 스페이서 — 텍스트 사이에 앵커된 "빈 사각형"의 폭 보존.
 *
 * <p>InDesign 조판에서 "( )" 답란의 안쪽 공백은 스페이스 문자가 아니라
 * fill/stroke 없는 납작한(높이 ~3pt) 인라인 Rectangle 로 폭을 확보한다.
 * 변환기가 이 앵커를 버리면 "()" 로 붙어 나온다 (과학 u1 p46 사례).
 *
 * <p>이런 사각형은 화면에 아무것도 그리지 않으므로, 도형 대신 같은 폭의
 * NBSP(U+00A0) 런으로 치환해도 시각적으로 동일하다. NBSP 를 쓰는 이유:
 * 일반 스페이스(U+0020)는 HWPX 출력 시 장평 축소(spaceCondenseRatio)가
 * 걸려 폭이 절반이 된다.
 */
public final class BlankAnchorSpacer {
    private BlankAnchorSpacer() {}

    /** 한도(pt) — 실측: ㉠㉡ 답란 28×8.5pt, 고르시오 답란 99×10.5pt. */
    private static final double MAX_HEIGHT_PT = 12.0;
    private static final double MIN_WIDTH_PT = 2.0;
    private static final double MAX_WIDTH_PT = 110.0;
    /** NBSP 1자당 폭 근사(pt, 본문 9pt 기준). */
    private static final double NBSP_ADVANCE_PT = 2.5;

    /** 폭(pt)을 NBSP 반복으로 치환한 스페이서 텍스트. 폭이 무효면 null. */
    public static String spacerText(double widthPt) {
        if (widthPt < MIN_WIDTH_PT) return null;
        int n = (int) Math.round(widthPt / NBSP_ADVANCE_PT);
        n = Math.max(2, Math.min(n, 48));
        StringBuilder sb = new StringBuilder(n);
        for (int i = 0; i < n; i++) sb.append('\u00A0');
        return sb.toString();
    }

    /** IDML 인라인 그래픽이 텍스트 사이 폭 확보용 blank 사각형인가. */
    public static boolean isBlankSpacerGraphic(IDMLCharacterRun.InlineGraphic graphic) {
        if (graphic == null || !"rectangle".equals(graphic.type())) return false;
        if (graphic.hasImage()) return false;
        if (graphic.childTextFrames() != null && !graphic.childTextFrames().isEmpty()) return false;
        // 자식 도형(밑줄 Polygon 등)이 있으면 시각물이다 — 투명 스페이서가 아니다.
        // (실측: 과학 u1 p19 답란 밑줄 rect 가 NBSP 로 치환되어 밑줄 유실)
        if (graphic.childGraphics() != null && !graphic.childGraphics().isEmpty()) return false;
        double w = graphic.widthPoints();
        double h = graphic.heightPoints();
        if (w < MIN_WIDTH_PT || w > MAX_WIDTH_PT) return false;
        if (h <= 0 || h > MAX_HEIGHT_PT) return false;
        IDMLVectorShape shape = graphic.vectorShape();
        if (shape != null && (shape.hasFill() || shape.hasStroke())) return false;
        return true;
    }

    /** IDML blank 스페이서 그래픽의 폭에 해당하는 스페이서 텍스트. 아니면 null. */
    public static String spacerTextForGraphic(IDMLCharacterRun.InlineGraphic graphic) {
        if (!isBlankSpacerGraphic(graphic)) return null;
        return spacerText(graphic.widthPoints());
    }

    /**
     * 수식 폰트(BT수식/EH/NP) 런인가 — 수식 런 안의 스페이서는 치환하지 않는다.
     * NBSP 가 수식 그룹에 흡수되면 lexer 가 radicand 경계를 잃는다
     * (실측: 수학 u1 sqrt{15␣␣␣-} 회귀).
     */
    public static boolean isEquationFontRun(String fontFamily) {
        if (fontFamily == null) return false;
        return fontFamily.startsWith("NP_")
                || kr.dogfoot.hwpxlib.tool.equationconverter.idml.BTFontGlyphMap.isBTFontFamily(fontFamily)
                || kr.dogfoot.hwpxlib.tool.equationconverter.idml.EHFontGlyphMap.isEHFontFamily(fontFamily);
    }

    /**
     * resolved pageItem 이 blank 스페이서 사각형인가.
     * resolved 에는 fill/stroke 가 없어 형상(Rectangle + 납작 + 폭 범위)만으로
     * 판정한다 — 이 형상에서 시각적 의미를 갖는 도형은 실측상 없다.
     */
    public static String spacerTextForPageItem(ResolvedPageItem item) {
        if (item == null || !"Rectangle".equals(item.type())) return null;
        double[] gb = item.geometricBounds();
        if (gb == null || gb.length < 4) return null;
        double w = Math.abs(gb[3] - gb[1]);
        double h = Math.abs(gb[2] - gb[0]);
        if (h <= 0 || h > MAX_HEIGHT_PT) return null;
        return spacerText(w);
    }
}
