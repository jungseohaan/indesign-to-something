package kr.dogfoot.hwpxlib.tool.equationconverter.latex.ast;

public class FractionNode extends AstNode {
    private final AstNode numerator;
    private final AstNode denominator;
    private final String variant;

    public FractionNode(AstNode numerator, AstNode denominator, String variant) {
        this.numerator = numerator;
        this.denominator = denominator;
        this.variant = variant;
    }

    public AstNodeType nodeType() {
        return AstNodeType.FRACTION;
    }

    public AstNode numerator() {
        return numerator;
    }

    public AstNode denominator() {
        return denominator;
    }

    public String variant() {
        return variant;
    }
}
