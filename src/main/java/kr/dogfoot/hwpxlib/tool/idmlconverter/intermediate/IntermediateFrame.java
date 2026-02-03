package kr.dogfoot.hwpxlib.tool.idmlconverter.intermediate;

import java.util.ArrayList;
import java.util.List;

/**
 * 중간 포맷 프레임 (text 또는 image).
 * HWPUNIT 좌표.
 */
public class IntermediateFrame {
    private String frameId;
    private String frameType;  // "text" or "image"
    private long x;
    private long y;
    private long width;
    private long height;
    private int zOrder;
    private List<IntermediateParagraph> paragraphs;
    private IntermediateImage image;

    public IntermediateFrame() {
        this.paragraphs = new ArrayList<IntermediateParagraph>();
    }

    public String frameId() { return frameId; }
    public void frameId(String v) { this.frameId = v; }

    public String frameType() { return frameType; }
    public void frameType(String v) { this.frameType = v; }

    public long x() { return x; }
    public void x(long v) { this.x = v; }

    public long y() { return y; }
    public void y(long v) { this.y = v; }

    public long width() { return width; }
    public void width(long v) { this.width = v; }

    public long height() { return height; }
    public void height(long v) { this.height = v; }

    public int zOrder() { return zOrder; }
    public void zOrder(int v) { this.zOrder = v; }

    public List<IntermediateParagraph> paragraphs() { return paragraphs; }
    public void addParagraph(IntermediateParagraph para) { paragraphs.add(para); }

    public IntermediateImage image() { return image; }
    public void image(IntermediateImage v) { this.image = v; }
}
