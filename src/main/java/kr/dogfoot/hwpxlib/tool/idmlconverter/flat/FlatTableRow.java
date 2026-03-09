package kr.dogfoot.hwpxlib.tool.idmlconverter.flat;

import java.util.ArrayList;
import java.util.List;

/**
 * Layer 1 하위 구조: 테이블 행.
 * FlatLayoutNode(TABLE)의 tableRows 요소.
 */
public class FlatTableRow {
    private int rowIndex;
    private long rowHeight;
    private boolean autoGrow;
    private List<FlatTableCell> cells;

    public FlatTableRow() {
        this.cells = new ArrayList<>();
    }

    public int rowIndex() { return rowIndex; }
    public void rowIndex(int v) { this.rowIndex = v; }

    public long rowHeight() { return rowHeight; }
    public void rowHeight(long v) { this.rowHeight = v; }

    public boolean autoGrow() { return autoGrow; }
    public void autoGrow(boolean v) { this.autoGrow = v; }

    public List<FlatTableCell> cells() { return cells; }
    public void addCell(FlatTableCell c) { cells.add(c); }
}
