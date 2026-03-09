package kr.dogfoot.hwpxlib.tool.idmlconverter.flat;

import java.util.ArrayList;
import java.util.List;

/**
 * Layer 1: 레이아웃 구조 노드.
 * TEXT_FRAME, TABLE, FIGURE, SPACER 모든 컨테이너 타입을 통합 표현한다.
 * 절대/인라인/오버레이 배치 모드를 지원하며,
 * componentIds로 Layer 2를, pageId로 Layer 0을 참조한다.
 */
public class FlatLayoutNode {
    public enum NodeType { TEXT_FRAME, TABLE, FIGURE, SPACER }
    public enum PositioningMode { ABSOLUTE, INLINE, OVERLAY }
    public enum SemanticLayer { BACKGROUND, CONTENT, FOREGROUND }

    // --- Common fields ---
    private String nodeId;
    private NodeType nodeType;
    private PositioningMode positioning;
    private String pageId;
    private String sourceId;
    private int zOrder;
    private SemanticLayer semanticLayer;
    private int layerRelativeOrder;
    private long x;
    private long y;
    private long width;
    private long height;

    // --- INLINE positioning ---
    private String parentComponentId;
    private int insertionIndex = -1;

    // --- OVERLAY positioning ---
    private String overlayParentId;
    private long overlayOffsetX;
    private long overlayOffsetY;
    private boolean isOverlay; // standalone isOverlay flag (INLINE TEXT_FRAME with overlay behavior)

    // --- Component references ---
    private List<String> componentIds;

    // --- TEXT_FRAME fields ---
    private int columnCount;
    private long columnGutter;
    private long[] columnWidths;
    private boolean verticalText;
    private String verticalJustification;
    private long insetTop;
    private long insetLeft;
    private long insetBottom;
    private long insetRight;
    private String fillColor;
    private String strokeColor;
    private double strokeWeight;
    private String strokeType = "Solid";
    private double fillTint = 100;
    private double strokeTint = 100;
    private double cornerRadius;
    private boolean fromGroup;
    private String storyId;
    private boolean distributed;
    private double rotationAngle;
    private long narrowedWidth;
    private String wrapperFillColor;
    private double wrapperFillTint = -1;
    private boolean dropShadow;
    private long[] pathPointsX;
    private long[] pathPointsY;
    private long textMarginTop;
    private long textMarginLeft;
    private long textMarginBottom;
    private long textMarginRight;

    // --- FIGURE fields ---
    private String figureKind;
    private String imageFormat;
    private byte[] imageData;
    private String imagePath;
    private int pixelWidth;
    private int pixelHeight;
    private double cropLeftFraction;
    private double cropTopFraction;
    private double cropRightFraction;
    private double cropBottomFraction;
    private boolean flipHorizontal;
    private boolean flipVertical;
    private String bundlePath;
    private long containerWidth;
    private long containerHeight;
    private long imageOffsetX;
    private long imageOffsetY;

    // --- TABLE fields ---
    private int rowCount;
    private int colCount;
    private List<Long> tableColumnWidths;
    private String appliedTableStyle;
    private String borderColor;
    private long borderWidth;
    private List<FlatTableRow> tableRows;

    // --- Text wrap fields (shared) ---
    private String anchoredPosition;
    private String textWrapMode;
    private String textWrapSide;
    private long textWrapTop;
    private long textWrapLeft;
    private long textWrapBottom;
    private long textWrapRight;

    // --- Overlay centering ---
    private long overlayCenterDeltaX;
    private long overlayCenterDeltaY;
    private long overlayParentWidth;
    private long overlayParentHeight;

    // --- Resolved coordinates ---
    private long resolvedPageX = -1;
    private long resolvedPageY = -1;
    private long resolvedWidth = -1;
    private long resolvedHeight = -1;

    public FlatLayoutNode() {
        this.componentIds = new ArrayList<>();
    }

    // --- Helper methods ---

