package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.*;
import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.ASTImageLoader;
import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.CoordinateConverter;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.*;
import kr.dogfoot.hwpxlib.tool.idmlconverter.util.ColorResolver;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedData;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedParagraph;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedRun;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedStory;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedTextFrame;

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

        // 테이블 위치: resolved.json의 테이블 bounds 우선, 없으면 TextFrame 좌표 폴백
        double[] resolvedTableBounds = null;
        if (resolvedData != null) {
            resolvedTableBounds = resolvedData.getTablePlacementBounds(idmlTable.selfId());
        }
        if (resolvedTableBounds != null) {
            // resolved placement bounds는 page-relative (mm 단위 → scale 적용 필요)
            double scale = resolvedData != null ? resolvedData.scaleFactor() : 2.8346;
            table.x(CoordinateConverter.pointsToHwpunits(resolvedTableBounds[1] * scale));
            table.y(CoordinateConverter.pointsToHwpunits(resolvedTableBounds[0] * scale));
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
        for (IDMLTableRow idmlRow : idmlTable.rows()) {
            ASTTableRow row = new ASTTableRow();
            row.rowIndex(rowIdx);
            row.rowHeight(CoordinateConverter.pointsToHwpunits(idmlRow.rowHeight()));
            row.autoGrow(idmlRow.autoGrow());
            totalHeight += row.rowHeight();

            // 셀 변환
            for (IDMLTableCell idmlCell : idmlRow.cells()) {
                int colIdx = idmlCell.columnIndex();
                ASTTableCell cell = convertTableCell(idmlCell, rowIdx, colIdx,
                        idmlDoc, colorResolver, imageLoader, resolvedData);
                row.addCell(cell);
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
        applyPlacementBounds(table, resolvedTableBounds,
                resolvedData != null ? resolvedData.scaleFactor() : 2.8346);
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

        // 인라인 객체 boundsX 기반 재정렬
        for (ASTParagraph p : cell.paragraphs()) {
            reorderInlineObjectsByBoundsX(p);
        }

        // 마지막 빈 단락 제거
        ASTPageProcessor.removeTrailingEmptyParagraphs(cell.paragraphs());
        replaceFlattenedCellTextWithResolvedStory(cell, idmlCell, resolvedData);

        return cell;
    }

    /**
     * IDMLTableCell.CellBorder → ASTTableCell.CellBorder 변환.
     */
    static ASTTableCell.CellBorder convertCellBorder(IDMLTableCell.CellBorder src,
                                                      ColorResolver colorResolver) {
        if (src == null) return null;
        if (src.strokeWeight <= 0 && isNoneColor(src.strokeColor)) return null;
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

    public static ASTTable convertTableSimple(IDMLTable idmlTable,
                                        long x, long y, int zOrder,
                                        IDMLDocument idmlDoc,
                                        kr.dogfoot.hwpxlib.tool.idmlconverter.util.ColorResolver colorResolver,
                                        kr.dogfoot.hwpxlib.tool.idmlconverter.converter.ASTImageLoader imageLoader,
                                        ResolvedData resolvedData,
                                        StylePropertyResolver styleResolver) {
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
        for (IDMLTableRow idmlRow : idmlTable.rows()) {
            ASTTableRow row = new ASTTableRow();
            row.rowIndex(rowIdx);
            row.rowHeight(CoordinateConverter.pointsToHwpunits(idmlRow.rowHeight()));
            row.autoGrow(idmlRow.autoGrow());
            totalHeight += row.rowHeight();

            for (IDMLTableCell idmlCell : idmlRow.cells()) {
                ASTTableCell cell;
                if (useFullConvert) {
                    cell = convertTableCell(idmlCell, rowIdx, idmlCell.columnIndex(),
                            idmlDoc, colorResolver, imageLoader, resolvedData);
                } else {
                    cell = convertTableCellSimple(
                            idmlCell, rowIdx, idmlCell.columnIndex(), resolvedData, styleResolver);
                }
                row.addCell(cell);
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
        double[] placementBounds = resolvedData != null
                ? resolvedData.getTablePlacementBounds(idmlTable.selfId()) : null;
        applyPlacementBounds(table, placementBounds,
                resolvedData != null ? resolvedData.scaleFactor() : 2.8346);
        return table;
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
        if (targetHeight > 0) scaleRowsToHeight(table, targetHeight);
        recalcCellSizes(table);
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

    /**
     * IDML table cells sometimes omit an edge stroke weight when the edge style is
     * inherited, while still carrying a visible stroke color/priority. HWPX needs a
     * concrete width per edge, so visible zero-width edges borrow the dominant
     * visible weight from the same converted table.
     */
    private static void normalizeInheritedTableBorders(ASTTable table) {
        if (table == null || table.rows() == null) return;
        double fallbackWeight = dominantVisibleBorderWeight(table);
        if (fallbackWeight <= 0) fallbackWeight = 0.25;
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
    private static ASTTableCell convertTableCellSimple(IDMLTableCell idmlCell,
                                                        int rowIdx, int colIdx,
                                                        ResolvedData resolvedData,
                                                        StylePropertyResolver styleResolver) {
        ASTTableCell cell = new ASTTableCell();
        cell.rowIndex(rowIdx);
        cell.columnIndex(colIdx);
        cell.rowSpan(idmlCell.rowSpan());
        cell.columnSpan(idmlCell.columnSpan());

        if (idmlCell.fillColor() != null) {
            cell.fillColor(resolveTableColor(resolvedData, idmlCell.fillColor(), idmlCell.fillTint()));
        }
        cell.verticalAlign(idmlCell.verticalJustification());

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

        // 셀 내용: 단락 변환 (간소화 — 텍스트만)
        for (IDMLParagraph cellPara : idmlCell.paragraphs()) {
            ASTParagraph astPara = new ASTParagraph();
            if (cellPara.appliedParagraphStyle() != null) {
                astPara.paragraphStyleRef(cellPara.appliedParagraphStyle());
            }
            for (IDMLCharacterRun run : cellPara.characterRuns()) {
                addSimpleTextRuns(astPara, run, cellPara.appliedParagraphStyle(),
                        resolvedData, styleResolver);
            }
            cell.addParagraph(astPara);
        }
        replaceFlattenedCellTextWithResolvedStory(cell, idmlCell, resolvedData);

        return cell;
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
            ResolvedStory story = resolvedData.getStory(storyId);
            if (!hasRicherResolvedStructure(story)) continue;
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
        List<ASTParagraph> paragraphs = new ArrayList<>();
        if (story == null || story.paragraphs() == null) return paragraphs;
        for (ResolvedParagraph rp : story.paragraphs()) {
            if (rp == null) continue;
            ASTParagraph para = new ASTParagraph();
            if (rp.styleName() != null) para.paragraphStyleRef(rp.styleName());
            if (rp.justification() != null) para.alignment(rp.justification());
            Double fixedLeading = rp.fixedLeading();
            if (fixedLeading != null && fixedLeading > 0) {
                para.lineSpacingType("fixed");
                para.lineSpacing((int) CoordinateConverter.pointsToHwpunits(fixedLeading));
            }
            if (rp.spaceBefore() != null && rp.spaceBefore() > 0) {
                para.spaceBefore(CoordinateConverter.pointsToHwpunits(rp.spaceBefore()));
            }
            if (rp.spaceAfter() != null && rp.spaceAfter() > 0) {
                para.spaceAfter(CoordinateConverter.pointsToHwpunits(rp.spaceAfter()));
            }
            if (rp.leftIndent() != null && rp.leftIndent() != 0) {
                para.leftMargin(CoordinateConverter.pointsToHwpunits(rp.leftIndent()));
            }
            if (rp.rightIndent() != null && rp.rightIndent() != 0) {
                para.rightMargin(CoordinateConverter.pointsToHwpunits(rp.rightIndent()));
            }
            if (rp.firstLineIndent() != null && rp.firstLineIndent() != 0) {
                para.firstLineIndent(CoordinateConverter.pointsToHwpunits(rp.firstLineIndent()));
            }
            if (rp.runs() != null) {
                for (ResolvedRun run : rp.runs()) {
                    if (run == null || run.isInlineAnchor() || run.text() == null) continue;
                    String text = run.text();
                    boolean stopAfterRun = false;
                    int crIdx = text.indexOf('\r');
                    if (crIdx >= 0) {
                        text = text.substring(0, crIdx);
                        stopAfterRun = true;
                    }
                    for (ASTTextRun astRun : TextRunSegmenter.fromResolvedText(
                            text,
                            run,
                            color -> resolvedData.resolveColorHex(color),
                            para.hasTabStops(),
                            false,
                            null)) {
                        para.addItem(astRun);
                    }
                    if (stopAfterRun) break;
                }
            }
            paragraphs.add(para);
        }
        return paragraphs;
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
        for (ASTTextRun textRun : TextRunSegmenter.fromIdmlRun(
                run,
                paragraphStyleRef,
                styleResolver,
                resolvedData,
                astPara.hasTabStops(),
                false)) {
            astPara.addItem(textRun);
        }
    }

    private static ASTTableCell.CellBorder convertCellBorderSimple(IDMLTableCell.CellBorder src,
                                                                   ResolvedData resolvedData) {
        if (src == null) return null;
        if (src.strokeWeight <= 0 && isNoneColor(src.strokeColor)) return null;
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
     * 인라인 객체를 boundsX 기반으로 재정렬.
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
}
