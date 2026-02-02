package kr.dogfoot.hwpxlib.tool.equationconverter.latex.ast;

public class BigOperatorNode extends AstNode {
    private final String operator;
    private final AstNode lower;
    private final AstNode upper;

    public BigOperatorNode(String operator, AstNode lower, AstNode upper) {
        this.operator = operator;
        this.lower = lower;
        this.upper = upper;
    }

    public AstNodeType nodeType() {
        return AstNodeType.BIG_OPERATOR;
    }

    public String operator() {
        return operator;
    }

    public AstNode lower() {
        return lower;
    }

    public AstNode upper() {
        return upper;
    }
}
