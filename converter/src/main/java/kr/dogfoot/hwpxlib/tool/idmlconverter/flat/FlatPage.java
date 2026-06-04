package kr.dogfoot.hwpxlib.tool.idmlconverter.flat;

import java.util.ArrayList;
import java.util.List;

/**
 * Layer 0: 페이지 컨텍스트.
 * 페이지의 물리적 크기, 여백, 다단 정보와 레이아웃 노드 참조를 담는다.
 */
public class FlatPage {
    private String pageId;
    private int pageNumber;
    private long pageWidth;
    private long pageHeight;
    private long marginTop;
    private long marginBottom;
    private long marginLeft;
    private long marginRight;
    private int columnCount;
    private long columnGutter;
    private List<String> layoutNodeIds;
    private List<String> zOrderedNodeIds;
    private List<String> backgroundNodeIds;
    private List<String> contentNodeIds;
    private List<String> foregroundNodeIds;

    public FlatPage() {
        this.layoutNodeIds = new ArrayList<>();
        this.zOrderedNodeIds = new ArrayList<>();
        this.backgroundNodeIds = new ArrayList<>();
        this.contentNodeIds = new ArrayList<>();
        this.foregroundNodeIds = new ArrayList<>();
    }

    public String pageId() { return pageId; }
    public void pageId(String v) { this.pageId = v; }

    public int pageNumber() { return pageNumber; }
    public void pageNumber(int v) { this.pageNumber = v; }

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

    public List<String> layoutNodeIds() { return layoutNodeIds; }
    public void layoutNodeIds(List<String> v) { this.layoutNodeIds = v; }

    public List<String> zOrderedNodeIds() { return zOrderedNodeIds; }
    public void zOrderedNodeIds(List<String> v) { this.zOrderedNodeIds = v; }

    public List<String> backgroundNodeIds() { return backgroundNodeIds; }
    public void backgroundNodeIds(List<String> v) { this.backgroundNodeIds = v; }

    public List<String> contentNodeIds() { return contentNodeIds; }
    public void contentNodeIds(List<String> v) { this.contentNodeIds = v; }

    public List<String> foregroundNodeIds() { return foregroundNodeIds; }
    public void foregroundNodeIds(List<String> v) { this.foregroundNodeIds = v; }
}
