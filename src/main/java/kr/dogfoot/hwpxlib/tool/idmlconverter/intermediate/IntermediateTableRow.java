package kr.dogfoot.hwpxlib.tool.idmlconverter.intermediate;

import java.util.ArrayList;
import java.util.List;

/**
 * Intermediate table row.
 * All dimensions in HWPUNIT.
 */
public class IntermediateTableRow {
    private int rowIndex;
    private long rowHeight;  // HWPUNIT
    private List<IntermediateTableCell> cells;

    public IntermediateTableRow() {
        this.cells = new ArrayList<>();
    }

    public int rowIndex() { return rowIndex; }
    public void rowIndex(int v) { this.rowIndex = v; }

    public long rowHeight() { return rowHeight; }
    public void rowHeight(long v) { this.rowHeight = v; }

    public List<IntermediateTableCell> cells() { return cells; }
    public void addCell(IntermediateTableCell cell) { cells.add(cell); }
}
