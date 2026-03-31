package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.*;
import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.ASTImageLoader;
import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.CoordinateConverter;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.*;
import kr.dogfoot.hwpxlib.tool.idmlconverter.util.ColorResolver;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedData;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

/**
 * 테이블/그리드 변환 전담.
 * ASTInlineObjectBuilder에서 분리됨.
 */
class ASTTableConverter {

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
            resolvedTableBounds = resolvedData.getTableBounds(idmlTable.selfId());
        }
        if (resolvedTableBounds != null) {
            // resolved bounds는 page-relative (mm 단위 → scale 적용 필요)
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

        // 빈 스페이서 행을 위 행에 여백으로 흡수
        ASTTableSpacerMerger.merge(table);
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
        // IDML FillTint=-1은 "기본값(100%)" 의미 → 0 이하는 100%로 처리
        if (idmlCell.fillColor() != null) {
            String resolved = colorResolver.resolve(idmlCell.fillColor());
            double tint = idmlCell.fillTint();
            if (tint > 0 && tint < 100 && resolved != null && resolved.startsWith("#") && resolved.length() >= 7) {
                resolved = blendColorWithWhite(resolved, tint / 100.0);
            }
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

        return cell;
    }

    /**
     * IDMLTableCell.CellBorder → ASTTableCell.CellBorder 변환.
     */
    static ASTTableCell.CellBorder convertCellBorder(IDMLTableCell.CellBorder src,
                                                      ColorResolver colorResolver) {
        if (src == null || src.strokeWeight <= 0) return null;
        ASTTableCell.CellBorder border = new ASTTableCell.CellBorder();
        border.weight(src.strokeWeight);
        border.strokeType(src.strokeType);
        border.tint(src.strokeTint);
        if (src.strokeColor != null) {
            String resolved = colorResolver.resolve(src.strokeColor);
            if (src.strokeTint > 0 && src.strokeTint < 100 && resolved != null && resolved.startsWith("#") && resolved.length() >= 7) {
                resolved = blendColorWithWhite(resolved, src.strokeTint / 100.0);
            }
            border.color(resolved);
        }
        return border;
    }

    /**
     * 새 파이프라인용: IDMLTable → ASTTable 변환 (좌표 직접 지정).
     * IDMLDocument/ColorResolver/ASTImageLoader가 있으면 레거시 셀 변환 사용,
     * 없으면 간소화 셀 변환으로 폴백.
     */
    static ASTTable convertTableSimple(IDMLTable idmlTable,
                                        long x, long y, int zOrder) {
        return convertTableSimple(idmlTable, x, y, zOrder, null, null, null, null);
    }

    static ASTTable convertTableSimple(IDMLTable idmlTable,
                                        long x, long y, int zOrder,
                                        IDMLDocument idmlDoc,
                                        kr.dogfoot.hwpxlib.tool.idmlconverter.util.ColorResolver colorResolver,
                                        kr.dogfoot.hwpxlib.tool.idmlconverter.converter.ASTImageLoader imageLoader,
                                        ResolvedData resolvedData) {
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
                    cell = convertTableCellSimple(idmlCell, rowIdx, idmlCell.columnIndex());
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

        ASTTableSpacerMerger.merge(table);
        return table;
    }

    /**
     * 간소화 셀 변환 (ColorResolver 없이).
     */
    private static ASTTableCell convertTableCellSimple(IDMLTableCell idmlCell,
                                                        int rowIdx, int colIdx) {
        ASTTableCell cell = new ASTTableCell();
        cell.rowIndex(rowIdx);
        cell.columnIndex(colIdx);
        cell.rowSpan(idmlCell.rowSpan());
        cell.columnSpan(idmlCell.columnSpan());

        // 색상: IDML 색상 이름을 그대로 저장 (HwpxTableBuilder에서 해석)
        if (idmlCell.fillColor() != null) {
            cell.fillColor(idmlCell.fillColor());
        }
        cell.verticalAlign(idmlCell.verticalJustification());

        // 셀 여백
        cell.marginTop(CoordinateConverter.pointsToHwpunits(idmlCell.topInset()));
        cell.marginBottom(CoordinateConverter.pointsToHwpunits(idmlCell.bottomInset()));
        cell.marginLeft(CoordinateConverter.pointsToHwpunits(idmlCell.leftInset()));
        cell.marginRight(CoordinateConverter.pointsToHwpunits(idmlCell.rightInset()));

        // 셀 테두리 (색상 해석 없이)
        cell.topBorder(convertCellBorderSimple(idmlCell.topBorder()));
        cell.bottomBorder(convertCellBorderSimple(idmlCell.bottomBorder()));
        cell.leftBorder(convertCellBorderSimple(idmlCell.leftBorder()));
        cell.rightBorder(convertCellBorderSimple(idmlCell.rightBorder()));

        // 대각선
        cell.topLeftDiagonalLine(idmlCell.topLeftDiagonalLine());
        cell.topRightDiagonalLine(idmlCell.topRightDiagonalLine());
        cell.diagonalBorder(convertCellBorderSimple(idmlCell.diagonalBorder()));

        // 셀 내용: 단락 변환 (간소화 — 텍스트만)
        for (IDMLParagraph cellPara : idmlCell.paragraphs()) {
            ASTParagraph astPara = new ASTParagraph();
            if (cellPara.appliedParagraphStyle() != null) {
                astPara.paragraphStyleRef(cellPara.appliedParagraphStyle());
            }
            for (IDMLCharacterRun run : cellPara.characterRuns()) {
                if (run.content() == null || run.content().isEmpty()) continue;
                ASTTextRun textRun = new ASTTextRun();
                textRun.text(run.content());
                if (run.appliedCharacterStyle() != null) {
                    textRun.characterStyleRef(run.appliedCharacterStyle());
                }
                if (run.fontSize() != null && run.fontSize() > 0) {
                    textRun.fontSizeHwpunits((int) CoordinateConverter.pointsToHwpunits(run.fontSize()));
                }
                if (run.fontFamily() != null) {
                    textRun.fontFamily(run.fontFamily());
                }
                if (run.fontStyle() != null) {
                    textRun.fontStyle(run.fontStyle());
                }
                astPara.addItem(textRun);
            }
            cell.addParagraph(astPara);
        }

        return cell;
    }

    private static ASTTableCell.CellBorder convertCellBorderSimple(IDMLTableCell.CellBorder src) {
        if (src == null || src.strokeWeight <= 0) return null;
        ASTTableCell.CellBorder border = new ASTTableCell.CellBorder();
        border.weight(src.strokeWeight);
        border.strokeType(src.strokeType);
        border.tint(src.strokeTint);
        if (src.strokeColor != null) {
            border.color(src.strokeColor); // 색상 이름 그대로 (hex 미해석)
        }
        return border;
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
                                       ASTInlineObjectBuilder.GroupBackground bg) {
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

        return buildTableFromGrid(rows, colCount, idmlDoc, colorResolver, imageLoader, bg);
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
                                                ASTInlineObjectBuilder.GroupBackground bg) {
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
                ASTTableCell cell = createCellFromFrame(pf, r, c, idmlDoc, colorResolver, imageLoader, bg);
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
                                                     ASTInlineObjectBuilder.GroupBackground bg) {
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
        if (tf.parentStoryId() != null) {
            IDMLStory story = idmlDoc.getStory(tf.parentStoryId());
            if (story != null) {
                FlattenedObjectPool emptyPool = new FlattenedObjectPool();
                for (IDMLParagraph p : story.paragraphs()) {
                    ASTParagraph astP = ASTStoryConverter.convertParagraph(
                            p, emptyPool, idmlDoc, colorResolver, imageLoader, false, null);
                    if (astP != null) {
                        cell.addParagraph(astP);
                    }
                }
                ASTPageProcessor.removeTrailingEmptyParagraphs(cell.paragraphs());
            }
        }

        return cell;
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
    static void reorderInlineObjectsByBoundsX(ASTParagraph para) {
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
            // 텍스트 런이 나오면, 미뤄둔 인라인을 텍스트 뒤에 삽입
            if (item instanceof ASTTextRun && !deferredInlines.isEmpty()) {
                ASTTextRun tr = (ASTTextRun) item;
                String text = tr.text();
                if (text != null && !text.trim().isEmpty() && !"\t".equals(text.trim())) {
                    reordered.add(item);
                    // 미뤄둔 인라인 삽입
                    reordered.addAll(deferredInlines);
                    deferredInlines.clear();
                    continue;
                }
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
