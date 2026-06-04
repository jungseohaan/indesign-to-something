package kr.dogfoot.hwpxlib.tool.idmlconverter.ast;

import java.util.ArrayList;
import java.util.List;

/**
 * 테이블 행.
 */
public class ASTTableRow {
    private int rowIndex;
    private long rowHeight;
    private boolean autoGrow;
    private List<ASTTableCell> cells;

    public ASTTableRow() {
        this.cells = new ArrayList<>();
    }

    public int rowIndex() { return rowIndex; }
    public void rowIndex(int v) { this.rowIndex = v; }

    public long rowHeight() { return rowHeight; }
    public void rowHeight(long v) { this.rowHeight = v; }

    public boolean autoGrow() { return autoGrow; }
    public void autoGrow(boolean v) { this.autoGrow = v; }

    public List<ASTTableCell> cells() { return cells; }
    public void addCell(ASTTableCell c) { cells.add(c); }
}
