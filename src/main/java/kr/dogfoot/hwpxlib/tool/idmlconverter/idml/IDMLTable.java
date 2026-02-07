package kr.dogfoot.hwpxlib.tool.idmlconverter.idml;

import java.util.ArrayList;
import java.util.List;

/**
 * IDML Table element from Story XML.
 */
public class IDMLTable {
    private String selfId;
    private int rowCount;
    private int columnCount;
    private String appliedTableStyle;
    private List<IDMLTableRow> rows;
    private List<Double> columnWidths;  // points

    // Table style properties
    private String strokeColor;
    private double strokeWeight;
    private String fillColor;
    private double spaceBefore;  // points
    private double spaceAfter;   // points

    public IDMLTable() {
        this.rows = new ArrayList<>();
        this.columnWidths = new ArrayList<>();
    }

    public String selfId() { return selfId; }
    public void selfId(String v) { this.selfId = v; }

    public int rowCount() { return rowCount; }
    public void rowCount(int v) { this.rowCount = v; }

    public int columnCount() { return columnCount; }
    public void columnCount(int v) { this.columnCount = v; }

    public String appliedTableStyle() { return appliedTableStyle; }
    public void appliedTableStyle(String v) { this.appliedTableStyle = v; }

    public List<IDMLTableRow> rows() { return rows; }
    public void addRow(IDMLTableRow row) { rows.add(row); }

    public List<Double> columnWidths() { return columnWidths; }
    public void addColumnWidth(double width) { columnWidths.add(width); }

    public String strokeColor() { return strokeColor; }
    public void strokeColor(String v) { this.strokeColor = v; }

    public double strokeWeight() { return strokeWeight; }
    public void strokeWeight(double v) { this.strokeWeight = v; }

    public String fillColor() { return fillColor; }
    public void fillColor(String v) { this.fillColor = v; }

    public double spaceBefore() { return spaceBefore; }
    public void spaceBefore(double v) { this.spaceBefore = v; }

    public double spaceAfter() { return spaceAfter; }
    public void spaceAfter(double v) { this.spaceAfter = v; }

    /**
     * Calculate total table width from column widths.
     */
    public double totalWidth() {
        double total = 0;
        for (Double w : columnWidths) {
            total += w;
        }
        return total;
    }

    /**
     * Calculate total table height from row heights.
     */
    public double totalHeight() {
        double total = 0;
        for (IDMLTableRow row : rows) {
            total += row.rowHeight();
        }
        return total;
    }
}
