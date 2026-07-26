package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.*;
import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.ASTImageLoader;
import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.CoordinateConverter;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.*;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.stage3.VisualCropper;
import kr.dogfoot.hwpxlib.tool.idmlconverter.util.ColorResolver;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedData;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedParagraph;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedRun;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedStory;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedTable;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedTextFrame;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.RenderedGroup;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.ObjectPlan;

import javax.imageio.ImageIO;
import java.awt.AlphaComposite;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 테이블/그리드 변환 전담.
 * ASTInlineObjectBuilder에서 분리됨.
 */
public class ASTTableConverter {

    /**
     * IDMLTable → ASTTable 변환 (플로팅 스토리 레벨 테이블).
     */
    static ASTTable convertTable(IDMLTable idmlTable, IDMLTextFrame tf,
                                  IDMLPage page, int zOrder,
                                  IDMLDocument idmlDoc, ColorResolver colorResolver,
                                  ASTImageLoader imageLoader,
                                  ResolvedData resolvedData) {
        ASTTable table = new ASTTable();
        table.sourceId(idmlTable.selfId());
        table.zOrder(zOrder);

        // 테이블 위치: resolved table bounds는 table content의 실제 bounds다.
        // table-only owner TextFrame fallback은 anchor일 뿐이므로 내부 column width를
        // 그 bounds에 맞춰 다시 스케일하면 안 된다.
        double[] resolvedTableBounds = null;
        double[] placementBounds = null;
        if (resolvedData != null) {
            resolvedTableBounds = resolvedData.getTableBounds(idmlTable.selfId());
            placementBounds = hasValidBounds(resolvedTableBounds)
                    ? resolvedTableBounds
                    : resolvedData.getTablePlacementBounds(idmlTable.selfId());
        }
        if (placementBounds != null) {
            // resolved placement bounds는 page-relative (mm 단위 → scale 적용 필요)
            double scale = resolvedData != null ? resolvedData.scaleFactor() : 2.8346;
            table.x(CoordinateConverter.pointsToHwpunits(placementBounds[1] * scale));
            table.y(CoordinateConverter.pointsToHwpunits(placementBounds[0] * scale));
        } else {
            // 폴백: TextFrame 좌표
            double[] relPos = IDMLGeometry.pageRelativePosition(
                    tf.geometricBounds(), tf.itemTransform(),
                    page.geometricBounds(), page.itemTransform());
            table.x(CoordinateConverter.pointsToHwpunits(relPos[0]));
            table.y(CoordinateConverter.pointsToHwpunits(relPos[1]));
        }

        // 컬럼 너비
        for (double cw : idmlTable.columnWidths()) {
            table.addColumnWidth(CoordinateConverter.pointsToHwpunits(cw));
        }
        table.colCount(idmlTable.columnWidths().size());

        // 행 변환
        long totalHeight = 0;
        int rowIdx = 0;
        boolean[][] occupied = tableCellOccupancy(idmlTable);
        for (IDMLTableRow idmlRow : idmlTable.rows()) {
            ASTTableRow row = new ASTTableRow();
            row.rowIndex(rowIdx);
            row.rowHeight(CoordinateConverter.pointsToHwpunits(idmlRow.rowHeight()));
            row.autoGrow(idmlRow.autoGrow());
            totalHeight += row.rowHeight();

            // 셀 변환
            for (IDMLTableCell idmlCell : idmlRow.cells()) {
                if (isCoveredTableCell(occupied, idmlCell)) continue;
                int colIdx = idmlCell.columnIndex();
                ASTTableCell cell = convertTableCell(idmlCell, rowIdx, colIdx,
                        idmlDoc, colorResolver, imageLoader, resolvedData);
                row.addCell(cell);
                markTableCellOccupied(occupied, idmlCell);
            }

            table.addRow(row);
            rowIdx++;
        }
        table.rowCount(rowIdx);

        // 테이블 크기
        long totalWidth = 0;
        for (long cw : table.columnWidths()) {
            totalWidth += cw;
        }
        table.width(totalWidth);
        table.height(totalHeight);

        // 셀 크기 계산 (columnWidths + rowHeights 기반)
        List<Long> colWidths = table.columnWidths();
        for (ASTTableRow row : table.rows()) {
            for (ASTTableCell cell : row.cells()) {
                // 셀 너비 = 시작 컬럼부터 colSpan만큼 합산
                long cellWidth = 0;
                int startCol = cell.columnIndex();
                int endCol = Math.min(startCol + cell.columnSpan(), colWidths.size());
                for (int c = startCol; c < endCol; c++) {
                    cellWidth += colWidths.get(c);
                }
                cell.width(cellWidth);

                // 셀 높이 = 시작 행부터 rowSpan만큼 합산
                long cellHeight = 0;
                int startRow = cell.rowIndex();
                int endRow = Math.min(startRow + cell.rowSpan(), table.rows().size());
                for (int r = startRow; r < endRow; r++) {
                    cellHeight += table.rows().get(r).rowHeight();
                }
                cell.height(cellHeight);
            }
        }

        normalizeInheritedTableBorders(table);
        clearHorizontalInsetsForEmptyColumns(table);

        // 빈 스페이서 행을 위 행에 여백으로 흡수
        ASTTableSpacerMerger.merge(table);
        removeDuplicatedTextFromSpacerCells(table);
        boolean hasResolvedTableBounds = hasValidBounds(resolvedTableBounds);
        if (hasResolvedTableBounds) {
            applyPlacementBounds(table, resolvedTableBounds,
                    resolvedData != null ? resolvedData.scaleFactor() : 2.8346);
        } else if (hasValidBounds(placementBounds)) {
            applyPlacementOrigin(table, placementBounds,
                    resolvedData != null ? resolvedData.scaleFactor() : 2.8346);
        }
        table.fixedOuterBounds(hasResolvedTableBounds);
        if (hasResolvedTableBounds) {
            lockFixedOuterBoundsRows(table);
        }
        ensureRowsFitVisibleCellContent(table);
        return table;
    }

    /**
     * IDMLTableCell → ASTTableCell 변환 (미니 문서).
     */
    static ASTTableCell convertTableCell(IDMLTableCell idmlCell,
                                          int rowIdx, int colIdx,
                                          IDMLDocument idmlDoc,
                                          ColorResolver colorResolver,
                                          ASTImageLoader imageLoader,
                                          ResolvedData resolvedData) {
        ASTTableCell cell = new ASTTableCell();
        cell.rowIndex(rowIdx);
        cell.columnIndex(colIdx);
        cell.rowSpan(idmlCell.rowSpan());
        cell.columnSpan(idmlCell.columnSpan());

        // 셀 스타일 (FillColor + FillTint 블렌딩)
        // IDML FillTint=-1은 "기본값(100%)" 의미, 0은 흰색 틴트로 처리
        if (idmlCell.fillColor() != null) {
            String resolved = colorResolver.resolve(idmlCell.fillColor());
            resolved = ColorResolver.applyTintToHex(resolved, idmlCell.fillTint());
            cell.fillColor(resolved);
        }
        cell.verticalAlign(idmlCell.verticalJustification());
        cell.firstBaselineOffset(idmlCell.firstBaselineOffset());
        cell.minimumFirstBaselineOffset(CoordinateConverter.pointsToHwpunits(
                idmlCell.minimumFirstBaselineOffset()));

        // 셀 여백
        cell.marginTop(CoordinateConverter.pointsToHwpunits(idmlCell.topInset()));
        cell.marginBottom(CoordinateConverter.pointsToHwpunits(idmlCell.bottomInset()));
        cell.marginLeft(CoordinateConverter.pointsToHwpunits(idmlCell.leftInset()));
        cell.marginRight(CoordinateConverter.pointsToHwpunits(idmlCell.rightInset()));

        // 셀 테두리 (IDMLTableCell.CellBorder → ASTTableCell.CellBorder)
        cell.topBorder(convertCellBorder(idmlCell.topBorder(), colorResolver));
        cell.bottomBorder(convertCellBorder(idmlCell.bottomBorder(), colorResolver));
        cell.leftBorder(convertCellBorder(idmlCell.leftBorder(), colorResolver));
        cell.rightBorder(convertCellBorder(idmlCell.rightBorder(), colorResolver));

        // 대각선
        cell.topLeftDiagonalLine(idmlCell.topLeftDiagonalLine());
        cell.topRightDiagonalLine(idmlCell.topRightDiagonalLine());
        cell.diagonalBorder(convertCellBorder(idmlCell.diagonalBorder(), colorResolver));

        // 셀 내용 → 미니 문서 (재귀)
        FlattenedObjectPool emptyPool = new FlattenedObjectPool(); // 셀 내 인라인은 별도 처리
        for (IDMLParagraph cellPara : idmlCell.paragraphs()) {
            ASTParagraph astPara = ASTStoryConverter.convertParagraph(cellPara, emptyPool, idmlDoc, colorResolver, imageLoader, false, resolvedData);
            if (astPara != null) {
                cell.addParagraph(astPara);
            }
        }

        // Legacy-only: ObjectPlan이 없는 변환면에서만 rendered X 기반 보정 허용.
        if (allowsLegacyBoundsXInlineReorder(resolvedData)) {
            for (ASTParagraph p : cell.paragraphs()) {
                reorderInlineObjectsByBoundsX(p);
            }
        }

        // 마지막 빈 단락 제거
        ASTPageProcessor.removeTrailingEmptyParagraphs(cell.paragraphs());
        replaceFlattenedCellTextWithResolvedStory(cell, idmlCell, resolvedData);
        appendNestedTextFrameTablesFromStoryRefs(
                cell, idmlCell, idmlDoc, colorResolver, imageLoader, resolvedData);

        return cell;
    }

    private static void appendNestedTextFrameTablesFromStoryRefs(
            ASTTableCell cell,
            IDMLTableCell idmlCell,
            IDMLDocument idmlDoc,
            ColorResolver colorResolver,
            ASTImageLoader imageLoader,
            ResolvedData resolvedData) {
        if (cell == null || idmlCell == null || idmlDoc == null
                || idmlCell.textFrameStoryRefs() == null
                || idmlCell.textFrameStoryRefs().isEmpty()) {
            return;
        }
        for (String storyRef : idmlCell.textFrameStoryRefs()) {
            IDMLStory nestedStory = storyRef != null ? idmlDoc.getStory(storyRef) : null;
            if (nestedStory == null || !nestedStory.hasTables() || nestedStory.tables() == null) continue;
            for (IDMLTable nestedTable : nestedStory.tables()) {
                if (nestedTable == null || inlineTableAlreadyPresent(cell, nestedTable.selfId())) continue;
                ASTTable nestedAst = convertTableSimple(
                        nestedTable,
                        0, 0, 0,
                        idmlDoc,
                        colorResolver,
                        imageLoader,
                        resolvedData);
                if (nestedAst == null) continue;
                if (cellHasOnlyObjectPlaceholder(cell)) {
                    cell.paragraphs().clear();
                }
                ASTParagraph paragraph = new ASTParagraph();
                paragraph.inlineTable(nestedAst);
                cell.addParagraph(paragraph);
            }
        }
    }

    private static boolean inlineTableAlreadyPresent(ASTTableCell cell, String sourceId) {
        if (cell == null || sourceId == null || cell.paragraphs() == null) return false;
        for (ASTParagraph paragraph : cell.paragraphs()) {
            if (paragraph == null) continue;
            if (sourceId.equals(paragraph.inlineTable() != null ? paragraph.inlineTable().sourceId() : null)) {
                return true;
            }
            if (paragraph.items() == null) continue;
            for (ASTInlineItem item : paragraph.items()) {
                if (!(item instanceof ASTInlineObject)) continue;
                ASTInlineObject obj = (ASTInlineObject) item;
                if (obj.inlineTables() == null) continue;
                for (ASTTable table : obj.inlineTables()) {
                    if (table != null && sourceId.equals(table.sourceId())) return true;
                }
            }
        }
        return false;
    }

