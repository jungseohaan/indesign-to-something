package kr.dogfoot.hwpxlib.tool.idmlconverter.ast;

/**
 * 줄바꿈/컬럼바꿈/페이지바꿈.
 */
public class ASTBreak extends ASTInlineItem {
    public enum BreakType { LINE, COLUMN, PAGE }

    private BreakType breakType;

    public ASTBreak() {}

    public ASTBreak(BreakType breakType) {
        this.breakType = breakType;
    }

    public ItemType itemType() { return ItemType.BREAK; }

    public BreakType breakType() { return breakType; }
    public void breakType(BreakType v) { this.breakType = v; }
}
