package kr.dogfoot.hwpxlib.tool.equationconverter.idml;

import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLCharacterRun;

import java.util.List;

/**
 * NP 커스텀 폰트 체계의 런 그룹을 HWP 수식 스크립트로 변환한다.
 *
 * BT수식M과 달리, NP는 폰트 변형(variant)으로 수식 구조를 인코딩한다:
 * - NP_PE/NP_BE: 연산자/함수명 (log, sin, cos 등)
 * - NP_YP/NP_YB: 특수 연산자 ({, }, ⋯, ∞, ∴, π 등)
 * - NP_ISHS 등: 아래첨자
 * - NP_SUSP 등: 위첨자
 * - NP_RUT: 근호 (√)
 * - NP_BUN: 분수
 * - NP_INTE: 적분 (∫)
 * - NP_SIG: 시그마 (Σ)
 * - NP_LIM: 극한 (lim)
 * - NP_SUN: 윗줄 (overline)
 * - NP_IE: 이탤릭 변수
 */
public class NPFontEquationConverter {

    /**
     * 연속된 NP 폰트 런 그룹을 HWP 수식 스크립트로 변환한다.
     *
     * @param runs 연속된 NP 폰트 CharacterRun 목록
     * @return HWP 수식 스크립트 문자열, 또는 수식이 아니면 null
     */
    public static String convert(List<IDMLCharacterRun> runs) {
        if (runs == null || runs.isEmpty()) return null;

        // 구조 카테고리(첨자, 근호, 분수 등)가 포함되어야 수식으로 인정
        if (!hasStructuralCategory(runs)) return null;

        StringBuilder sb = new StringBuilder();
        boolean inSubscript = false;
        boolean inSuperscript = false;
        boolean inSqrt = false;

        for (int i = 0; i < runs.size(); i++) {
            IDMLCharacterRun run = runs.get(i);
            String text = run.content();
            if (text == null || text.isEmpty()) continue;

            String npFont = run.npFontName();
            if (npFont == null) {
                // 비NP 브릿지 런 — 첨자 컨텍스트 닫기 (NP 폰트가 아니면 첨자 범위 종료)
                if (inSubscript) { sb.append("}"); inSubscript = false; }
                if (inSuperscript) { sb.append("}"); inSuperscript = false; }
                if (inSqrt) {
                    // sqrt 내부: 선행 알파벳/숫자만 피근호로 취급하고 닫기
                    int end = 0;
                    while (end < text.length() && Character.isLetterOrDigit(text.charAt(end))) {
                        end++;
                    }
                    if (end > 0) {
                        sb.append(text, 0, end);
                    }
                    sb.append("}");
                    inSqrt = false;
                    if (end < text.length()) {
                        sb.append(text.substring(end));
                    }
                } else {
                    sb.append(text);
                }
                continue;
            }

            NPFontGlyphMap.FontCategory cat = NPFontGlyphMap.getCategory(npFont);

            switch (cat) {
                case OPERATOR:
                    if (inSubscript) { sb.append("}"); inSubscript = false; }
                    if (inSuperscript) { sb.append("}"); inSuperscript = false; }
                    appendOperator(text, npFont, sb);
                    break;

                case VARIABLE:
                    if (inSubscript) { sb.append("}"); inSubscript = false; }
                    if (inSuperscript) { sb.append("}"); inSuperscript = false; }
                    appendVariable(text, npFont, sb);
                    break;

                case ITALIC:
                    if (inSubscript) { sb.append("}"); inSubscript = false; }
                    if (inSuperscript) { sb.append("}"); inSuperscript = false; }
                    appendItalic(text, sb);
                    break;

                case SUBSCRIPT_INDEX:
                    if (inSuperscript) { sb.append("}"); inSuperscript = false; }
                    if (!inSubscript) {
                        sb.append("_{");
                        inSubscript = true;
                    }
                    appendSubscript(text, npFont, sb);
                    break;

                case SUPERSCRIPT_INDEX:
                    if (inSubscript) { sb.append("}"); inSubscript = false; }
                    if (!inSuperscript) {
                        sb.append("^{");
                        inSuperscript = true;
                    }
                    appendSuperscript(text, npFont, sb);
                    break;

                case ROOT:
                    if (inSubscript) { sb.append("}"); inSubscript = false; }
                    if (inSuperscript) { sb.append("}"); inSuperscript = false; }
                    inSqrt = appendRoot(text, sb, inSqrt);
                    break;

                case FRACTION_BAR:
                    if (inSubscript) { sb.append("}"); inSubscript = false; }
                    if (inSuperscript) { sb.append("}"); inSuperscript = false; }
                    appendFraction(text, sb);
                    break;

                case INTEGRAL:
                    if (inSubscript) { sb.append("}"); inSubscript = false; }
                    if (inSuperscript) { sb.append("}"); inSuperscript = false; }
                    appendIntegral(text, npFont, sb);
                    break;

                case SUMMATION:
                    if (inSubscript) { sb.append("}"); inSubscript = false; }
                    if (inSuperscript) { sb.append("}"); inSuperscript = false; }
                    appendSummation(text, npFont, sb);
                    break;

                case LIMIT:
                    if (inSubscript) { sb.append("}"); inSubscript = false; }
                    if (inSuperscript) { sb.append("}"); inSuperscript = false; }
                    appendLimit(text, npFont, sb);
                    break;

                case SPECIAL_SYMBOL:
                    if (inSubscript) { sb.append("}"); inSubscript = false; }
                    if (inSuperscript) { sb.append("}"); inSuperscript = false; }
                    appendSpecial(text, npFont, sb);
                    break;

                default:
                    // UNKNOWN — 그대로 출력
                    if (inSubscript) { sb.append("}"); inSubscript = false; }
                    if (inSuperscript) { sb.append("}"); inSuperscript = false; }
                    sb.append(text);
                    break;
            }
        }

        // 열린 컨텍스트 닫기
        if (inSubscript) sb.append("}");
        if (inSuperscript) sb.append("}");
        if (inSqrt) sb.append("}");

        String result = cleanHwpScript(sb.toString());
        if (result.isEmpty()) return null;

        // 글자/숫자가 없으면 수식이 아님
        boolean hasContent = false;
        for (int i = 0; i < result.length(); i++) {
            char c = result.charAt(i);
            if (Character.isLetterOrDigit(c) || c >= 0x2460 && c <= 0x2473) {
                hasContent = true;
                break;
            }
        }
        if (!hasContent) return null;

        return result;
    }

