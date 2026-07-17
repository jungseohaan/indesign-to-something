package kr.dogfoot.hwpxlib.tool.idmlconverter.formula;

import kr.dogfoot.hwpxlib.tool.equationconverter.EquationBuilder;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTEquation;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTextRun;

import java.util.ArrayList;
import java.util.List;

/**
 * Converts classified formula scripts into editable HWPX text runs.
 */
public final class FormulaRenderer {
    private FormulaRenderer() {
    }

    public static List<ASTTextRun> toChemicalTextRuns(ASTEquation eq, FormulaStyle style, boolean initialLastElement) {
        if (eq == null || eq.hwpScript() == null || eq.hwpScript().trim().isEmpty()) {
            return null;
        }
        String script = FormulaClassifier.normalizeChemicalTextScript(
                EquationBuilder.sanitizeHwpScript(eq.hwpScript()));
        List<ASTTextRun> runs = new ArrayList<ASTTextRun>();
        boolean emittedFormulaToken = false;
        boolean lastElement = initialLastElement;
        for (int i = 0; i < script.length(); ) {
            char c = script.charAt(i);
            if (Character.isWhitespace(c)) {
                addTextRun(runs, style, String.valueOf(c), false, false);
                lastElement = false;
                i++;
                continue;
            }
            if (script.regionMatches(true, i, "rarrow", 0, "rarrow".length())) {
                addTextRun(runs, style, "\u2192", false, false);
                lastElement = false;
                i += "rarrow".length();
                continue;
            }
            if (isUpperAscii(c)) {
                int start = i++;
                if (i < script.length() && isLowerAscii(script.charAt(i))) {
                    i++;
                }
                addTextRun(runs, style, script.substring(start, i), false, false);
                emittedFormulaToken = true;
                lastElement = true;
                continue;
            }
            if (Character.isDigit(c)) {
                int start = i++;
                while (i < script.length() && Character.isDigit(script.charAt(i))) {
                    i++;
                }
                addTextRun(runs, style, script.substring(start, i), lastElement, false);
                emittedFormulaToken = true;
                lastElement = false;
                continue;
            }
            if (c == '_' || c == '^') {
                boolean sub = c == '_';
                ScriptToken token = readScriptToken(script, i + 1);
                if (token != null && token.text.length() > 0) {
                    addTextRun(runs, style, token.text, sub, !sub);
                    emittedFormulaToken = true;
                    lastElement = false;
                    i = token.nextIndex;
                    continue;
                }
                i++;
                continue;
            }
            if (c == '{' || c == '}') {
                i++;
                continue;
            }
            addTextRun(runs, style, String.valueOf(c), false, false);
            lastElement = false;
            i++;
        }
        return emittedFormulaToken ? runs : null;
    }

    private static ScriptToken readScriptToken(String script, int index) {
        if (script == null || index >= script.length()) return null;
        while (index < script.length() && Character.isWhitespace(script.charAt(index))) {
            index++;
        }
        if (index >= script.length()) return null;
        if (script.charAt(index) == '{') {
            int close = script.indexOf('}', index + 1);
            if (close > index) {
                return new ScriptToken(script.substring(index + 1, close), close + 1);
            }
            return null;
        }
        return new ScriptToken(String.valueOf(script.charAt(index)), index + 1);
    }

    private static void addTextRun(
            List<ASTTextRun> runs,
            FormulaStyle style,
            String text,
            boolean subscript,
            boolean superscript) {
        if (text == null || text.isEmpty()) return;
        ASTTextRun run = new ASTTextRun();
        run.text(text);
        if (style != null) {
            run.fontFamily(style.fontFamily());
            run.fontSizeHwpunits(style.baseUnit());
            run.textColor(style.textColor());
        }
        run.subscript(subscript);
        run.superscript(superscript);
        runs.add(run);
    }

    private static boolean isUpperAscii(char c) {
        return c >= 'A' && c <= 'Z';
    }

    private static boolean isLowerAscii(char c) {
        return c >= 'a' && c <= 'z';
    }

    private static final class ScriptToken {
        final String text;
        final int nextIndex;

        ScriptToken(String text, int nextIndex) {
            this.text = text;
            this.nextIndex = nextIndex;
        }
    }
}
