package kr.dogfoot.hwpxlib.tool.idmlconverter.ast;

/**
 * 블록 레벨 노드의 추상 기반.
 * 구현: ASTTextFrameBlock, ASTTable, ASTFigure
 */
public abstract class ASTBlock {
    public enum BlockType { TEXT_FRAME_BLOCK, TABLE, FIGURE }

    private String sourceId;

    public String sourceId() { return sourceId; }
    public void sourceId(String v) { this.sourceId = v; }

    public abstract BlockType blockType();
}
