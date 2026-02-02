package kr.dogfoot.hwpxlib.tool.equationconverter.latex.ast;

public class SubSuperNode extends AstNode {
    private final AstNode base;
    private final AstNode subscript;
    private final AstNode superscript;

    public SubSuperNode(AstNode base, AstNode subscript, AstNode superscript) {
        this.base = base;
        this.subscript = subscript;
        this.superscript = superscript;
    }

    public AstNodeType nodeType() {
        return AstNodeType.SUB_SUPER;
    }

    public AstNode base() {
        return base;
    }

    public AstNode subscript() {
        return subscript;
    }

    public AstNode superscript() {
        return superscript;
    }
}