    private static boolean cellHasOnlyObjectPlaceholder(ASTTableCell cell) {
        if (cell == null || cell.paragraphs() == null || cell.paragraphs().isEmpty()) return false;
        boolean sawObject = false;
        for (ASTParagraph paragraph : cell.paragraphs()) {
            if (paragraph == null) continue;
            if (paragraph.inlineTable() != null) return false;
            if (paragraph.items() == null || paragraph.items().isEmpty()) continue;
            for (ASTInlineItem item : paragraph.items()) {
                if (item instanceof ASTTextRun) {
                    String text = ((ASTTextRun) item).text();
                    if (text != null && !text.replace("\uFFFC", "").trim().isEmpty()) return false;
                    continue;
                }
                if (item instanceof ASTInlineObject) {
                    sawObject = true;
                    continue;
                }
                return false;
            }
        }
        return sawObject;
    }

    /**
     * IDMLTableCell.CellBorder → ASTTableCell.CellBorder 변환.
     */
    static ASTTableCell.CellBorder convertCellBorder(IDMLTableCell.CellBorder src,
                                                      ColorResolver colorResolver) {
        if (src == null) return null;
        if (src.strokeWeight <= 0 && isNoneColor(src.strokeColor)) return null;
        if (src.strokeWeight <= 0 && src.strokeWeightSpecified) return null;
        ASTTableCell.CellBorder border = new ASTTableCell.CellBorder();
        border.weight(src.strokeWeight);
        border.strokeType(src.strokeType);
        border.tint(src.strokeTint);
        if (src.strokeColor != null) {
            String resolved = colorResolver.resolve(src.strokeColor);
            resolved = ColorResolver.applyTintToHex(resolved, src.strokeTint);
            border.color(resolved);
        }
        return border;
    }

    /**
     * 새 파이프라인용: IDMLTable → ASTTable 변환 (좌표 직접 지정).
     * IDMLDocument/ColorResolver/ASTImageLoader가 있으면 레거시 셀 변환 사용,
     * 없으면 간소화 셀 변환으로 폴백.
     */
    public static ASTTable convertTableSimple(IDMLTable idmlTable,
                                        long x, long y, int zOrder) {
        return convertTableSimple(idmlTable, x, y, zOrder, null, null, null, null, null);
    }

    public static ASTTable convertTableSimple(IDMLTable idmlTable,
                                        long x, long y, int zOrder,
                                        IDMLDocument idmlDoc,
                                        kr.dogfoot.hwpxlib.tool.idmlconverter.util.ColorResolver colorResolver,
                                        kr.dogfoot.hwpxlib.tool.idmlconverter.converter.ASTImageLoader imageLoader,
                                        ResolvedData resolvedData) {
        return convertTableSimple(idmlTable, x, y, zOrder,
                idmlDoc, colorResolver, imageLoader, resolvedData, null);
    }

    /**
     * 셀 단락/런 빌드 seam. ctx를 가진 호출부(TableBuilder)가 공급하면 셀이
     * 셀 밖과 같은 공용 런 빌더(RunBuilder.createRunFromIDML)를 쓴다.
     */
    public interface CellParagraphBuilder {
        java.util.List<ASTParagraph> build(IDMLTable idmlTable, IDMLTableCell idmlCell);
    }

    public static ASTTable convertTableSimple(IDMLTable idmlTable,
                                        long x, long y, int zOrder,
                                        IDMLDocument idmlDoc,
                                        kr.dogfoot.hwpxlib.tool.idmlconverter.util.ColorResolver colorResolver,
                                        kr.dogfoot.hwpxlib.tool.idmlconverter.converter.ASTImageLoader imageLoader,
                                        ResolvedData resolvedData,
                                        StylePropertyResolver styleResolver) {
        return convertTableSimple(idmlTable, x, y, zOrder, idmlDoc, colorResolver,
                imageLoader, resolvedData, styleResolver, null);
    }

    public static ASTTable convertTableSimple(IDMLTable idmlTable,
                                        long x, long y, int zOrder,
                                        IDMLDocument idmlDoc,
                                        kr.dogfoot.hwpxlib.tool.idmlconverter.util.ColorResolver colorResolver,
                                        kr.dogfoot.hwpxlib.tool.idmlconverter.converter.ASTImageLoader imageLoader,
                                        ResolvedData resolvedData,
                                        StylePropertyResolver styleResolver,
                                        CellParagraphBuilder cellParagraphBuilder) {
        ASTTable table = new ASTTable();
        table.sourceId(idmlTable.selfId());
        table.zOrder(zOrder);
        table.x(x);
        table.y(y);

        // 컬럼 너비
        for (double cw : idmlTable.columnWidths()) {
            table.addColumnWidth(CoordinateConverter.pointsToHwpunits(cw));
        }
        table.colCount(idmlTable.columnWidths().size());

        // 행 변환
        boolean useFullConvert = (idmlDoc != null && colorResolver != null);
        long totalHeight = 0;
        int rowIdx = 0;
        boolean[][] occupied = tableCellOccupancy(idmlTable);
        for (IDMLTableRow idmlRow : idmlTable.rows()) {
            ASTTableRow row = new ASTTableRow();
            row.rowIndex(rowIdx);
            row.rowHeight(CoordinateConverter.pointsToHwpunits(idmlRow.rowHeight()));
            row.autoGrow(idmlRow.autoGrow());
            totalHeight += row.rowHeight();

            for (IDMLTableCell idmlCell : idmlRow.cells()) {
                if (isCoveredTableCell(occupied, idmlCell)) continue;
                ASTTableCell cell;
                if (useFullConvert) {
                    cell = convertTableCell(idmlCell, rowIdx, idmlCell.columnIndex(),
                            idmlDoc, colorResolver, imageLoader, resolvedData);
                } else {
                    cell = convertTableCellSimple(
                            idmlTable, idmlCell, rowIdx, idmlCell.columnIndex(), resolvedData, styleResolver,
                            cellParagraphBuilder);
                }
                row.addCell(cell);
                markTableCellOccupied(occupied, idmlCell);
            }

            table.addRow(row);
            rowIdx++;
        }
        table.rowCount(rowIdx);

        // 테이블 크기
        long totalWidth = 0;
        for (long cw : table.columnWidths()) totalWidth += cw;
        table.width(totalWidth);
        table.height(totalHeight);

        // 셀 크기 계산
        List<Long> colWidths = table.columnWidths();
        for (ASTTableRow row : table.rows()) {
            for (ASTTableCell cell : row.cells()) {
                long cellWidth = 0;
                int startCol = cell.columnIndex();
                int endCol = Math.min(startCol + cell.columnSpan(), colWidths.size());
                for (int c = startCol; c < endCol; c++) cellWidth += colWidths.get(c);
                cell.width(cellWidth);

                long cellHeight = 0;
                int startRow = cell.rowIndex();
                int endRow = Math.min(startRow + cell.rowSpan(), table.rows().size());
                for (int r = startRow; r < endRow; r++) cellHeight += table.rows().get(r).rowHeight();
                cell.height(cellHeight);
            }
        }

        normalizeInheritedTableBorders(table);
        clearHorizontalInsetsForEmptyColumns(table);
        ASTTableSpacerMerger.merge(table);
        removeDuplicatedTextFromSpacerCells(table);
        double[] resolvedTableBounds = resolvedData != null
                ? resolvedData.getTableBounds(idmlTable.selfId()) : null;
        double[] placementBounds = resolvedData != null
                ? (hasValidBounds(resolvedTableBounds)
                        ? resolvedTableBounds
                        : resolvedData.getTablePlacementBounds(idmlTable.selfId()))
                : null;
        boolean hasResolvedTableBounds = hasValidBounds(resolvedTableBounds);
        if (hasResolvedTableBounds) {
            applyPlacementBounds(table, resolvedTableBounds,
                    resolvedData != null ? resolvedData.scaleFactor() : 2.8346);
        } else if (hasValidBounds(placementBounds)) {
            applyPlacementOrigin(table, placementBounds,
                    resolvedData != null ? resolvedData.scaleFactor() : 2.8346);
        }
        table.fixedOuterBounds(hasResolvedTableBounds);
        if (hasResolvedTableBounds) {
            lockFixedOuterBoundsRows(table);
        }
        ensureRowsFitVisibleCellContent(table);
        return table;
    }

    public static ASTTable convertResolvedTableSimple(
            ResolvedTable resolvedTable,
            long x,
            long y,
            int zOrder,
            ResolvedData resolvedData) {
        if (resolvedTable == null) return null;
        ASTTable table = new ASTTable();
        table.sourceId(resolvedTable.id());
        table.zOrder(zOrder);
        table.x(x);
        table.y(y);

        double scale = resolvedData != null ? resolvedData.scaleFactor() : 2.8346;
        int colCount = Math.max(0, resolvedTable.columnCount());
        double[] columnWidths = resolvedTable.columnWidths();
        if (columnWidths != null && columnWidths.length > 0) {
            for (double cw : columnWidths) {
                table.addColumnWidth(CoordinateConverter.pointsToHwpunits(cw));
            }
            colCount = columnWidths.length;
        } else if (colCount > 0 && hasValidBounds(resolvedTable.bounds())) {
            double totalWidthPt = Math.max(0, resolvedTable.bounds()[3] - resolvedTable.bounds()[1]) * scale;
            long each = CoordinateConverter.pointsToHwpunits(totalWidthPt / colCount);
            for (int c = 0; c < colCount; c++) table.addColumnWidth(each);
        }
        table.colCount(colCount);

        int rowCount = Math.max(0, resolvedTable.rowCount());
        double[] rowHeights = resolvedTable.rowHeights();
        if (rowHeights != null && rowHeights.length > 0) {
            rowCount = rowHeights.length;
        }

        long totalHeight = 0;
        for (int r = 0; r < rowCount; r++) {
            ASTTableRow row = new ASTTableRow();
            row.rowIndex(r);
            long rowHeight = resolvedRowHeight(resolvedTable, rowHeights, r, rowCount, scale);
            row.rowHeight(rowHeight);
            row.autoGrow(false);
            totalHeight += rowHeight;

            if (resolvedTable.cells() != null) {
                for (ResolvedTable.Cell resolvedCell : resolvedTable.cells()) {
                    if (resolvedCell == null || resolvedCell.row() != r) continue;
                    ASTTableCell cell = convertResolvedCell(resolvedCell, resolvedData);
                    applyResolvedCellSize(table, row, cell);
                    row.addCell(cell);
                }
            }
            table.addRow(row);
        }
        table.rowCount(rowCount);

        long totalWidth = 0;
        for (long cw : table.columnWidths()) totalWidth += cw;
        table.width(totalWidth);
        table.height(totalHeight);

        if (hasValidBounds(resolvedTable.bounds())) {
            applyPlacementBounds(table, resolvedTable.bounds(), scale);
            table.fixedOuterBounds(true);
            lockFixedOuterBoundsRows(table);
        }
        ensureRowsFitVisibleCellContent(table);
        return table;
    }

    private static long resolvedRowHeight(
            ResolvedTable resolvedTable,
            double[] rowHeights,
            int row,
            int rowCount,
            double scale) {
        if (rowHeights != null && row >= 0 && row < rowHeights.length) {
            return CoordinateConverter.pointsToHwpunits(rowHeights[row]);
        }
        if (rowCount > 0 && hasValidBounds(resolvedTable.bounds())) {
            double totalHeightPt = Math.max(0, resolvedTable.bounds()[2] - resolvedTable.bounds()[0]) * scale;
            return CoordinateConverter.pointsToHwpunits(totalHeightPt / rowCount);
        }
        return CoordinateConverter.pointsToHwpunits(12);
    }

    private static ASTTableCell convertResolvedCell(
            ResolvedTable.Cell resolvedCell,
            ResolvedData resolvedData) {
        ASTTableCell cell = new ASTTableCell();
        cell.rowIndex(resolvedCell.row());
        cell.columnIndex(resolvedCell.col());
        cell.rowSpan(resolvedCell.rowSpan());
        cell.columnSpan(resolvedCell.colSpan());
        if (!isNoneColor(resolvedCell.fillColor())) {
            cell.fillColor(resolveTableColor(resolvedData, resolvedCell.fillColor(), resolvedCell.fillTint()));
        }
        ResolvedStory cellStory = new ResolvedStory();
        if (resolvedCell.paragraphs() != null) {
            for (ResolvedParagraph paragraph : resolvedCell.paragraphs()) {
                cellStory.addParagraph(paragraph);
            }
        }
        List<ASTParagraph> paragraphs = convertResolvedStoryForCell(cellStory, resolvedData);
        if (paragraphs != null) {
            for (ASTParagraph paragraph : paragraphs) {
                if (paragraph != null) cell.addParagraph(paragraph);
            }
        }
        return cell;
    }

