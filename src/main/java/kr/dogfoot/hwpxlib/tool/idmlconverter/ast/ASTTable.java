package kr.dogfoot.hwpxlib.tool.idmlconverter.ast;

import java.util.ArrayList;
import java.util.List;

/**
 * 테이블 블록 — 미니 문서 (셀 안에 ASTParagraph 재귀).
 * 좌표 단위: HWPUNIT.
 */
public class ASTTable extends ASTBlock {
    private int rowCount;
    private int colCount;
    private List<Long> columnWidths;
    private List<ASTTableRow> rows;

    // 절대 좌표 (HWPX 배치용)
    private long x;
    private long y;
    private long width;
    private long height;
    private int zOrder;

    // 테이블 스타일
    private String appliedTableStyle;
    private String borderColor;
    private long borderWidth;

    public ASTTable() {
        this.columnWidths = new ArrayList<>();
        this.rows = new ArrayList<>();
    }

    public BlockType blockType() { return BlockType.TABLE; }

    public int rowCount() { return rowCount; }
    public void rowCount(int v) { this.rowCount = v; }

    public int colCount() { return colCount; }
    public void colCount(int v) { this.colCount = v; }

    public List<Long> columnWidths() { return columnWidths; }
    public void addColumnWidth(long w) { columnWidths.add(w); }

    public List<ASTTableRow> rows() { return rows; }
    public void addRow(ASTTableRow r) { rows.add(r); }

    public long x() { return x; }
    public void x(long v) { this.x = v; }

    public long y() { return y; }
    public void y(long v) { this.y = v; }

    public long width() { return width; }
    public void width(long v) { this.width = v; }

    public long height() { return height; }
    public void height(long v) { this.height = v; }

    public int zOrder() { return zOrder; }
    public void zOrder(int v) { this.zOrder = v; }

    public String appliedTableStyle() { return appliedTableStyle; }
    public void appliedTableStyle(String v) { this.appliedTableStyle = v; }

    public String borderColor() { return borderColor; }
    public void borderColor(String v) { this.borderColor = v; }

    public long borderWidth() { return borderWidth; }
    public void borderWidth(long v) { this.borderWidth = v; }
}
