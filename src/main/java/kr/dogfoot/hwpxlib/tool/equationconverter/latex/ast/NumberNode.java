package kr.dogfoot.hwpxlib.tool.equationconverter.latex.ast;

public class NumberNode extends AstNode {
    private final String value;

    public NumberNode(String value) {
        this.value = value;
    }

    public AstNodeType nodeType() {
        return AstNodeType.NUMBER;
    }

    public String value() {
        return value;
    }
}