    private static void applyResolvedCellSize(ASTTable table, ASTTableRow row, ASTTableCell cell) {
        if (table == null || row == null || cell == null) return;
        long width = 0;
        int startCol = Math.max(0, cell.columnIndex());
        int endCol = Math.min(table.columnWidths().size(), startCol + Math.max(1, cell.columnSpan()));
        for (int c = startCol; c < endCol; c++) width += table.columnWidths().get(c);
        cell.width(width);
        cell.height(row.rowHeight() * Math.max(1, cell.rowSpan()));
    }

    private static boolean[][] tableCellOccupancy(IDMLTable table) {
        int rows = table != null && table.rows() != null ? table.rows().size() : 0;
        int cols = table != null && table.columnWidths() != null ? table.columnWidths().size() : 0;
        if (rows <= 0 || cols <= 0) return new boolean[0][0];
        return new boolean[rows][cols];
    }

    private static boolean isCoveredTableCell(boolean[][] occupied, IDMLTableCell cell) {
        if (occupied == null || occupied.length == 0 || cell == null) return false;
        int row = cell.rowIndex();
        int col = cell.columnIndex();
        if (row < 0 || row >= occupied.length) return false;
        if (col < 0 || col >= occupied[row].length) return false;
        return occupied[row][col];
    }

    private static void markTableCellOccupied(boolean[][] occupied, IDMLTableCell cell) {
        if (occupied == null || occupied.length == 0 || cell == null) return;
        int row0 = Math.max(0, cell.rowIndex());
        int col0 = Math.max(0, cell.columnIndex());
        int row1 = Math.min(occupied.length, row0 + Math.max(1, cell.rowSpan()));
        for (int r = row0; r < row1; r++) {
            if (occupied[r] == null || occupied[r].length == 0) continue;
            int col1 = Math.min(occupied[r].length, col0 + Math.max(1, cell.columnSpan()));
            for (int c = col0; c < col1; c++) {
                occupied[r][c] = true;
            }
        }
    }

    private static boolean hasValidBounds(double[] bounds) {
        if (bounds == null || bounds.length < 4) return false;
        if (!Double.isFinite(bounds[0]) || !Double.isFinite(bounds[1])
                || !Double.isFinite(bounds[2]) || !Double.isFinite(bounds[3])) {
            return false;
        }
        return bounds[3] > bounds[1] && bounds[2] > bounds[0];
    }

    private static void lockFixedOuterBoundsRows(ASTTable table) {
        if (table == null || table.rows() == null) return;
        for (ASTTableRow row : table.rows()) {
            if (row != null) {
                row.autoGrow(false);
            }
        }
        recalcCellSizes(table);
    }

    private static void ensureRowsFitVisibleCellContent(ASTTable table) {
        if (table == null || table.rows() == null || table.rows().isEmpty()) return;
        boolean changed = false;
        for (ASTTableRow row : table.rows()) {
            if (row == null || row.cells() == null) continue;
            long required = 0;
            for (ASTTableCell cell : row.cells()) {
                if (cell == null) continue;
                long contentMin = minimumCellContentHeight(cell);
                if (contentMin <= 0) continue;
                if (cell.rowSpan() <= 1) {
                    required = Math.max(required, contentMin);
                } else {
                    long currentSpanHeight = spannedRowHeight(table, cell.rowIndex(), cell.rowSpan());
                    if (contentMin > currentSpanHeight) {
                        required = Math.max(required, row.rowHeight() + (contentMin - currentSpanHeight));
                    }
                }
            }
            if (required > 0 && row.rowHeight() < required) {
                row.rowHeight(required);
                changed = true;
            }
        }
        if (changed) {
            long h = 0;
            for (ASTTableRow row : table.rows()) {
                if (row != null) h += row.rowHeight();
            }
            table.height(h);
            recalcCellSizes(table);
        }
    }

    private static long minimumCellContentHeight(ASTTableCell cell) {
        if (cell == null || cell.paragraphs() == null || cell.paragraphs().isEmpty()) return 0;
        long content = 0;
        for (ASTParagraph paragraph : cell.paragraphs()) {
            long paragraphMin = minimumParagraphHeight(paragraph);
            if (paragraphMin <= 0) continue;
            content += paragraphMin;
        }
        if (content <= 0) return 0;
        return content + Math.max(0, cell.marginTop()) + Math.max(0, cell.marginBottom());
    }

    private static long minimumParagraphHeight(ASTParagraph paragraph) {
        if (paragraph == null) return 0;
        long maxInline = 0;
        boolean hasVisibleText = false;
        if (paragraph.items() != null) {
            for (ASTInlineItem item : paragraph.items()) {
                if (item instanceof ASTTextRun) {
                    ASTTextRun run = (ASTTextRun) item;
                    String text = run.text();
                    if (text == null || text.trim().isEmpty()) continue;
                    hasVisibleText = true;
                    Integer fontSize = run.fontSizeHwpunits();
                    if (fontSize != null && fontSize > 0) {
                        maxInline = Math.max(maxInline, Math.round(fontSize * 1.25));
                    }
                } else if (item instanceof ASTInlineObject) {
                    ASTInlineObject obj = (ASTInlineObject) item;
                    maxInline = Math.max(maxInline, Math.max(obj.height(), obj.containerHeight()));
                }
            }
        }
        if (!hasVisibleText && maxInline <= 0 && paragraph.inlineTable() == null) return 0;
        long lineHeight = 0;
        if ("fixed".equals(paragraph.lineSpacingType()) && paragraph.lineSpacing() != null
                && paragraph.lineSpacing() > 0) {
            lineHeight = paragraph.lineSpacing();
        }
        if (lineHeight <= 0 && hasVisibleText) {
            lineHeight = Math.max(maxInline, CoordinateConverter.pointsToHwpunits(10.0 * 1.25));
        }
        if (paragraph.inlineTable() != null) {
            lineHeight = Math.max(lineHeight, paragraph.inlineTable().height());
        }
        long min = Math.max(lineHeight, maxInline);
        if (paragraph.spaceBefore() != null && paragraph.spaceBefore() > 0) min += paragraph.spaceBefore();
        if (paragraph.spaceAfter() != null && paragraph.spaceAfter() > 0) min += paragraph.spaceAfter();
        return min;
    }

    private static long spannedRowHeight(ASTTable table, int startRow, int rowSpan) {
        if (table == null || table.rows() == null || startRow < 0 || rowSpan <= 0) return 0;
        long height = 0;
        int end = Math.min(table.rows().size(), startRow + rowSpan);
        for (int i = startRow; i < end; i++) {
            ASTTableRow row = table.rows().get(i);
            if (row != null) height += row.rowHeight();
        }
        return height;
    }

    public static void applyPlacementBounds(ASTTable table, double[] bounds, double scale) {
        if (table == null || bounds == null || bounds.length < 4) return;
        if (!Double.isFinite(bounds[0]) || !Double.isFinite(bounds[1])
                || !Double.isFinite(bounds[2]) || !Double.isFinite(bounds[3])) {
            return;
        }
        double width = bounds[3] - bounds[1];
        double height = bounds[2] - bounds[0];
        if (width <= 0 || height <= 0) return;
        double s = scale > 0 ? scale : 1.0;
        long x = CoordinateConverter.pointsToHwpunits(bounds[1] * s);
        long y = CoordinateConverter.pointsToHwpunits(bounds[0] * s);
        long targetWidth = CoordinateConverter.pointsToHwpunits(width * s);
        long targetHeight = CoordinateConverter.pointsToHwpunits(height * s);
        table.x(x);
        table.y(y);
        if (targetWidth > 0) scaleColumnsToWidth(table, targetWidth);
        /*
         * Placement bounds describe where the IDML table is anchored on the page.
         * They must not become a second source for row geometry: IDML rowHeights
         * already carry the table's internal layout. Scaling rows to an owner
         * TextFrame's outer bounds creates artificial vertical gaps, especially
         * for icon-row + label-row tables.
         */
        if (targetHeight > 0 && table.height() <= 0) {
            table.height(targetHeight);
        }
        recalcCellSizes(table);
    }

    public static void applyPlacementOrigin(ASTTable table, double[] bounds, double scale) {
        if (table == null || bounds == null || bounds.length < 4) return;
        if (!Double.isFinite(bounds[0]) || !Double.isFinite(bounds[1])) return;
        double s = scale > 0 ? scale : 1.0;
        table.x(CoordinateConverter.pointsToHwpunits(bounds[1] * s));
        table.y(CoordinateConverter.pointsToHwpunits(bounds[0] * s));
    }

    private static void scaleColumnsToWidth(ASTTable table, long targetWidth) {
        if (table.columnWidths() == null || table.columnWidths().isEmpty()) return;
        long current = 0;
        for (Long width : table.columnWidths()) {
            if (width != null) current += width;
        }
        if (current <= 0 || targetWidth <= 0) return;
        long used = 0;
        int last = table.columnWidths().size() - 1;
        for (int i = 0; i < table.columnWidths().size(); i++) {
            long next;
            if (i == last) {
                next = Math.max(1L, targetWidth - used);
            } else {
                long original = Math.max(1L, table.columnWidths().get(i));
                next = Math.max(1L, Math.round(original * (double) targetWidth / current));
                used += next;
            }
            table.columnWidths().set(i, next);
        }
        table.width(targetWidth);
    }

    private static void scaleRowsToHeight(ASTTable table, long targetHeight) {
        if (table.rows() == null || table.rows().isEmpty()) return;
        long current = 0;
        for (ASTTableRow row : table.rows()) {
            if (row != null) current += row.rowHeight();
        }
        if (current <= 0 || targetHeight <= 0) return;
        long used = 0;
        int last = table.rows().size() - 1;
        for (int i = 0; i < table.rows().size(); i++) {
            ASTTableRow row = table.rows().get(i);
            if (row == null) continue;
            long next;
            if (i == last) {
                next = Math.max(1L, targetHeight - used);
            } else {
                next = Math.max(1L, Math.round(row.rowHeight() * (double) targetHeight / current));
                used += next;
            }
            row.rowHeight(next);
        }
        table.height(targetHeight);
    }

    private static void recalcCellSizes(ASTTable table) {
        if (table == null || table.rows() == null || table.columnWidths() == null) return;
        for (ASTTableRow row : table.rows()) {
            if (row == null || row.cells() == null) continue;
            for (ASTTableCell cell : row.cells()) {
                if (cell == null) continue;
                long cellWidth = 0;
                int startCol = cell.columnIndex();
                int endCol = Math.min(startCol + Math.max(1, cell.columnSpan()), table.columnWidths().size());
                for (int c = Math.max(0, startCol); c < endCol; c++) {
                    cellWidth += table.columnWidths().get(c);
                }
                cell.width(cellWidth);

                long cellHeight = 0;
                int startRow = cell.rowIndex();
                int endRow = Math.min(startRow + Math.max(1, cell.rowSpan()), table.rows().size());
                for (int r = Math.max(0, startRow); r < endRow; r++) {
                    cellHeight += table.rows().get(r).rowHeight();
                }
                cell.height(cellHeight);
            }
        }
    }

    private static void removeDuplicatedTextFromSpacerCells(ASTTable table) {
        if (table == null || table.rows() == null || table.columnWidths() == null
                || table.columnWidths().isEmpty()) {
            return;
        }
        long totalWidth = 0;
        for (Long width : table.columnWidths()) {
            if (width != null) totalWidth += Math.max(0L, width);
        }
        if (totalWidth <= 0) return;

        for (ASTTableRow row : table.rows()) {
            if (row == null || row.cells() == null || row.cells().size() < 2) continue;
            for (ASTTableCell narrow : row.cells()) {
                if (!isNarrowSpacerCell(table, narrow, totalWidth)) continue;
                String signature = visibleTextSignature(narrow);
                if (signature.length() < 4) continue;
                ASTTableCell widerOwner = duplicatedWiderTextOwner(row, narrow, signature);
                if (widerOwner != null) {
                    narrow.paragraphs().clear();
                }
            }
        }
    }

