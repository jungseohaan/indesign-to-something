package kr.dogfoot.hwpxlib.tool.idmlconverter.ast;

import java.util.ArrayList;
import java.util.List;

/**
 * 테이블의 spacer row(콘텐츠 없는 여백 행)를 이웃 행의 셀 여백으로 흡수하여 제거.
 */
public class ASTTableSpacerMerger {

    /**
     * 테이블에서 spacer row를 식별하고 이웃 행에 병합한다.
     */
    public static void merge(ASTTable table) {
        List<ASTTableRow> rows = table.rows();
        if (rows.size() <= 1) return;

        // spacer 식별
        boolean[] isSpacer = new boolean[rows.size()];
        int spacerCount = 0;
        for (int i = 0; i < rows.size(); i++) {
            isSpacer[i] = isSpacerRow(rows.get(i), table);
            if (isSpacer[i]) spacerCount++;
        }
        if (spacerCount == 0) return;

        // spacer run 그룹핑 및 병합
        long removedHeight = 0;
        List<Integer> removeIndices = new ArrayList<>();

        int i = 0;
        while (i < rows.size()) {
            if (!isSpacer[i]) {
                i++;
                continue;
            }

            // spacer run 시작
            int runStart = i;
            long runHeight = 0;
            while (i < rows.size() && isSpacer[i]) {
                runHeight += rows.get(i).rowHeight();
                removeIndices.add(i);
                i++;
            }
            int runEnd = i; // exclusive

            ASTTableRow above = (runStart > 0 && !isSpacer[runStart - 1])
                    ? rows.get(runStart - 1) : null;
            ASTTableRow below = (runEnd < rows.size() && !isSpacer[runEnd])
                    ? rows.get(runEnd) : null;

            // spacer 높이를 축소하여 위 행의 셀 여백(bottom)으로 흡수
            long absorbedHeight = runHeight * 3 / 4;
            removedHeight += runHeight - absorbedHeight;
            if (above != null) {
                above.rowHeight(above.rowHeight() + absorbedHeight);
                for (ASTTableCell ac : above.cells()) {
                    ac.marginBottom(ac.marginBottom() + absorbedHeight);
                }
            } else if (below != null) {
                below.rowHeight(below.rowHeight() + absorbedHeight);
                for (ASTTableCell bc : below.cells()) {
                    bc.marginTop(bc.marginTop() + absorbedHeight);
                }
            } else {
                // 양쪽 모두 spacer 또는 없음 → 제거 취소
                for (int k = runEnd - 1; k >= runStart; k--) {
                    removeIndices.remove(removeIndices.size() - 1);
                }
                continue;
            }

            // 테두리 보존
            preserveBorders(rows, runStart, runEnd, above, below);
            removedHeight += runHeight;
        }

        if (removeIndices.isEmpty()) return;

        // 역순으로 제거
        for (int j = removeIndices.size() - 1; j >= 0; j--) {
            rows.remove((int) removeIndices.get(j));
        }

        // 재번호
        reindex(table);
        table.rowCount(rows.size());
        table.height(table.height() - removedHeight);

        // 셀 높이 재계산
        recalcCellHeights(table);
    }

    /**
     * spacer row 판별: 모든 셀이 비어있고, 외부 rowSpan에 포함되지 않음.
     * 추가 조건: 1컬럼 테이블에서 배경색 투명 + 모든 테두리 색 없음이면 spacer.
     */
    static boolean isSpacerRow(ASTTableRow row, ASTTable table) {
        // 1. 모든 셀이 비어있는지 확인
        for (ASTTableCell cell : row.cells()) {
            if (!isCellEmpty(cell)) return false;
        }
        // 2. 배경색이나 유효 테두리가 있으면 기입란이므로 spacer 아님
        for (ASTTableCell cell : row.cells()) {
            if (!isTransparentFill(cell.fillColor())) return false;
            if (!allBordersTransparent(cell)) return false;
        }
        // 3. 다른 행의 셀이 이 행을 rowSpan으로 걸치는지 확인
        int ri = row.rowIndex();
        for (ASTTableRow otherRow : table.rows()) {
            if (otherRow.rowIndex() >= ri) break;
            for (ASTTableCell cell : otherRow.cells()) {
                if (cell.rowIndex() + cell.rowSpan() > ri) return false;
            }
        }
        return true;
    }

