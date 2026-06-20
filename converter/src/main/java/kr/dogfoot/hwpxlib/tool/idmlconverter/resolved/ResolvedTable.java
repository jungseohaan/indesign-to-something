package kr.dogfoot.hwpxlib.tool.idmlconverter.resolved;

import java.util.ArrayList;
import java.util.List;

/**
 * resolved.json의 테이블 데이터.
 * InDesign DOM에서 수집한 테이블 치수 정보.
 */
public class ResolvedTable {
    public static class Cell {
        private int row;
        private int col;
        private final List<Integer> inlineAnchorIds = new ArrayList<>();
        private boolean hasTextRuns;

        public int row() { return row; }
        public void row(int v) { this.row = v; }

        public int col() { return col; }
        public void col(int v) { this.col = v; }

        public List<Integer> inlineAnchorIds() { return inlineAnchorIds; }
        public void addInlineAnchorId(int v) { inlineAnchorIds.add(v); }

        public boolean hasTextRuns() { return hasTextRuns; }
        public void hasTextRuns(boolean v) { this.hasTextRuns = v; }
    }

    private String id;
    private int rowCount;
    private int columnCount;
    private double[] columnWidths;  // pts
    private double[] rowHeights;    // pts
    private double[] bounds;        // [top, left, bottom, right] page-relative
    private final List<Cell> cells = new ArrayList<>();

    public String id() { return id; }
    public void id(String v) { this.id = v; }

    public int rowCount() { return rowCount; }
    public void rowCount(int v) { this.rowCount = v; }

    public int columnCount() { return columnCount; }
    public void columnCount(int v) { this.columnCount = v; }

    public double[] columnWidths() { return columnWidths; }
    public void columnWidths(double[] v) { this.columnWidths = v; }

    public double[] rowHeights() { return rowHeights; }
    public void rowHeights(double[] v) { this.rowHeights = v; }

    public double[] bounds() { return bounds; }
    public void bounds(double[] v) { this.bounds = v; }

    public List<Cell> cells() { return cells; }
    public void addCell(Cell v) { if (v != null) cells.add(v); }

    public Cell cellAt(int row, int col) {
        for (Cell cell : cells) {
            if (cell.row() == row && cell.col() == col) return cell;
        }
        return null;
    }
}
