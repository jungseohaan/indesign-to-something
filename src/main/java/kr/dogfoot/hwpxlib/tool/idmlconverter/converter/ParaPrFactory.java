package kr.dogfoot.hwpxlib.tool.idmlconverter.converter;

import kr.dogfoot.hwpxlib.object.content.header_xml.enumtype.*;
import kr.dogfoot.hwpxlib.object.content.header_xml.references.BorderFill;
import kr.dogfoot.hwpxlib.object.content.header_xml.references.ParaPr;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.*;

/**
 * ParaPr 생성 + 단락 속성 오버라이드 (W4-2 Step B).
 * - findParaPrById, findParagraphStyle: 조회 헬퍼
 * - hasParagraphOverrides, isHangingIndentParagraph: 판별
 * - createOverrideParaPr, createParaShadingBorderFill: 생성
 * - resolveParaLong: 정적 유틸
 *
 * HwpxParagraphBuilder에서 분리됨.
 */
final class ParaPrFactory {

    private final HwpxConverterContext ctx;

    ParaPrFactory(HwpxConverterContext ctx) {
        this.ctx = ctx;
    }

    // ── 인라인 객체 높이 기반 줄 간격 확장 ──


    ParaPr findParaPrById(String id) {
        for (ParaPr pr : ctx.hwpxFile.headerXMLFile().refList().paraProperties().items()) {
            if (id.equals(pr.id())) return pr;
        }
        return null;
    }

    // ── 단락 속성 오버라이드 ──

    boolean hasParagraphOverrides(ASTParagraph para) {
        return para.alignment() != null
                || para.firstLineIndent() != null
                || para.leftMargin() != null
                || para.rightMargin() != null
                || para.spaceBefore() != null
                || para.spaceAfter() != null
                || para.lineSpacing() != null
                || para.hasTabStops()
                || para.shadingOn();
    }

    ASTStyleDef findParagraphStyle(String styleRef) {
        if (styleRef == null) return null;
        for (ASTStyleDef sd : ctx.paragraphStyles) {
            if (styleRef.equals(sd.styleId())) return sd;
        }
        // ParagraphStyle/ 접두어 없이 검색
        for (ASTStyleDef sd : ctx.paragraphStyles) {
            String id = sd.styleId();
            if (id != null && id.endsWith("/" + styleRef)) return sd;
        }
        return null;
    }

