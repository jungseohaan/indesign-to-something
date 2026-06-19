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
        // overflow 방지 높이 축소: resolved 기반 파이프라인에서는 geometricBounds가 정확하므로 비활성화
        // (레거시 파이프라인용 코드, 새 파이프라인에서는 필요 없음)

        w = expandWidthForDotLeaderTabs(block, paragraphs, w);

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
                .flowWithTextAnd(block.anchoredFlowWithText())
                .allowOverlapAnd(!block.anchoredFlowWithText())
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

        // 인라인 텍스트 프레임 균등 분배: 연속 단락에 각각 1개씩 인라인 TextFrame이 있으면
        // 부모 컨테이너 폭 기준으로 균등 분배
        ctx.currentContainerWidth = w - block.insetLeft() - block.insetRight();
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
        suppressFirstParagraphSpaceBefore(paragraphs);

        // suppressBorder: 배경 사각형이 테두리를 담당하므로 개별 컬럼은 테두리/배경 없이
        String cellBfId = suppressBorder ? "1" : textBoxBuilder.createTextFrameBorderFill(block);

        // 테이블 속성
        // This 1x1 table is a text-frame carrier, not a semantic table.
        // Let page breaks happen inside the carrier so paragraphs after inline tables
        // continue from the visible table row instead of reserving the whole cell.
        table.pageBreakAnd(TablePageBreak.TABLE)
                .repeatHeaderAnd(false)
                .rowCntAnd((short) 1)
                .colCntAnd((short) 1)
                .cellSpacingAnd(0)
                .borderFillIDRefAnd(cellBfId)
                .noAdjustAnd(false);

        // ShapeSize — 실제 프레임 높이를 명시한다.
        // TOP 정렬 표도 height=0(auto)로 두면 일부 HWPX 렌더러에서 겹침/표시 누락이 발생한다.
        long _tblHeight = h > 0 ? h : 0L;
        table.createSZ();
        table.sz().widthAnd(w).widthRelToAnd(WidthRelTo.ABSOLUTE)
                .heightAnd(_tblHeight).heightRelToAnd(HeightRelTo.ABSOLUTE)
                .protectAnd(false);

        // 블록 위치 추적 (오버레이 좌표 계산용)
        ctx.blockPageX = x;
        ctx.blockPageY = y;
        ctx.blockInsetLeft = block.insetLeft();
        ctx.blockInsetTop = block.insetTop();
        ctx.cellContentYCursor = 0;

        Tr tr = table.addNewTr();
        Tc tc = tr.addNewTc();
        tc.nameAnd("")
                .headerAnd(false)
                .hasMarginAnd(true)
                .protectAnd(false)
                .editableAnd(true)
                .dirtyAnd(false)
                .borderFillIDRefAnd(cellBfId);

        tc.createCellAddr();
        tc.cellAddr().colAddrAnd((short) 0).rowAddrAnd((short) 0);
        tc.createCellSpan();
        tc.cellSpan().colSpanAnd((short) 1).rowSpanAnd((short) 1);
        tc.createCellSz();
        tc.cellSz().widthAnd(w).heightAnd(h);
        tc.createCellMargin();
        tc.cellMargin().leftAnd(block.insetLeft())
                .rightAnd(block.insetRight())
                .topAnd(block.insetTop())
                .bottomAnd(block.insetBottom());

        tc.createSubList();
        SubList subList = tc.subList();
        TextDirection textDir = block.verticalText() ? TextDirection.VERTICAL : TextDirection.HORIZONTAL;
        VerticalAlign2 cellVAlign = HwpxEnumMapper.mapVerticalJustification(block.verticalJustification());
        subList.idAnd("").textDirectionAnd(textDir)
                .lineWrapAnd(HwpxTextBoxBuilder.textFrameLineWrap(block))
                .vertAlignAnd(cellVAlign);
        if (block.distributed()) {
            subList.linkListIDRefAnd("0").linkListNextIDRefAnd("0");
        } else {
            textBoxBuilder.applySubListLink(subList, block.storyId());
        }

        boolean savedInsideTableCell = ctx.insideTableCell;
        ctx.insideTableCell = true;
        try {
            // 셀 내용 (단락) 추가 — drawText/일반 셀과 동일한 공용 루틴
            paragraphBuilder.fillSubListContent(subList, paragraphs, null, 0);
        } finally {
            ctx.insideTableCell = savedInsideTableCell;
        }
    }

    private void suppressFirstParagraphSpaceBefore(java.util.List<ASTParagraph> paragraphs) {
        if (paragraphs == null || paragraphs.isEmpty()) return;
        ASTParagraph firstPara = paragraphs.get(0);
        if (firstPara == null || firstPara.spaceBefore() == null || firstPara.spaceBefore() <= 0) return;
        // InDesign does not render paragraph Space Before as top padding at the start of a text frame.
        firstPara.spaceBefore(0L);
    }

    private long expandWidthForDotLeaderTabs(ASTTextFrameBlock block,
                                             java.util.List<ASTParagraph> paragraphs,
                                             long width) {
        if (block == null || paragraphs == null || paragraphs.isEmpty()) return width;
        final long safetyPad = 500L; // 5pt: HWP tab leader/right edge clipping guard.
        long requiredInnerWidth = 0L;
        for (ASTParagraph para : paragraphs) {
            if (para == null || para.tabStops() == null) continue;
            boolean blankUnderline = hasUnderlinedTabRun(para);
            long paraRight = para.rightMargin() != null ? Math.max(0L, para.rightMargin()) : 0L;
            for (ASTTabStop stop : para.tabStops()) {
                if (stop == null || stop.position() <= 0) continue;
                String ld = stop.leader();
                // 리더 있는 탭(점선 페이지번호) 또는 밑줄 빈칸 탭 run → 폭 확보
                if ((ld == null || ld.isEmpty()) && !blankUnderline) continue;
                requiredInnerWidth = Math.max(requiredInnerWidth, stop.position() + paraRight);
            }
        }
        if (requiredInnerWidth <= 0L) return width;
        long requiredOuterWidth = block.insetLeft() + requiredInnerWidth + block.insetRight() + safetyPad;
        return Math.max(width, requiredOuterWidth);
    }

    /** 밑줄 속성이 걸린 탭 run(빈칸 채움선)이 있는지 — 프레임 폭 확보 판정용. */
    private static boolean hasUnderlinedTabRun(ASTParagraph para) {
        if (para == null || para.items() == null) return false;
        for (ASTInlineItem item : para.items()) {
            if (!(item instanceof ASTTextRun)) continue;
            ASTTextRun run = (ASTTextRun) item;
            String text = run.text();
            if (text != null && text.indexOf('\t') >= 0 && run.underline()) return true;
        }
        return false;
    }
}
