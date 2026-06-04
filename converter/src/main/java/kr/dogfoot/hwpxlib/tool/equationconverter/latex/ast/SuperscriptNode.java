package kr.dogfoot.hwpxlib.tool.equationconverter.latex.ast;

public class SuperscriptNode extends AstNode {
    private final AstNode base;
    private final AstNode exponent;

    public SuperscriptNode(AstNode base, AstNode exponent) {
        this.base = base;
        this.exponent = exponent;
    }

    public AstNodeType nodeType() {
        return AstNodeType.SUPERSCRIPT;
    }

    public AstNode base() {
        return base;
    }

    public AstNode exponent() {
        return exponent;
    }
}