    private static boolean isNarrowSpacerCell(ASTTable table, ASTTableCell cell, long totalWidth) {
        if (table == null || cell == null || totalWidth <= 0) return false;
        long width = cell.width();
        if (width <= 0 && table.columnWidths() != null) {
            int start = Math.max(0, cell.columnIndex());
            int end = Math.min(table.columnWidths().size(), start + Math.max(1, cell.columnSpan()));
            for (int c = start; c < end; c++) {
                Long colWidth = table.columnWidths().get(c);
                if (colWidth != null) width += Math.max(0L, colWidth);
            }
        }
        return width > 0 && width * 100 <= totalWidth * 18;
    }

    private static ASTTableCell duplicatedWiderTextOwner(
            ASTTableRow row,
            ASTTableCell narrow,
            String signature) {
        if (row == null || narrow == null || signature == null || signature.isEmpty()) return null;
        long narrowWidth = Math.max(1L, narrow.width());
        for (ASTTableCell candidate : row.cells()) {
            if (candidate == null || candidate == narrow) continue;
            if (candidate.width() <= narrowWidth * 2) continue;
            if (isDuplicatedTextSignature(signature, visibleTextSignature(candidate))) {
                return candidate;
            }
        }
        return null;
    }

    private static boolean isDuplicatedTextSignature(String a, String b) {
        if (a == null || b == null) return false;
        if (a.isEmpty() || b.isEmpty()) return false;
        if (a.equals(b)) return true;
        int prefix = commonPrefixLength(a, b);
        int shorter = Math.min(a.length(), b.length());
        return prefix >= 16 && prefix * 2 >= shorter;
    }

    private static int commonPrefixLength(String a, String b) {
        int len = Math.min(a != null ? a.length() : 0, b != null ? b.length() : 0);
        int i = 0;
        while (i < len && a.charAt(i) == b.charAt(i)) {
            i++;
        }
        return i;
    }

