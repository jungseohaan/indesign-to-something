package kr.dogfoot.hwpxlib.tool.equationconverter.hwpscript;

public class HwpScriptBuilder {
    private final StringBuilder sb;

    public HwpScriptBuilder() {
        sb = new StringBuilder();
    }

    public void append(String text) {
        sb.append(text);
    }

    public void appendWithSpace(String text) {
        if (sb.length() > 0 && sb.charAt(sb.length() - 1) != ' '
                && sb.charAt(sb.length() - 1) != '{') {
            sb.append(' ');
        }
        sb.append(text);
    }

    public void space() {
        if (sb.length() > 0 && sb.charAt(sb.length() - 1) != ' ') {
            sb.append(' ');
        }
    }

    public void openBrace() {
        sb.append('{');
    }

    public void closeBrace() {
        sb.append('}');
    }

    public String result() {
        return sb.toString().trim();
    }

    public int length() {
        return sb.length();
    }
}
