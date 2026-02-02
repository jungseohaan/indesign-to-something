package kr.dogfoot.hwpxlib.tool.equationconverter.latex.ast;

public class DelimiterNode extends AstNode {
    private final String leftDelim;
    private final String rightDelim;
    private final AstNode content;

    public DelimiterNode(String leftDelim, String rightDelim, AstNode content) {
        this.leftDelim = leftDelim;
        this.rightDelim = rightDelim;
        this.content = content;
    }

    public AstNodeType nodeType() {
        return AstNodeType.DELIMITER;
    }

    public String leftDelim() {
        return leftDelim;
    }

    public String rightDelim() {
        return rightDelim;
    }

    public AstNode content() {
        return content;
    }
}
