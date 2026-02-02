package kr.dogfoot.hwpxlib.tool.equationconverter.latex.ast;

public class SubscriptNode extends AstNode {
    private final AstNode base;
    private final AstNode subscript;

    public SubscriptNode(AstNode base, AstNode subscript) {
        this.base = base;
        this.subscript = subscript;
    }

    public AstNodeType nodeType() {
        return AstNodeType.SUBSCRIPT;
    }

    public AstNode base() {
        return base;
    }

    public AstNode subscript() {
        return subscript;
    }
}
