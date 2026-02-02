package kr.dogfoot.hwpxlib.tool.equationconverter.latex.ast;

public class TextNode extends AstNode {
    private final String text;

    public TextNode(String text) {
        this.text = text;
    }

    public AstNodeType nodeType() {
        return AstNodeType.TEXT;
    }

    public String text() {
        return text;
    }
}