    public boolean hasNonRectPath() {
        return pathPointsX != null && pathPointsX.length > 4;
    }

    public boolean hasCrop() {
        return cropLeftFraction > 0 || cropTopFraction > 0
                || cropRightFraction > 0 || cropBottomFraction > 0;
    }

    public boolean hasWrapperFill() {
        return wrapperFillColor != null && wrapperFillColor.startsWith("#");
    }

    public long effectiveWidth() {
        return narrowedWidth > 0 ? narrowedWidth : width;
    }

    // --- Common accessors ---

    public String nodeId() { return nodeId; }
    public void nodeId(String v) { this.nodeId = v; }

    public NodeType nodeType() { return nodeType; }
    public void nodeType(NodeType v) { this.nodeType = v; }

    public PositioningMode positioning() { return positioning; }
    public void positioning(PositioningMode v) { this.positioning = v; }

    public String pageId() { return pageId; }
    public void pageId(String v) { this.pageId = v; }

    public String sourceId() { return sourceId; }
    public void sourceId(String v) { this.sourceId = v; }

    public int zOrder() { return zOrder; }
    public void zOrder(int v) { this.zOrder = v; }

    public SemanticLayer semanticLayer() { return semanticLayer; }
    public void semanticLayer(SemanticLayer v) { this.semanticLayer = v; }

    public int layerRelativeOrder() { return layerRelativeOrder; }
    public void layerRelativeOrder(int v) { this.layerRelativeOrder = v; }

    public long x() { return x; }
    public void x(long v) { this.x = v; }

    public long y() { return y; }
    public void y(long v) { this.y = v; }

    public long width() { return width; }
    public void width(long v) { this.width = v; }

    public long height() { return height; }
    public void height(long v) { this.height = v; }

    // --- INLINE positioning accessors ---

    public String parentComponentId() { return parentComponentId; }
    public void parentComponentId(String v) { this.parentComponentId = v; }

    public int insertionIndex() { return insertionIndex; }
    public void insertionIndex(int v) { this.insertionIndex = v; }

    // --- OVERLAY positioning accessors ---

    public String overlayParentId() { return overlayParentId; }
    public void overlayParentId(String v) { this.overlayParentId = v; }

    public long overlayOffsetX() { return overlayOffsetX; }
    public void overlayOffsetX(long v) { this.overlayOffsetX = v; }

    public long overlayOffsetY() { return overlayOffsetY; }
    public void overlayOffsetY(long v) { this.overlayOffsetY = v; }

    public boolean isOverlay() { return isOverlay; }
    public void isOverlay(boolean v) { this.isOverlay = v; }

    // --- Component references accessors ---

    public List<String> componentIds() { return componentIds; }
    public void componentIds(List<String> v) { this.componentIds = v; }
    public void addComponentId(String id) { componentIds.add(id); }

    // --- TEXT_FRAME accessors ---

    public int columnCount() { return columnCount; }
    public void columnCount(int v) { this.columnCount = v; }

    public long columnGutter() { return columnGutter; }
    public void columnGutter(long v) { this.columnGutter = v; }

    public long[] columnWidths() { return columnWidths; }
    public void columnWidths(long[] v) { this.columnWidths = v; }

    public boolean verticalText() { return verticalText; }
    public void verticalText(boolean v) { this.verticalText = v; }

    public String verticalJustification() { return verticalJustification; }
    public void verticalJustification(String v) { this.verticalJustification = v; }

    public long insetTop() { return insetTop; }
    public void insetTop(long v) { this.insetTop = v; }

    public long insetLeft() { return insetLeft; }
    public void insetLeft(long v) { this.insetLeft = v; }

    public long insetBottom() { return insetBottom; }
    public void insetBottom(long v) { this.insetBottom = v; }

    public long insetRight() { return insetRight; }
    public void insetRight(long v) { this.insetRight = v; }

    public String fillColor() { return fillColor; }
    public void fillColor(String v) { this.fillColor = v; }

