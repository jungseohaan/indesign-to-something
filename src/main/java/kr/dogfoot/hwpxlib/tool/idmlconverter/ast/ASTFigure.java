package kr.dogfoot.hwpxlib.tool.idmlconverter.ast;

/**
 * 블록 레벨 이미지/렌더링된 도형.
 * 절대 좌표로 페이지에 배치됨.
 * 좌표 단위: HWPUNIT.
 */
public class ASTFigure extends ASTBlock {
    public enum FigureKind { IMAGE, RENDERED_SHAPE, RENDERED_GROUP }

    private FigureKind kind;
    private long x;
    private long y;
    private long width;
    private long height;
    private int zOrder;
    private double rotationAngle;

    // 이미지 데이터
    private String imageFormat;
    private byte[] imageData;
    private String imagePath;
    private int pixelWidth;
    private int pixelHeight;

    public BlockType blockType() { return BlockType.FIGURE; }

    public FigureKind kind() { return kind; }
    public void kind(FigureKind v) { this.kind = v; }

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

    public double rotationAngle() { return rotationAngle; }
    public void rotationAngle(double v) { this.rotationAngle = v; }

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
}