    String createOverrideParaPr(ASTParagraph astPara, String baseParaPrId) {
        // 기본 스타일에서 값 상속
        ASTStyleDef baseStyle = findParagraphStyle(astPara.paragraphStyleRef());

        String newId = ctx.styleRegistry.nextParaPrId();
        ParaPr paraPr = ctx.hwpxFile.headerXMLFile().refList().paraProperties().addNew();

        // 문단 배경 → BorderFill 생성
        String borderFillRef = "2";
        if (astPara.shadingOn() && astPara.shadingColor() != null) {
            borderFillRef = createParaShadingBorderFill(astPara.shadingColor(), astPara.shadingTint());
        }

        // 마진: 단락 오버라이드 → 스타일 → 0
        int indent = resolveParaLong(astPara.firstLineIndent(),
                baseStyle != null ? baseStyle.firstLineIndent() : null);
        int left = resolveParaLong(astPara.leftMargin(),
                baseStyle != null ? baseStyle.leftMargin() : null);
        int right = resolveParaLong(astPara.rightMargin(),
                baseStyle != null ? baseStyle.rightMargin() : null);
        int prev = resolveParaLong(astPara.spaceBefore(),
                baseStyle != null ? baseStyle.spaceBefore() : null);
        int next = resolveParaLong(astPara.spaceAfter(),
                baseStyle != null ? baseStyle.spaceAfter() : null);

        // InDesign 탭 위치는 프레임 기준(절대) — HWPX에서도 동일하게 사용

        // 인라인 탭 정지점 → TabPr 생성 (마진 조정 후에 실행해야 암시적 탭 포함)
        String tabPrId;
        if (astPara.hasTabStops()) {
            tabPrId = ctx.styleRegistry.createInlineTabPr(astPara.tabStops());
        } else {
            // 인라인 탭이 없으면 기본 스타일의 tabPr 상속
            ParaPr basePr = findParaPrById(baseParaPrId);
            tabPrId = basePr != null ? basePr.tabPrIDRef() : "0";
        }

        paraPr.idAnd(newId)
                .tabPrIDRefAnd(tabPrId)
                .condenseAnd((byte) 0)
                .fontLineHeightAnd(false)
                .snapToGridAnd(true)
                .suppressLineNumbersAnd(false)
                .checked(false);

        // 문단 배경이 있으면 border에 borderFillIDRef 설정 + 음영 오프셋 적용
        if (!"2".equals(borderFillRef)) {
            paraPr.createBorder();
            paraPr.border().borderFillIDRefAnd(borderFillRef);
            // 단락 배경 여백 (InDesign shading offset → HWPX border offset)
            if (astPara.shadingLeftOffset() != null) paraPr.border().offsetLeft(astPara.shadingLeftOffset().intValue());
            if (astPara.shadingRightOffset() != null) paraPr.border().offsetRight(astPara.shadingRightOffset().intValue());
            if (astPara.shadingTopOffset() != null) paraPr.border().offsetTop(astPara.shadingTopOffset().intValue());
            if (astPara.shadingBottomOffset() != null) paraPr.border().offsetBottom(astPara.shadingBottomOffset().intValue());
        }

        // 정렬: 단락 오버라이드 → 스타일 → JUSTIFY
        String alignStr = astPara.alignment();
        if (alignStr == null && baseStyle != null) {
            alignStr = baseStyle.alignment();
        }
        HorizontalAlign2 hAlign = HwpxUtil.mapAlignment(alignStr);
        paraPr.createAlign();
        paraPr.align().horizontalAnd(hAlign).vertical(VerticalAlign1.BASELINE);

        paraPr.createHeading();
        paraPr.heading().typeAnd(ParaHeadingType.NONE).idRefAnd("0").level((byte) 0);

        paraPr.createBreakSetting();
        paraPr.breakSetting()
                .breakLatinWordAnd(LineBreakForLatin.KEEP_WORD)
                .breakNonLatinWordAnd(LineBreakForNonLatin.KEEP_WORD)
                .widowOrphanAnd(false)
                .keepWithNextAnd(astPara.keepWithNext())
                .keepLinesAnd(astPara.keepLinesTogether())
                .pageBreakBeforeAnd(astPara.pageBreakBefore())
                .lineWrap(LineWrap.BREAK);

        paraPr.createAutoSpacing();
        paraPr.autoSpacing().eAsianEngAnd(false).eAsianNum(false);

        paraPr.createMargin();
        paraPr.margin().createIntent();
        paraPr.margin().intent().valueAnd(indent).unit(ValueUnit2.HWPUNIT);
        paraPr.margin().createLeft();
        paraPr.margin().left().valueAnd(left).unit(ValueUnit2.HWPUNIT);
        paraPr.margin().createRight();
        paraPr.margin().right().valueAnd(right).unit(ValueUnit2.HWPUNIT);
        paraPr.margin().createPrev();
        paraPr.margin().prev().valueAnd(prev).unit(ValueUnit2.HWPUNIT);
        paraPr.margin().createNext();
        paraPr.margin().next().valueAnd(next).unit(ValueUnit2.HWPUNIT);

        // 줄 간격: 단락 오버라이드 → 스타일 → 160% PERCENT
        paraPr.createLineSpacing();
        Integer lsValue = astPara.lineSpacing();
        String lsType = astPara.lineSpacingType();
        if (lsValue == null && baseStyle != null && baseStyle.lineSpacing() != null) {
            lsValue = baseStyle.lineSpacing();
            lsType = baseStyle.lineSpacingType();
        }
        if (lsValue != null) {
            LineSpacingType hwpxType = "fixed".equals(lsType)
                    ? LineSpacingType.FIXED : LineSpacingType.PERCENT;
            paraPr.lineSpacing()
                    .typeAnd(hwpxType)
                    .valueAnd(lsValue)
                    .unit(ValueUnit2.HWPUNIT);
        } else {
            paraPr.lineSpacing()
                    .typeAnd(LineSpacingType.PERCENT)
                    .valueAnd(160)
                    .unit(ValueUnit2.HWPUNIT);
        }

        return newId;
    }

