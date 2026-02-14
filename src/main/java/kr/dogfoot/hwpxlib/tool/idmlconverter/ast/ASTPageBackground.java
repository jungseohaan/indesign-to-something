package kr.dogfoot.hwpxlib.tool.idmlconverter.ast;

/**
 * 페이지 배경 — 플로팅 객체들을 단일 PNG로 렌더링한 결과.
 */
public class ASTPageBackground {
    private int pageNumber;
    private long pageWidth;
    private long pageHeight;
    private byte[] pngData;
    private int pixelWidth;
    private int pixelHeight;

    public int pageNumber() { return pageNumber; }
    public void pageNumber(int v) { this.pageNumber = v; }

    public long pageWidth() { return pageWidth; }
    public void pageWidth(long v) { this.pageWidth = v; }

    public long pageHeight() { return pageHeight; }
    public void pageHeight(long v) { this.pageHeight = v; }

    public byte[] pngData() { return pngData; }
    public void pngData(byte[] v) { this.pngData = v; }

    public int pixelWidth() { return pixelWidth; }
    public void pixelWidth(int v) { this.pixelWidth = v; }

    public int pixelHeight() { return pixelHeight; }
    public void pixelHeight(int v) { this.pixelHeight = v; }
}
