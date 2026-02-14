package kr.dogfoot.hwpxlib.tool.idmlconverter.ast;

/**
 * 인라인 항목의 추상 기반.
 * 구현: ASTTextRun, ASTInlineObject, ASTBreak
 */
public abstract class ASTInlineItem {
    public enum ItemType { TEXT_RUN, INLINE_OBJECT, BREAK }

    public abstract ItemType itemType();
}
