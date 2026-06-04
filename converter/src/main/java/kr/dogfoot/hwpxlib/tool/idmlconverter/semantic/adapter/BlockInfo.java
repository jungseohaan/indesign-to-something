package kr.dogfoot.hwpxlib.tool.idmlconverter.semantic.adapter;

/**
 * 블록 정보 (FeatureExtractor 입력).
 * TS {@code BlockInfo} 와 1:1.
 */
public class BlockInfo {
    /** TS의 BlockType: TEXT_FRAME / TABLE / FIGURE. */
    public enum BlockType { TEXT_FRAME, TABLE, FIGURE }

    public String id = "";
    public BlockType blockType = BlockType.TEXT_FRAME;
    public int pageNumber;
    public double x;
    public double y;
    public double width;
    public double height;
    public int zOrder;
    public double rotation;

    // 텍스트 프레임 전용
    public String storyId;
    public int columnCount;
    public String fillColor;
    public String strokeColor;
    public boolean hasFill;
    public boolean hasStroke;
    public boolean isBackgroundOnly;
    public String verticalJustification;
}
