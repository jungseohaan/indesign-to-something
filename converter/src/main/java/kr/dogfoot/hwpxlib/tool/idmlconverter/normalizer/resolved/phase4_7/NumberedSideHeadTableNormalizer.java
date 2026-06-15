package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.phase4_7;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTBlock;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTInlineItem;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTInlineObject;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTParagraph;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTSection;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTable;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTableCell;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTableRow;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTextRun;
import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.CoordinateConverter;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ResolvedBuildContext;

import java.util.ArrayList;
import java.util.List;

/**
 * Stage 1 {@code SideHeadFlowPlan}을 실행하는 bridge.
 *
 * <p>새 visible 객체를 만들지 않고, Stage 2/4가 이미 생성한 테이블 셀/문단/인라인 라벨을
 * 재배치한다. 페이지/문구 예외 없이 plan된 source table만 재구성한다.</p>
 */
public final class NumberedSideHeadTableNormalizer {

    private NumberedSideHeadTableNormalizer() {}

    private static final long MIN_SIDE_COL = CoordinateConverter.pointsToHwpunits(10.0);
    private static final long MAX_SIDE_COL = CoordinateConverter.pointsToHwpunits(18.0);
    private static final long MIN_NUMBER_FONT = CoordinateConverter.pointsToHwpunits(18.0);

    public static void run(ResolvedBuildContext ctx, List<ASTSection> sections) {
        if (sections == null) return;
        int changed = 0;
        for (ASTSection section : sections) {
            if (section == null || section.blocks() == null) continue;
            for (ASTBlock block : section.blocks()) {
            if (!(block instanceof ASTTable)) continue;
                ASTTable table = (ASTTable) block;
                boolean planned = ctx != null && ctx.isSideHeadFlowTableSource(table.sourceId());
                if (normalize(table)) {
                    changed++;
                    if (!planned && ctx != null && ctx.ownershipWarningLines != null) {
                        ctx.ownershipWarningLines.add("{\"code\":\"SIDE_HEAD_FLOW_UNPLANNED_BRIDGE\","
                                + "\"detail\":\"Stage4 bridge normalized structural side-head table"
                                + " without Stage1 SideHeadFlowPlan; sourceId="
                                + escape(table.sourceId()) + "\"}");
                    }
                }
            }
        }
        if (changed > 0) {
            System.err.println("[ResolvedToASTBuilder] Phase 4.7: "
                    + changed + " numbered side-head tables normalized");
        }
    }

    public static boolean normalizePlanned(ResolvedBuildContext ctx, ASTTable table) {
        if (ctx == null || table == null || !ctx.isSideHeadFlowTableSource(table.sourceId())) {
            return false;
        }
        return normalize(table);
    }

    private static boolean normalize(ASTTable table) {
        if (table == null || table.colCount() != 1 || table.rows() == null || table.rows().size() < 2) {
            return false;
        }
        if (table.columnWidths() == null || table.columnWidths().size() != 1) return false;
        ASTTableRow firstRow = table.rows().get(0);
        ASTTableRow bodyRow = table.rows().get(1);
        if (!singleCellRow(firstRow) || !singleCellRow(bodyRow)) return false;

        ASTTableCell firstCell = firstRow.cells().get(0);
        ASTTableCell bodyCell = bodyRow.cells().get(0);
        if (firstCell.paragraphs() == null || firstCell.paragraphs().size() != 1) return false;
        if (!hasSubstantiveParagraph(bodyCell)) return false;

        SplitHead split = splitNumberAndHead(firstCell.paragraphs().get(0));
        if (split == null) return false;

        long originalWidth = table.columnWidths().get(0);
        if (originalWidth <= 0) return false;
        long sideWidth = sideColumnWidth(originalWidth, split.numberWidthHint);
        long rightWidth = originalWidth;
        long newWidth = sideWidth + rightWidth;

        table.x(table.x() - sideWidth);
        table.width(newWidth);
        table.colCount(2);
        table.columnWidths().clear();
        table.addColumnWidth(sideWidth);
        table.addColumnWidth(rightWidth);

        firstRow.cells().clear();
        ASTTableCell numberCell = cloneCellShell(firstCell);
        numberCell.columnIndex(0);
        numberCell.rowSpan(2);
        numberCell.columnSpan(1);
        numberCell.width(sideWidth);
        numberCell.height(firstRow.rowHeight() + bodyRow.rowHeight());
        numberCell.verticalAlign("TopAlign");
        numberCell.marginLeft(0);
        numberCell.marginRight(0);
        numberCell.marginTop(0);
        numberCell.marginBottom(0);
        numberCell.addParagraph(split.numberParagraph);

        ASTTableCell headCell = cloneCellShell(firstCell);
        headCell.columnIndex(1);
        headCell.rowSpan(1);
        headCell.columnSpan(1);
        headCell.width(rightWidth);
        headCell.addParagraph(split.headParagraph);

        firstRow.addCell(numberCell);
        firstRow.addCell(headCell);

        bodyRow.cells().clear();
        ASTTableCell rightBody = cloneCellFull(bodyCell);
        rightBody.columnIndex(1);
        rightBody.rowSpan(1);
        rightBody.columnSpan(1);
        rightBody.width(rightWidth);
        bodyRow.addCell(rightBody);

        for (int i = 2; i < table.rows().size(); i++) {
            ASTTableRow row = table.rows().get(i);
            if (!singleCellRow(row)) continue;
            ASTTableCell cell = row.cells().get(0);
            cell.columnIndex(0);
            cell.columnSpan(2);
            cell.width(newWidth);
        }
        return true;
    }