    public String strokeColor() { return strokeColor; }
    public void strokeColor(String v) { this.strokeColor = v; }

    public double strokeWeight() { return strokeWeight; }
    public void strokeWeight(double v) { this.strokeWeight = v; }

    public String strokeType() { return strokeType; }
    public void strokeType(String v) { this.strokeType = v; }

    public double fillTint() { return fillTint; }
    public void fillTint(double v) { this.fillTint = v; }

    public double strokeTint() { return strokeTint; }
    public void strokeTint(double v) { this.strokeTint = v; }

    public double cornerRadius() { return cornerRadius; }
    public void cornerRadius(double v) { this.cornerRadius = v; }

    public boolean fromGroup() { return fromGroup; }
    public void fromGroup(boolean v) { this.fromGroup = v; }

    public String storyId() { return storyId; }
    public void storyId(String v) { this.storyId = v; }

    public boolean distributed() { return distributed; }
    public void distributed(boolean v) { this.distributed = v; }

    public double rotationAngle() { return rotationAngle; }
    public void rotationAngle(double v) { this.rotationAngle = v; }

    public long narrowedWidth() { return narrowedWidth; }
    public void narrowedWidth(long v) { this.narrowedWidth = v; }

    public String wrapperFillColor() { return wrapperFillColor; }
    public void wrapperFillColor(String v) { this.wrapperFillColor = v; }

    public double wrapperFillTint() { return wrapperFillTint; }
    public void wrapperFillTint(double v) { this.wrapperFillTint = v; }

    public boolean dropShadow() { return dropShadow; }
    public void dropShadow(boolean v) { this.dropShadow = v; }

    public long[] pathPointsX() { return pathPointsX; }
    public long[] pathPointsY() { return pathPointsY; }
    public void pathPoints(long[] px, long[] py) {
        this.pathPointsX = px;
        this.pathPointsY = py;
    }

    public long textMarginTop() { return textMarginTop; }
    public void textMarginTop(long v) { this.textMarginTop = v; }

    public long textMarginLeft() { return textMarginLeft; }
    public void textMarginLeft(long v) { this.textMarginLeft = v; }

    public long textMarginBottom() { return textMarginBottom; }
    public void textMarginBottom(long v) { this.textMarginBottom = v; }

    public long textMarginRight() { return textMarginRight; }
    public void textMarginRight(long v) { this.textMarginRight = v; }

    // --- FIGURE accessors ---

    public String figureKind() { return figureKind; }
    public void figureKind(String v) { this.figureKind = v; }

    public String imageFormat() { return imageFormat; }
    public void imageFormat(String v) { this.imageFormat = v; }

    public byte[] imageData() { return imageData; }
    public void imageData(byte[] v) { this.imageData = v; }

    public String imagePath() { return imagePath; }
    public void imagePath(String v) { this.imagePath = v; }

    public int pixelWidth() { return pixelWidth; }
    public void pixelWidth(int v) { this.pixelWidth = v; }

    public int pixelHeight() { return pixelHeight; }
    public void pixelHeight(int v) { this.pixelHeight = v; }

    public double cropLeftFraction() { return cropLeftFraction; }
    public void cropLeftFraction(double v) { this.cropLeftFraction = v; }

    public double cropTopFraction() { return cropTopFraction; }
    public void cropTopFraction(double v) { this.cropTopFraction = v; }

    public double cropRightFraction() { return cropRightFraction; }
    public void cropRightFraction(double v) { this.cropRightFraction = v; }

    public double cropBottomFraction() { return cropBottomFraction; }
    public void cropBottomFraction(double v) { this.cropBottomFraction = v; }

    public boolean flipHorizontal() { return flipHorizontal; }
    public void flipHorizontal(boolean v) { this.flipHorizontal = v; }

    public boolean flipVertical() { return flipVertical; }
    public void flipVertical(boolean v) { this.flipVertical = v; }

    public String bundlePath() { return bundlePath; }
    public void bundlePath(String v) { this.bundlePath = v; }

