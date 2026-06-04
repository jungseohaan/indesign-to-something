package kr.dogfoot.hwpxlib.tool.idmlconverter.ast;

/**
 * 블록 레벨 노드의 추상 기반.
 * 구현: ASTTextFrameBlock, ASTTable, ASTFigure
 */
public abstract class ASTBlock {
    public enum BlockType { TEXT_FRAME_BLOCK, TABLE, FIGURE }

    private String sourceId;
    private DebugMeta debug;

    public String sourceId() { return sourceId; }
    public void sourceId(String v) { this.sourceId = v; }

    /** SPEC-015: 디버그 메타. {@code --debug-ast} 비활성화 시 항상 null. */
    public DebugMeta debug() { return debug; }
    public void debug(DebugMeta v) { this.debug = v; }

    /** 디버그 메타가 없으면 만들고, 있으면 기존 것을 반환. lazy. */
    public DebugMeta debugOrNew() {
        if (debug == null) debug = new DebugMeta();
        return debug;
    }

    public abstract BlockType blockType();
}
