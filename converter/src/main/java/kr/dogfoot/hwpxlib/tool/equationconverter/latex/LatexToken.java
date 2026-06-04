package kr.dogfoot.hwpxlib.tool.equationconverter.latex;

public class LatexToken {
    private final LatexTokenType type;
    private final String value;
    private final int position;

    public LatexToken(LatexTokenType type, String value, int position) {
        this.type = type;
        this.value = value;
        this.position = position;
    }

    public LatexTokenType type() {
        return type;
    }

    public String value() {
        return value;
    }

    public int position() {
        return position;
    }
}