    public long containerWidth() { return containerWidth; }
    public void containerWidth(long v) { this.containerWidth = v; }

    public long containerHeight() { return containerHeight; }
    public void containerHeight(long v) { this.containerHeight = v; }

    public long imageOffsetX() { return imageOffsetX; }
    public void imageOffsetX(long v) { this.imageOffsetX = v; }

    public long imageOffsetY() { return imageOffsetY; }
    public void imageOffsetY(long v) { this.imageOffsetY = v; }

    // --- TABLE accessors ---

    public int rowCount() { return rowCount; }
    public void rowCount(int v) { this.rowCount = v; }

    public int colCount() { return colCount; }
    public void colCount(int v) { this.colCount = v; }

    public List<Long> tableColumnWidths() { return tableColumnWidths; }
    public void tableColumnWidths(List<Long> v) { this.tableColumnWidths = v; }
    public void addTableColumnWidth(long w) {
        if (this.tableColumnWidths == null) {
            this.tableColumnWidths = new ArrayList<>();
        }
        this.tableColumnWidths.add(w);
    }

    public String appliedTableStyle() { return appliedTableStyle; }
    public void appliedTableStyle(String v) { this.appliedTableStyle = v; }

    public String borderColor() { return borderColor; }
    public void borderColor(String v) { this.borderColor = v; }

    public long borderWidth() { return borderWidth; }
    public void borderWidth(long v) { this.borderWidth = v; }

    public List<FlatTableRow> tableRows() { return tableRows; }
    public void tableRows(List<FlatTableRow> v) { this.tableRows = v; }
    public void addTableRow(FlatTableRow r) {
        if (this.tableRows == null) {
            this.tableRows = new ArrayList<>();
        }
        this.tableRows.add(r);
    }

    // --- Text wrap accessors ---

    public String anchoredPosition() { return anchoredPosition; }
    public void anchoredPosition(String v) { this.anchoredPosition = v; }

    public String textWrapMode() { return textWrapMode; }
    public void textWrapMode(String v) { this.textWrapMode = v; }

    public String textWrapSide() { return textWrapSide; }
    public void textWrapSide(String v) { this.textWrapSide = v; }

    public long textWrapTop() { return textWrapTop; }
    public void textWrapTop(long v) { this.textWrapTop = v; }

    public long textWrapLeft() { return textWrapLeft; }
    public void textWrapLeft(long v) { this.textWrapLeft = v; }

    public long textWrapBottom() { return textWrapBottom; }
    public void textWrapBottom(long v) { this.textWrapBottom = v; }

    public long textWrapRight() { return textWrapRight; }
    public void textWrapRight(long v) { this.textWrapRight = v; }

    // --- Overlay centering accessors ---

    public long overlayCenterDeltaX() { return overlayCenterDeltaX; }
    public void overlayCenterDeltaX(long v) { this.overlayCenterDeltaX = v; }

    public long overlayCenterDeltaY() { return overlayCenterDeltaY; }
    public void overlayCenterDeltaY(long v) { this.overlayCenterDeltaY = v; }

    public long overlayParentWidth() { return overlayParentWidth; }
    public void overlayParentWidth(long v) { this.overlayParentWidth = v; }

    public long overlayParentHeight() { return overlayParentHeight; }
    public void overlayParentHeight(long v) { this.overlayParentHeight = v; }

    // --- Resolved coordinates accessors ---

    public long resolvedPageX() { return resolvedPageX; }
    public void resolvedPageX(long v) { this.resolvedPageX = v; }

    public long resolvedPageY() { return resolvedPageY; }
    public void resolvedPageY(long v) { this.resolvedPageY = v; }

    public long resolvedWidth() { return resolvedWidth; }
    public void resolvedWidth(long v) { this.resolvedWidth = v; }

    public long resolvedHeight() { return resolvedHeight; }
    public void resolvedHeight(long v) { this.resolvedHeight = v; }
}
