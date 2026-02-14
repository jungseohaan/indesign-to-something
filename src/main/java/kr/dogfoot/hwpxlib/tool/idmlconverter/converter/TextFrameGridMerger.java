package kr.dogfoot.hwpxlib.tool.idmlconverter.converter;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.*;

import java.util.*;

/**
 * 한 페이지(섹션)의 TEXT_FRAME_BLOCK들을 하나의 ASTTable로 병합한다.
 *
 * 각 블록의 경계선(x, x+w, y, y+h)을 그리드 라인으로 사용하여
 * colSpan/rowSpan 기반의 단일 테이블을 생성한다.
 * 겹치는 블록은 z-order가 높은 것이 우선권을 갖는다.
 */
public class TextFrameGridMerger {

    /**
     * 텍스트 프레임 블록 리스트를 단일 ASTTable로 병합.
     *
     * @param blocks 한 섹션의 TEXT_FRAME_BLOCK 리스트 (비어있지 않아야 함)
     * @return 병합된 ASTTable, 빈 리스트면 null
     */
    public static ASTTable merge(List<ASTTextFrameBlock> blocks) {
        if (blocks == null || blocks.isEmpty()) return null;
        if (blocks.size() == 1) return singleBlockTable(blocks.get(0));

        // Phase 1: 그리드 라인 수집
        TreeSet<Long> xSet = new TreeSet<>();
        TreeSet<Long> ySet = new TreeSet<>();
        for (ASTTextFrameBlock b : blocks) {
            long bx = Math.max(0, b.x());
            long by = Math.max(0, b.y());
            xSet.add(bx);
            xSet.add(bx + b.width());
            ySet.add(by);
            ySet.add(by + b.height());
        }

        List<Long> xLines = new ArrayList<>(xSet);
        List<Long> yLines = new ArrayList<>(ySet);
        int numCols = xLines.size() - 1;
        int numRows = yLines.size() - 1;

        if (numCols <= 0 || numRows <= 0) {
            return singleBlockTable(blocks.get(0));
        }

        // Phase 2: 블록 → 그리드 매핑
        // z-order 내림차순 정렬 (높은 z가 우선)
        List<ASTTextFrameBlock> sorted = new ArrayList<>(blocks);
        sorted.sort((a, b2) -> Integer.compare(b2.zOrder(), a.zOrder()));

        // 각 블록의 그리드 범위 계산
        Map<ASTTextFrameBlock, int[]> blockExtent = new LinkedHashMap<>();
        for (ASTTextFrameBlock b : sorted) {
            long bx = Math.max(0, b.x());
            long by = Math.max(0, b.y());
            int startCol = Collections.binarySearch(xLines, bx);
            int endCol = Collections.binarySearch(xLines, bx + b.width());
            int startRow = Collections.binarySearch(yLines, by);
            int endRow = Collections.binarySearch(yLines, by + b.height());

            // binarySearch가 음수를 반환하면 가장 가까운 인덱스 사용
            if (startCol < 0) startCol = -(startCol + 1);
            if (endCol < 0) endCol = -(endCol + 1);
            if (startRow < 0) startRow = -(startRow + 1);
            if (endRow < 0) endRow = -(endRow + 1);

            // 범위 클램핑
            startCol = Math.min(startCol, numCols);
            endCol = Math.min(endCol, numCols);
            startRow = Math.min(startRow, numRows);
            endRow = Math.min(endRow, numRows);

            if (startCol < endCol && startRow < endRow) {
                blockExtent.put(b, new int[]{startRow, startCol, endRow, endCol});
            }
        }

        // Phase 3: 겹침 해소 — gridOwner[row][col]
        ASTTextFrameBlock[][] gridOwner = new ASTTextFrameBlock[numRows][numCols];
        for (Map.Entry<ASTTextFrameBlock, int[]> entry : blockExtent.entrySet()) {
            ASTTextFrameBlock b = entry.getKey();
            int[] ext = entry.getValue();
            for (int r = ext[0]; r < ext[2]; r++) {
                for (int c = ext[1]; c < ext[3]; c++) {
                    if (gridOwner[r][c] == null) {
                        gridOwner[r][c] = b;
                    }
                }
            }
        }

        // Phase 4: ASTTable 생성
        ASTTable table = new ASTTable();
        table.x(xLines.get(0));
        table.y(yLines.get(0));
        table.width(xLines.get(numCols) - xLines.get(0));
        table.height(yLines.get(numRows) - yLines.get(0));
        table.zOrder(findMaxZOrder(blocks));
        table.rowCount(numRows);
        table.colCount(numCols);

        for (int c = 0; c < numCols; c++) {
            table.addColumnWidth(xLines.get(c + 1) - xLines.get(c));
        }

        // 각 블록이 소유하는 실제 셀 영역 계산 (겹침 해소 후)
        // 블록의 원래 extent에서 실제로 소유하는 셀만으로 최대 직사각형 계산
        Map<ASTTextFrameBlock, int[]> actualExtent = computeActualExtents(
                blocks, blockExtent, gridOwner, numRows, numCols);

        // covered[r][c] = 이미 다른 셀의 span에 포함된 셀
        boolean[][] covered = new boolean[numRows][numCols];

        for (int r = 0; r < numRows; r++) {
            ASTTableRow row = new ASTTableRow();
            row.rowIndex(r);
            row.rowHeight(yLines.get(r + 1) - yLines.get(r));

            for (int c = 0; c < numCols; c++) {
                if (covered[r][c]) continue;

                ASTTextFrameBlock owner = gridOwner[r][c];

                if (owner != null && actualExtent.containsKey(owner)) {
                    int[] ext = actualExtent.get(owner);
                    // 이 셀이 블록의 좌상단인 경우에만 콘텐츠 셀 생성
                    if (r == ext[0] && c == ext[1]) {
                        int rowSpan = ext[2] - ext[0];
                        int colSpan = ext[3] - ext[1];

                        ASTTableCell cell = createCellFromBlock(
                                owner, r, c, rowSpan, colSpan, xLines, yLines);
                        row.addCell(cell);

                        // span 영역 마킹
                        for (int rr = r; rr < r + rowSpan; rr++) {
                            for (int cc = c; cc < c + colSpan; cc++) {
                                covered[rr][cc] = true;
                            }
                        }
                        continue;
                    }
                }

                // 빈 셀 또는 span 내부가 아닌 나머지
                if (!covered[r][c]) {
                    // 인접한 빈 셀을 가로로 병합하여 셀 수 줄이기
                    int emptyColSpan = 1;
                    while (c + emptyColSpan < numCols
                            && !covered[r][c + emptyColSpan]
                            && isEmptyOrNonTopLeft(gridOwner, actualExtent, r, c + emptyColSpan)) {
                        emptyColSpan++;
                    }

                    ASTTableCell emptyCell = createEmptyCell(r, c, emptyColSpan, xLines, yLines);
                    row.addCell(emptyCell);

                    for (int cc = c; cc < c + emptyColSpan; cc++) {
                        covered[r][cc] = true;
                    }
                }
            }

            table.addRow(row);
        }

        System.err.println("[TextFrameGridMerger] Merged " + blocks.size()
                + " text frames into " + numRows + "x" + numCols + " grid table");

        return table;
    }