    /**
     * 문단 배경색용 BorderFill 생성.
     */
    String createParaShadingBorderFill(String color, Double tint) {
        String bfId = String.valueOf(ctx.borderFillIdCounter.getAndIncrement());
        BorderFill bf = ctx.hwpxFile.headerXMLFile().refList().borderFills().addNew();

        bf.idAnd(bfId)
                .threeDAnd(false)
                .shadowAnd(false)
                .centerLineAnd(CenterLineSort.NONE)
                .breakCellSeparateLine(false);

        bf.createSlash();
        bf.slash().typeAnd(SlashType.NONE).CrookedAnd(false).isCounter(false);
        bf.createBackSlash();
        bf.backSlash().typeAnd(SlashType.NONE).CrookedAnd(false).isCounter(false);

        bf.createLeftBorder();
        bf.leftBorder().typeAnd(LineType2.NONE).widthAnd(LineWidth.MM_0_1).color("#000000");
        bf.createRightBorder();
        bf.rightBorder().typeAnd(LineType2.NONE).widthAnd(LineWidth.MM_0_1).color("#000000");
        bf.createTopBorder();
        bf.topBorder().typeAnd(LineType2.NONE).widthAnd(LineWidth.MM_0_1).color("#000000");
        bf.createBottomBorder();
        bf.bottomBorder().typeAnd(LineType2.NONE).widthAnd(LineWidth.MM_0_1).color("#000000");
        bf.createDiagonal();
        bf.diagonal().typeAnd(LineType2.NONE).widthAnd(LineWidth.MM_0_1).color("#000000");

        // tint 적용: InDesign tint는 -1(100%)이 기본, 0~100 범위
        float alpha = 0f;
        if (tint != null && tint >= 0 && tint < 100) {
            alpha = (float) ((100.0 - tint) / 100.0);
        }
        bf.createFillBrush();
        bf.fillBrush().createWinBrush();
        bf.fillBrush().winBrush()
                .faceColorAnd(color)
                .hatchColorAnd("#FF000000")
                .alpha(alpha);

        return bfId;
    }

    /**
     * RuleBelow 답안 밑줄선 → 하단만 SOLID인 BorderFill 생성.
     */
    static int resolveParaLong(Long paraOverride, Long styleValue) {
        if (paraOverride != null) return paraOverride.intValue();
        if (styleValue != null) return styleValue.intValue();
        return 0;
    }

    // ── 텍스트 런 변환 ──

    /**
     * 단락이 행잉 인덴트(LeftIndent > 0, FirstLineIndent < 0) 인지 판별한다.
     * 단락 자체 오버라이드 또는 적용된 paragraph style 의 값을 검사.
     */
    boolean isHangingIndentParagraph(ASTParagraph astPara) {
        Long left = astPara.leftMargin();
        Long indent = astPara.firstLineIndent();
        if (left == null || indent == null) {
            String ref = astPara.paragraphStyleRef();
            if (ref != null) {
                String resolved = HwpxUtil.resolveStyleRef(ref, ctx.styleRegistry);
                if (ctx.paragraphStyles != null) {
                    for (kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTStyleDef sd : ctx.paragraphStyles) {
                        if (sd != null && resolved.equals(sd.styleId())) {
                            if (left == null) left = sd.leftMargin();
                            if (indent == null) indent = sd.firstLineIndent();
                            break;
                        }
                    }
                }
            }
        }
        return left != null && indent != null && left > 0 && indent < 0;
    }
}
