package kr.dogfoot.hwpxlib.tool.idmlconverter.converter;

import kr.dogfoot.hwpxlib.object.content.header_xml.enumtype.*;
import kr.dogfoot.hwpxlib.object.content.header_xml.references.BorderFill;
import kr.dogfoot.hwpxlib.object.content.header_xml.references.borderfill.Border;
import kr.dogfoot.hwpxlib.object.content.section_xml.SubList;
import kr.dogfoot.hwpxlib.object.content.section_xml.enumtype.*;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.Para;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.Run;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.object.Table;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.object.table.Tc;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.object.table.Tr;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.*;

/**
 * ASTTable → HWPX Table(플로팅 또는 인라인)로 변환한다.
 */
public class HwpxTableBuilder {

    private final HwpxConverterContext ctx;
    private final HwpxParagraphBuilder paragraphBuilder;

    public HwpxTableBuilder(HwpxConverterContext ctx, HwpxParagraphBuilder paragraphBuilder) {
        this.ctx = ctx;
        this.paragraphBuilder = paragraphBuilder;
    }

    // ── 플로팅 테이블 변환 ──

    public void convertTable(Para framePara, ASTTable astTable) {
        Run anchorRun = framePara.addNewRun();
        anchorRun.charPrIDRef("0");

        long x = astTable.x();
        long y = astTable.y();
        long totalWidth = astTable.width();

        Table table = anchorRun.addNewTable();

        // ShapeObject
        String tableId = HwpxUtil.nextShapeId();
        // 테이블 z-order를 원래 값보다 높게 설정하여
        // 동일 영역의 배경 이미지 위에 렌더링되도록 함
        table.idAnd(tableId)
                .zOrderAnd(astTable.zOrder() + 100)
                .numberingTypeAnd(NumberingType.TABLE)
                .textWrapAnd(TextWrapMethod.IN_FRONT_OF_TEXT)
                .textFlowAnd(TextFlowSide.BOTH_SIDES)
                .lockAnd(false)
                .dropcapstyleAnd(DropCapStyle.None);

        // 테이블 속성
        table.pageBreakAnd(TablePageBreak.CELL)
                .repeatHeaderAnd(false)
                .rowCntAnd((short) astTable.rowCount())
                .colCntAnd((short) astTable.colCount())
                .cellSpacingAnd(0)
                .borderFillIDRefAnd("1")
                .noAdjustAnd(false);

        // ShapeSize
        table.createSZ();
        table.sz().widthAnd(totalWidth).widthRelToAnd(WidthRelTo.ABSOLUTE)
                .heightAnd(astTable.height()).heightRelToAnd(HeightRelTo.ABSOLUTE)
                .protectAnd(false);

        // ShapePosition — PAPER 기준
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
                .vertOffsetAnd(y)
                .horzOffset(x);

        // OutMargin
        table.createOutMargin();
        table.outMargin().leftAnd(0L).rightAnd(0L).topAnd(0L).bottomAnd(0L);

        // InMargin
        table.createInMargin();
        table.inMargin().leftAnd(0L).rightAnd(0L).topAnd(0L).bottomAnd(0L);

        // 행(Tr) 생성
        buildTableRows(table, astTable, x, y);

        // 테이블 뒤에 빈 텍스트 요소 추가 (한글 렌더링 필수)
        anchorRun.addNewT().addText("");
    }

    // ── 인라인 테이블 변환 ──

    /**
     * SubList 내에 인라인 테이블을 추가한다 (treatAsChar=true).
     */
    void addInlineTableToSubList(SubList subList, ASTTable astTable) {
        long totalWidth = astTable.width();

        Para tablePara = subList.addNewPara();
        tablePara.idAnd(HwpxUtil.nextParaId())
                .paraPrIDRefAnd("3")
                .styleIDRefAnd("0")
                .pageBreakAnd(false)
                .columnBreakAnd(false)
                .merged(false);

        Run run = tablePara.addNewRun();
        run.charPrIDRef("0");

        Table table = run.addNewTable();
        String tableId = HwpxUtil.nextShapeId();

        table.idAnd(tableId)
                .zOrderAnd(0)
                .numberingTypeAnd(NumberingType.TABLE)
                .textWrapAnd(TextWrapMethod.TOP_AND_BOTTOM)
                .textFlowAnd(TextFlowSide.BOTH_SIDES)
                .lockAnd(false)
                .dropcapstyleAnd(DropCapStyle.None);

        table.pageBreakAnd(TablePageBreak.CELL)
                .repeatHeaderAnd(false)
                .rowCntAnd((short) astTable.rowCount())
                .colCntAnd((short) astTable.colCount())
                .cellSpacingAnd(0)
                .borderFillIDRefAnd("1")
                .noAdjustAnd(false);

        table.createSZ();
        table.sz().widthAnd(totalWidth).widthRelToAnd(WidthRelTo.ABSOLUTE)
                .heightAnd(astTable.height()).heightRelToAnd(HeightRelTo.ABSOLUTE)
                .protectAnd(false);

        // 인라인 위치 (글자처럼 취급)
        table.createPos();
        table.pos().treatAsCharAnd(true)
                .affectLSpacingAnd(true)
                .flowWithTextAnd(true)
                .allowOverlapAnd(false)
                .holdAnchorAndSOAnd(false)
                .vertRelToAnd(VertRelTo.PARA)
                .horzRelToAnd(HorzRelTo.PARA)
                .vertAlignAnd(VertAlign.BOTTOM)
                .horzAlignAnd(HorzAlign.LEFT)
                .vertOffsetAnd(0L)
                .horzOffset(0L);

        table.createOutMargin();
        table.outMargin().leftAnd(0L).rightAnd(0L).topAnd(0L).bottomAnd(0L);

        table.createInMargin();
        table.inMargin().leftAnd(0L).rightAnd(0L).topAnd(0L).bottomAnd(0L);

        // 행(Tr) 생성 — 인라인 테이블은 텍스트 프레임 내부이므로 현재 블록 좌표 사용
        long inlineTableX = ctx.blockPageX + ctx.blockInsetLeft;
        long inlineTableY = ctx.blockPageY + ctx.blockInsetTop + ctx.cellContentYCursor;
        buildTableRows(table, astTable, inlineTableX, inlineTableY);

        // 테이블 뒤에 빈 텍스트 요소 추가 (한글 렌더링 필수)
        run.addNewT().addText("");
    }