    private static String visibleTextSignature(ASTTableCell cell) {
        if (cell == null || cell.paragraphs() == null || cell.paragraphs().isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (ASTParagraph paragraph : cell.paragraphs()) {
            appendVisibleText(sb, paragraph);
        }
        return sb.toString().replaceAll("\\s+", " ").trim();
    }

    private static void appendVisibleText(StringBuilder sb, ASTParagraph paragraph) {
        if (sb == null || paragraph == null || paragraph.items() == null) return;
        for (ASTInlineItem item : paragraph.items()) {
            if (item instanceof ASTTextRun) {
                String text = ((ASTTextRun) item).text();
                if (text != null && !text.isEmpty()) sb.append(text);
            } else if (item instanceof ASTInlineObject) {
                ASTInlineObject obj = (ASTInlineObject) item;
                if (obj.paragraphs() != null) {
                    for (ASTParagraph nested : obj.paragraphs()) {
                        appendVisibleText(sb, nested);
                    }
                }
                if (obj.inlineTables() != null) {
                    for (ASTTable table : obj.inlineTables()) {
                        appendVisibleText(sb, table);
                    }
                }
            }
        }
    }

    private static void appendVisibleText(StringBuilder sb, ASTTable table) {
        if (sb == null || table == null || table.rows() == null) return;
        for (ASTTableRow row : table.rows()) {
            if (row == null || row.cells() == null) continue;
            for (ASTTableCell cell : row.cells()) {
                if (cell == null || cell.paragraphs() == null) continue;
                for (ASTParagraph paragraph : cell.paragraphs()) {
                    appendVisibleText(sb, paragraph);
                }
            }
        }
    }

    /**
     * IDML table cells sometimes omit an edge stroke weight when the edge style is
     * inherited, while still carrying a visible stroke color/priority. HWPX needs a
     * concrete width per edge, so visible zero-width edges borrow the dominant
     * visible weight from the same converted table.
     */
    private static void normalizeInheritedTableBorders(ASTTable table) {
        if (table == null || table.rows() == null) return;
        double fallbackWeight = dominantVisibleBorderWeight(table);
        if (fallbackWeight <= 0) return;
        for (ASTTableRow row : table.rows()) {
            if (row == null || row.cells() == null) continue;
            for (ASTTableCell cell : row.cells()) {
                if (cell == null) continue;
                normalizeInheritedBorder(cell.leftBorder(), fallbackWeight);
                normalizeInheritedBorder(cell.rightBorder(), fallbackWeight);
                normalizeInheritedBorder(cell.topBorder(), fallbackWeight);
                normalizeInheritedBorder(cell.bottomBorder(), fallbackWeight);
                normalizeInheritedBorder(cell.diagonalBorder(), fallbackWeight);
            }
        }
    }

    private static double dominantVisibleBorderWeight(ASTTable table) {
        double max = 0;
        for (ASTTableRow row : table.rows()) {
            if (row == null || row.cells() == null) continue;
            for (ASTTableCell cell : row.cells()) {
                if (cell == null) continue;
                max = Math.max(max, visibleWeight(cell.leftBorder()));
                max = Math.max(max, visibleWeight(cell.rightBorder()));
                max = Math.max(max, visibleWeight(cell.topBorder()));
                max = Math.max(max, visibleWeight(cell.bottomBorder()));
                max = Math.max(max, visibleWeight(cell.diagonalBorder()));
            }
        }
        return max;
    }

    private static double visibleWeight(ASTTableCell.CellBorder border) {
        return border != null && border.weight() > 0 ? border.weight() : 0;
    }

    private static void normalizeInheritedBorder(ASTTableCell.CellBorder border, double fallbackWeight) {
        if (border == null || border.weight() > 0) return;
        if (isNoneColor(border.color())) return;
        border.weight(fallbackWeight);
        if (border.strokeType() == null || border.strokeType().isEmpty()) {
            border.strokeType("Solid");
        }
    }

    /**
     * IDML에는 얇은 빈 컬럼을 시각적 간격/분리선 용도로 넣는 경우가 있다.
     * HWP 셀은 내용이 없어도 margin이 폭 계산에 영향을 줄 수 있으므로,
     * 컬럼 전체가 비어 있으면 해당 컬럼 셀의 좌우 inset을 제거한다.
     */
    private static void clearHorizontalInsetsForEmptyColumns(ASTTable table) {
        if (table == null || table.colCount() <= 0 || table.rows() == null || table.rows().isEmpty()) return;
        boolean[] emptyColumn = new boolean[table.colCount()];
        boolean[] seenColumn = new boolean[table.colCount()];
        java.util.Arrays.fill(emptyColumn, true);

        for (ASTTableRow row : table.rows()) {
            for (ASTTableCell cell : row.cells()) {
                int start = Math.max(0, cell.columnIndex());
                int end = Math.min(table.colCount(), start + Math.max(1, cell.columnSpan()));
                boolean empty = isCellEmptyForColumnPolicy(cell);
                for (int c = start; c < end; c++) {
                    seenColumn[c] = true;
                    if (!empty) emptyColumn[c] = false;
                }
            }
        }

        for (int c = 0; c < emptyColumn.length; c++) {
            if (!seenColumn[c]) emptyColumn[c] = false;
        }

        for (ASTTableRow row : table.rows()) {
            for (ASTTableCell cell : row.cells()) {
                int start = Math.max(0, cell.columnIndex());
                int end = Math.min(table.colCount(), start + Math.max(1, cell.columnSpan()));
                boolean allCoveredColumnsEmpty = true;
                for (int c = start; c < end; c++) {
                    if (!emptyColumn[c]) {
                        allCoveredColumnsEmpty = false;
                        break;
                    }
                }
                if (allCoveredColumnsEmpty && isCellEmptyForColumnPolicy(cell)) {
                    cell.marginLeft(0);
                    cell.marginRight(0);
                }
            }
        }
    }

    private static boolean isCellEmptyForColumnPolicy(ASTTableCell cell) {
        if (cell == null || cell.paragraphs() == null || cell.paragraphs().isEmpty()) return true;
        for (ASTParagraph p : cell.paragraphs()) {
            if (p.items() == null || p.items().isEmpty()) continue;
            for (ASTInlineItem item : p.items()) {
                if (item == null) continue;
                if (item.itemType() != ASTInlineItem.ItemType.TEXT_RUN) return false;
                ASTTextRun run = (ASTTextRun) item;
                if (run.text() != null && !run.text().trim().isEmpty()) return false;
            }
        }
        return true;
    }

    /**
     * 간소화 셀 변환 (ColorResolver 없이).
     */
    private static ASTTableCell convertTableCellSimple(IDMLTable idmlTable,
                                                        IDMLTableCell idmlCell,
                                                        int rowIdx, int colIdx,
                                                        ResolvedData resolvedData,
                                                        StylePropertyResolver styleResolver,
                                                        CellParagraphBuilder cellParagraphBuilder) {
        ASTTableCell cell = new ASTTableCell();
        cell.rowIndex(rowIdx);
        cell.columnIndex(colIdx);
        cell.rowSpan(idmlCell.rowSpan());
        cell.columnSpan(idmlCell.columnSpan());

        // nested TextFrame/table 콘텐츠가 있는 셀은 직접 텍스트가 비어 있어도
        // 빈 셀(spacer)이 아니다. 콘텐츠 복원은 buildPreparedAstTable에서 이후 단계에 일어난다.
        if ((idmlCell.textFrameStoryRefs() != null && !idmlCell.textFrameStoryRefs().isEmpty())
                || idmlCell.hasDirectNestedTables()) {
            cell.reservedForNestedContent(true);
        }

        if (idmlCell.fillColor() != null) {
            cell.fillColor(resolveTableColor(resolvedData, idmlCell.fillColor(), idmlCell.fillTint()));
        } else {
            ResolvedTable.Cell resolvedCell = findResolvedCell(resolvedData, idmlTable, idmlCell);
            if (resolvedCell != null && !isNoneColor(resolvedCell.fillColor())) {
                cell.fillColor(resolveTableColor(resolvedData, resolvedCell.fillColor(), resolvedCell.fillTint()));
            }
        }
        cell.verticalAlign(idmlCell.verticalJustification());
        cell.firstBaselineOffset(idmlCell.firstBaselineOffset());
        cell.minimumFirstBaselineOffset(CoordinateConverter.pointsToHwpunits(
                idmlCell.minimumFirstBaselineOffset()));

        // 셀 여백
        cell.marginTop(CoordinateConverter.pointsToHwpunits(idmlCell.topInset()));
        cell.marginBottom(CoordinateConverter.pointsToHwpunits(idmlCell.bottomInset()));
        cell.marginLeft(CoordinateConverter.pointsToHwpunits(idmlCell.leftInset()));
        cell.marginRight(CoordinateConverter.pointsToHwpunits(idmlCell.rightInset()));

        // 셀 테두리 (색상 해석 없이)
        cell.topBorder(convertCellBorderSimple(idmlCell.topBorder(), resolvedData));
        cell.bottomBorder(convertCellBorderSimple(idmlCell.bottomBorder(), resolvedData));
        cell.leftBorder(convertCellBorderSimple(idmlCell.leftBorder(), resolvedData));
        cell.rightBorder(convertCellBorderSimple(idmlCell.rightBorder(), resolvedData));

        // 대각선
        cell.topLeftDiagonalLine(idmlCell.topLeftDiagonalLine());
        cell.topRightDiagonalLine(idmlCell.topRightDiagonalLine());
        cell.diagonalBorder(convertCellBorderSimple(idmlCell.diagonalBorder(), resolvedData));

        // 셀 내용: 셀 밖과 같은 공용 런 빌더(ctx 보유 호출부 제공) 우선, 없으면 간소 폴백.
        boolean usedCellParagraphBuilder = cellParagraphBuilder != null;
        if (usedCellParagraphBuilder) {
            java.util.List<ASTParagraph> builtParagraphs = cellParagraphBuilder.build(idmlTable, idmlCell);
            if (builtParagraphs != null) {
                for (ASTParagraph astPara : builtParagraphs) {
                    if (astPara != null) {
                        cell.addParagraph(astPara);
                    }
                }
            }
        } else {
            for (IDMLParagraph cellPara : idmlCell.paragraphs()) {
                ASTParagraph astPara = new ASTParagraph();
                if (cellPara.appliedParagraphStyle() != null) {
                    astPara.paragraphStyleRef(cellPara.appliedParagraphStyle());
                }
                applyTableCellParagraphAlignment(astPara, cellPara, resolvedData, styleResolver);
                for (IDMLCharacterRun run : cellPara.characterRuns()) {
                    addSimpleTextRuns(astPara, run, cellPara.appliedParagraphStyle(),
                            resolvedData, styleResolver);
                }
                cell.addParagraph(astPara);
            }
        }
        if (!usedCellParagraphBuilder) {
            replaceFlattenedCellTextWithResolvedStory(cell, idmlCell, resolvedData);
        }
        normalizeTextHiddenInlineShellCarriers(cell, resolvedData);

        return cell;
    }

    private static void applyTableCellParagraphAlignment(
            ASTParagraph astPara,
            IDMLParagraph cellPara,
            ResolvedData resolvedData,
            StylePropertyResolver styleResolver) {
        if (astPara == null || cellPara == null) return;
        if (cellPara.justification() != null && !cellPara.justification().isEmpty()) {
            astPara.alignment(cellPara.justification());
            return;
        }
        String styleRef = cellPara.appliedParagraphStyle();
        if (styleRef == null || styleRef.isEmpty()) return;
        IDMLStyleDef style = styleResolver != null
                ? styleResolver.getResolvedParagraphStyle(styleRef)
                : null;
        if (style != null && style.textAlignment() != null && !style.textAlignment().isEmpty()) {
            astPara.alignment(style.textAlignment());
            return;
        }
        if (resolvedData != null) {
            String cleanStyle = cleanParagraphStyleName(styleRef);
            String resolvedJustification = resolvedData.getParagraphStyleJustification(cleanStyle);
            if (resolvedJustification != null && !resolvedJustification.isEmpty()) {
                astPara.alignment(resolvedJustification);
            }
        }
    }

    private static String cleanParagraphStyleName(String styleRef) {
        if (styleRef == null) return null;
        String clean = styleRef;
        int slash = clean.lastIndexOf('/');
        if (slash >= 0 && slash + 1 < clean.length()) {
            clean = clean.substring(slash + 1);
        }
        return clean;
    }

    private static void normalizeTextHiddenInlineShellCarriers(ASTTableCell cell, ResolvedData resolvedData) {
        if (cell == null || resolvedData == null || cell.paragraphs() == null) return;
        for (ASTParagraph para : cell.paragraphs()) {
            if (para == null || para.items() == null || para.items().isEmpty()) continue;
            for (ASTInlineItem item : para.items()) {
                if (!(item instanceof ASTInlineObject)) continue;
                ASTInlineObject obj = (ASTInlineObject) item;
                if (obj.kind() != ASTInlineObject.ObjectKind.INLINE_TEXT_FRAME) continue;
                String domId = domIdFromSourceId(obj.sourceId());
                if (domId == null) continue;
                TextHiddenShellMatch shell = findTextHiddenShellForInlineObject(resolvedData, domId);
                if (shell == null || shell.renderedGroup == null) continue;
                if (!hasPlannedInlineShellFill(obj, shell.plan)) {
                    applyExtractedShellAsInlineFrameFill(obj, shell.renderedGroup, resolvedData, domId);
                }
                populateInlineShellOwnedText(obj, shell.plan, resolvedData);
                removeStandaloneShellImage(para, shell.renderedGroup.id());
            }
        }
    }

    private static boolean hasPlannedInlineShellFill(ASTInlineObject obj, ObjectPlan plan) {
        return obj != null
                && plan != null
                && ((obj.imageFillData() != null && obj.imageFillData().length > 0)
                        || obj.nativeGraphicsAllowed());
    }

    private static TextHiddenShellMatch findTextHiddenShellForInlineObject(
            ResolvedData resolvedData, String inlineObjectDomId) {
        if (resolvedData == null || inlineObjectDomId == null) return null;
        kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ResolvedBuildContext ctx =
                tableBridgeContext(resolvedData);
        boolean hasStage1ObjectPlans = ctx.ownershipPlans != null && !ctx.ownershipPlans.isEmpty();
        int domId = parseDomIdOrNeg(inlineObjectDomId);
        if (hasStage1ObjectPlans) {
            return findPlannedTextHiddenShellForInlineObject(ctx, resolvedData, domId);
        }
        ResolvedTextFrame textFrame = resolvedData.getTextFrame(inlineObjectDomId);
        if (textFrame == null || !textFrame.isInline()) return null;
        if (resolvedData.allRenderedFloatingItems() == null) return null;
        for (RenderedGroup rg : resolvedData.allRenderedFloatingItems()) {
            if (!isTextHiddenShellForInlineTextFrame(rg)) continue;
            String[] ids = rg.editableTextFrameIds();
            if (ids == null) continue;
            for (String id : ids) {
                if (inlineObjectDomId.equals(id)) return new TextHiddenShellMatch(rg, null);
            }
        }
        return null;
    }

    private static TextHiddenShellMatch findPlannedTextHiddenShellForInlineObject(
            kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ResolvedBuildContext ctx,
            ResolvedData resolvedData,
            int inlineObjectDomId) {
        if (ctx == null || resolvedData == null || inlineObjectDomId < 0) return null;
        for (ObjectPlan plan : ctx.ownershipPlans) {
            if (!isPlannedTextHiddenShellForInlineObject(plan, inlineObjectDomId)) continue;
            RenderedGroup rg = renderedGroupByPlannedFile(resolvedData, plan.file);
            if (rg != null) return new TextHiddenShellMatch(rg, plan);
        }
        return null;
    }

    private static boolean isPlannedTextHiddenShellForInlineObject(
            ObjectPlan plan,
            int inlineObjectDomId) {
        if (plan == null || inlineObjectDomId < 0) return false;
        if (plan.visualAction
                != kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.VisualAction.PLACE_TEXT_SHELL) {
            return false;
        }
        if (!kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.ShellRole.isTextShell(plan)) {
            return false;
        }
        if (plan.domId != inlineObjectDomId
                && !containsInt(plan.sourceObjectIds, inlineObjectDomId)
                && !containsInt(plan.ownedTextFrameIds, inlineObjectDomId)) {
            return false;
        }
        return plan.file != null && !plan.file.isEmpty();
    }

    private static void populateInlineShellOwnedText(
            ASTInlineObject obj,
            ObjectPlan plan,
            ResolvedData resolvedData) {
        if (obj == null || plan == null || resolvedData == null) return;
        if (plan.ownedTextFrameIds == null || plan.ownedTextFrameIds.length == 0) return;
        // phase3 가 이미 오버레이 채널(자식 텍스트를 셸 내 상대좌표에 배치)을 구성한
        // 셸에는 소유 텍스트를 평문 문단으로 주입하지 않는다 — 주입하면
        // InlineFrameBuilder 의 !hasParagraphs 게이트에서 오버레이가 통째로 버려지고,
        // 가장자리 라벨(구절 풀이)이 본문과 한 문단 흐름으로 합쳐진다 (SPEC-041 p187).
        if (obj.overlayFrames() != null && !obj.overlayFrames().isEmpty()) return;
        if (inlineObjectHasVisibleText(obj)) return;
        List<ResolvedTextFrame> frames = new ArrayList<>();
        for (int id : plan.ownedTextFrameIds) {
            ResolvedTextFrame tf = resolvedData.getTextFrame(String.valueOf(id));
            if (tf == null || tf.storyId() == null) continue;
            if (tf.sourceHidden()) continue;
            frames.add(tf);
        }
        if (frames.isEmpty()) return;
        frames.sort((a, b) -> {
            double[] ab = a.pageRelativeBounds();
            double[] bb = b.pageRelativeBounds();
            double ay = ab != null && ab.length >= 4 ? ab[0] : 0;
            double by = bb != null && bb.length >= 4 ? bb[0] : 0;
            if (Math.abs(ay - by) > 1.0) return Double.compare(ay, by);
            double ax = ab != null && ab.length >= 4 ? ab[1] : 0;
            double bx = bb != null && bb.length >= 4 ? bb[1] : 0;
            return Double.compare(ax, bx);
        });
        List<ASTParagraph> paragraphs = new ArrayList<>();
        for (ResolvedTextFrame tf : frames) {
            ResolvedStory story = resolvedData.getStory(tf.storyId());
            if (story == null) continue;
            List<ASTParagraph> converted = convertResolvedStoryForCell(story, resolvedData);
            if (converted == null || converted.isEmpty()) continue;
            paragraphs.addAll(converted);
        }
        if (paragraphs.isEmpty()) return;
        obj.paragraphs(new ArrayList<>());
        obj.paragraphs().addAll(paragraphs);
    }

    private static boolean inlineObjectHasVisibleText(ASTInlineObject obj) {
        if (obj == null || obj.paragraphs() == null || obj.paragraphs().isEmpty()) return false;
        for (ASTParagraph paragraph : obj.paragraphs()) {
            if (paragraph == null || paragraph.items() == null) continue;
            for (ASTInlineItem item : paragraph.items()) {
                // SPEC-058: 수식(ASTEquation)도 가시 콘텐츠 — 반응식 박스가 통짜
                // 수식으로 승격되면 텍스트런이 없어져 "텍스트 없음"으로 오판되고,
                // 여기서 resolved 평문으로 재주입돼 수식이 사장되던 회귀 지점.
                if (item instanceof kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTEquation) {
                    String script = ((kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTEquation) item).hwpScript();
                    if (script != null && !script.trim().isEmpty()) return true;
                    continue;
                }
                if (!(item instanceof ASTTextRun)) continue;
                String text = ((ASTTextRun) item).text();
                if (text != null && !text.trim().isEmpty()) return true;
            }
        }
        return false;
    }

    private static final class TextHiddenShellMatch {
        final RenderedGroup renderedGroup;
        final ObjectPlan plan;

        TextHiddenShellMatch(RenderedGroup renderedGroup, ObjectPlan plan) {
            this.renderedGroup = renderedGroup;
            this.plan = plan;
        }
    }

    private static RenderedGroup renderedGroupByPlannedFile(ResolvedData resolvedData, String plannedFile) {
        if (resolvedData == null || plannedFile == null || plannedFile.isEmpty()
                || resolvedData.allRenderedFloatingItems() == null) {
            return null;
        }
        for (RenderedGroup rg : resolvedData.allRenderedFloatingItems()) {
            if (rg == null || rg.file() == null || rg.file().isEmpty()) continue;
            if (plannedFile.equals(rg.file())) return rg;
        }
        return null;
    }

    private static kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ResolvedBuildContext tableBridgeContext(
            ResolvedData resolvedData) {
        kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ResolvedBuildContext ctx =
                new kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ResolvedBuildContext();
        if (resolvedData != null) {
            ctx.resolvedData = resolvedData;
            ctx.basePath = resolvedData.basePath();
            ctx.scaleFactor = resolvedData.scaleFactor();
            if (resolvedData.ownershipPlans() != null) {
                ctx.ownershipPlans.addAll(resolvedData.ownershipPlans());
            }
        }
        return ctx;
    }

    private static int parseDomIdOrNeg(String id) {
        if (id == null) return -1;
        try {
            return Integer.parseInt(id);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static boolean containsInt(int[] ids, int target) {
        if (ids == null) return false;
        for (int id : ids) {
            if (id == target) return true;
        }
        return false;
    }

    private static boolean isTextHiddenShellForInlineTextFrame(RenderedGroup rg) {
        if (rg == null || !rg.hasEditableTextHiddenFromPng()) return false;
        if (rg.file() == null || rg.file().isEmpty()) return false;
        String role = rg.itemType() != null ? rg.itemType() : rg.type();
        if ("inline_object".equals(role)) return true;
        if ("TEXTLESS_SHELL_WITH_TF".equals(rg.atomicObjectKind())) return true;
        return rg.editableTextFrameIds() != null
                && rg.editableTextFrameIds().length > 0
                && "hwpx_tf".equals(rg.textOwner());
    }

    private static void applyExtractedShellAsInlineFrameFill(
            ASTInlineObject obj, RenderedGroup shell, ResolvedData resolvedData, String textFrameDomId) {
        byte[] shellPng = readRenderedPng(shell, resolvedData);
        if (shellPng == null) return;
        obj.imageFillData(shellPng);
        obj.forceImageFill(true);
        obj.nativeGraphicsAllowed(false);
        obj.fillColor(null);
        obj.strokeColor(null);
        obj.strokeWeight(0);
        obj.keepInline(true);
        ResolvedTextFrame tf = resolvedData != null ? resolvedData.getTextFrame(textFrameDomId) : null;
        if (tf != null && tf.cornerRadius() > 0) {
            obj.cornerRadius(tf.cornerRadius());
            obj.nativeGraphicsAllowed(true);
        }
    }

    private static byte[] readRenderedPng(RenderedGroup rg, ResolvedData resolvedData) {
        if (rg == null || rg.file() == null || resolvedData == null || resolvedData.basePath() == null) return null;
        try {
            File pngFile = new File(resolvedData.basePath(), rg.file());
            if (!pngFile.isFile()) return null;
            BufferedImage img = ImageIO.read(pngFile);
            if (img == null || img.getWidth() <= 2 || img.getHeight() <= 2) {
                return Files.readAllBytes(pngFile.toPath());
            }
            BufferedImage shell = VisualCropper.knockOutPaperLikeFill(img);
            if (shouldPreserveTransparentInlineShell(shell)) {
                BufferedImage blended = blendTransparentShellOverPagePlane(shell, rg, resolvedData);
                if (blended != null) return encodePng(blended);
            }
            return flattenOntoWhite(shell);
        } catch (Exception e) {
            return null;
        }
    }

    private static BufferedImage blendTransparentShellOverPagePlane(
            BufferedImage shell,
            RenderedGroup rg,
            ResolvedData resolvedData) {
        if (shell == null || rg == null || resolvedData == null || resolvedData.basePath() == null) return null;
        double[] bounds = normalizeRenderedBoundsToPageLocal(rg, resolvedData);
        if (!hasValidBounds(bounds)) return null;
        double pageW = localPageWidth(resolvedData, rg.pageIndex());
        double pageH = localPageHeight(resolvedData, rg.pageIndex());
        if (pageW <= 0.0 || pageH <= 0.0) return null;

        File pagePlaneFile = pageTextlessPlaneFile(resolvedData, rg.pageIndex());
        if (pagePlaneFile == null || !pagePlaneFile.isFile()) return null;
        try {
            BufferedImage pagePlane = ImageIO.read(pagePlaneFile);
            if (pagePlane == null || pagePlane.getWidth() <= 0 || pagePlane.getHeight() <= 0) return null;
            int sx1 = clamp((int) Math.round((bounds[1] / pageW) * pagePlane.getWidth()), 0, pagePlane.getWidth());
            int sy1 = clamp((int) Math.round((bounds[0] / pageH) * pagePlane.getHeight()), 0, pagePlane.getHeight());
            int sx2 = clamp((int) Math.round((bounds[3] / pageW) * pagePlane.getWidth()), 0, pagePlane.getWidth());
            int sy2 = clamp((int) Math.round((bounds[2] / pageH) * pagePlane.getHeight()), 0, pagePlane.getHeight());
            if (sx2 <= sx1 || sy2 <= sy1) return null;

            BufferedImage out = new BufferedImage(shell.getWidth(), shell.getHeight(), BufferedImage.TYPE_INT_RGB);
            Graphics2D g = out.createGraphics();
            try {
                g.drawImage(pagePlane, 0, 0, out.getWidth(), out.getHeight(), sx1, sy1, sx2, sy2, null);
                g.setComposite(AlphaComposite.SrcOver);
                g.drawImage(shell, 0, 0, out.getWidth(), out.getHeight(), null);
            } finally {
                g.dispose();
            }
            return out;
        } catch (Exception e) {
            return null;
        }
    }

    private static File pageTextlessPlaneFile(ResolvedData resolvedData, int pageIndex) {
        File direct = new File(resolvedData.basePath(), "rendered_frames/page_textless_plane_p" + (pageIndex + 1) + ".png");
        if (direct.isFile()) return direct;
        File dir = new File(resolvedData.basePath(), "rendered_frames");
        File[] files = dir.listFiles((d, name) -> name.startsWith("page_textless_plane_p") && name.endsWith(".png"));
        if (files != null && files.length == 1) return files[0];
        return direct;
    }

    private static double[] normalizeRenderedBoundsToPageLocal(RenderedGroup rg, ResolvedData resolvedData) {
        if (rg == null || !hasValidBounds(rg.bounds())) return null;
        double scale = resolvedData != null && resolvedData.scaleFactor() > 0.0 ? resolvedData.scaleFactor() : 1.0;
        double[] out = new double[]{
                rg.bounds()[0],
                rg.bounds()[1],
                rg.bounds()[2],
                rg.bounds()[3]
        };
        double[] page = pageBounds(resolvedData, rg.pageIndex());
        if (page == null) return out;
        double localPageWidth = localPageWidth(resolvedData, rg.pageIndex());
        double localPageHeight = localPageHeight(resolvedData, rg.pageIndex());
        if (scale > 1.0
                && localPageWidth > 0.0
                && (out[1] > localPageWidth * 2.5 || out[3] > localPageWidth * 3.0)) {
            out[1] /= scale;
            out[3] /= scale;
        }
        if (scale > 1.0
                && localPageHeight > 0.0
                && (out[0] > localPageHeight * 1.5 || out[2] > localPageHeight * 2.0)) {
            out[0] /= scale;
            out[2] /= scale;
        }
        double pageLeft = page[1] / scale;
        double pageTop = page[0] / scale;
        if (pageLeft > 1.0 && out[1] >= pageLeft - 0.5) {
            out[1] -= pageLeft;
            out[3] -= pageLeft;
        }
        if (pageTop > 1.0 && out[0] >= pageTop - 0.5) {
            out[0] -= pageTop;
            out[2] -= pageTop;
        }
        return out;
    }

    private static double localPageWidth(ResolvedData resolvedData, int pageIndex) {
        double[] page = pageBounds(resolvedData, pageIndex);
        if (page == null) return 0.0;
        double scale = resolvedData != null && resolvedData.scaleFactor() > 0.0 ? resolvedData.scaleFactor() : 1.0;
        return Math.abs(page[3] - page[1]) / scale;
    }

    private static double localPageHeight(ResolvedData resolvedData, int pageIndex) {
        double[] page = pageBounds(resolvedData, pageIndex);
        if (page == null) return 0.0;
        double scale = resolvedData != null && resolvedData.scaleFactor() > 0.0 ? resolvedData.scaleFactor() : 1.0;
        return Math.abs(page[2] - page[0]) / scale;
    }

    private static double[] pageBounds(ResolvedData resolvedData, int pageIndex) {
        if (resolvedData == null || resolvedData.pages() == null) return null;
        if (pageIndex >= 0 && pageIndex < resolvedData.pages().size()) {
            kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedPage byPosition =
                    resolvedData.pages().get(pageIndex);
            if (byPosition != null && byPosition.index() == pageIndex
                    && hasValidBounds(byPosition.bounds())) {
                return byPosition.bounds();
            }
        }
        for (kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedPage page : resolvedData.pages()) {
            if (page != null && page.index() == pageIndex && hasValidBounds(page.bounds())) {
                return page.bounds();
            }
        }
        return null;
    }

    private static boolean shouldPreserveTransparentInlineShell(BufferedImage img) {
        if (img == null || img.getWidth() <= 0 || img.getHeight() <= 0) return false;
        long total = (long) img.getWidth() * (long) img.getHeight();
        if (total <= 0) return false;

        long transparent = 0;
        long translucent = 0;
        long visible = 0;
        long opaquePaperLike = 0;
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                int argb = img.getRGB(x, y);
                int a = (argb >>> 24) & 0xFF;
                if (a <= 8) {
                    transparent++;
                    continue;
                }
                visible++;
                if (a < 245) translucent++;
                int r = (argb >>> 16) & 0xFF;
                int g = (argb >>> 8) & 0xFF;
                int b = argb & 0xFF;
                if (a >= 245 && r >= 245 && g >= 245 && b >= 245) opaquePaperLike++;
            }
        }
        if (transparent == 0 && translucent == 0) return false;
        double visibleRatio = (double) visible / (double) total;
        double paperRatio = (double) opaquePaperLike / (double) total;
        return paperRatio < 0.10 && visibleRatio > 0.002 && visibleRatio <= 0.35;
    }

    private static byte[] encodePng(BufferedImage image) throws java.io.IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ImageIO.write(image, "png", bos);
        return bos.toByteArray();
    }