    /**
     * NP 런 그룹이 수식으로 변환할 가치가 있는지 확인.
     * 다음 중 하나라도 해당하면 수식으로 인정:
     * 1. 구조 카테고리(첨자, 근호, 분수, 적분, 시그마, 극한, 오버라인)
     * 2. 텍스트에 수학 연산자(=, <, >, +, -, ×) 포함
     * 3. VARIABLE 카테고리의 특수 매핑 문자
     * 4. 문자 + 숫자 혼합 (예: log2, 2x)
     */
    private static boolean hasStructuralCategory(List<IDMLCharacterRun> runs) {
        boolean hasLetter = false;
        boolean hasDigit = false;

        for (IDMLCharacterRun run : runs) {
            String npFont = run.npFontName();
            String text = run.content();

            // NP 폰트 카테고리 확인
            if (npFont != null) {
                NPFontGlyphMap.FontCategory cat = NPFontGlyphMap.getCategory(npFont);
                switch (cat) {
                    case SUBSCRIPT_INDEX:
                    case SUPERSCRIPT_INDEX:
                    case ROOT:
                    case FRACTION_BAR:
                    case INTEGRAL:
                    case SUMMATION:
                    case LIMIT:
                    case SPECIAL_SYMBOL:
                        return true;
                    default:
                        break;
                }

                // VARIABLE 카테고리의 특수 매핑 문자
                if (cat == NPFontGlyphMap.FontCategory.VARIABLE && text != null) {
                    for (int i = 0; i < text.length(); i++) {
                        char c = text.charAt(i);
                        if ("90yE/<>p".indexOf(c) >= 0) return true;
                    }
                }
            }

            // 텍스트에 수학 연산자가 있는지 확인
            if (text != null) {
                for (int i = 0; i < text.length(); i++) {
                    char c = text.charAt(i);
                    if (c == '=' || c == '<' || c == '>' || c == '+'
                            || c == '\u00D7' || c == '\u00F7') {
                        return true;
                    }
                    // - 는 단독이면 모호하지만, 다른 문자와 함께면 수식
                    if (c == '-' && text.length() > 1) return true;
                    if (Character.isLetter(c)) hasLetter = true;
                    if (Character.isDigit(c)) hasDigit = true;
                }
            }
        }

        // 문자 + 숫자 혼합 (예: log2, 2x, a3) → 수식
        if (hasLetter && hasDigit) return true;

        return false;
    }

    // ── 카테고리별 변환 메서드 ──

    private static void appendOperator(String text, String npFont, StringBuilder sb) {
        for (int i = 0; i < text.length(); i++) {
            String ch = String.valueOf(text.charAt(i));
            String mapped = NPFontGlyphMap.mapGlyph(npFont, ch);
            sb.append(mapped);
        }
    }

    private static void appendVariable(String text, String npFont, StringBuilder sb) {
        for (int i = 0; i < text.length(); i++) {
            String ch = String.valueOf(text.charAt(i));
            String mapped = NPFontGlyphMap.mapGlyph(npFont, ch);
            sb.append(mapped);
        }
    }

    private static void appendItalic(String text, StringBuilder sb) {
        sb.append(text);
    }