    private static boolean isTransparentFill(String fillColor) {
        return fillColor == null || fillColor.isEmpty() || fillColor.contains("None");
    }

    private static boolean isBorderTransparent(ASTTableCell.CellBorder border) {
        if (border == null) return true;
        if (border.weight() <= 0) return true;
        String c = border.color();
        return c == null || c.isEmpty() || c.contains("None");
    }

    private static boolean allBordersTransparent(ASTTableCell cell) {
        return isBorderTransparent(cell.topBorder())
                && isBorderTransparent(cell.bottomBorder())
                && isBorderTransparent(cell.leftBorder())
                && isBorderTransparent(cell.rightBorder());
    }

    /**
     * 셀이 비어있는지 확인: paragraphs가 없거나, 모든 단락이 빈 텍스트만 포함.
     * InDesign의 빈 셀도 빈 CharacterStyleRange를 포함하여 빈 텍스트 런이 생성되므로
     * 텍스트 런의 내용까지 확인한다.
     */
    static boolean isCellEmpty(ASTTableCell cell) {
        // nested TextFrame/table 콘텐츠가 복원될 셀은 비어 있지 않다 (spacer 병합 금지).
        if (cell.reservedForNestedContent()) return false;
        if (cell.paragraphs().isEmpty()) return true;
        for (ASTParagraph p : cell.paragraphs()) {
            if (p.items() == null || p.items().isEmpty()) continue;
            for (ASTInlineItem item : p.items()) {
                if (item.itemType() != ASTInlineItem.ItemType.TEXT_RUN) return false;
                ASTTextRun run = (ASTTextRun) item;
                if (run.text() != null && !run.text().trim().isEmpty()) return false;
            }
        }
        return true;
    }

    /**
     * spacer run의 테두리를 이웃 행에 전달.
     */
    static void preserveBorders(List<ASTTableRow> rows, int runStart, int runEnd,
                                ASTTableRow above, ASTTableRow below) {
        // spacer run 첫 행의 상단 테두리 → 위 행의 하단 테두리
        if (above != null) {
            ASTTableRow firstSpacer = rows.get(runStart);
            for (ASTTableCell sc : firstSpacer.cells()) {
                if (sc.topBorder() != null && sc.topBorder().weight() > 0) {
                    for (ASTTableCell ac : above.cells()) {
                        if (ac.bottomBorder() == null || ac.bottomBorder().weight() == 0) {
                            ac.bottomBorder(sc.topBorder());
                        }
                    }
                }
            }
        }
        // spacer run 마지막 행의 하단 테두리 → 아래 행의 상단 테두리
        if (below != null) {
            ASTTableRow lastSpacer = rows.get(runEnd - 1);
            for (ASTTableCell sc : lastSpacer.cells()) {
                if (sc.bottomBorder() != null && sc.bottomBorder().weight() > 0) {
                    for (ASTTableCell bc : below.cells()) {
                        if (bc.topBorder() == null || bc.topBorder().weight() == 0) {
                            bc.topBorder(sc.bottomBorder());
                        }
                    }
                }
            }
        }
    }

    /**
     * 행/셀 인덱스 재번호.
     */
    static void reindex(ASTTable table) {
        List<ASTTableRow> rows = table.rows();
        for (int r = 0; r < rows.size(); r++) {
            rows.get(r).rowIndex(r);
            for (ASTTableCell cell : rows.get(r).cells()) {
                cell.rowIndex(r);
            }
        }
    }

    /**
     * rowSpan 기반 셀 높이 재계산.
     */
    static void recalcCellHeights(ASTTable table) {
        List<ASTTableRow> rows = table.rows();
        for (ASTTableRow row : rows) {
            for (ASTTableCell cell : row.cells()) {
                long h = 0;
                int endRow = Math.min(cell.rowIndex() + cell.rowSpan(), rows.size());
                for (int r = cell.rowIndex(); r < endRow; r++) {
                    h += rows.get(r).rowHeight();
                }
                cell.height(h);
            }
        }
    }
}