    private static int clamp(int value, int min, int max) {
        if (value < min) return min;
        if (value > max) return max;
        return value;
    }

    private static byte[] flattenOntoWhite(BufferedImage src) throws java.io.IOException {
        BufferedImage flat = new BufferedImage(
                src.getWidth(), src.getHeight(), BufferedImage.TYPE_INT_RGB);
        java.awt.Graphics2D g = flat.createGraphics();
        g.setColor(java.awt.Color.WHITE);
        g.fillRect(0, 0, src.getWidth(), src.getHeight());
        g.drawImage(src, 0, 0, null);
        g.dispose();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        ImageIO.write(flat, "png", bos);
        return bos.toByteArray();
    }

    private static void removeStandaloneShellImage(ASTParagraph para, int shellDomId) {
        if (para == null || para.items() == null) return;
        String shellSourceId = "u" + Integer.toHexString(shellDomId);
        para.items().removeIf(item -> {
            if (!(item instanceof ASTInlineObject)) return false;
            ASTInlineObject obj = (ASTInlineObject) item;
            if (obj.kind() != ASTInlineObject.ObjectKind.RENDERED_GROUP
                    && obj.kind() != ASTInlineObject.ObjectKind.IMAGE) {
                return false;
            }
            return shellSourceId.equalsIgnoreCase(obj.sourceId());
        });
    }

