package kr.dogfoot.hwpxlib.tool.equationconverter;

public class ConvertException extends Exception {
    private final String latex;
    private final int position;

    public ConvertException(String message, String latex, int position) {
        super(message + " at position " + position + " in: " + latex);
        this.latex = latex;
        this.position = position;
    }

    public ConvertException(String message) {
        super(message);
        this.latex = null;
        this.position = -1;
    }

    public String latex() {
        return latex;
    }

    public int position() {
        return position;
    }
}
