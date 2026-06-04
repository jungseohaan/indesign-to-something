package kr.dogfoot.hwpxlib.tool.idmlconverter.idml;

import java.util.ArrayList;
import java.util.List;

/**
 * IDML Table Cell (Cell element).
 */
public class IDMLTableCell {
    private String selfId;
    private int rowIndex;
    private int columnIndex;
    private int rowSpan;
    private int columnSpan;

    // Cell style
    private String appliedCellStyle;
    private String fillColor;
    private double fillTint;

    // Cell content (paragraphs)
    private List<IDMLParagraph> paragraphs;

    // Cell borders (individual)
    private CellBorder topBorder;
    private CellBorder bottomBorder;
    private CellBorder leftBorder;
    private CellBorder rightBorder;

    // Diagonal lines
    private boolean topLeftDiagonalLine;   // 대각선 ↘ (좌상→우하)
    private boolean topRightDiagonalLine;  // 대각선 ↙ (우상→좌하)
    private CellBorder diagonalBorder;     // 대각선 스타일

    // TextFrame Story 참조 (셀 내 인라인 텍스트 프레임)
    private List<String> textFrameStoryRefs;

    // Cell padding/insets (points)
    private double topInset;
    private double bottomInset;
    private double leftInset;
    private double rightInset;

    // Vertical alignment: TopAlign, CenterAlign, BottomAlign, JustifyAlign
    private String verticalJustification;

    public IDMLTableCell() {
        this.paragraphs = new ArrayList<>();
        this.textFrameStoryRefs = new ArrayList<>();
        this.rowSpan = 1;
        this.columnSpan = 1;
        this.topInset = 4;
        this.bottomInset = 4;
        this.leftInset = 4;
        this.rightInset = 4;
        this.verticalJustification = "TopAlign";
    }

    public static class CellBorder {
        public String strokeColor;
        public double strokeWeight;
        public String strokeType;  // Solid, Dashed, etc.
        public double strokeTint;

        public CellBorder() {
            this.strokeColor = "#000000";
            this.strokeWeight = 1;
            this.strokeType = "Solid";
            this.strokeTint = 100;
        }
    }

    public String selfId() { return selfId; }
    public void selfId(String v) { this.selfId = v; }

    public int rowIndex() { return rowIndex; }
    public void rowIndex(int v) { this.rowIndex = v; }

    public int columnIndex() { return columnIndex; }
    public void columnIndex(int v) { this.columnIndex = v; }

    public int rowSpan() { return rowSpan; }
    public void rowSpan(int v) { this.rowSpan = v; }

    public int columnSpan() { return columnSpan; }
    public void columnSpan(int v) { this.columnSpan = v; }

    public String appliedCellStyle() { return appliedCellStyle; }
    public void appliedCellStyle(String v) { this.appliedCellStyle = v; }

    public String fillColor() { return fillColor; }
    public void fillColor(String v) { this.fillColor = v; }

    public double fillTint() { return fillTint; }
    public void fillTint(double v) { this.fillTint = v; }

    public List<IDMLParagraph> paragraphs() { return paragraphs; }
    public void addParagraph(IDMLParagraph para) { paragraphs.add(para); }

    public List<String> textFrameStoryRefs() { return textFrameStoryRefs; }
    public void addTextFrameStoryRef(String storyId) { textFrameStoryRefs.add(storyId); }

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

    public double topInset() { return topInset; }
    public void topInset(double v) { this.topInset = v; }

    public double bottomInset() { return bottomInset; }
    public void bottomInset(double v) { this.bottomInset = v; }

    public double leftInset() { return leftInset; }
    public void leftInset(double v) { this.leftInset = v; }

    public double rightInset() { return rightInset; }
    public void rightInset(double v) { this.rightInset = v; }

    public String verticalJustification() { return verticalJustification; }
    public void verticalJustification(String v) { this.verticalJustification = v; }

    /**
     * Check if this is a merged cell (spans more than 1 row or column).
     */
    public boolean isMerged() {
        return rowSpan > 1 || columnSpan > 1;
    }
}
