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
        private int rowSpan = 1;
        private int colSpan = 1;
        private String fillColor;
        private double fillTint = 100;
        private final List<ResolvedParagraph> paragraphs = new ArrayList<>();
        private final List<Integer> inlineAnchorIds = new ArrayList<>();
        private boolean hasTextRuns;

        public int row() { return row; }
        public void row(int v) { this.row = v; }

        public int col() { return col; }
        public void col(int v) { this.col = v; }

        public int rowSpan() { return rowSpan; }
        public void rowSpan(int v) { this.rowSpan = Math.max(1, v); }

        public int colSpan() { return colSpan; }
        public void colSpan(int v) { this.colSpan = Math.max(1, v); }

        public String fillColor() { return fillColor; }
        public void fillColor(String v) { this.fillColor = v; }

        public double fillTint() { return fillTint; }
        public void fillTint(double v) { this.fillTint = v; }

        public List<ResolvedParagraph> paragraphs() { return paragraphs; }
        public void addParagraph(ResolvedParagraph v) { if (v != null) paragraphs.add(v); }

        public List<Integer> inlineAnchorIds() { return inlineAnchorIds; }
        public void addInlineAnchorId(int v) { inlineAnchorIds.add(v); }

        public boolean hasTextRuns() { return hasTextRuns; }
        public void hasTextRuns(boolean v) { this.hasTextRuns = v; }
    }

    private String id;
    private String storyId;
    private int rowCount;
    private int columnCount;
    private double[] columnWidths;  // pts
    private double[] rowHeights;    // pts
    private double[] bounds;        // [top, left, bottom, right] page-relative
    private final List<Cell> cells = new ArrayList<>();

    public String id() { return id; }
    public void id(String v) { this.id = v; }

    public String storyId() { return storyId; }
    public void storyId(String v) { this.storyId = v; }

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
