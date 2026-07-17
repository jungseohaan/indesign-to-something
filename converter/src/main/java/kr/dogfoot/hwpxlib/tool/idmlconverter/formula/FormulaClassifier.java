package kr.dogfoot.hwpxlib.tool.idmlconverter.formula;

import java.util.Locale;

/**
 * Single classification surface for chemical/formula text.
 *
 * <p>This class decides only the semantic shape: text-only, inline chemical
 * text, or HWP equation materialization. Rendering is handled by
 * {@link FormulaRenderer}.
 */
public final class FormulaClassifier {
    private FormulaClassifier() {
    }

    public static boolean isBodyTextChemicalSource(String sourceType) {
        return "CHEM_FORMULA".equals(sourceType);
    }

    public static FormulaDecision classifyChemicalScript(String script) {
        String normalized = normalizeChemicalTextScript(script);
        if (normalized == null || normalized.trim().isEmpty()) {
            return FormulaDecision.of(FormulaDecision.Type.REJECTED, script, normalized, "empty");
        }
        if (containsEquationSyntax(normalized)) {
            return FormulaDecision.of(FormulaDecision.Type.REACTION_EQUATION, script, normalized, "equation-syntax");
        }
        if (isChemicalFormulaElementSequence(normalized)) {
            return FormulaDecision.of(FormulaDecision.Type.INLINE_CHEM_FORMULA, script, normalized, "chemical-token");
        }
        return FormulaDecision.of(FormulaDecision.Type.TEXT_ONLY, script, normalized, "plain-text");
    }

    public static boolean shouldEmitConvertedEquation(String script, boolean hasEncodedMathEvidence) {
        if (script == null) return false;
        String trimmed = script.trim();
        if (trimmed.isEmpty()) return false;
        if (containsKorean(trimmed)) return false;
        if (isPlainUnitOrNumber(trimmed)) return false;
        if (trimmed.matches("[:;]?\\d+[:;]?")) return false;
        if (trimmed.matches("\\d*[A-Za-z]+\\d*") && !containsEquationSyntax(trimmed)) {
            return false;
        }
        if (trimmed.matches("^[=+*/<>].*") || trimmed.matches(".*[=+*/<>-]$")) {
            return false;
        }
        if (containsEquationSyntax(trimmed)) return true;
        if (hasEncodedMathEvidence) return true;

        int letters = 0;
        for (int i = 0; i < trimmed.length(); i++) {
            if (isAsciiLetter(trimmed.charAt(i))) letters++;
        }
        return letters == 1 && trimmed.length() == 1;
    }

    public static boolean shouldMaterializeChemicalEquation(
            String hwpScript,
            boolean chemicalElementSequence,
            boolean hasFormulaFontEvidence,
            boolean hasEquation,
            boolean hasOperator,
            boolean hasBox,
            boolean hasArrow,
            boolean hasPositioned,
            boolean hasChemicalSymbol) {
        if (hwpScript == null || hwpScript.isEmpty()) return false;
        if (!hasChemicalSymbol && !hasBox && !hasArrow) return false;

        if (hasArrow || hasBox) return true;
        if (hasEquation && hasOperator && hasChemicalSymbol) return true;

        // Plain chemical formula text such as H2O/Cu2O remains editable text
        // with character-level subscript/superscript. It is not an equation.
        if (chemicalElementSequence && hasPositioned && !hasOperator) return false;
        if (chemicalElementSequence && !hasOperator) return false;

        return hasOperator && hasChemicalSymbol && (hasPositioned || hasFormulaFontEvidence);
    }

    public static boolean containsEquationSyntax(String script) {
        if (script == null) return false;
        String lower = script.toLowerCase(Locale.ROOT);
        if (lower.contains(" over ") || lower.contains("sqrt") || lower.contains("root")
                || lower.contains("rarrow") || lower.contains("overline")) {
            return true;
        }
        for (int i = 0; i < script.length(); i++) {
            char c = script.charAt(i);
            if ("+=<>≤≥±×÷√²³^_π∑∫∞{}[]()/".indexOf(c) >= 0) return true;
        }
        return false;
    }

    public static boolean isPlainUnitOrNumber(String script) {
        if (script == null) return true;
        String compact = script.trim().replace(" ", "");
        if (compact.isEmpty()) return true;
        if (compact.matches("[+-]?\\d+(?:\\.\\d+)?")) return true;
        if (compact.matches("[*°℃]?[A-Za-z]+")) return true;
        if (compact.matches("[+-]?\\d+(?:\\.\\d+)?[A-Za-z%°℃]+")) return true;
        return false;
    }

    public static boolean previousTextEndsWithFormulaElement(String text) {
        Character last = lastNonSpaceChar(text);
        if (last == null) return false;
        if (isLowerAscii(last)) {
            int idx = lastNonSpaceIndex(text);
            Character before = previousNonSpaceChar(text, idx - 1);
            if (before != null && isUpperAscii(before)) {
                return isChemicalElement("" + before + last);
            }
            return false;
        }
        return isUpperAscii(last) && isChemicalElement(String.valueOf(last));
    }