    private static String domIdFromSourceId(String sourceId) {
        if (sourceId == null || sourceId.isEmpty()) return null;
        try {
            if (sourceId.charAt(0) == 'u' || sourceId.charAt(0) == 'U') {
                return String.valueOf(Integer.parseInt(sourceId.substring(1), 16));
            }
            return sourceId;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static void replaceFlattenedCellTextWithResolvedStory(
            ASTTableCell cell, IDMLTableCell idmlCell, ResolvedData resolvedData) {
        if (cell == null || resolvedData == null || !looksLikeFlattenedLongCell(cell)) return;
        if (idmlCell == null || idmlCell.textFrameStoryRefs() == null
                || idmlCell.textFrameStoryRefs().isEmpty()) {
            return;
        }
        String cellText = normalizeComparableText(cellPlainText(cell));
        if (cellText.isEmpty()) return;

        ResolvedStory best = null;
        for (String storyId : idmlCell.textFrameStoryRefs()) {
            if (isStoryOwnedByInlineTextShellPlan(resolvedData, storyId)) continue;
            ResolvedStory story = resolvedData.getStory(storyId);
            if (!hasRicherResolvedStructure(story)) continue;
            if (hasInlineAnchors(story)) continue;
            String storyText = normalizeComparableText(resolvedStoryText(story));
            if (storyText.isEmpty()) continue;
            if (cellText.equals(storyText) || storyText.startsWith(cellText) || cellText.startsWith(storyText)) {
                best = story;
                break;
            }
        }
        if (best == null) return;
        List<ASTParagraph> paragraphs = convertResolvedStoryForCell(best, resolvedData);
        if (paragraphs.isEmpty()) return;
        cell.paragraphs().clear();
        cell.paragraphs().addAll(paragraphs);
    }

    private static boolean isStoryOwnedByInlineTextShellPlan(ResolvedData resolvedData, String storyId) {
        if (resolvedData == null || storyId == null || storyId.isEmpty()) return false;
        kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ResolvedBuildContext ctx =
                tableBridgeContext(resolvedData);
        String decimalStoryId = toDecimalStoryId(storyId);
        List<ResolvedTextFrame> frames = resolvedData.getTextFramesForStory(decimalStoryId);
        if ((frames == null || frames.isEmpty()) && !decimalStoryId.equals(storyId)) {
            frames = resolvedData.getTextFramesForStory(storyId);
        }
        if (frames == null || frames.isEmpty()) return false;
        for (ResolvedTextFrame tf : frames) {
            if (tf == null || tf.id() == null) continue;
            int domId = parseDomIdOrNeg(tf.id());
            if (domId < 0) continue;
            if (ctx.isTextFrameOwnedByTextShellPlan(domId)
                    && ctx.ownershipPlanPlacesInlineHwpxText(domId)) {
                return true;
            }
        }
        return false;
    }

    private static String toDecimalStoryId(String storyRef) {
        if (storyRef == null) return "";
        String value = storyRef.trim();
        if (value.isEmpty()) return value;
        if (value.startsWith("child_")) {
            value = value.substring("child_".length());
        }
        if (value.startsWith("Story_")) {
            value = value.substring("Story_".length());
        }
        if (value.startsWith("u") && value.length() > 1) {
            try {
                return String.valueOf(Long.parseLong(value.substring(1), 16));
            } catch (NumberFormatException ignored) {
                return value;
            }
        }
        return value;
    }

    private static boolean looksLikeFlattenedLongCell(ASTTableCell cell) {
        ASTParagraph para = singleTextContentParagraph(cell);
        if (para == null) return false;
        int textRuns = 0;
        int chars = 0;
        for (ASTInlineItem item : para.items()) {
            if (!(item instanceof ASTTextRun)) return false;
            textRuns++;
            String text = ((ASTTextRun) item).text();
            if (text != null) chars += text.trim().length();
        }
        return textRuns <= 1 && chars > 20;
    }

    private static boolean hasInlineAnchors(ResolvedStory story) {
        if (story == null || story.paragraphs() == null) return false;
        for (ResolvedParagraph paragraph : story.paragraphs()) {
            if (paragraph == null || paragraph.runs() == null) continue;
            for (ResolvedRun run : paragraph.runs()) {
                if (run != null && run.isInlineAnchor()) return true;
            }
        }
        return false;
    }

    private static ASTParagraph singleTextContentParagraph(ASTTableCell cell) {
        if (cell == null || cell.paragraphs() == null || cell.paragraphs().isEmpty()) return null;
        ASTParagraph content = null;
        for (ASTParagraph para : cell.paragraphs()) {
            if (para == null) continue;
            if (para.inlineTable() != null) return null;
            boolean hasText = false;
            boolean hasNonText = false;
            if (para.items() != null) {
                for (ASTInlineItem item : para.items()) {
                    if (item == null) continue;
                    if (!(item instanceof ASTTextRun)) {
                        hasNonText = true;
                        break;
                    }
                    String text = ((ASTTextRun) item).text();
                    if (text != null && !text.trim().isEmpty()) {
                        hasText = true;
                    }
                }
            }
            if (hasNonText) return null;
            if (!hasText) continue;
            if (content != null) return null;
            content = para;
        }
        return content;
    }

    private static List<ASTParagraph> convertResolvedStoryForCell(ResolvedStory story, ResolvedData resolvedData) {
        return ResolvedTextFlowAstConverter.convertStory(
                story,
                ResolvedTextFlowAstConverter.options()
                        .colorResolver(color -> resolvedData != null ? resolvedData.resolveColorHex(color) : color)
                        .copyTabStops(false)
                        .truncateAtParagraphBreak(true));
    }

    private static boolean hasRicherResolvedStructure(ResolvedStory story) {
        if (story == null || story.paragraphs() == null || story.paragraphs().isEmpty()) return false;
        int visibleParagraphs = 0;
        int visibleRuns = 0;
        for (ResolvedParagraph paragraph : story.paragraphs()) {
            if (paragraph == null || paragraph.runs() == null) continue;
            boolean hasText = false;
            for (ResolvedRun run : paragraph.runs()) {
                if (run == null || run.isInlineAnchor() || run.text() == null) continue;
                String text = normalizeComparableText(run.text());
                if (text.isEmpty()) continue;
                visibleRuns++;
                hasText = true;
            }
            if (hasText) visibleParagraphs++;
        }
        return visibleParagraphs > 1 || visibleRuns > 1;
    }

    private static String cellPlainText(ASTTableCell cell) {
        StringBuilder sb = new StringBuilder();
        if (cell == null || cell.paragraphs() == null) return "";
        for (ASTParagraph paragraph : cell.paragraphs()) {
            if (paragraph == null || paragraph.items() == null) continue;
            for (ASTInlineItem item : paragraph.items()) {
                if (item instanceof ASTTextRun) {
                    String text = ((ASTTextRun) item).text();
                    if (text != null) sb.append(text);
                }
            }
        }
        return sb.toString();
    }

    private static String resolvedStoryText(ResolvedStory story) {
        StringBuilder sb = new StringBuilder();
        if (story == null || story.paragraphs() == null) return "";
        for (ResolvedParagraph paragraph : story.paragraphs()) {
            if (paragraph == null || paragraph.runs() == null) continue;
            for (ResolvedRun run : paragraph.runs()) {
                if (run == null || run.isInlineAnchor() || run.text() == null) continue;
                sb.append(run.text());
            }
        }
        return sb.toString();
    }

    private static String normalizeComparableText(String text) {
        if (text == null || text.isEmpty()) return "";
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (ch == '\uFFFC' || ch == '\u0007' || ch == '\u0008') continue;
            if (Character.isWhitespace(ch) || Character.isISOControl(ch)) continue;
            sb.append(ch);
        }
        return sb.toString();
    }

    private static void addSimpleTextRuns(ASTParagraph astPara, IDMLCharacterRun run,
                                          String paragraphStyleRef,
                                          ResolvedData resolvedData,
                                          StylePropertyResolver styleResolver) {
        for (ASTTextRun textRun : ResolvedTextFlowAstConverter.convertIdmlRun(
                run,
                paragraphStyleRef,
                styleResolver,
                resolvedData,
                astPara,
                false)) {
            astPara.addItem(textRun);
        }
    }

    private static ASTTableCell.CellBorder convertCellBorderSimple(IDMLTableCell.CellBorder src,
                                                                   ResolvedData resolvedData) {
        if (src == null) return null;
        if (src.strokeWeight <= 0 && isNoneColor(src.strokeColor)) return null;
        if (src.strokeWeight <= 0 && src.strokeWeightSpecified) return null;
        ASTTableCell.CellBorder border = new ASTTableCell.CellBorder();
        border.weight(src.strokeWeight);
        border.strokeType(src.strokeType);
        border.tint(src.strokeTint);
        if (src.strokeColor != null) {
            border.color(resolveTableColor(resolvedData, src.strokeColor, src.strokeTint));
        }
        return border;
    }

    private static boolean isNoneColor(String color) {
        return color == null || color.isEmpty() || color.contains("None");
    }

    private static String resolveTableColor(ResolvedData resolvedData, String color, double tint) {
        if (color == null) return null;
        if (resolvedData == null) return color;
        String hex = resolvedData.resolveColorHex(color);
        if (hex == null) return color;
        return ColorResolver.applyTintToHex(hex, tint);
    }

    private static ResolvedTable.Cell findResolvedCell(
            ResolvedData resolvedData,
            IDMLTable idmlTable,
            IDMLTableCell idmlCell) {
        if (resolvedData == null || idmlCell == null) return null;
        ResolvedTable resolvedTable = idmlTable != null
                ? resolvedData.getTableByIdOrSourceId(idmlTable.selfId())
                : null;
        if (resolvedTable == null && idmlCell.selfId() != null) {
            resolvedTable = resolvedData.getTableByIdOrSourceId(idmlCell.selfId());
        }
        if (resolvedTable == null) return null;
        return resolvedTable.cellAt(idmlCell.rowIndex(), idmlCell.columnIndex());
    }

    // ── 그리드 TextFrame 감지 및 ASTTable 변환 ──

    /**
     * Group 내 TextFrame들의 좌표를 분석하여 그리드(2×2 이상)를 감지하고 ASTTable로 변환.
     * 그리드가 아니면 null 반환 → 호출자가 기존 순차 처리로 폴백.
     */
    static ASTTable tryBuildGridTable(IDMLCharacterRun.InlineGraphic ig,
                                       IDMLDocument idmlDoc,
                                       ColorResolver colorResolver,
                                       ASTImageLoader imageLoader,
                                       ASTInlineObjectBuilder.GroupBackground bg,
                                       ResolvedData resolvedData) {
        List<PositionedFrame> frames = new ArrayList<>();
        collectAllTextFramesFlat(ig, frames, 0, 0);
        if (frames.size() < 2) return null;  // 최소 2프레임

        // Y 클러스터링 → 행
        List<List<PositionedFrame>> rows = clusterByCoordinate(frames, true);

        // 각 행의 X 정렬 → 열 수 일관성 확인
        int colCount = -1;
        for (List<PositionedFrame> row : rows) {
            Collections.sort(row, new Comparator<PositionedFrame>() {
                public int compare(PositionedFrame a, PositionedFrame b) {
                    return Double.compare(a.x, b.x);
                }
            });
            if (colCount == -1) {
                colCount = row.size();
            } else if (row.size() != colCount) {
                return null;  // 비정규 그리드 → 폴백
            }
        }
        // 최소 2행 또는 2열 (1×1은 제외)
        if (rows.size() < 2 && colCount < 2) return null;

        return buildTableFromGrid(rows, colCount, idmlDoc, colorResolver, imageLoader, bg, resolvedData);
    }

    static class PositionedFrame {
        final IDMLTextFrame textFrame;
        final double x, y;
        final double width, height;
        PositionedFrame(IDMLTextFrame tf, double x, double y, double w, double h) {
            this.textFrame = tf;
            this.x = x;
            this.y = y;
            this.width = w;
            this.height = h;
        }
    }

    private static void collectAllTextFramesFlat(IDMLCharacterRun.InlineGraphic ig,
                                                  List<PositionedFrame> result,
                                                  double accTx, double accTy) {
        for (IDMLTextFrame childTf : ig.childTextFrames()) {
            double[] gb = childTf.geometricBounds();
            double[] it = childTf.itemTransform();
            if (gb == null) continue;
            double tx = accTx + (it != null ? it[4] : 0);
            double ty = accTy + (it != null ? it[5] : 0);
            double x = gb[1] + tx;
            double y = gb[0] + ty;
            double w = gb[3] - gb[1];
            double h = gb[2] - gb[0];
            result.add(new PositionedFrame(childTf, x, y, w, h));
        }
        for (IDMLCharacterRun.InlineGraphic childIg : ig.childGraphics()) {
            double[] ct = childIg.itemTransform();
            double childTx = accTx + (ct != null ? ct[4] : 0);
            double childTy = accTy + (ct != null ? ct[5] : 0);
            collectAllTextFramesFlat(childIg, result, childTx, childTy);
        }
    }

    private static List<List<PositionedFrame>> clusterByCoordinate(
            List<PositionedFrame> frames, boolean byY) {
        List<PositionedFrame> sorted = new ArrayList<>(frames);
        Collections.sort(sorted, new Comparator<PositionedFrame>() {
            public int compare(PositionedFrame a, PositionedFrame b) {
                return Double.compare(byY ? a.y : a.x, byY ? b.y : b.x);
            }
        });

        List<List<PositionedFrame>> clusters = new ArrayList<>();
        List<PositionedFrame> current = new ArrayList<>();
        current.add(sorted.get(0));
        double lastVal = byY ? sorted.get(0).y : sorted.get(0).x;

        for (int i = 1; i < sorted.size(); i++) {
            double val = byY ? sorted.get(i).y : sorted.get(i).x;
            if (Math.abs(val - lastVal) > 2.0) {
                clusters.add(current);
                current = new ArrayList<>();
            }
            current.add(sorted.get(i));
            lastVal = val;
        }
        clusters.add(current);
        return clusters;
    }

    private static ASTTable buildTableFromGrid(List<List<PositionedFrame>> rows, int colCount,
                                                IDMLDocument idmlDoc,
                                                ColorResolver colorResolver,
                                                ASTImageLoader imageLoader,
                                                ASTInlineObjectBuilder.GroupBackground bg,
                                                ResolvedData resolvedData) {
        ASTTable table = new ASTTable();
        table.rowCount(rows.size());
        table.colCount(colCount);

        // 열 너비: 셀 간 간격 포함하여 계산
        // 각 열의 시작 X ~ 다음 열의 시작 X (마지막 열은 자체 폭 사용)
        List<PositionedFrame> firstRow = rows.get(0);
        long totalWidth = 0;
        for (int c = 0; c < colCount; c++) {
            long w;
            if (c < colCount - 1) {
                // 이 열의 시작 X ~ 다음 열의 시작 X
                double span = firstRow.get(c + 1).x - firstRow.get(c).x;
                w = CoordinateConverter.pointsToHwpunits(span);
            } else {
                // 마지막 열: 자체 폭 사용
                w = CoordinateConverter.pointsToHwpunits(firstRow.get(c).width);
            }
            table.addColumnWidth(w);
            totalWidth += w;
        }
        table.width(totalWidth);

        // 행 높이: 행 간 간격 포함
        long[] rowHeights = new long[rows.size()];
        for (int r = 0; r < rows.size(); r++) {
            if (r < rows.size() - 1) {
                double span = rows.get(r + 1).get(0).y - rows.get(r).get(0).y;
                rowHeights[r] = CoordinateConverter.pointsToHwpunits(span);
            } else {
                rowHeights[r] = CoordinateConverter.pointsToHwpunits(rows.get(r).get(0).height);
            }
        }

        // 행 빌드
        long totalHeight = 0;
        for (int r = 0; r < rows.size(); r++) {
            List<PositionedFrame> row = rows.get(r);
            ASTTableRow astRow = new ASTTableRow();
            astRow.rowIndex(r);
            astRow.rowHeight(rowHeights[r]);
            totalHeight += rowHeights[r];

            for (int c = 0; c < colCount; c++) {
                PositionedFrame pf = row.get(c);
                ASTTableCell cell = createCellFromFrame(pf, r, c, idmlDoc, colorResolver, imageLoader, bg, resolvedData);
                // 셀 크기를 열/행 크기와 일치
                long colW = table.columnWidths().get(c);
                long rowH = rowHeights[r];
                cell.width(colW);
                cell.height(rowH);

                // 원본 TextFrame 크기와의 차이를 마진으로 변환 → 간격 보존 + 고정 크기
                long frameW = CoordinateConverter.pointsToHwpunits(pf.width);
                long frameH = CoordinateConverter.pointsToHwpunits(pf.height);
                long gapW = colW - frameW;
                long gapH = rowH - frameH;
                if (gapW > 0) {
                    cell.marginRight(cell.marginRight() + gapW);
                }
                if (gapH > 0) {
                    cell.marginBottom(cell.marginBottom() + gapH);
                }

                astRow.addCell(cell);
            }
            table.addRow(astRow);
        }
        table.height(totalHeight);

        // 그룹 배경을 테이블 외곽선으로 적용 (셀 배경 대신 외곽 테두리로 표현)
        if (bg != null) {
            String borderColor = bg.strokeHex != null ? bg.strokeHex : bg.fillHex;
            double borderWeight = bg.strokeWeight > 0 ? bg.strokeWeight : 0.5;
            if (borderColor != null) {
                int rowCount = rows.size();
                for (ASTTableRow astRow : table.rows()) {
                    for (ASTTableCell cell : astRow.cells()) {
                        int r = cell.rowIndex();
                        int c = cell.columnIndex();
                        if (r == 0 && cell.topBorder() == null) {
                            cell.topBorder(makeBgBorder(borderColor, borderWeight));
                        }
                        if (r == rowCount - 1 && cell.bottomBorder() == null) {
                            cell.bottomBorder(makeBgBorder(borderColor, borderWeight));
                        }
                        if (c == 0 && cell.leftBorder() == null) {
                            cell.leftBorder(makeBgBorder(borderColor, borderWeight));
                        }
                        if (c == colCount - 1 && cell.rightBorder() == null) {
                            cell.rightBorder(makeBgBorder(borderColor, borderWeight));
                        }
                    }
                }
            }
        }

        return table;
    }

    private static ASTTableCell.CellBorder makeBgBorder(String color, double weight) {
        ASTTableCell.CellBorder border = new ASTTableCell.CellBorder();
        border.color(color);
        border.weight(weight);
        return border;
    }

    private static ASTTableCell createCellFromFrame(PositionedFrame pf, int rowIdx, int colIdx,
                                                     IDMLDocument idmlDoc,
                                                     ColorResolver colorResolver,
                                                     ASTImageLoader imageLoader,
                                                     ASTInlineObjectBuilder.GroupBackground bg,
                                                     ResolvedData resolvedData) {
        ASTTableCell cell = new ASTTableCell();
        cell.rowIndex(rowIdx);
        cell.columnIndex(colIdx);
        cell.width(CoordinateConverter.pointsToHwpunits(pf.width));
        cell.height(CoordinateConverter.pointsToHwpunits(pf.height));

        IDMLTextFrame tf = pf.textFrame;

        // 셀 배경색 — TextFrame 자체 fillColor만 사용 (그룹 배경은 외곽 컨테이너 색이므로 셀에 전파하지 않음)
        if (tf.fillColor() != null) {
            String resolved = colorResolver.resolve(tf.fillColor());
            if (resolved != null) cell.fillColor(resolved);
        }

        // TextFrame 여백
        double[] inset = tf.insetSpacing();
        if (inset != null) {
            cell.marginTop(CoordinateConverter.pointsToHwpunits(inset[0]));
            cell.marginLeft(CoordinateConverter.pointsToHwpunits(inset[1]));
            cell.marginBottom(CoordinateConverter.pointsToHwpunits(inset[2]));
            cell.marginRight(CoordinateConverter.pointsToHwpunits(inset[3]));
        }

        // TextFrame 스토리 → 단락 변환
        boolean usedResolvedStory = false;
        ResolvedStory resolvedStory = resolvedStoryForTextFrame(tf, resolvedData);
        if (hasRicherResolvedStructure(resolvedStory)) {
            List<ASTParagraph> paragraphs = convertResolvedStoryForCell(resolvedStory, resolvedData);
            if (!paragraphs.isEmpty()) {
                cell.paragraphs().addAll(paragraphs);
                usedResolvedStory = true;
            }
        }

        if (!usedResolvedStory && tf.parentStoryId() != null) {
            IDMLStory story = idmlDoc.getStory(tf.parentStoryId());
            if (story != null) {
                FlattenedObjectPool emptyPool = new FlattenedObjectPool();
                for (IDMLParagraph p : story.paragraphs()) {
                    ASTParagraph astP = ASTStoryConverter.convertParagraph(
                            p, emptyPool, idmlDoc, colorResolver, imageLoader, false, resolvedData);
                    if (astP != null) {
                        cell.addParagraph(astP);
                    }
                }
                ASTPageProcessor.removeTrailingEmptyParagraphs(cell.paragraphs());
            }
        }

        return cell;
    }

    private static ResolvedStory resolvedStoryForTextFrame(IDMLTextFrame tf, ResolvedData resolvedData) {
        if (tf == null || resolvedData == null) return null;
        String textFrameId = idmlIdToDecimal(tf.selfId());
        if (textFrameId != null) {
            ResolvedTextFrame resolvedTf = resolvedData.getTextFrame(textFrameId);
            if (resolvedTf != null && resolvedTf.storyId() != null) {
                ResolvedStory story = resolvedData.getStory(resolvedTf.storyId());
                if (story != null) return story;
            }
        }
        String storyId = idmlIdToDecimal(tf.parentStoryId());
        return storyId != null ? resolvedData.getStory(storyId) : null;
    }

    private static String idmlIdToDecimal(String idmlId) {
        if (idmlId == null || idmlId.length() < 2 || idmlId.charAt(0) != 'u') return null;
        try {
            return String.valueOf(Integer.parseInt(idmlId.substring(1), 16));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * 색상에 tint(농도)를 적용: 흰색과 블렌딩.
     */
    private static String blendColorWithWhite(String hex, double fraction) {
        if (hex == null || !hex.startsWith("#") || hex.length() < 7) return hex;
        try {
            int rgb = Integer.parseInt(hex.substring(1, 7), 16);
            int r = (rgb >> 16) & 0xFF;
            int g = (rgb >> 8) & 0xFF;
            int b = rgb & 0xFF;
            r = (int) Math.round(255 + (r - 255) * fraction);
            g = (int) Math.round(255 + (g - 255) * fraction);
            b = (int) Math.round(255 + (b - 255) * fraction);
            return String.format("#%02X%02X%02X",
                    Math.max(0, Math.min(255, r)),
                    Math.max(0, Math.min(255, g)),
                    Math.max(0, Math.min(255, b)));
        } catch (Exception e) {
            return hex;
        }
    }

    /**
     * Legacy-only 인라인 객체 boundsX 기반 재정렬.
     *
     * Stage 1 ObjectPlan이 있는 변환 경로에서는 source story/anchor order가
     * 실행 계약이다. 이 메서드는 rendered bounds X로 paragraph item 순서를
     * 다시 쓰므로 ObjectPlan 경로에서 호출하면 안 된다.
     *
     * IDML에서 인라인 객체 순서(FFFC 순서)가 시각적 배치 순서와 다를 때,
     * rendered bounds의 X 좌표로 올바른 위치에 재배치한다.
     * 예: [IMG(01,x=359) IMG(풍선,x=419) TEXT] → [IMG(01,x=359) TEXT IMG(풍선,x=419)]
     */
    public static void reorderInlineObjectsByBoundsX(ASTParagraph para) {
        List<ASTInlineItem> items = para.items();
        if (items == null || items.size() < 3) return;

        // boundsX가 설정된 인라인 객체가 있는지 확인
        boolean hasBoundsX = false;
        double minBoundsX = Double.MAX_VALUE;
        double maxBoundsX = Double.MIN_VALUE;
        for (ASTInlineItem item : items) {
            if (item instanceof ASTInlineObject) {
                ASTInlineObject io = (ASTInlineObject) item;
                if (io.boundsX() >= 0) {
                    hasBoundsX = true;
                    minBoundsX = Math.min(minBoundsX, io.boundsX());
                    maxBoundsX = Math.max(maxBoundsX, io.boundsX());
                }
            }
        }
        if (!hasBoundsX || maxBoundsX - minBoundsX < 1) return; // boundsX 차이 없으면 skip

        // boundsX가 큰 인라인 객체 → 텍스트 뒤로 이동
        // 전략: 연속 인라인 객체 + 텍스트 시퀀스에서, boundsX가 큰 인라인을 텍스트 뒤로 재배치
        List<ASTInlineItem> reordered = new ArrayList<>();
        List<ASTInlineObject> deferredInlines = new ArrayList<>();

        for (ASTInlineItem item : items) {
            if (item instanceof ASTInlineObject) {
                ASTInlineObject io = (ASTInlineObject) item;
                if (io.boundsX() > minBoundsX + 10) {
                    // boundsX가 큰 인라인 → 뒤로 미룸
                    deferredInlines.add(io);
                    continue;
                }
            }
            // 미뤄둔 인라인이 있을 때 탭/빈 텍스트는 제거 (원래 인라인 간 간격용이었으므로)
            if (item instanceof ASTTextRun && !deferredInlines.isEmpty()) {
                ASTTextRun tr = (ASTTextRun) item;
                String text = tr.text();
                // 탭이나 빈 텍스트 → 제거 (인라인 재배치 시 불필요한 간격)
                if (text == null || text.trim().isEmpty() || "\t".equals(text)) {
                    continue; // skip
                }
                // 실질적 텍스트 → 먼저 추가, 그 뒤에 미뤄둔 인라인 삽입
                reordered.add(item);
                reordered.addAll(deferredInlines);
                deferredInlines.clear();
                continue;
            }
            reordered.add(item);
        }
        // 남은 미뤄둔 인라인 추가
        reordered.addAll(deferredInlines);

        // items 교체
        if (!reordered.equals(items)) {
            items.clear();
            items.addAll(reordered);
        }
    }

    public static boolean allowsLegacyBoundsXInlineReorder(ResolvedData resolvedData) {
        return resolvedData == null
                || resolvedData.ownershipPlans() == null
                || resolvedData.ownershipPlans().isEmpty();
    }
}
