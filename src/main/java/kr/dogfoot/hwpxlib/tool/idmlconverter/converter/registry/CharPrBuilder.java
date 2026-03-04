package kr.dogfoot.hwpxlib.tool.idmlconverter.converter.registry;

import kr.dogfoot.hwpxlib.object.content.header_xml.enumtype.*;
import kr.dogfoot.hwpxlib.object.content.header_xml.references.CharPr;

/**
 * CharPr(문자 속성) 빌드 유틸리티.
 * 스타일 등록, 인라인 오버라이드, 수식 폰트 등 다양한 컨텍스트에서
 * 공통적으로 사용되는 CharPr 초기화 로직을 한 곳에 모아둔다.
 */
public final class CharPrBuilder {

    private CharPrBuilder() {
        // 인스턴스화 방지
    }

    /**
     * CharPr 객체를 완전히 초기화한다.
     *
     * @param charPr         대상 CharPr 객체
     * @param id             CharPr ID
     * @param height         글자 크기 (HWPX 단위)
     * @param textColor      글자 색상 (예: "#000000")
     * @param fontFamily     폰트 패밀리 이름
     * @param fontRegistry   폰트 레지스트리 (폰트 ID 조회용)
     * @param letterSpacing  자간 (nullable, 기본 0)
     * @param bold           볼드 여부
     * @param italic         이탤릭 여부
     * @param superscript    위첨자 여부
     * @param subscript      아래첨자 여부
     * @param underlineType  밑줄 타입 (예: NONE, BOTTOM)
     * @param underlineColor 밑줄 색상 (예: "#000000", "#008000")
     * @param strikethrough  취소선 여부
     * @param verticalScale  세로 비율 (nullable, %, 100 = normal)
     * @param baselineShift  기준선 이동 (nullable, %, 양수=위)
     */
    public static void build(CharPr charPr, String id, int height, String textColor,
                              String fontFamily, FontRegistry fontRegistry,
                              Short letterSpacing,
                              boolean bold, boolean italic,
                              boolean superscript, boolean subscript,
                              UnderlineType underlineType, String underlineColor,
                              LineType3 underlineShape,
                              Short horizontalScale,
                              boolean strikethrough,
                              Short verticalScale,
                              Short baselineShift) {
        // 기본 속성
        charPr.idAnd(id)
                .heightAnd(height)
                .textColorAnd(textColor)
                .shadeColorAnd("none")
                .useFontSpaceAnd(false)
                .useKerningAnd(false)
                .symMarkAnd(SymMarkSort.NONE)
                .borderFillIDRef("2");

        // Bold/Italic 설정
        if (bold) {
            charPr.createBold();
        }
        if (italic) {
            charPr.createItalic();
        }

        // 위첨자/아래첨자
        if (superscript) {
            charPr.createSupscript();
        }
        if (subscript) {
            charPr.createSubscript();
        }

        // 폰트 참조
        String fontId = fontRegistry.resolveFontId(fontFamily);
        charPr.createFontRef();
        charPr.fontRef().set(fontId, fontId, fontId, fontId, fontId, fontId, fontId);

        short ratio = 90;
        if (horizontalScale != null) {
            ratio = (short)(90 * horizontalScale / 100);
        }
        charPr.createRatio();
        charPr.ratio().set(ratio, ratio, ratio, ratio, ratio, ratio, ratio);

        // 자간 — 전역 -10% 적용
        short baseSpacing = letterSpacing != null ? letterSpacing : 0;
        short spacing = (short) (baseSpacing - 10);
        charPr.createSpacing();
        charPr.spacing().set(spacing, spacing, spacing, spacing, spacing, spacing, spacing);

        short relSzVal = (verticalScale != null) ? verticalScale : 100;
        charPr.createRelSz();
        charPr.relSz().set(relSzVal, relSzVal, relSzVal, relSzVal, relSzVal, relSzVal, relSzVal);

        short offsetVal = (baselineShift != null) ? baselineShift : 0;
        charPr.createOffset();
        charPr.offset().set(offsetVal, offsetVal, offsetVal, offsetVal, offsetVal, offsetVal, offsetVal);

        charPr.createUnderline();
        LineType3 ulShape = (underlineShape != null) ? underlineShape : LineType3.SOLID;
        charPr.underline().typeAnd(underlineType).shapeAnd(ulShape).color(underlineColor);

        charPr.createStrikeout();
        charPr.strikeout().shapeAnd(strikethrough ? LineType2.SOLID : LineType2.NONE).color("#000000");

        charPr.createOutline();
        charPr.outline().type(LineType1.NONE);

        charPr.createShadow();
        charPr.shadow().typeAnd(CharShadowType.NONE).colorAnd("#B2B2B2")
                .offsetXAnd((short) 10).offsetY((short) 10);
    }
}