    /**
     * 겹침 해소 후 각 블록의 실제 소유 영역(최대 직사각형)을 계산.
     */
    private static Map<ASTTextFrameBlock, int[]> computeActualExtents(
            List<ASTTextFrameBlock> blocks,
            Map<ASTTextFrameBlock, int[]> blockExtent,
            ASTTextFrameBlock[][] gridOwner,
            int numRows, int numCols) {

        Map<ASTTextFrameBlock, int[]> result = new LinkedHashMap<>();

        for (ASTTextFrameBlock b : blocks) {
            int[] ext = blockExtent.get(b);
            if (ext == null) continue;

            // 블록이 실제로 소유하는 셀 중 최대 직사각형 찾기
            // 원래 extent 내에서 이 블록이 owner인 셀들의 bounding box
            int minR = numRows, maxR = -1, minC = numCols, maxC = -1;
            for (int r = ext[0]; r < ext[2]; r++) {
                for (int c = ext[1]; c < ext[3]; c++) {
                    if (gridOwner[r][c] == b) {
                        minR = Math.min(minR, r);
                        maxR = Math.max(maxR, r);
                        minC = Math.min(minC, c);
                        maxC = Math.max(maxC, c);
                    }
                }
            }

            if (maxR >= 0 && maxC >= 0) {
                result.put(b, new int[]{minR, minC, maxR + 1, maxC + 1});
            }
        }

        return result;
    }

    private static boolean isEmptyOrNonTopLeft(
            ASTTextFrameBlock[][] gridOwner,
            Map<ASTTextFrameBlock, int[]> actualExtent,
            int r, int c) {
        ASTTextFrameBlock owner = gridOwner[r][c];
        if (owner == null) return true;

        int[] ext = actualExtent.get(owner);
        if (ext == null) return true;

        // 이 셀이 블록의 좌상단이 아니면 빈 셀 취급 (span에 포함될 것이므로)
        return !(r == ext[0] && c == ext[1]);
    }

