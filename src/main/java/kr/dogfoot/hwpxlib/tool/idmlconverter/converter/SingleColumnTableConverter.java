package kr.dogfoot.hwpxlib.tool.idmlconverter.converter;

import kr.dogfoot.hwpxlib.object.content.header_xml.enumtype.*;
import kr.dogfoot.hwpxlib.object.content.section_xml.SubList;
import kr.dogfoot.hwpxlib.object.content.section_xml.enumtype.*;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.Para;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.Run;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.object.Table;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.object.table.Tc;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.object.table.Tr;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.*;

/**
 * 단일 컬럼 텍스트 프레임 → 1x1 테이블 변환 (W4 Step D).
 * 플로팅 글상자를 hp:tbl + Tr + Tc 구조로 변환. 다단인 경우 컬럼마다 호출.
 * HwpxTextBoxBuilder에서 분리됨.
 */
final class SingleColumnTableConverter {

    private final HwpxConverterContext ctx;
    private final HwpxParagraphBuilder paragraphBuilder;
    private final HwpxTextBoxBuilder textBoxBuilder;

    SingleColumnTableConverter(HwpxConverterContext ctx, HwpxParagraphBuilder paragraphBuilder,
                                HwpxTextBoxBuilder textBoxBuilder) {
        this.ctx = ctx;
        this.paragraphBuilder = paragraphBuilder;
        this.textBoxBuilder = textBoxBuilder;
    }

    private DropCapStyle resolveDropCapStyle(java.util.List<ASTParagraph> paragraphs) {
        if (paragraphs == null || paragraphs.isEmpty()) return DropCapStyle.None;
        ASTParagraph firstPara = paragraphs.get(0);
        String styleRef = firstPara.paragraphStyleRef();
        if (styleRef == null) return DropCapStyle.None;
        ASTStyleDef style = paragraphBuilder.findParagraphStyle(styleRef);
        if (style == null || style.dropCapLines() == null) return DropCapStyle.None;
        if (style.dropCapLines() >= 3) return DropCapStyle.TripleLine;
        if (style.dropCapLines() >= 2) return DropCapStyle.DoubleLine;
        return DropCapStyle.None;
    }

    /**
     * 단일 1×1 테이블(글상자) 생성.
     * 다단인 경우 각 컬럼마다 호출된다.
     */
    void convertSingleColumnTable(Para framePara, ASTTextFrameBlock block,
                                           long x, long y, long w, long h,
                                           java.util.List<ASTParagraph> paragraphs) {
        convertSingleColumnTable(framePara, block, x, y, w, h, paragraphs, false);
    }

