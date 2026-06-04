package kr.dogfoot.hwpxlib.tool.equationconverter.latex.ast;

public class SymbolNode extends AstNode {
    private final String name;

    public SymbolNode(String name) {
        this.name = name;
    }

    public AstNodeType nodeType() {
        return AstNodeType.SYMBOL;
    }

    public String name() {
        return name;
    }
}