    private static ASTTableCell createCellFromBlock(
            ASTTextFrameBlock block, int rowIdx, int colIdx,
            int rowSpan, int colSpan,
            List<Long> xLines, List<Long> yLines) {

        ASTTableCell cell = new ASTTableCell();
        cell.rowIndex(rowIdx);
        cell.columnIndex(colIdx);
        cell.rowSpan(rowSpan);
        cell.columnSpan(colSpan);

        // 셀 크기 = span 범위의 합
        long width = 0;
        for (int c = colIdx; c < colIdx + colSpan && c < xLines.size() - 1; c++) {
            width += xLines.get(c + 1) - xLines.get(c);
        }
        cell.width(width);

        long height = 0;
        for (int r = rowIdx; r < rowIdx + rowSpan && r < yLines.size() - 1; r++) {
            height += yLines.get(r + 1) - yLines.get(r);
        }
        cell.height(height);

        // 블록의 단락 복사
        for (ASTParagraph para : block.paragraphs()) {
            cell.addParagraph(para);
        }

        // 셀 여백 = 블록 inset
        cell.marginTop(block.insetTop());
        cell.marginBottom(block.insetBottom());
        cell.marginLeft(block.insetLeft());
        cell.marginRight(block.insetRight());

        // 배경색
        cell.fillColor(block.fillColor());

        // 테두리
        if (block.strokeColor() != null && block.strokeWeight() > 0) {
            ASTTableCell.CellBorder border = new ASTTableCell.CellBorder();
            border.color(block.strokeColor());
            border.weight(block.strokeWeight());
            border.strokeType("solid");
            cell.topBorder(border);
            cell.bottomBorder(border);
            cell.leftBorder(border);
            cell.rightBorder(border);
        }

        // 세로 정렬
        String vj = block.verticalJustification();
        if ("CenterAlign".equals(vj) || "center".equals(vj)) {
            cell.verticalAlign("CenterAlign");
        } else if ("BottomAlign".equals(vj) || "bottom".equals(vj)) {
            cell.verticalAlign("BottomAlign");
        } else {
            cell.verticalAlign("TopAlign");
        }

        return cell;
    }

    private static ASTTableCell createEmptyCell(
            int rowIdx, int colIdx, int colSpan,
            List<Long> xLines, List<Long> yLines) {

        ASTTableCell cell = new ASTTableCell();
        cell.rowIndex(rowIdx);
        cell.columnIndex(colIdx);
        cell.rowSpan(1);
        cell.columnSpan(colSpan);

        long width = 0;
        for (int c = colIdx; c < colIdx + colSpan && c < xLines.size() - 1; c++) {
            width += xLines.get(c + 1) - xLines.get(c);
        }
        cell.width(width);
        cell.height(yLines.get(rowIdx + 1) - yLines.get(rowIdx));

        return cell;
    }

    /**
     * 블록 1개일 때 간단한 1x1 테이블 생성.
     */
    private static ASTTable singleBlockTable(ASTTextFrameBlock block) {
        long bx = Math.max(0, block.x());
        long by = Math.max(0, block.y());

        ASTTable table = new ASTTable();
        table.x(bx);
        table.y(by);
        table.width(block.width());
        table.height(block.height());
        table.zOrder(block.zOrder());
        table.rowCount(1);
        table.colCount(1);
        table.addColumnWidth(block.width());

        ASTTableRow row = new ASTTableRow();
        row.rowIndex(0);
        row.rowHeight(block.height());

        ASTTableCell cell = new ASTTableCell();
        cell.rowIndex(0);
        cell.columnIndex(0);
        cell.rowSpan(1);
        cell.columnSpan(1);
        cell.width(block.width());
        cell.height(block.height());
        cell.marginTop(block.insetTop());
        cell.marginBottom(block.insetBottom());
        cell.marginLeft(block.insetLeft());
        cell.marginRight(block.insetRight());
        cell.fillColor(block.fillColor());

        if (block.strokeColor() != null && block.strokeWeight() > 0) {
            ASTTableCell.CellBorder border = new ASTTableCell.CellBorder();
            border.color(block.strokeColor());
            border.weight(block.strokeWeight());
            border.strokeType("solid");
            cell.topBorder(border);
            cell.bottomBorder(border);
            cell.leftBorder(border);
            cell.rightBorder(border);
        }

        String vj = block.verticalJustification();
        if ("CenterAlign".equals(vj) || "center".equals(vj)) {
            cell.verticalAlign("CenterAlign");
        } else if ("BottomAlign".equals(vj) || "bottom".equals(vj)) {
            cell.verticalAlign("BottomAlign");
        } else {
            cell.verticalAlign("TopAlign");
        }

        for (ASTParagraph para : block.paragraphs()) {
            cell.addParagraph(para);
        }

        row.addCell(cell);
        table.addRow(row);

        return table;
    }

    private static int findMaxZOrder(List<ASTTextFrameBlock> blocks) {
        int max = 0;
        for (ASTTextFrameBlock b : blocks) {
            max = Math.max(max, b.zOrder());
        }
        return max;
    }
}
