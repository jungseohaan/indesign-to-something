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
        if (isPlainUnitOrNumber(trimmed)
                && !(hasEncodedMathEvidence && isSingleAsciiLetter(trimmed))) {
            return false;
        }
        if (trimmed.matches("[:;]?\\d+[:;]?")) return false;
        if (trimmed.matches("\\d*[A-Za-z]+\\d*")
                && !containsEquationSyntax(trimmed)
                && !(hasEncodedMathEvidence && isSingleAsciiLetter(trimmed))) {
            return false;
        }
        // =/연산자로 시작·끝나는 조각은 보통 수식이 아니지만, 위첨자(^{)·분수(over)·
        // 루트(sqrt) 같은 구조적 수식 마크업이 있으면 앞 항의 연속(예: (a+4)²=a²+8a+16)
        // 이므로 수식으로 인정한다(실측: 2단원 =a^{2}+8a+16 이 평문 a² 로 폴백되던 문제).
        if (!hasStructuralMathMarkup(trimmed)
                && (trimmed.matches("^[=+*/<>].*") || trimmed.matches(".*[=+*/<>-]$"))) {
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

    /**
     * 구조적 수식 마크업(위첨자·아래첨자·분수·루트·선분) 포함 여부.
     * 단순 연산자(=,+,-)만으로는 true가 아니며, HWP 수식 스크립트의 구조 토큰이
     * 있을 때만 true. =/연산자 시작·끝 조각을 수식으로 살릴지 판단하는 데 쓴다.
     */
    private static boolean hasStructuralMathMarkup(String script) {
        if (script == null) return false;
        if (script.indexOf("^{") >= 0 || script.indexOf("_{") >= 0) return true;
        String lower = script.toLowerCase(Locale.ROOT);
        return lower.contains(" over ") || lower.contains("sqrt")
                || lower.contains("overline") || lower.contains("root")
                // 곱셈·나눗셈 키워드도 수식 연속의 증거다 — 다행 수식의 마지막 행
                // "=2 TIMES 3" 이 평문으로 폴백되면 키워드가 그대로 노출된다
                // (실측: p32 =2×3 이 "2 TIMES 3" 텍스트로).
                || script.contains(" TIMES ") || script.contains(" div ");
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

    /**
     * SPEC-055: 화학식 스크립트의 첨자를 명시 토큰으로 재추론한다.
     *
     * <p>수식 그룹이 첨자 위치 정보 없이 평문화된 조각("N2+H2 rarrow NH3")을
     * 만들 수 있다. 텍스트런 강등 경로는 출력 시 같은 추론(원소 뒤 숫자 = 아래첨자)
     * 으로 첨자를 복원했는데, hp:equation 방출에서는 스크립트 자체에 {@code _{n}}
     * 이 있어야 한다. 규칙:
     * <ul>
     *   <li>원소기호([A-Z][a-z]?) 또는 닫는 괄호 ')' 뒤의 숫자열 → {@code _{n}}</li>
     *   <li>그 외 위치(선두, '+', 화살표, 공백 뒤)의 숫자열 = 계수 → 그대로</li>
     *   <li>기존 {@code _}/{@code ^} 명시 토큰과 {@code rarrow} 키워드는 보존</li>
     * </ul>
     */
    public static String inferChemicalSubscriptScript(String script) {
        if (script == null || script.isEmpty()) return script;
        String src = normalizeChemicalTextScript(script);
        StringBuilder out = new StringBuilder(src.length() + 8);
        boolean lastElement = false;
        for (int i = 0; i < src.length(); ) {
            char c = src.charAt(i);
            if (src.regionMatches(true, i, "rarrow", 0, 6)) {
                out.append(src, i, i + 6);
                i += 6;
                lastElement = false;
                continue;
            }
            if (c == '_' || c == '^') {
                // 명시 토큰: normalizeChemicalTextScript 가 이미 {..} 로 감쌌다.
                out.append(c);
                i++;
                if (i < src.length() && src.charAt(i) == '{') {
                    int close = src.indexOf('}', i);
                    if (close < 0) close = src.length() - 1;
                    out.append(src, i, close + 1);
                    i = close + 1;
                }
                lastElement = false;
                continue;
            }
            if (isUpperAscii(c)) {
                out.append(c);
                i++;
                if (i < src.length() && isLowerAscii(src.charAt(i))) {
                    out.append(src.charAt(i));
                    i++;
                }
                lastElement = true;
                continue;
            }
            if (Character.isDigit(c)) {
                int start = i;
                while (i < src.length() && Character.isDigit(src.charAt(i))) i++;
                if (lastElement) {
                    out.append("_{").append(src, start, i).append('}');
                } else {
                    out.append(src, start, i);
                }
                lastElement = false;
                continue;
            }
            out.append(c);
            lastElement = (c == ')');
            i++;
        }
        return out.toString();
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
                .replaceAll("(?i)\\brarrow\\b", "→")
                // 텍스트 폴백 시 수식 키워드가 그대로 노출되지 않게 기호로 치환
                .replaceAll("\\s*TIMES\\s*", "×")
                .replaceAll("(?<=[\\w})])\\s*div\\s*(?=[\\w{(])", "÷");
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

    private static boolean isSingleAsciiLetter(String text) {
        return text != null && text.length() == 1 && isAsciiLetter(text.charAt(0));
    }

    private static boolean isUpperAscii(char c) {
        return c >= 'A' && c <= 'Z';
    }

    private static boolean isLowerAscii(char c) {
        return c >= 'a' && c <= 'z';
    }
}
