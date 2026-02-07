package kr.dogfoot.hwpxlib.tool.idmlconverter.idml;

import java.util.ArrayList;
import java.util.List;

/**
 * IDML Table Row (Row element).
 */
public class IDMLTableRow {
    private String selfId;
    private int rowIndex;
    private double rowHeight;      // points
    private double minRowHeight;   // points
    private double maxRowHeight;   // points
    private boolean autoGrow;
    private List<IDMLTableCell> cells;

    public IDMLTableRow() {
        this.cells = new ArrayList<>();
        this.autoGrow = true;
    }

    public String selfId() { return selfId; }
    public void selfId(String v) { this.selfId = v; }

    public int rowIndex() { return rowIndex; }
    public void rowIndex(int v) { this.rowIndex = v; }

    public double rowHeight() { return rowHeight; }
    public void rowHeight(double v) { this.rowHeight = v; }

    public double minRowHeight() { return minRowHeight; }
    public void minRowHeight(double v) { this.minRowHeight = v; }

    public double maxRowHeight() { return maxRowHeight; }
    public void maxRowHeight(double v) { this.maxRowHeight = v; }

    public boolean autoGrow() { return autoGrow; }
    public void autoGrow(boolean v) { this.autoGrow = v; }

    public List<IDMLTableCell> cells() { return cells; }
    public void addCell(IDMLTableCell cell) { cells.add(cell); }
}