    // ── 공통 행/셀 생성 ──

    private void buildTableRows(Table table, ASTTable astTable, long tablePageX, long tablePageY) {
        // 오버레이 승격을 위해 컨텍스트 저장/복원
        boolean savedInsideTableCell = ctx.insideTableCell;
        long savedBlockPageX = ctx.blockPageX;
        long savedBlockPageY = ctx.blockPageY;
        long savedBlockInsetLeft = ctx.blockInsetLeft;
        long savedBlockInsetTop = ctx.blockInsetTop;
        long savedCellContentYCursor = ctx.cellContentYCursor;

        ctx.insideTableCell = true;
        long rowYOffset = 0;

        for (ASTTableRow astRow : astTable.rows()) {
            Tr tr = table.addNewTr();

            for (ASTTableCell astCell : astRow.cells()) {
                // 셀의 컬럼 X 오프셋 계산
                long cellColX = 0;
                java.util.List<Long> colWidths = astTable.columnWidths();
                for (int c = 0; c < astCell.columnIndex() && c < colWidths.size(); c++) {
                    cellColX += colWidths.get(c);
                }

                // 오버레이 좌표 계산용 컨텍스트 설정
                ctx.blockPageX = tablePageX + cellColX;
                ctx.blockPageY = tablePageY + rowYOffset;
                ctx.blockInsetLeft = astCell.marginLeft();
                ctx.blockInsetTop = astCell.marginTop();
                ctx.cellContentYCursor = 0;

                Tc tc = tr.addNewTc();

                String cellBorderFillId = createCellBorderFill(astCell);

                tc.nameAnd("")
                        .headerAnd(false)
                        .hasMarginAnd(true)
                        .protectAnd(false)
                        .editableAnd(true)
                        .dirtyAnd(false)
                        .borderFillIDRefAnd(cellBorderFillId);

                // 셀 주소
                tc.createCellAddr();
                tc.cellAddr().colAddrAnd((short) astCell.columnIndex())
                        .rowAddrAnd((short) astCell.rowIndex());

                // 셀 병합 (span은 최소 1, 테이블 범위 초과 방지)
                tc.createCellSpan();
                int colSpan = Math.max(1, astCell.columnSpan());
                int rowSpan = Math.max(1, astCell.rowSpan());
                if (astCell.columnIndex() + colSpan > colWidths.size()) {
                    colSpan = Math.max(1, colWidths.size() - astCell.columnIndex());
                }
                if (astCell.rowIndex() + rowSpan > astTable.rowCount()) {
                    rowSpan = Math.max(1, astTable.rowCount() - astCell.rowIndex());
                }
                tc.cellSpan().colSpanAnd((short) colSpan)
                        .rowSpanAnd((short) rowSpan);

                // 셀 크기
                tc.createCellSz();
                tc.cellSz().widthAnd(astCell.width()).heightAnd(astCell.height());

                // 셀 여백
                tc.createCellMargin();
                tc.cellMargin().leftAnd(astCell.marginLeft())
                        .rightAnd(astCell.marginRight())
                        .topAnd(astCell.marginTop())
                        .bottomAnd(astCell.marginBottom());

                // 셀 내부 SubList
                tc.createSubList();
                SubList subList = tc.subList();
                subList.idAnd("")
                        .textDirectionAnd(TextDirection.HORIZONTAL)
                        .lineWrapAnd(LineWrapMethod.BREAK);

                // 수직 정렬
                String vAlign = astCell.verticalAlign();
                if ("CenterAlign".equals(vAlign) || "center".equals(vAlign)) {
                    subList.vertAlignAnd(VerticalAlign2.CENTER);
                } else if ("BottomAlign".equals(vAlign) || "bottom".equals(vAlign)) {
                    subList.vertAlignAnd(VerticalAlign2.BOTTOM);
                } else {
                    subList.vertAlignAnd(VerticalAlign2.TOP);
                }

                // 인라인 텍스트 프레임 균등 분배 (셀 폭 기준)
                long cellContentWidth = astCell.width() - astCell.marginLeft() - astCell.marginRight();
                HwpxTextBoxBuilder.redistributeInlineTextFrameWidths(astCell.paragraphs(), cellContentWidth);

                // 셀 내용 (단락) 추가
                for (ASTParagraph astPara : astCell.paragraphs()) {
                    paragraphBuilder.addParagraphToSubList(subList, astPara, astCell.height());
                }
                // 빈 셀 방지
                if (subList.countOfPara() == 0) {
                    paragraphBuilder.addEmptySubListPara(subList, astCell.height());
                }
            }

            rowYOffset += astRow.rowHeight();
        }

        // 컨텍스트 복원
        ctx.insideTableCell = savedInsideTableCell;
        ctx.blockPageX = savedBlockPageX;
        ctx.blockPageY = savedBlockPageY;
        ctx.blockInsetLeft = savedBlockInsetLeft;
        ctx.blockInsetTop = savedBlockInsetTop;
        ctx.cellContentYCursor = savedCellContentYCursor;
    }

