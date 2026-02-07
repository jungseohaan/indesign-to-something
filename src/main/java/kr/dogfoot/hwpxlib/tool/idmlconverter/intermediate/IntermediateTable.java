package kr.dogfoot.hwpxlib.tool.idmlconverter.intermediate;

import java.util.ArrayList;
import java.util.List;

/**
 * Intermediate table representation.
 * All dimensions in HWPUNIT.
 */
public class IntermediateTable {
    private String tableId;
    private int rowCount;
    private int columnCount;
    private List<IntermediateTableRow> rows;
    private List<Long> columnWidths;  // HWPUNIT

    // Table position (for floating tables)
    private long x;
    private long y;
    private long width;
    private long height;
    private int zOrder;

    // Table styling
    private String borderColor;
    private long borderWidth;  // HWPUNIT
    private int cellSpacing;   // HWPUNIT

    // Margins
    private long insetTop;
    private long insetBottom;
    private long insetLeft;
    private long insetRight;

    public IntermediateTable() {
        this.rows = new ArrayList<>();
        this.columnWidths = new ArrayList<>();
    }

    public String tableId() { return tableId; }
    public void tableId(String v) { this.tableId = v; }

    public int rowCount() { return rowCount; }
    public void rowCount(int v) { this.rowCount = v; }

    public int columnCount() { return columnCount; }
    public void columnCount(int v) { this.columnCount = v; }

    public List<IntermediateTableRow> rows() { return rows; }
    public void addRow(IntermediateTableRow row) { rows.add(row); }

    public List<Long> columnWidths() { return columnWidths; }
    public void addColumnWidth(long width) { columnWidths.add(width); }

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

    public String borderColor() { return borderColor; }
    public void borderColor(String v) { this.borderColor = v; }

    public long borderWidth() { return borderWidth; }
    public void borderWidth(long v) { this.borderWidth = v; }

    public int cellSpacing() { return cellSpacing; }
    public void cellSpacing(int v) { this.cellSpacing = v; }

    public long insetTop() { return insetTop; }
    public void insetTop(long v) { this.insetTop = v; }

    public long insetBottom() { return insetBottom; }
    public void insetBottom(long v) { this.insetBottom = v; }

    public long insetLeft() { return insetLeft; }
    public void insetLeft(long v) { this.insetLeft = v; }

    public long insetRight() { return insetRight; }
    public void insetRight(long v) { this.insetRight = v; }

    /**
     * Calculate total table width from column widths.
     */
    public long calculatedWidth() {
        long total = 0;
        for (Long w : columnWidths) {
            total += w;
        }
        return total;
    }

    /**
     * Calculate total table height from row heights.
     */
    public long calculatedHeight() {
        long total = 0;
        for (IntermediateTableRow row : rows) {
            total += row.rowHeight();
        }
        return total;
    }
}
