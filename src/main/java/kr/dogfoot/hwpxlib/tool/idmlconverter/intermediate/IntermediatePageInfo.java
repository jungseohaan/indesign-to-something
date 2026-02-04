package kr.dogfoot.hwpxlib.tool.idmlconverter.intermediate;

/**
 * 스프레드 내 개별 페이지 정보.
 * 스프레드 기반 변환 시, 각 페이지의 위치와 크기 정보를 저장한다.
 */
public class IntermediatePageInfo {
    private int pageNumber;     // 페이지 번호 (4, 5, 6, ...)
    private long offsetX;       // 스프레드 내 X 오프셋 (HWPUNIT)
    private long offsetY;       // 스프레드 내 Y 오프셋 (HWPUNIT)
    private long pageWidth;     // 개별 페이지 너비 (HWPUNIT)
    private long pageHeight;    // 개별 페이지 높이 (HWPUNIT)

    public IntermediatePageInfo() {
    }

    public int pageNumber() { return pageNumber; }
    public void pageNumber(int v) { this.pageNumber = v; }

    public long offsetX() { return offsetX; }
    public void offsetX(long v) { this.offsetX = v; }

    public long offsetY() { return offsetY; }
    public void offsetY(long v) { this.offsetY = v; }

    public long pageWidth() { return pageWidth; }
    public void pageWidth(long v) { this.pageWidth = v; }

    public long pageHeight() { return pageHeight; }
    public void pageHeight(long v) { this.pageHeight = v; }
}
