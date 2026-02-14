package kr.dogfoot.hwpxlib.tool.idmlconverter.analyzer;

import java.util.ArrayList;
import java.util.List;

/**
 * IDML 문서 구조 분석 결과.
 */
public class IDMLStructure {
    private List<SpreadInfo> spreads;
    private List<MasterSpreadInfo> masterSpreads;
    private int totalTextFrames;
    private int totalImageFrames;
    private int totalVectorShapes;
    private int totalTables;

    public IDMLStructure() {
        this.spreads = new ArrayList<>();
        this.masterSpreads = new ArrayList<>();
    }

    public List<SpreadInfo> getSpreads() { return spreads; }
    public void addSpread(SpreadInfo spread) { spreads.add(spread); }

    public List<MasterSpreadInfo> getMasterSpreads() { return masterSpreads; }
    public void addMasterSpread(MasterSpreadInfo ms) { masterSpreads.add(ms); }

    public int getTotalTextFrames() { return totalTextFrames; }
    public void setTotalTextFrames(int v) { this.totalTextFrames = v; }

    public int getTotalImageFrames() { return totalImageFrames; }
    public void setTotalImageFrames(int v) { this.totalImageFrames = v; }

    public int getTotalVectorShapes() { return totalVectorShapes; }
    public void setTotalVectorShapes(int v) { this.totalVectorShapes = v; }

    public int getTotalTables() { return totalTables; }
    public void setTotalTables(int v) { this.totalTables = v; }

    /**
     * 스프레드 정보.
     */
    public static class SpreadInfo {
        private String id;
        private int pageCount;
        private List<PageInfo> pages;
        private int textFrameCount;
        private int imageFrameCount;
        private int vectorCount;
        private String masterSpreadName; // 적용된 마스터 스프레드 이름

        // 스프레드 레이아웃 상세 정보
        private double boundsTop;
        private double boundsLeft;
        private double boundsBottom;
        private double boundsRight;
        private double totalWidth;
        private double totalHeight;

        public SpreadInfo() {
            this.pages = new ArrayList<>();
        }

        public String getId() { return id; }
        public void setId(String v) { this.id = v; }

        public int getPageCount() { return pageCount; }
        public void setPageCount(int v) { this.pageCount = v; }

        public List<PageInfo> getPages() { return pages; }
        public void addPage(PageInfo page) { pages.add(page); }

        public int getTextFrameCount() { return textFrameCount; }
        public void setTextFrameCount(int v) { this.textFrameCount = v; }

        public int getImageFrameCount() { return imageFrameCount; }
        public void setImageFrameCount(int v) { this.imageFrameCount = v; }

        public int getVectorCount() { return vectorCount; }
        public void setVectorCount(int v) { this.vectorCount = v; }

        public double getBoundsTop() { return boundsTop; }
        public void setBoundsTop(double v) { this.boundsTop = v; }

        public double getBoundsLeft() { return boundsLeft; }
        public void setBoundsLeft(double v) { this.boundsLeft = v; }

        public double getBoundsBottom() { return boundsBottom; }
        public void setBoundsBottom(double v) { this.boundsBottom = v; }

        public double getBoundsRight() { return boundsRight; }
        public void setBoundsRight(double v) { this.boundsRight = v; }

        public double getTotalWidth() { return totalWidth; }
        public void setTotalWidth(double v) { this.totalWidth = v; }

        public double getTotalHeight() { return totalHeight; }
        public void setTotalHeight(double v) { this.totalHeight = v; }

        public String getMasterSpreadName() { return masterSpreadName; }
        public void setMasterSpreadName(String v) { this.masterSpreadName = v; }
    }

    /**
     * 마스터 스프레드 정보.
     */
    public static class MasterSpreadInfo {
        private String id;
        private String name;  // 마스터 이름 (예: "A-마스터")
        private int pageCount;
        private int textFrameCount;
        private int imageFrameCount;
        private int vectorCount;
        private int groupCount;
        private List<String> appliedPages;  // 이 마스터를 사용하는 일반 페이지 번호