    void convertSingleColumnTable(Para framePara, ASTTextFrameBlock block,
                                           long x, long y, long w, long h,
                                           java.util.List<ASTParagraph> paragraphs,
                                           boolean suppressBorder) {
        // 폰트 메트릭 기반 높이 보정: 매핑 폰트가 원본보다 세로로 크면 글상자 확장
        h = TextBoxLayoutHelpers.adjustHeightByFontMetrics(ctx, h, paragraphs);

        // overflow 방지 높이 축소: resolved 기반 파이프라인에서는 geometricBounds가 정확하므로 비활성화
        // (레거시 파이프라인용 코드, 새 파이프라인에서는 필요 없음)

        // 글상자 너비 확장: 매핑 폰트의 폭 차이로 텍스트 넘침 방지
        if (ctx.config != null && ctx.config.textBoxWidthExpandPercent() > 0) {
            w = w + w * ctx.config.textBoxWidthExpandPercent() / 100;
        }

        Run anchorRun = framePara.addNewRun();
        anchorRun.charPrIDRef("0");

        Table table = anchorRun.addNewTable();

        // ShapeObject — 배경 전용 블록은 BEHIND_TEXT + z-order=0, 일반은 IN_FRONT_OF_TEXT
        TextWrapMethod wrapMethod = block.isBackgroundOnly()
                ? TextWrapMethod.BEHIND_TEXT
                : TextWrapMethod.IN_FRONT_OF_TEXT;
        int zOrder = block.isBackgroundOnly() ? 0 : block.zOrder();
        String tableId = HwpxUtil.nextShapeId();
        table.idAnd(tableId)
                .zOrderAnd(zOrder)
                .numberingTypeAnd(NumberingType.TABLE)
                .textWrapAnd(wrapMethod)
                .textFlowAnd(TextFlowSide.BOTH_SIDES)
                .lockAnd(false)
                .dropcapstyleAnd(resolveDropCapStyle(paragraphs));

        // 테이블 속성 — 1행 1열
        table.pageBreakAnd(TablePageBreak.CELL)
                .repeatHeaderAnd(false)
                .rowCntAnd((short) 1)
                .colCntAnd((short) 1)
                .cellSpacingAnd(0)
                .borderFillIDRefAnd("1")
                .noAdjustAnd(false);

        // ShapeSize — 높이 0: 콘텐츠에 맞게 자동 확장 (하단 빈 공간 방지)
        table.createSZ();
        table.sz().widthAnd(w).widthRelToAnd(WidthRelTo.ABSOLUTE)
                .heightAnd(0L).heightRelToAnd(HeightRelTo.ABSOLUTE)
                .protectAnd(false);

        // BaselineShift → 컨테이너 Y 오프셋 보정.
        // HWPX charPr offset으로는 baseline 위치 보정이 불완전하므로,
        // 프레임 전체의 Y 위치를 직접 조정하고 charPr offset은 제거한다.
        long adjustedY = y;
        if (paragraphs != null && !paragraphs.isEmpty()) {
            ASTParagraph firstPara = paragraphs.get(0);
            if (firstPara.items() != null && !firstPara.items().isEmpty()) {
                kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTInlineItem firstItem = firstPara.items().get(0);
                if (firstItem instanceof kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTextRun) {
                    kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTextRun firstRun =
                            (kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTextRun) firstItem;
                    if (firstRun.baselineShift() != null && firstRun.baselineShift() != 0
                            && firstRun.fontSizeHwpunits() != null) {
                        // baselineShift: %단위 (양수=위). Y를 위로 이동.
                        long shiftHwp = (long) firstRun.baselineShift()
                                * firstRun.fontSizeHwpunits() / 100;
                        adjustedY = y - shiftHwp;
                        // charPr offset에서 이중 적용 방지: baselineShift 제거
                        firstRun.baselineShift(null);
                    }
                }
            }
        }

        // ShapePosition — PAPER 기준 절대 좌표
        table.createPos();
        table.pos().treatAsCharAnd(false)
                .affectLSpacingAnd(false)
                .flowWithTextAnd(false)
                .allowOverlapAnd(true)
                .holdAnchorAndSOAnd(false)
                .vertRelToAnd(VertRelTo.PAPER)
                .horzRelToAnd(HorzRelTo.PAPER)
                .vertAlignAnd(VertAlign.TOP)
                .horzAlignAnd(HorzAlign.LEFT)
                .vertOffsetAnd(adjustedY)
                .horzOffsetAnd(x);

        // OutMargin
        table.createOutMargin();
        table.outMargin().leftAnd(0L).rightAnd(0L).topAnd(0L).bottomAnd(0L);

        // InMargin
        table.createInMargin();
        table.inMargin().leftAnd(0L).rightAnd(0L).topAnd(0L).bottomAnd(0L);

        // 1행 1열 셀 생성
        Tr tr = table.addNewTr();
        Tc tc = tr.addNewTc();

        // suppressBorder: 배경 사각형이 테두리를 담당하므로 개별 컬럼은 테두리/배경 없이
        String cellBfId = suppressBorder ? "1" : textBoxBuilder.createTextFrameBorderFill(block);

        tc.nameAnd("")
                .headerAnd(false)
                .hasMarginAnd(true)
                .protectAnd(false)
                .editableAnd(true)
                .dirtyAnd(false)
                .borderFillIDRefAnd(cellBfId);

        // 셀 주소
        tc.createCellAddr();
        tc.cellAddr().colAddrAnd((short) 0).rowAddrAnd((short) 0);

        // 셀 병합 (1x1이므로 span=1)
        tc.createCellSpan();
        tc.cellSpan().colSpanAnd((short) 1).rowSpanAnd((short) 1);

        // 셀 크기 — 높이 0: 콘텐츠에 맞게 자동 확장
        tc.createCellSz();
        tc.cellSz().widthAnd(w).heightAnd(0L);

        // 셀 여백 — 블록 인셋 적용 (InDesign insetSpacing), 하단 최소 5pt
        tc.createCellMargin();
        long bottomInset = Math.max(block.insetBottom(), 500L);
        tc.cellMargin().leftAnd(block.insetLeft())
                .rightAnd(block.insetRight())
                .topAnd(block.insetTop())
                .bottomAnd(bottomInset);

        // 셀 내부 SubList
        tc.createSubList();
        SubList subList = tc.subList();
        TextDirection textDir = block.verticalText() ? TextDirection.VERTICAL : TextDirection.HORIZONTAL;
        VerticalAlign2 cellVAlign = HwpxEnumMapper.mapVerticalJustification(block.verticalJustification());
        subList.idAnd("").textDirectionAnd(textDir)
                .lineWrapAnd(LineWrapMethod.BREAK)
                .vertAlignAnd(cellVAlign);

        // 연결 글상자 링크 설정
        // resolved 기반 문단 재배치가 완료된 프레임은 링크 해제 (각 프레임이 독립적으로 표시)
        if (block.distributed()) {
            subList.linkListIDRefAnd("0").linkListNextIDRefAnd("0");
        } else {
            textBoxBuilder.applySubListLink(subList, block.storyId());
        }

        // 블록 위치 추적 (오버레이 좌표 계산용)
        ctx.blockPageX = x;
        ctx.blockPageY = y;
        ctx.blockInsetLeft = block.insetLeft();
        ctx.blockInsetTop = block.insetTop();
        ctx.cellContentYCursor = 0;
        // 셀 내부 콘텐츠 폭 (인라인 텍스트 프레임 균등 분배용)
        ctx.currentContainerWidth = w - block.insetLeft() - block.insetRight();

        // 인라인 텍스트 프레임 균등 분배: 연속 단락에 각각 1개씩 인라인 TextFrame이 있으면
        // 부모 컨테이너 폭 기준으로 균등 분배
        HwpxTextBoxBuilder.redistributeInlineTextFrameWidths(paragraphs, ctx.currentContainerWidth);

        // lineSpacing이 셀 높이를 초과하면 셀 높이로 제한.
        // InDesign에서는 FirstBaselineOffset이 첫 줄 위치를 제어하고 Leading은 줄간 간격만 담당하지만,
        // HWPX에서는 FIXED lineSpacing이 첫 줄의 baseline 위치에도 영향을 주므로,
        // 셀 높이를 넘는 lineSpacing은 baseline을 불필요하게 아래로 밀어냄.
        // 단, h=0(자동 확장)이면 클램핑하지 않음
        if (h > 0) {
            int cellH = (int) h;
            for (ASTParagraph para : paragraphs) {
                if (para.lineSpacing() != null && para.lineSpacing() > cellH) {
                    para.lineSpacing(cellH);
                }
            }
        }

        // 단락 추가 (인라인 테이블 포함)
        for (ASTParagraph para : paragraphs) {
            if (para.inlineTable() != null && ctx.tableBuilderRef != null) {
                ctx.tableBuilderRef.addInlineTableToSubList(subList, para.inlineTable());
            } else {
                paragraphBuilder.addParagraphToSubList(subList, para);
            }
        }
        // 빈 텍스트 프레임 방지
        if (subList.countOfPara() == 0) {
            paragraphBuilder.addEmptySubListPara(subList);
        }
    }
}
