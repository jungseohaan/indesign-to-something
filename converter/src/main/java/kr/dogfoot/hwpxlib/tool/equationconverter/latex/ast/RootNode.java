package kr.dogfoot.hwpxlib.tool.equationconverter.latex.ast;

public class RootNode extends AstNode {
    private final AstNode radicand;
    private final AstNode index;

    public RootNode(AstNode radicand, AstNode index) {
        this.radicand = radicand;
        this.index = index;
    }

    public AstNodeType nodeType() {
        return AstNodeType.ROOT;
    }

    public AstNode radicand() {
        return radicand;
    }

    public AstNode index() {
        return index;
    }
}
