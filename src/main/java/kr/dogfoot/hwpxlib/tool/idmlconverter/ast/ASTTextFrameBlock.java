package kr.dogfoot.hwpxlib.tool.idmlconverter.ast;

import java.util.ArrayList;
import java.util.List;

/**
 * 텍스트 프레임 블록 — 하나의 IDML TextFrame에서 유래한 단락 그룹.
 * HWPX에서 Rectangle + DrawText로 변환됨.
 * 좌표 단위: HWPUNIT (페이지 상대).
 */
public class ASTTextFrameBlock extends ASTBlock {
    private long x;
    private long y;
    private long width;
    private long height;
    private int columnCount;
    private long columnGutter;
    private int zOrder;
    private boolean verticalText;
    private String verticalJustification;

    // 텍스트 프레임 내부 여백
    private long insetTop;
    private long insetLeft;
    private long insetBottom;
    private long insetRight;

    // 프레임 스타일
    private String fillColor;
    private String strokeColor;
    private double strokeWeight;
    private String strokeType = "Solid"; // Solid, Dashed, Dotted
    private double fillTint = 100;       // 0~100
    private double strokeTint = 100;     // 0~100
    private double cornerRadius;
    private boolean fromGroup;

    private List<ASTParagraph> paragraphs;

    public ASTTextFrameBlock() {
        this.paragraphs = new ArrayList<>();
    }

    public BlockType blockType() { return BlockType.TEXT_FRAME_BLOCK; }

    public long x() { return x; }
    public void x(long v) { this.x = v; }

    public long y() { return y; }
    public void y(long v) { this.y = v; }

    public long width() { return width; }
    public void width(long v) { this.width = v; }

    public long height() { return height; }
    public void height(long v) { this.height = v; }

    public int columnCount() { return columnCount; }
    public void columnCount(int v) { this.columnCount = v; }

    public long columnGutter() { return columnGutter; }
    public void columnGutter(long v) { this.columnGutter = v; }

    public int zOrder() { return zOrder; }
    public void zOrder(int v) { this.zOrder = v; }

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

    public List<ASTParagraph> paragraphs() { return paragraphs; }
    public void addParagraph(ASTParagraph p) { paragraphs.add(p); }
}
