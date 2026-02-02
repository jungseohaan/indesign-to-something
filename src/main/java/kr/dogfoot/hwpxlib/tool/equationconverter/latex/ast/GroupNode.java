package kr.dogfoot.hwpxlib.tool.equationconverter.latex.ast;

public class GroupNode extends AstNode {
    private final AstNode child;

    public GroupNode(AstNode child) {
        this.child = child;
    }

    public AstNodeType nodeType() {
        return AstNodeType.GROUP;
    }

    public AstNode child() {
        return child;
    }
}
