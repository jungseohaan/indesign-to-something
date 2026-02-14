package kr.dogfoot.hwpxlib.tool.idmlconverter.ast;

/**
 * 페이지 레이아웃 — 크기, 마진, 컬럼.
 * 좌표 단위: HWPUNIT (1/7200 inch).
 */
public class ASTPageLayout {
    private long pageWidth;
    private long pageHeight;
    private long marginTop;
    private long marginBottom;
    private long marginLeft;
    private long marginRight;
    private int columnCount;
    private long columnGutter;

    public long pageWidth() { return pageWidth; }
    public void pageWidth(long v) { this.pageWidth = v; }

    public long pageHeight() { return pageHeight; }
    public void pageHeight(long v) { this.pageHeight = v; }

    public long marginTop() { return marginTop; }
    public void marginTop(long v) { this.marginTop = v; }

    public long marginBottom() { return marginBottom; }
    public void marginBottom(long v) { this.marginBottom = v; }

    public long marginLeft() { return marginLeft; }
    public void marginLeft(long v) { this.marginLeft = v; }

    public long marginRight() { return marginRight; }
    public void marginRight(long v) { this.marginRight = v; }

    public int columnCount() { return columnCount; }
    public void columnCount(int v) { this.columnCount = v; }

    public long columnGutter() { return columnGutter; }
    public void columnGutter(long v) { this.columnGutter = v; }
}