    private static void appendSubscript(String text, String npFont, StringBuilder sb) {
        for (int i = 0; i < text.length(); i++) {
            String ch = String.valueOf(text.charAt(i));
            String mapped = NPFontGlyphMap.mapGlyph(npFont, ch);
            sb.append(mapped);
        }
    }

    private static void appendSuperscript(String text, String npFont, StringBuilder sb) {
        for (int i = 0; i < text.length(); i++) {
            String ch = String.valueOf(text.charAt(i));
            String mapped = NPFontGlyphMap.mapGlyph(npFont, ch);
            sb.append(mapped);
        }
    }

    /**
     * 근호 처리. j=sqrt 시작, k=sqrt 닫기.
     * @return inSqrt 상태
     */
    private static boolean appendRoot(String text, StringBuilder sb, boolean inSqrt) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == 'j') {
                if (inSqrt) sb.append("}");  // 이전 sqrt 닫기
                sb.append("sqrt{");
                inSqrt = true;
            } else if (c == 'k') {
                if (inSqrt) {
                    sb.append("}");
                    inSqrt = false;
                }
                // k (바 확장)는 sqrt 닫기 역할
            } else {
                // ! → ①, @ → ② 등 원기호
                String mapped = NPFontGlyphMap.mapGlyph("NP_RUT", String.valueOf(c));
                sb.append(mapped);
            }
        }
        return inSqrt;
    }

    private static void appendFraction(String text, StringBuilder sb) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            String mapped = NPFontGlyphMap.mapGlyph("NP_BUN", String.valueOf(c));
            sb.append(mapped);
        }
    }

    private static void appendIntegral(String text, String npFont, StringBuilder sb) {
        for (int i = 0; i < text.length(); i++) {
            String ch = String.valueOf(text.charAt(i));
            String mapped = NPFontGlyphMap.mapGlyph(npFont, ch);
            sb.append(mapped);
        }
    }

    private static void appendSummation(String text, String npFont, StringBuilder sb) {
        for (int i = 0; i < text.length(); i++) {
            String ch = String.valueOf(text.charAt(i));
            String mapped = NPFontGlyphMap.mapGlyph(npFont, ch);
            sb.append(mapped);
        }
    }

    private static void appendLimit(String text, String npFont, StringBuilder sb) {
        for (int i = 0; i < text.length(); i++) {
            String ch = String.valueOf(text.charAt(i));
            String mapped = NPFontGlyphMap.mapGlyph(npFont, ch);
            sb.append(mapped);
        }
    }

    /**
     * 특수 기호 처리. Z=overline(bar), !=→ 등.
     * Z가 나오면 직전 출력을 look-back하여 bar{...}로 래핑.
     */
    private static void appendSpecial(String text, String npFont, StringBuilder sb) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == 'Z') {
                // overline: 직전 내용에 bar 적용
                // 연속 Z는 첫 번째만 처리 (나머지는 바 확장)
                if (i == 0 || text.charAt(i - 1) != 'Z') {
                    applyOverline(sb);
                }
                // 연속 Z는 무시 (바 확장)
            } else {
                String mapped = NPFontGlyphMap.mapGlyph(npFont, String.valueOf(c));
                sb.append(mapped);
            }
        }
    }

    /**
     * sb의 직전 내용에 overline(bar) 적용.
     * 직전 단어/변수를 추출하여 "bar {content}" 로 래핑.
     */
    private static void applyOverline(StringBuilder sb) {
        if (sb.length() == 0) return;

        // 직전 내용 추출: 공백 또는 구조 키워드 이전까지
        int end = sb.length();
        int start = end;
        while (start > 0) {
            char prev = sb.charAt(start - 1);
            if (Character.isLetterOrDigit(prev)) {
                start--;
            } else {
                break;
            }
        }

        if (start < end) {
            String content = sb.substring(start, end);
            sb.delete(start, end);
            sb.append("bar ").append(content).append(" ");
        } else {
            // 직전이 닫는 중괄호 등이면 그 블록에 bar 적용
            sb.append("bar ");
        }
    }

    /**
     * HWP 수식 스크립트 정리.
     */
    private static String cleanHwpScript(String script) {
        // 연속 공백 정리
        script = script.replaceAll("\\s+", " ");
        // _{a} _{b} → _{a b} (연속 아래첨자 병합)
        script = script.replaceAll("(_\\{[^}]*)\\} _\\{", "$1 ");
        // ^{a} ^{b} → ^{a b} (연속 위첨자 병합)
        script = script.replaceAll("(\\^\\{[^}]*)\\} \\^\\{", "$1 ");
        // 빈 sqrt{} → sqrt (피근호가 다른 그룹에 있는 경우)
        script = script.replace("sqrt{}", "sqrt ");
        // 남은 @ → ^{2} (NP 폰트에서 매핑되지 않은 제곱 기호)
        script = script.replace("@", "^{2}");
        return script.trim();
    }
}
