package kr.dogfoot.hwpxlib.tool.equationconverter.latex.ast;

public class FunctionNode extends AstNode {
    private final String name;
    private final AstNode argument;

    public FunctionNode(String name, AstNode argument) {
        this.name = name;
        this.argument = argument;
    }

    public AstNodeType nodeType() {
        return AstNodeType.FUNCTION;
    }

    public String name() {
        return name;
    }

    public AstNode argument() {
        return argument;
    }
}