    // ── 셀 BorderFill 생성 ──

    String createCellBorderFill(ASTTableCell cell) {
        String bfId = String.valueOf(ctx.borderFillIdCounter.getAndIncrement());
        BorderFill bf = ctx.hwpxFile.headerXMLFile().refList().borderFills().addNew();

        bf.idAnd(bfId)
                .threeDAnd(false)
                .shadowAnd(false)
                .centerLineAnd(CenterLineSort.NONE)
                .breakCellSeparateLine(false);

        // 대각선
        // IDML TopRightDiagonalLine(↙ /) → HWPX slash(/)
        bf.createSlash();
        if (cell.topRightDiagonalLine()) {
            bf.slash().typeAnd(SlashType.CENTER).CrookedAnd(false).isCounter(false);
        } else {
            bf.slash().typeAnd(SlashType.NONE).CrookedAnd(false).isCounter(false);
        }

        // IDML TopLeftDiagonalLine(↘ \) → HWPX backSlash(\)
        bf.createBackSlash();
        if (cell.topLeftDiagonalLine()) {
            bf.backSlash().typeAnd(SlashType.CENTER).CrookedAnd(false).isCounter(false);
        } else {
            bf.backSlash().typeAnd(SlashType.NONE).CrookedAnd(false).isCounter(false);
        }

        // 테두리
        bf.createLeftBorder();
        applyCellBorder(bf.leftBorder(), cell.leftBorder());

        bf.createRightBorder();
        applyCellBorder(bf.rightBorder(), cell.rightBorder());

        bf.createTopBorder();
        applyCellBorder(bf.topBorder(), cell.topBorder());

        bf.createBottomBorder();
        applyCellBorder(bf.bottomBorder(), cell.bottomBorder());

        // 대각선 스타일
        bf.createDiagonal();
        if ((cell.topLeftDiagonalLine() || cell.topRightDiagonalLine()) && cell.diagonalBorder() != null) {
            ASTTableCell.CellBorder diag = cell.diagonalBorder();
            LineType2 lineType = HwpxParagraphBuilder.strokeTypeToLineType(diag.strokeType());
            LineWidth lineWidth = HwpxParagraphBuilder.hwpunitToLineWidth(diag.weight());
            String color = diag.color() != null ? diag.color() : "#000000";
            bf.diagonal().typeAnd(lineType).widthAnd(lineWidth).color(color);
        } else {
            bf.diagonal().typeAnd(LineType2.NONE).widthAnd(LineWidth.MM_0_1).color("#000000");
        }

        // 배경 채우기 — 실제 색상값(#으로 시작)이 있을 때만
        String cellFill = cell.fillColor();
        if (cellFill != null && cellFill.startsWith("#")) {
            bf.createFillBrush();
            bf.fillBrush().createWinBrush();
            bf.fillBrush().winBrush()
                    .faceColorAnd(cellFill)
                    .hatchColorAnd("#FF000000")
                    .alpha(0f);
        }

        return bfId;
    }

    void applyCellBorder(Border hwpxBorder, ASTTableCell.CellBorder cellBorder) {
        if (cellBorder == null || cellBorder.weight() <= 0) {
            hwpxBorder.typeAnd(LineType2.NONE).widthAnd(LineWidth.MM_0_1).color("#000000");
            return;
        }
        LineType2 lineType = HwpxParagraphBuilder.strokeTypeToLineType(cellBorder.strokeType());
        hwpxBorder.typeAnd(lineType);
        LineWidth lineWidth = HwpxParagraphBuilder.hwpunitToLineWidth(cellBorder.weight());
        hwpxBorder.widthAnd(lineWidth);
        String color = cellBorder.color();
        if (color == null || color.isEmpty() || !color.startsWith("#")) color = "#000000";
        hwpxBorder.color(color);
    }
}