        // 마스터 페이지 레이아웃 정보 (첫 번째 페이지 기준)
        private double pageWidth;
        private double pageHeight;
        private double marginTop;
        private double marginBottom;
        private double marginLeft;
        private double marginRight;
        private int columnCount;
        private double columnGutter;

        public MasterSpreadInfo() {
            this.appliedPages = new ArrayList<>();
        }

        public String getId() { return id; }
        public void setId(String v) { this.id = v; }

        public String getName() { return name; }
        public void setName(String v) { this.name = v; }

        public int getPageCount() { return pageCount; }
        public void setPageCount(int v) { this.pageCount = v; }

        public int getTextFrameCount() { return textFrameCount; }
        public void setTextFrameCount(int v) { this.textFrameCount = v; }

        public int getImageFrameCount() { return imageFrameCount; }
        public void setImageFrameCount(int v) { this.imageFrameCount = v; }

        public int getVectorCount() { return vectorCount; }
        public void setVectorCount(int v) { this.vectorCount = v; }

        public int getGroupCount() { return groupCount; }
        public void setGroupCount(int v) { this.groupCount = v; }

        public List<String> getAppliedPages() { return appliedPages; }
        public void addAppliedPage(String pageNum) { appliedPages.add(pageNum); }

        public double getPageWidth() { return pageWidth; }
        public void setPageWidth(double v) { this.pageWidth = v; }

        public double getPageHeight() { return pageHeight; }
        public void setPageHeight(double v) { this.pageHeight = v; }

        public double getMarginTop() { return marginTop; }
        public void setMarginTop(double v) { this.marginTop = v; }

        public double getMarginBottom() { return marginBottom; }
        public void setMarginBottom(double v) { this.marginBottom = v; }

        public double getMarginLeft() { return marginLeft; }
        public void setMarginLeft(double v) { this.marginLeft = v; }

        public double getMarginRight() { return marginRight; }
        public void setMarginRight(double v) { this.marginRight = v; }

        public int getColumnCount() { return columnCount; }
        public void setColumnCount(int v) { this.columnCount = v; }

        public double getColumnGutter() { return columnGutter; }
        public void setColumnGutter(double v) { this.columnGutter = v; }
    }

    /**
     * 페이지 정보.
     */
    public static class PageInfo {
        private String id;
        private String name;
        private int pageNumber;
        private double width;
        private double height;
        private List<FrameInfo> frames;

        // 페이지 레이아웃 상세 정보
        private double[] geometricBounds;  // [top, left, bottom, right]
        private double[] itemTransform;    // 6요소 변환 행렬
        private double marginTop;
        private double marginBottom;
        private double marginLeft;
        private double marginRight;
        private int columnCount;
        private String masterSpread;

        public PageInfo() {
            this.frames = new ArrayList<>();
        }

        public String getId() { return id; }
        public void setId(String v) { this.id = v; }

        public String getName() { return name; }
        public void setName(String v) { this.name = v; }

        public int getPageNumber() { return pageNumber; }
        public void setPageNumber(int v) { this.pageNumber = v; }

        public double getWidth() { return width; }
        public void setWidth(double v) { this.width = v; }

        public double getHeight() { return height; }
        public void setHeight(double v) { this.height = v; }

        public List<FrameInfo> getFrames() { return frames; }
        public void addFrame(FrameInfo frame) { frames.add(frame); }

        public double[] getGeometricBounds() { return geometricBounds; }
        public void setGeometricBounds(double[] v) { this.geometricBounds = v; }

        public double[] getItemTransform() { return itemTransform; }
        public void setItemTransform(double[] v) { this.itemTransform = v; }

        public double getMarginTop() { return marginTop; }
        public void setMarginTop(double v) { this.marginTop = v; }

        public double getMarginBottom() { return marginBottom; }
        public void setMarginBottom(double v) { this.marginBottom = v; }

        public double getMarginLeft() { return marginLeft; }
        public void setMarginLeft(double v) { this.marginLeft = v; }

        public double getMarginRight() { return marginRight; }
        public void setMarginRight(double v) { this.marginRight = v; }

        public int getColumnCount() { return columnCount; }
        public void setColumnCount(int v) { this.columnCount = v; }

        public String getMasterSpread() { return masterSpread; }
        public void setMasterSpread(String v) { this.masterSpread = v; }
    }

