package kr.dogfoot.hwpxlib.tool.idmlconverter.intermediate;

import java.util.ArrayList;
import java.util.List;

/**
 * Intermediate table cell.
 * All dimensions in HWPUNIT.
 */
public class IntermediateTableCell {
    private int rowIndex;
    private int columnIndex;
    private int rowSpan;
    private int columnSpan;

    // Cell dimensions (HWPUNIT)
    private long width;
    private long height;

    // Cell content
    private List<IntermediateParagraph> paragraphs;

    // Cell styling
    private String fillColor;
    private String borderFillId;

    // Cell borders (개별 테두리)
    private CellBorder topBorder;
    private CellBorder bottomBorder;
    private CellBorder leftBorder;
    private CellBorder rightBorder;

    // Diagonal lines (대각선)
    private boolean topLeftDiagonalLine;   // 대각선 ↘ (좌상→우하) - HWPX slash
    private boolean topRightDiagonalLine;  // 대각선 ↙ (우상→좌하) - HWPX backSlash
    private CellBorder diagonalBorder;

    // Cell margins (HWPUNIT)
    private long marginTop;
    private long marginBottom;
    private long marginLeft;
    private long marginRight;

    // Vertical alignment: "top", "center", "bottom"
    private String verticalAlign;

    public IntermediateTableCell() {
        this.paragraphs = new ArrayList<>();
        this.rowSpan = 1;
        this.columnSpan = 1;
        this.verticalAlign = "top";
    }

    public int rowIndex() { return rowIndex; }
    public void rowIndex(int v) { this.rowIndex = v; }

    public int columnIndex() { return columnIndex; }
    public void columnIndex(int v) { this.columnIndex = v; }

    public int rowSpan() { return rowSpan; }
    public void rowSpan(int v) { this.rowSpan = v; }

    public int columnSpan() { return columnSpan; }
    public void columnSpan(int v) { this.columnSpan = v; }

    public long width() { return width; }
    public void width(long v) { this.width = v; }

    public long height() { return height; }
    public void height(long v) { this.height = v; }

    public List<IntermediateParagraph> paragraphs() { return paragraphs; }
    public void addParagraph(IntermediateParagraph para) { paragraphs.add(para); }

    public String fillColor() { return fillColor; }
    public void fillColor(String v) { this.fillColor = v; }

    public String borderFillId() { return borderFillId; }
    public void borderFillId(String v) { this.borderFillId = v; }

    public long marginTop() { return marginTop; }
    public void marginTop(long v) { this.marginTop = v; }

    public long marginBottom() { return marginBottom; }
    public void marginBottom(long v) { this.marginBottom = v; }

    public long marginLeft() { return marginLeft; }
    public void marginLeft(long v) { this.marginLeft = v; }

    public long marginRight() { return marginRight; }
    public void marginRight(long v) { this.marginRight = v; }

    public String verticalAlign() { return verticalAlign; }
    public void verticalAlign(String v) { this.verticalAlign = v; }

    public CellBorder topBorder() { return topBorder; }
    public void topBorder(CellBorder v) { this.topBorder = v; }

    public CellBorder bottomBorder() { return bottomBorder; }
    public void bottomBorder(CellBorder v) { this.bottomBorder = v; }

    public CellBorder leftBorder() { return leftBorder; }
    public void leftBorder(CellBorder v) { this.leftBorder = v; }

    public CellBorder rightBorder() { return rightBorder; }
    public void rightBorder(CellBorder v) { this.rightBorder = v; }

    public boolean topLeftDiagonalLine() { return topLeftDiagonalLine; }
    public void topLeftDiagonalLine(boolean v) { this.topLeftDiagonalLine = v; }

    public boolean topRightDiagonalLine() { return topRightDiagonalLine; }
    public void topRightDiagonalLine(boolean v) { this.topRightDiagonalLine = v; }

    public CellBorder diagonalBorder() { return diagonalBorder; }
    public void diagonalBorder(CellBorder v) { this.diagonalBorder = v; }

    /**
     * Check if this is a merged cell (spans more than 1 row or column).
     */
    public boolean isMerged() {
        return rowSpan > 1 || columnSpan > 1;
    }

    /**
     * Cell border properties (중간 포맷).
     */
    public static class CellBorder {
        public String strokeColor;    // 색상 (#RRGGBB 형식)
        public double strokeWeight;   // 선 두께 (HWPUNIT)
        public String strokeType;     // Solid, Dashed, Dotted 등
        public double strokeTint;     // 투명도 (0-100)

        public CellBorder() {
            this.strokeColor = "#000000";
            this.strokeWeight = 0;
            this.strokeType = "Solid";
            this.strokeTint = 100;
        }
    }
}
