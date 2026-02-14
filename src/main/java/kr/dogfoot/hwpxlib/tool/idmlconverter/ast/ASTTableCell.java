package kr.dogfoot.hwpxlib.tool.idmlconverter.ast;

import java.util.ArrayList;
import java.util.List;

/**
 * 테이블 셀 — 미니 문서 (ASTParagraph 리스트 포함).
 */
public class ASTTableCell {
    private int rowIndex;
    private int columnIndex;
    private int rowSpan = 1;
    private int columnSpan = 1;
    private long width;
    private long height;

    // 셀 내용
    private List<ASTParagraph> paragraphs;

    // 셀 스타일
    private String fillColor;
    private CellBorder topBorder;
    private CellBorder bottomBorder;
    private CellBorder leftBorder;
    private CellBorder rightBorder;
    private boolean topLeftDiagonalLine;
    private boolean topRightDiagonalLine;
    private CellBorder diagonalBorder;

    // 셀 여백
    private long marginTop;
    private long marginBottom;
    private long marginLeft;
    private long marginRight;
    private String verticalAlign = "TopAlign";

    public ASTTableCell() {
        this.paragraphs = new ArrayList<>();
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

    public List<ASTParagraph> paragraphs() { return paragraphs; }
    public void addParagraph(ASTParagraph p) { paragraphs.add(p); }

    public String fillColor() { return fillColor; }
    public void fillColor(String v) { this.fillColor = v; }

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

    /**
     * 셀 테두리 정보.
     */
    public static class CellBorder {
        private String color;
        private double weight;
        private String strokeType;
        private double tint = 100;

        public String color() { return color; }
        public void color(String v) { this.color = v; }

        public double weight() { return weight; }
        public void weight(double v) { this.weight = v; }

        public String strokeType() { return strokeType; }
        public void strokeType(String v) { this.strokeType = v; }

        public double tint() { return tint; }
        public void tint(double v) { this.tint = v; }
    }
}
