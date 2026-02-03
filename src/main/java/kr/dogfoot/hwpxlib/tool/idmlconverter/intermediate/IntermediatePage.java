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
}
