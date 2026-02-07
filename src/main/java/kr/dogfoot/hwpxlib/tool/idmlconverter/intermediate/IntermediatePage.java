package kr.dogfoot.hwpxlib.tool.idmlconverter.intermediate;

import java.util.ArrayList;
import java.util.List;

/**
 * 중간 포맷 페이지.
 */
public class IntermediatePage {
    private int pageNumber;
    private long pageWidth;
    private long pageHeight;
    private List<IntermediateFrame> frames;

    // 페이지 마진 (HWPUNIT)
    private long marginTop;
    private long marginBottom;
    private long marginLeft;
    private long marginRight;

    public IntermediatePage() {
        this.frames = new ArrayList<IntermediateFrame>();
    }

    public int pageNumber() { return pageNumber; }
    public void pageNumber(int v) { this.pageNumber = v; }

    public long pageWidth() { return pageWidth; }
    public void pageWidth(long v) { this.pageWidth = v; }

    public long pageHeight() { return pageHeight; }
    public void pageHeight(long v) { this.pageHeight = v; }

    public List<IntermediateFrame> frames() { return frames; }
    public void addFrame(IntermediateFrame frame) { frames.add(frame); }

    // 마진 접근자
    public long marginTop() { return marginTop; }
    public void marginTop(long v) { this.marginTop = v; }

    public long marginBottom() { return marginBottom; }
    public void marginBottom(long v) { this.marginBottom = v; }

    public long marginLeft() { return marginLeft; }
    public void marginLeft(long v) { this.marginLeft = v; }

    public long marginRight() { return marginRight; }
    public void marginRight(long v) { this.marginRight = v; }

    /**
     * 페이지에 유효한 마진이 설정되어 있는지 확인.
     */
    public boolean hasValidMargins() {
        return marginTop > 0 || marginBottom > 0 || marginLeft > 0 || marginRight > 0;
    }
}
