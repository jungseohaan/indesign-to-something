package kr.dogfoot.hwpxlib.tool.idmlconverter.resolved;

/**
 * resolved.json의 테이블 데이터.
 * InDesign DOM에서 수집한 테이블 치수 정보.
 */
public class ResolvedTable {
    private String id;
    private int rowCount;
    private int columnCount;
    private double[] columnWidths;  // pts
    private double[] rowHeights;    // pts
    private double[] bounds;        // [top, left, bottom, right] page-relative

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
}