    private static boolean singleCellRow(ASTTableRow row) {
        return row != null && row.cells() != null && row.cells().size() == 1;
    }

    private static boolean hasSubstantiveParagraph(ASTTableCell cell) {
        if (cell == null || cell.paragraphs() == null) return false;
        for (ASTParagraph paragraph : cell.paragraphs()) {
            if (paragraphText(paragraph).trim().length() >= 8) return true;
        }
        return false;
    }

    private static SplitHead splitNumberAndHead(ASTParagraph source) {
        if (source == null || source.items() == null || source.items().size() < 2) return null;
        List<ASTInlineItem> items = source.items();
        int index = firstSubstantiveRunIndex(items);
        if (index < 0 || !(items.get(index) instanceof ASTTextRun)) return null;
        ASTTextRun numberRun = (ASTTextRun) items.get(index);
        String number = numberRun.text() != null ? numberRun.text().trim() : "";
        if (!number.matches("[0-9]{1,2}")) return null;
        Integer fs = numberRun.fontSizeHwpunits();
        if (fs == null || fs < MIN_NUMBER_FONT) return null;

        int afterNumber = index + 1;
        while (afterNumber < items.size() && isWhitespaceRun(items.get(afterNumber))) {
            afterNumber++;
        }
        if (afterNumber >= items.size()) return null;
        boolean hasInlineHead = false;
        boolean hasHeadText = false;
        for (int i = afterNumber; i < items.size(); i++) {
            ASTInlineItem item = items.get(i);
            if (item instanceof ASTInlineObject) hasInlineHead = true;
            if (item instanceof ASTTextRun) {
                String t = ((ASTTextRun) item).text();
                if (t != null && !t.trim().isEmpty()) hasHeadText = true;
            }
        }
        if (!hasInlineHead && !hasHeadText) return null;

        ASTParagraph numberParagraph = copyParagraphShell(source);
        numberParagraph.addItem(numberRun);

        ASTParagraph headParagraph = copyParagraphShell(source);
        for (int i = afterNumber; i < items.size(); i++) {
            headParagraph.addItem(items.get(i));
        }

        SplitHead out = new SplitHead();
        out.numberParagraph = numberParagraph;
        out.headParagraph = headParagraph;
        out.numberWidthHint = estimateNumberWidth(numberRun);
        return out;
    }

    private static int firstSubstantiveRunIndex(List<ASTInlineItem> items) {
        for (int i = 0; i < items.size(); i++) {
            ASTInlineItem item = items.get(i);
            if (!(item instanceof ASTTextRun)) continue;
            String text = ((ASTTextRun) item).text();
            if (text != null && !text.trim().isEmpty()) return i;
        }
        return -1;
    }

    private static boolean isWhitespaceRun(ASTInlineItem item) {
        if (!(item instanceof ASTTextRun)) return false;
        String text = ((ASTTextRun) item).text();
        return text == null || text.trim().isEmpty();
    }

    private static long estimateNumberWidth(ASTTextRun run) {
        Integer fs = run.fontSizeHwpunits();
        if (fs == null || fs <= 0) return MIN_SIDE_COL;
        String text = run.text() != null ? run.text().trim() : "";
        int digits = Math.max(1, text.length());
        return Math.round(fs * (0.36 + 0.18 * Math.max(0, digits - 1)));
    }