    /**
     * 프레임 정보.
     */
    public static class FrameInfo {
        private String id;
        private String type;  // "text", "image", "vector", "table"
        private String label;
        private double x;
        private double y;
        private double width;
        private double height;
        private String linkPath;      // 이미지 링크 경로 (이미지 타입만)
        private boolean needsPreview; // PSD, AI, EPS 파일 여부
        private List<FrameInfo> children; // 인라인 자식 프레임 (텍스트 타입만)
        private StoryContentInfo storyContent; // Story 내용 요약 (텍스트 타입만)

        public String getId() { return id; }
        public void setId(String v) { this.id = v; }

        public String getType() { return type; }
        public void setType(String v) { this.type = v; }

        public String getLabel() { return label; }
        public void setLabel(String v) { this.label = v; }

        public double getX() { return x; }
        public void setX(double v) { this.x = v; }

        public double getY() { return y; }
        public void setY(double v) { this.y = v; }

        public double getWidth() { return width; }
        public void setWidth(double v) { this.width = v; }

        public double getHeight() { return height; }
        public void setHeight(double v) { this.height = v; }

        public String getLinkPath() { return linkPath; }
        public void setLinkPath(String v) { this.linkPath = v; }

        public boolean isNeedsPreview() { return needsPreview; }
        public void setNeedsPreview(boolean v) { this.needsPreview = v; }

        public List<FrameInfo> getChildren() { return children; }
        public void addChild(FrameInfo child) {
            if (this.children == null) this.children = new ArrayList<>();
            this.children.add(child);
        }
        public boolean hasChildren() { return children != null && !children.isEmpty(); }

        public StoryContentInfo getStoryContent() { return storyContent; }
        public void setStoryContent(StoryContentInfo v) { this.storyContent = v; }
    }

    /**
     * Story 내용 요약 정보 (텍스트 프레임용).
     */
    public static class StoryContentInfo {
        private String storyId;
        private int paragraphCount;
        private List<ParagraphSummary> paragraphs;

        public StoryContentInfo() {
            this.paragraphs = new ArrayList<>();
        }

        public String getStoryId() { return storyId; }
        public void setStoryId(String v) { this.storyId = v; }

        public int getParagraphCount() { return paragraphCount; }
        public void setParagraphCount(int v) { this.paragraphCount = v; }

        public List<ParagraphSummary> getParagraphs() { return paragraphs; }
        public void addParagraph(ParagraphSummary p) { this.paragraphs.add(p); }
    }

    /**
     * 단락 요약 정보.
     */
    public static class ParagraphSummary {
        private String styleName;
        private String text;
        private List<RunSummary> runs;

        public ParagraphSummary() {
            this.runs = new ArrayList<>();
        }

        public String getStyleName() { return styleName; }
        public void setStyleName(String v) { this.styleName = v; }

        public String getText() { return text; }
        public void setText(String v) { this.text = v; }

        public List<RunSummary> getRuns() { return runs; }
        public void addRun(RunSummary r) { this.runs.add(r); }
    }

    /**
     * 런(CharacterRun) 요약 정보.
     */
    public static class RunSummary {
        private String type;  // "text", "inline_frame", "inline_graphic"
        private String text;
        private String fontStyle;
        private Double fontSize;
        private String frameId;       // inline_frame일 때
        private String graphicType;   // inline_graphic일 때
        private double width;
        private double height;

        public String getType() { return type; }
        public void setType(String v) { this.type = v; }

        public String getText() { return text; }
        public void setText(String v) { this.text = v; }

        public String getFontStyle() { return fontStyle; }
        public void setFontStyle(String v) { this.fontStyle = v; }

        public Double getFontSize() { return fontSize; }
        public void setFontSize(Double v) { this.fontSize = v; }

        public String getFrameId() { return frameId; }
        public void setFrameId(String v) { this.frameId = v; }

        public String getGraphicType() { return graphicType; }
        public void setGraphicType(String v) { this.graphicType = v; }

        public double getWidth() { return width; }
        public void setWidth(double v) { this.width = v; }

        public double getHeight() { return height; }
        public void setHeight(double v) { this.height = v; }
    }
}
