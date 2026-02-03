package kr.dogfoot.hwpxlib.tool.idmlconverter.intermediate;

/**
 * 페이지 기본 레이아웃 (HWPUNIT 단위).
 */
public class DocumentLayout {
    private long defaultPageWidth;
    private long defaultPageHeight;
    private long marginTop;
    private long marginBottom;
    private long marginLeft;
    private long marginRight;

    public long defaultPageWidth() { return defaultPageWidth; }
    public void defaultPageWidth(long v) { this.defaultPageWidth = v; }

    public long defaultPageHeight() { return defaultPageHeight; }
    public void defaultPageHeight(long v) { this.defaultPageHeight = v; }

    public long marginTop() { return marginTop; }
    public void marginTop(long v) { this.marginTop = v; }

    public long marginBottom() { return marginBottom; }
    public void marginBottom(long v) { this.marginBottom = v; }

    public long marginLeft() { return marginLeft; }
    public void marginLeft(long v) { this.marginLeft = v; }

    public long marginRight() { return marginRight; }
    public void marginRight(long v) { this.marginRight = v; }
}