    private static long sideColumnWidth(long originalWidth, long numberWidthHint) {
        long ratio = Math.round(originalWidth * 0.085);
        long wanted = Math.max(ratio, numberWidthHint + CoordinateConverter.pointsToHwpunits(2.0));
        return Math.max(MIN_SIDE_COL, Math.min(MAX_SIDE_COL, wanted));
    }

    private static ASTTableCell cloneCellShell(ASTTableCell src) {
        ASTTableCell out = new ASTTableCell();
        out.rowIndex(src.rowIndex());
        out.columnIndex(src.columnIndex());
        out.rowSpan(src.rowSpan());
        out.columnSpan(src.columnSpan());
        out.width(src.width());
        out.height(src.height());
        out.fillColor(src.fillColor());
        out.topBorder(cloneBorder(src.topBorder()));
        out.bottomBorder(cloneBorder(src.bottomBorder()));
        out.leftBorder(cloneBorder(src.leftBorder()));
        out.rightBorder(cloneBorder(src.rightBorder()));
        out.topLeftDiagonalLine(src.topLeftDiagonalLine());
        out.topRightDiagonalLine(src.topRightDiagonalLine());
        out.diagonalBorder(cloneBorder(src.diagonalBorder()));
        out.marginTop(src.marginTop());
        out.marginBottom(src.marginBottom());
        out.marginLeft(src.marginLeft());
        out.marginRight(src.marginRight());
        out.verticalAlign(src.verticalAlign());
        out.reservedForNestedContent(src.reservedForNestedContent());
        return out;
    }

    private static ASTTableCell cloneCellFull(ASTTableCell src) {
        ASTTableCell out = cloneCellShell(src);
        if (src.paragraphs() != null) {
            for (ASTParagraph paragraph : src.paragraphs()) {
                out.addParagraph(paragraph);
            }
        }
        return out;
    }

    private static ASTTableCell.CellBorder cloneBorder(ASTTableCell.CellBorder src) {
        if (src == null) return null;
        ASTTableCell.CellBorder out = new ASTTableCell.CellBorder();
        out.color(src.color());
        out.weight(src.weight());
        out.strokeType(src.strokeType());
        out.tint(src.tint());
        return out;
    }

    private static ASTParagraph copyParagraphShell(ASTParagraph src) {
        ASTParagraph out = new ASTParagraph();
        out.paragraphStyleRef(src.paragraphStyleRef());
        out.alignment(src.alignment());
        out.firstLineIndent(src.firstLineIndent());
        out.leftMargin(src.leftMargin());
        out.rightMargin(src.rightMargin());
        out.spaceBefore(src.spaceBefore());
        out.spaceAfter(src.spaceAfter());
        out.lineSpacing(src.lineSpacing());
        out.lineSpacingType(src.lineSpacingType());
        out.letterSpacing(src.letterSpacing());
        out.shadingOn(src.shadingOn());
        out.shadingColor(src.shadingColor());
        out.shadingTint(src.shadingTint());
        out.shadingLeftOffset(src.shadingLeftOffset());
        out.shadingRightOffset(src.shadingRightOffset());
        out.shadingTopOffset(src.shadingTopOffset());
        out.shadingBottomOffset(src.shadingBottomOffset());
        if (src.tabStops() != null) {
            for (kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTabStop tabStop : src.tabStops()) {
                out.addTabStop(tabStop);
            }
        }
        out.yOffsetInFrame(src.yOffsetInFrame());
        out.pageX(src.pageX());
        out.pageY(src.pageY());
        out.pageWidth(src.pageWidth());
        out.pageHeight(src.pageHeight());
        out.columnBreakAfter(src.columnBreakAfter());
        out.keepWithNext(src.keepWithNext());
        out.keepLinesTogether(src.keepLinesTogether());
        out.pageBreakBefore(src.pageBreakBefore());
        out.indentToHerePosition(src.indentToHerePosition());
        out.pendingUnderlineColor(src.pendingUnderlineColor());
        out.bulletParagraph(src.bulletParagraph());
        out.dropLeadingSmallInlineObjects(src.dropLeadingSmallInlineObjects());
        return out;
    }

    private static String paragraphText(ASTParagraph paragraph) {
        if (paragraph == null || paragraph.items() == null) return "";
        StringBuilder sb = new StringBuilder();
        for (ASTInlineItem item : paragraph.items()) {
            if (item instanceof ASTTextRun) {
                String t = ((ASTTextRun) item).text();
                if (t != null) sb.append(t);
            }
        }
        return sb.toString();
    }

    private static String escape(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private static final class SplitHead {
        ASTParagraph numberParagraph;
        ASTParagraph headParagraph;
        long numberWidthHint;
    }
}
