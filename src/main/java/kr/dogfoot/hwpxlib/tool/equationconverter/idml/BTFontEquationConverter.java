package kr.dogfoot.hwpxlib.tool.equationconverter.idml;

import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLCharacterRun;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * BT수식M 폰트 체계의 텍스트를 HWP 수식 스크립트로 변환한다.
 *
 * BT수식M 인코딩 규칙:
 * - _x: 아래첨자 (underscore + 문자)
 * - &: 위치 리셋 (아래첨자 종료)
 * - \: 곱셈 (×)
 * - `: thin space
 * - !: 팩토리얼
 * - cup: ∪ (합집합)
 * - hap: ∪ (합집합, 한국어)
 * - cap: ∩ (교집합)
 * - div: ÷
 * - ^x: 위첨자 (superscript)
 * - -<: ≤ (LEQ)
 * - /<: ⊂ (SUBSET)
 * - not=: ≠
 * - ^-XX^-: bar (윗줄)
 * - .c3: 가운데점 (CDOTS)
 * - rNpar: 원기호 (①, ②, ...)
 */
public class BTFontEquationConverter {

    /**
     * 연속된 BT수식M 런 그룹을 HWP 수식 스크립트로 변환한다.
     * 일반 텍스트 런도 같은 그룹에 포함될 수 있다 (수식 내 비수식폰트 부분).
     *
     * @param runs 연속된 CharacterRun 목록 (BT수식M + 인접 일반 텍스트)
     * @return HWP 수식 스크립트 문자열, 또는 의미 없는 수식이면 null
     */
    public static String convert(List<IDMLCharacterRun> runs) {
        if (runs == null || runs.isEmpty()) return null;

        // 1. 모든 런의 텍스트를 연결 (수식 연속체)
        StringBuilder rawBuilder = new StringBuilder();
        for (IDMLCharacterRun run : runs) {
            String text = run.content();
            if (text != null) {
                rawBuilder.append(text);
            }
        }
        String raw = rawBuilder.toString().trim();
        if (raw.isEmpty()) return null;

        // BT수식 폰트 런이 하나라도 있으면 수식으로 확정 → 필터 건너뛰기
        boolean hasMathFontRun = false;
        for (IDMLCharacterRun run : runs) {
            if (run.isBTFont() || run.grepMathFont()) {
                hasMathFontRun = true;
                break;
            }
        }

        if (!hasMathFontRun) {
            // 2. 영문 단어 필터링 (이름, 영단어 등 — 변환 전 원본 텍스트 기준)
            if (looksLikeWord(raw)) return null;

            // 3. 수식 콘텐츠 확인
            if (!hasMathContent(raw)) return null;
        }

        // 4. 키워드 + 마커 기반 변환
        String hwpScript = convertRawToHwpScript(raw);

        // 5. 의미 없는 수식 필터링
        if (hwpScript == null || hwpScript.trim().isEmpty()) return null;
        String trimmed = hwpScript.trim();
        // 글자나 숫자가 없는 순수 기호/공백 → 수식 아님
        boolean hasLetterOrDigit = false;
        for (int i = 0; i < trimmed.length(); i++) {
            char ch = trimmed.charAt(i);
            if (Character.isLetterOrDigit(ch)) { hasLetterOrDigit = true; break; }
            // 원기호 ① U+2460 ~ ⑳ U+2473
            if (ch >= 0x2460 && ch <= 0x2473) { hasLetterOrDigit = true; break; }
        }
        if (!hasLetterOrDigit) return null;

        return trimmed;
    }

    /**
     * BT수식M 텍스트가 수식 콘텐츠인지 확인.
     * 연산자, 숫자+문자 혼합, 짧은 변수명, 콤마 구분 변수 목록, BT 마커를 포함하면 수식.
     * 순수 영문 단어(이름 등)는 수식으로 취급하지 않는다.
     */
    private static boolean hasMathContent(String raw) {
        // BT수식M 위치 마커 확인 (기존)
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (c == '_' || c == '&' || c == '\\' || c == '`') return true;
            if (c == '^' && i + 1 < raw.length()) return true; // ^- (overline) 또는 ^x (superscript)
        }
        // 다문자 키워드
        if (raw.contains("cup") || raw.contains("hap") || raw.contains("cap")) return true;
        if (raw.contains("div")) return true;
        if (raw.contains("not=") || raw.contains("-<") || raw.contains("/<")) return true;

        // 수학 연산자 포함 → 수식
        boolean hasOperator = false;
        boolean hasLetter = false;
        boolean hasDigit = false;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if ("+-*/=<>()[]{}|!".indexOf(c) >= 0) hasOperator = true;
            if (Character.isLetter(c)) hasLetter = true;
            if (Character.isDigit(c)) hasDigit = true;
        }
        if (hasOperator) return true;
        // 문자+숫자 혼합 (e.g., "2x", "3n") → 수식
        if (hasLetter && hasDigit) return true;

        // 짧은 텍스트 (변수명 — A, B, xy 등) → 수식
        String stripped = raw.replaceAll("[\\s,.]", "");
        if (hasLetter && stripped.length() <= 3) return true;

        // 콤마 구분 변수 목록 (e.g., "A, B") → 수식
        if (raw.contains(",") && hasLetter) {
            String[] parts = raw.split(",");
            boolean allShort = true;
            for (String p : parts) {
                if (p.trim().length() > 3) { allShort = false; break; }
            }
            if (allShort) return true;
        }

        return false;
    }

    /**
     * 텍스트 내에 영문 단어(4자 이상 연속 알파벳)가 포함되어 있는지 확인.
     * "Arnold, 1874~1951)", "(Shakespeare", "Permutation(" 등 이름/영단어를 필터링.
     */
    private static boolean looksLikeWord(String text) {
        // BT수식M 키워드와 마커를 제거한 후 확인 (원본 텍스트 기준)
        String cleaned = text;
        // 다문자 키워드 제거
        cleaned = cleaned.replace(".c3", " ");
        cleaned = cleaned.replace("not=", " ");
        // 단어 키워드 제거 (cup, hap, cap, div 등이 단어에 포함될 수 있음)
        for (String kw : new String[]{"cup", "hap", "cap", "div"}) {
            cleaned = cleaned.replace(kw, " ");
        }
        // BT 마커 제거
        cleaned = cleaned.replaceAll("[_&\\\\`^]", " ");
        // 비알파벳 문자로 분리하여 각 부분이 4자 이상인지 확인
        String[] words = cleaned.split("[^a-zA-Z]+");
        for (String word : words) {
            if (word.length() >= 4) return true;
        }
        return false;
    }

    /**
     * 원시 BT수식M 텍스트를 HWP 수식 스크립트로 변환.
     */
    static String convertRawToHwpScript(String raw) {
        // r1par, r2par 등 → 원기호(①, ②, ...)로 변환
        raw = convertCircledNumbers(raw);

        // 단계별 변환
        String result = raw;

        // ^-XX^- → bar XX (overline) — 먼저 처리 (^가 다른 규칙에 영향)
        result = convertOverlines(result);

        // 키워드 치환 (긴 것부터)
        result = replaceKeywords(result);

        // 중괄호 → lbrace/rbrace (위치 마커 전에 처리해야 _{} ^{} 구문과 충돌 방지)
        result = result.replace("{", " lbrace ");
        result = result.replace("}", " rbrace ");

        // 위치 마커 파싱: _x → _{x}, & → 리셋
        result = convertPositionMarkers(result);

        // 폰트별 글리프 치환
        result = convertGlyphs(result);

        // 정리
        result = cleanHwpScript(result);

        return result;
    }

    /**
     * ^-XX^- 패턴을 bar XX로 변환 (윗줄/overline).
     */
    private static String convertOverlines(String text) {
        // ^-XX^- → bar XX
        Pattern p = Pattern.compile("\\^-([A-Za-z0-9]+)\\^-");
        Matcher m = p.matcher(text);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String content = m.group(1);
            m.appendReplacement(sb, "bar " + content + " ");
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * 키워드를 HWP 수식 스크립트 명령어로 치환.
     */
    private static String replaceKeywords(String text) {
        // 순서 중요: 긴 키워드부터 (not= 전에 not 매칭 방지)
        text = text.replace("not=", " != ");
        text = text.replace(".c3", " CDOTS ");
        text = text.replace("-<", " LEQ ");
        text = text.replace("/<", " SUBSET ");

        // 단어 경계 기반 키워드 치환
        text = replaceKeyword(text, "cup", " CUP ");
        text = replaceKeyword(text, "hap", " CUP ");
        text = replaceKeyword(text, "cap", " CAP ");
        text = replaceKeyword(text, "div", " div ");

        return text;
    }

    /**
     * 단어 경계를 고려한 키워드 치환.
     * "AcupB" → "A CUP B" 처리를 위해 단순 replace 대신 사용.
     */
    private static String replaceKeyword(String text, String keyword, String replacement) {
        // 키워드 앞뒤에 영문자가 있으면 공백으로 분리
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            int found = text.indexOf(keyword, i);
            if (found < 0) {
                sb.append(text.substring(i));
                break;
            }
            sb.append(text, i, found);
            sb.append(replacement);
            i = found + keyword.length();
        }
        return sb.toString();
    }

    /**
     * 위치 마커를 HWP 수식 스크립트로 변환.
     * _x... → _{x...} (아래첨자, &로 종료)
     * ^x... → ^{x...} (위첨자, &로 종료)
     * & → 위치 리셋 (첨자 종료)
     */
    private static String convertPositionMarkers(String text) {
        StringBuilder sb = new StringBuilder();
        boolean inSubscript = false;
        boolean inSuperscript = false;
        int i = 0;

        while (i < text.length()) {
            char c = text.charAt(i);

            if (c == '_' && i + 1 < text.length()) {
                // 위첨자 열려있으면 먼저 닫기
                if (inSuperscript) { sb.append("}"); inSuperscript = false; }
                // 아래첨자 시작
                if (inSubscript) {
                    sb.append("} _{");
                } else {
                    // 첨자 기저가 없으면 빈 그룹 추가
                    if (!hasSubscriptBase(sb)) {
                        sb.append("{}");
                    }
                    sb.append("_{");
                    inSubscript = true;
                }
                i++;
                // 아래첨자 내용 수집 (다음 & 또는 _ 또는 ^ 또는 특수문자까지)
                while (i < text.length()) {
                    char sc = text.charAt(i);
                    if (sc == '&' || sc == '_' || sc == '^') break;
                    if (sc == '(' || sc == ')' || sc == '=' || sc == '+' || sc == ',' || sc == ' ') break;
                    sb.append(sc);
                    i++;
                }
                // 아래첨자 닫기
                if (i < text.length() && text.charAt(i) == '&') {
                    sb.append("}");
                    inSubscript = false;
                    i++; // & 건너뛰기
                } else if (!inSubscript || (i < text.length() && text.charAt(i) != '_')) {
                    if (inSubscript) {
                        sb.append("}");
                        inSubscript = false;
                    }
                }
            } else if (c == '^' && i + 1 < text.length()) {
                // 아래첨자 열려있으면 먼저 닫기
                if (inSubscript) { sb.append("}"); inSubscript = false; }
                // 위첨자 시작
                if (inSuperscript) {
                    sb.append("} ^{");
                } else {
                    // 첨자 기저가 없으면 빈 그룹 추가
                    if (!hasSubscriptBase(sb)) {
                        sb.append("{}");
                    }
                    sb.append("^{");
                    inSuperscript = true;
                }
                i++;
                // 위첨자 내용 수집 (다음 & 또는 ^ 또는 _ 또는 특수문자까지)
                while (i < text.length()) {
                    char sc = text.charAt(i);
                    if (sc == '&' || sc == '^' || sc == '_') break;
                    if (sc == '(' || sc == ')' || sc == '=' || sc == '+' || sc == ',' || sc == ' ') break;
                    sb.append(sc);
                    i++;
                }
                // 위첨자 닫기
                if (i < text.length() && text.charAt(i) == '&') {
                    sb.append("}");
                    inSuperscript = false;
                    i++; // & 건너뛰기
                } else if (!inSuperscript || (i < text.length() && text.charAt(i) != '^')) {
                    if (inSuperscript) {
                        sb.append("}");
                        inSuperscript = false;
                    }
                }
            } else if (c == '&') {
                // 위치 리셋 (공백 삽입)
                if (inSubscript) { sb.append("}"); inSubscript = false; }
                if (inSuperscript) { sb.append("}"); inSuperscript = false; }
                sb.append(" ");
                i++;
            } else {
                sb.append(c);
                i++;
            }
        }

        if (inSubscript) sb.append("}");
        if (inSuperscript) sb.append("}");

        return sb.toString();
    }

    /**
     * 첨자(_/^) 앞에 기저 문자가 있는지 확인.
     * 문자, 숫자, 닫는 괄호/중괄호가 기저 역할을 한다.
     */
    private static boolean hasSubscriptBase(StringBuilder sb) {
        if (sb.length() == 0) return false;
        char last = sb.charAt(sb.length() - 1);
        return Character.isLetterOrDigit(last) || last == '}' || last == ')';
    }

    /**
     * rNpar 패턴을 원기호(①②③...)로 변환.
     */
    private static String convertCircledNumbers(String text) {
        Matcher m = Pattern.compile("r(\\d+)par").matcher(text);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            int num = Integer.parseInt(m.group(1));
            String replacement;
            if (num >= 1 && num <= 20) {
                // ① U+2460 ~ ⑳ U+2473
                replacement = String.valueOf((char) (0x2460 + num - 1));
            } else {
                replacement = "(" + num + ")";
            }
            m.appendReplacement(sb, replacement);
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /**
     * 폰트별 특수 글리프 변환.
     */
    private static String convertGlyphs(String text) {
        // \ → TIMES (곱셈)
        text = text.replace("\\", " TIMES ");
        // … (U+2026) → CDOTS (가운데점)
        text = text.replace("\u2026", " CDOTS ");
        // ` → ~ (thin space in HWP script)
        text = text.replace("`", "~");

        return text;
    }

    /**
     * HWP 수식 스크립트 정리.
     */
    private static String cleanHwpScript(String script) {
        // 연속 공백 정리
        script = script.replaceAll("\\s+", " ");
        // _{a} _{b} → _{a b} (같은 타입 연속 아래첨자만 병합)
        script = script.replaceAll("(_\\{[^}]*)\\} _\\{", "$1 ");
        // ^{a} ^{b} → ^{a b} (같은 타입 연속 위첨자만 병합)
        script = script.replaceAll("(\\^\\{[^}]*)\\} \\^\\{", "$1 ");
        return script.trim();
    }
}
