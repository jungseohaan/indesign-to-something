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
    private boolean flipHorizontal;
    private boolean flipVertical;

    // 이미지 데이터
    private String imageFormat;
    private byte[] imageData;
    private String imagePath;
    private int pixelWidth;
    private int pixelHeight;

    // 페이지 크롭 비율 (0.0~1.0, 이미지가 페이지를 넘는 경우)
    private double cropLeftFraction;
    private double cropTopFraction;
    private double cropRightFraction;
    private double cropBottomFraction;

    // 텍스트 감싸기 (TextWrapPreference → HWPUNIT)
    private String textWrapMode;
    private String textWrapSide;
    private long textWrapTop;
    private long textWrapLeft;
    private long textWrapBottom;
    private long textWrapRight;

    // 번들 내 이미지 경로 (예: "images/fig_001.png")
    private String bundlePath;

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

    public boolean flipHorizontal() { return flipHorizontal; }
    public void flipHorizontal(boolean v) { this.flipHorizontal = v; }

    public boolean flipVertical() { return flipVertical; }
    public void flipVertical(boolean v) { this.flipVertical = v; }

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

    public String bundlePath() { return bundlePath; }
    public void bundlePath(String v) { this.bundlePath = v; }

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

    public boolean hasCrop() {
        return cropLeftFraction > 0 || cropTopFraction > 0
                || cropRightFraction > 0 || cropBottomFraction > 0;
    }
}