    public static String normalizeChemicalTextScript(String script) {
        if (script == null || script.isEmpty()) return script;
        StringBuilder out = new StringBuilder(script.length());
        for (int i = 0; i < script.length(); ) {
            char c = script.charAt(i);
            if ((c == '_' || c == '^') && i + 1 < script.length()) {
                out.append(c);
                int j = i + 1;
                while (j < script.length() && Character.isWhitespace(script.charAt(j))) {
                    j++;
                }
                if (j < script.length() && script.charAt(j) == '{') {
                    out.append('{');
                    j++;
                    while (j < script.length() && Character.isWhitespace(script.charAt(j))) {
                        j++;
                    }
                    int tokenStart = j;
                    while (j < script.length() && script.charAt(j) != '}') {
                        j++;
                    }
                    out.append(script, tokenStart, j);
                    out.append('}');
                    if (j < script.length() && script.charAt(j) == '}') j++;
                    i = j;
                    continue;
                }
                int tokenStart = j;
                while (j < script.length() && Character.isLetterOrDigit(script.charAt(j))) {
                    j++;
                }
                if (j > tokenStart) {
                    out.append('{').append(script, tokenStart, j).append('}');
                    if (j < script.length() && script.charAt(j) == '}') j++;
                    i = j;
                    continue;
                }
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    public static String hwpScriptFallbackText(String script) {
        if (script == null || script.isEmpty()) return "";
        String normalized = normalizeChemicalTextScript(script)
                .replaceAll("(?i)\\brarrow\\b", "→");
        StringBuilder out = new StringBuilder(normalized.length());
        for (int i = 0; i < normalized.length(); i++) {
            char c = normalized.charAt(i);
            if (c == '_' || c == '^' || c == '{' || c == '}') continue;
            out.append(c);
        }
        return out.toString();
    }

    public static boolean isChemicalFormulaElementSequence(String script) {
        String token = chemicalFormulaToken(script);
        if (token.isEmpty()) return false;
        int i = 0;
        int elements = 0;
        boolean hasDigit = false;
        boolean hasLeadingCoefficient = false;
        while (i < token.length() && Character.isDigit(token.charAt(i))) {
            hasLeadingCoefficient = true;
            hasDigit = true;
            i++;
        }
        while (i < token.length()) {
            char c = token.charAt(i);
            if (c < 'A' || c > 'Z') return false;
            String symbol = String.valueOf(c);
            if (i + 1 < token.length()) {
                char next = token.charAt(i + 1);
                if (next >= 'a' && next <= 'z') {
                    String two = "" + c + next;
                    if (isChemicalElement(two)) {
                        symbol = two;
                        i += 2;
                    } else if (isChemicalElement(symbol)) {
                        i++;
                    } else {
                        return false;
                    }
                } else {
                    if (!isChemicalElement(symbol)) return false;
                    i++;
                }
            } else {
                if (!isChemicalElement(symbol)) return false;
                i++;
            }
            elements++;
            while (i < token.length() && Character.isDigit(token.charAt(i))) {
                hasDigit = true;
                i++;
            }
        }
        if (elements == 0) return false;
        return hasDigit || hasLeadingCoefficient || elements >= 2;
    }

    public static boolean isChemicalElement(String symbol) {
        if (symbol == null) return false;
        switch (symbol) {
            case "H": case "He": case "Li": case "Be": case "B": case "C":
            case "N": case "O": case "F": case "Ne": case "Na": case "Mg":
            case "Al": case "Si": case "P": case "S": case "Cl": case "Ar":
            case "K": case "Ca": case "Fe": case "Cu": case "Zn": case "Ag":
            case "I": case "Ba": case "Pt": case "Au": case "Hg": case "Pb":
                return true;
            default:
                return false;
        }
    }

    private static String chemicalFormulaToken(String script) {
        if (script == null || script.isEmpty()) return "";
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < script.length(); i++) {
            char c = script.charAt(i);
            if (Character.isWhitespace(c) || c == '_' || c == '^' || c == '{' || c == '}'
                    || c == '\u2005' || c == '\u2007' || c == '\u2009' || c == '\u200A') {
                continue;
            }
            if (Character.isDigit(c) || isAsciiLetter(c)) {
                out.append(c);
                continue;
            }
            return "";
        }
        return out.toString();
    }

    private static boolean containsKorean(String text) {
        if (text == null) return false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if ((c >= 0xAC00 && c <= 0xD7AF) || (c >= 0x3131 && c <= 0x318E)) return true;
        }
        return false;
    }

    private static Character lastNonSpaceChar(String text) {
        int idx = lastNonSpaceIndex(text);
        return idx >= 0 ? text.charAt(idx) : null;
    }

    private static int lastNonSpaceIndex(String text) {
        if (text == null) return -1;
        for (int i = text.length() - 1; i >= 0; i--) {
            if (!Character.isWhitespace(text.charAt(i))) return i;
        }
        return -1;
    }

    private static Character previousNonSpaceChar(String text, int start) {
        if (text == null) return null;
        for (int i = Math.min(start, text.length() - 1); i >= 0; i--) {
            if (!Character.isWhitespace(text.charAt(i))) return text.charAt(i);
        }
        return null;
    }

    private static boolean isAsciiLetter(char c) {
        return isUpperAscii(c) || isLowerAscii(c);
    }

    private static boolean isUpperAscii(char c) {
        return c >= 'A' && c <= 'Z';
    }

    private static boolean isLowerAscii(char c) {
        return c >= 'a' && c <= 'z';
    }
}
