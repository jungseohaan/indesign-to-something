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
 * - z: ±  (플러스마이너스)
 * - rt...&: √ (제곱근). rt 뒤 & 종료자까지가 피근호수. & 없으면 단순 토큰(연속 숫자/단일 문자)
 *   예: rt3 → sqrt{3}, rt3& → sqrt{3}, rt(-1) → sqrt{(-1)}, 2rt5 → 2sqrt{5}
 * - zrt...&: ±√ (플러스마이너스 제곱근). 예: zrt3 → +- sqrt{3}
 */
public class BTFontEquationConverter {

    /**
     * HWP 수식 스크립트에서 인식되는 그리스 문자 키워드.
     * 길이 내림차순 정렬 — greedy 매칭 시 theta가 th+eta로 분리되지 않도록.
     */
    private static final String[] GREEK_KEYWORDS = {
            "upsilon", "epsilon", "omicron",   // 7
            "lambda",                           // 6
            "alpha", "gamma", "delta", "theta", "kappa", "sigma", "omega",  // 5
            "beta", "zeta", "iota",            // 4
            "eta", "phi", "chi", "psi", "rho", // 3
    };

    /**
     * 텍스트에 그리스 문자 키워드가 포함되어 있는지 확인.
     */
    public static boolean containsGreekKeyword(String text) {
        for (String kw : GREEK_KEYWORDS) {
            if (text.contains(kw)) return true;
        }
        return false;
    }

    /**
     * 그리스 문자 키워드를 공백으로 대체 (looksLikeWord 판정용).
     * greedy longest-first 매칭으로 theta→eta 오분할 방지.
     */
    private static String removeGreekKeywords(String text) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            boolean matched = false;
            for (String kw : GREEK_KEYWORDS) {
                if (text.regionMatches(i, kw, 0, kw.length())) {
                    sb.append(' ');
                    i += kw.length();
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                sb.append(text.charAt(i));
                i++;
            }
        }
        return sb.toString();
    }

    /**
     * 그리스 문자 키워드 사이에 공백을 삽입 (alphabeta → alpha beta).
     * greedy longest-first 매칭으로 theta→eta 오분할 방지.
     */
    private static String separateGreekKeywords(String text) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            boolean matched = false;
            for (String kw : GREEK_KEYWORDS) {
                if (text.regionMatches(i, kw, 0, kw.length())) {
                    sb.append(' ').append(kw).append(' ');
                    i += kw.length();
                    matched = true;
                    break;
                }
            }
            if (!matched) {
                sb.append(text.charAt(i));
                i++;
            }
        }
        return sb.toString();
    }

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

        // 문자/숫자/콤마/공백만으로 구성된 단순 텍스트는 수식이 아님 (폰트 여부 무관)
        // 예: "A", "B", "m,", "400" → 수식폰트 텍스트 런으로 폴백
        if (isPlainText(raw)) return null;

        // 영문 단어 필터링 (이름, 영단어 등 — 변환 전 원본 텍스트 기준, 폰트 무관)
        // 예: "Permutation(", "Combination(", "Shakespeare, W., 1564~1616)"
        if (looksLikeWord(raw)) return null;

        if (!hasMathFontRun) {
            // 수식 콘텐츠 확인
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
     * 문자/숫자/콤마/공백만으로 구성된 단순 텍스트인지 확인.
     * 수식 마커나 연산자가 없으면 수식이 아닌 수식폰트 텍스트로 처리.
     * 단, 그리스 문자 키워드(alpha, beta 등)가 포함되면 수식으로 간주.
     */
    private static boolean isPlainText(String raw) {
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            if (Character.isLetterOrDigit(c) || Character.isWhitespace(c) || c == ',') continue;
            return false;
        }
        // 문자/숫자/콤마/공백만으로 구성되었더라도 그리스 문자 키워드가 있으면 수식
        if (containsGreekKeyword(raw)) return false;
        // "rt" + 숫자/문자 패턴이 있으면 수식 (제곱근)
        if (containsRtPattern(raw)) return false;
        return true;
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
        // 제곱근 패턴
        if (containsRtPattern(raw)) return true;

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
        // 그리스 문자 키워드 제거 (greedy longest-first로 theta→eta 오분할 방지)
        cleaned = removeGreekKeywords(cleaned);
        // 다문자 키워드 제거
        cleaned = cleaned.replace(".c3", " ");
        cleaned = cleaned.replace("not=", " ");
        // 단어 키워드 제거 (cup, hap, cap, div 등이 단어에 포함될 수 있음)
        for (String kw : new String[]{"cup", "hap", "cap", "div"}) {
            cleaned = cleaned.replace(kw, " ");
        }
        // BT 마커 제거
        cleaned = cleaned.replaceAll("[_&\\\\`^]", " ");
        // 제곱근 마커 "zrt" / "rt" 제거 (영문자 뒤가 아닌 경우만)
        StringBuilder rtCleaned = new StringBuilder();
        for (int j = 0; j < cleaned.length(); j++) {
            // "zrt" 패턴
            if (j + 2 < cleaned.length() && cleaned.charAt(j) == 'z'
                    && cleaned.charAt(j + 1) == 'r' && cleaned.charAt(j + 2) == 't'
                    && (j == 0 || !Character.isLetter(cleaned.charAt(j - 1)))) {
                rtCleaned.append("   ");
                j += 2; // skip 'r', 't'
            }
            // "rt" 패턴
            else if (j + 1 < cleaned.length() && cleaned.charAt(j) == 'r' && cleaned.charAt(j + 1) == 't'
                    && (j == 0 || !Character.isLetter(cleaned.charAt(j - 1)))) {
                rtCleaned.append("  ");
                j++; // skip 't'
            } else {
                rtCleaned.append(cleaned.charAt(j));
            }
        }
        cleaned = rtCleaned.toString();
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
    public static String convertRawToHwpScript(String raw) {
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

        // 제곱근: rt...& → sqrt{...} (위치 마커 전에 처리해야 &가 리셋으로 소비되지 않음)
        result = convertRoots(result);

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
        // 그리스 문자 키워드 분리 (alphabeta → alpha beta)
        text = separateGreekKeywords(text);

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
                // & 종료자까지의 거리 확인 — 다중문자 첨자 감지
                int ampIdx = findTerminator(text, i, '&');
                int nextSub = findTerminator(text, i, '_');
                int nextSup = findTerminator(text, i, '^');
                if (ampIdx > i && (nextSub < 0 || ampIdx < nextSub) && (nextSup < 0 || ampIdx < nextSup)) {
                    // & 종료자가 다음 _/^ 보다 먼저 → 그 사이 전체가 첨자 내용
                    sb.append(text, i, ampIdx);
                    i = ampIdx;
                } else {
                    // 기존 동작: 단일 문자 수집
                    i = collectScriptContent(text, i, sb);
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
                // & 종료자까지의 거리 확인 — 다중문자 첨자 감지
                int ampIdx2 = findTerminator(text, i, '&');
                int nextSub2 = findTerminator(text, i, '_');
                int nextSup2 = findTerminator(text, i, '^');
                if (ampIdx2 > i && (nextSub2 < 0 || ampIdx2 < nextSub2) && (nextSup2 < 0 || ampIdx2 < nextSup2)) {
                    sb.append(text, i, ampIdx2);
                    i = ampIdx2;
                } else {
                    i = collectScriptContent(text, i, sb);
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
     * 첨자 내용 수집: 한 글자(letter) 또는 연속 숫자.
     * BT수식M에서 _x는 단일 문자 첨자이며, 다중 문자 첨자는 &로 종료.
     * 런 경계 정보가 없는 연결 텍스트에서 _nP_r → _{n}P_{r} 올바르게 처리.
     *
     * @return 수집 후 다음 읽을 인덱스
     */
    private static int collectScriptContent(String text, int i, StringBuilder sb) {
        if (i >= text.length()) return i;
        char first = text.charAt(i);
        // 특수문자/마커이면 수집 없이 반환
        if (first == '&' || first == '_' || first == '^') return i;
        if (first == '(' || first == ')' || first == '=' || first == '+' || first == ',' || first == ' ') return i;

        if (Character.isLetter(first)) {
            // 글자 하나만 수집
            sb.append(first);
            i++;
        } else if (Character.isDigit(first)) {
            // 연속 숫자 수집
            while (i < text.length() && Character.isDigit(text.charAt(i))) {
                sb.append(text.charAt(i));
                i++;
            }
        } else {
            // 기타 문자: 하나만 수집
            sb.append(first);
            i++;
        }
        return i;
    }

    /**
     * 텍스트에서 특정 종료 문자를 찾는다.
     * @return 종료 문자의 인덱스, 없으면 -1
     */
    private static int findTerminator(String text, int from, char termChar) {
        for (int j = from; j < text.length(); j++) {
            if (text.charAt(j) == termChar) return j;
        }
        return -1;
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
     * "rt" 또는 "zrt" + 숫자/문자/부호/괄호 패턴(제곱근)이 포함되어 있는지 확인.
     * "rt"가 영문자 뒤에 오면 무시 (start, part 등). 단, "z" 뒤의 "rt"는 ±√으로 인식.
     */
    private static boolean containsRtPattern(String text) {
        for (int i = 0; i + 2 < text.length(); i++) {
            if (text.charAt(i) == 'r' && text.charAt(i + 1) == 't') {
                // "zrt" 패턴: z 바로 앞이 영문자가 아니면 ±√
                boolean isZrt = (i >= 1 && text.charAt(i - 1) == 'z'
                        && (i < 2 || !Character.isLetter(text.charAt(i - 2))));
                // 일반 "rt" 패턴: 앞이 영문자가 아니면 √
                boolean isRt = (i == 0 || !Character.isLetter(text.charAt(i - 1)));

                if (isZrt || isRt) {
                    if (i + 2 < text.length()) {
                        char next = text.charAt(i + 2);
                        if (Character.isLetterOrDigit(next) || next == '-' || next == '(') {
                            return true;
                        }
                    }
                }
            }
        }
        return false;
    }

    /**
     * "rt...&" / "zrt...&" 패턴을 "sqrt{...}" / "+- sqrt{...}"로 변환.
     * - zrt: ±√ (z = 플러스마이너스, rt = 제곱근)
     * - rt 뒤 &가 있으면(단, _/^ 마커보다 먼저) 그 사이가 피근호수
     * - & 없으면: 괄호식, 연속 숫자, 또는 단일 문자를 피근호수로 수집
     * - rt 앞에 영문자가 있으면 무시 (start, part 등의 일반 단어). 단 z는 예외
     */
    private static String convertRoots(String text) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < text.length()) {
            // "zrt" 패턴 감지: z + rt (z 앞이 영문자가 아님)
            boolean isZrt = false;
            if (i + 3 < text.length()
                    && text.charAt(i) == 'z'
                    && text.charAt(i + 1) == 'r' && text.charAt(i + 2) == 't'
                    && (i == 0 || !Character.isLetter(text.charAt(i - 1)))) {
                isZrt = true;
            }

            // "rt" 패턴 감지
            boolean isRt = !isZrt
                    && i + 2 < text.length()
                    && text.charAt(i) == 'r' && text.charAt(i + 1) == 't'
                    && (i == 0 || !Character.isLetter(text.charAt(i - 1)));

            if (isZrt || isRt) {
                if (isZrt) {
                    sb.append("+- ");
                    i += 3; // skip "zrt"
                } else {
                    i += 2; // skip "rt"
                }

                // & 종료자 탐색 (단, _/^/닫는괄호가 먼저 오면 중단 — 외부 괄호가 피근호수에 포함 방지)
                int ampIdx = -1;
                for (int j = i; j < text.length(); j++) {
                    char ch = text.charAt(j);
                    if (ch == '&') { ampIdx = j; break; }
                    if (ch == '_' || ch == '^' || ch == ')') break;
                }

                if (ampIdx >= 0 && ampIdx > i) {
                    // & 종료자까지 전체가 피근호수
                    String radicand = text.substring(i, ampIdx);
                    sb.append("sqrt{").append(radicand).append("}");
                    i = ampIdx + 1; // & 건너뛰기
                } else {
                    // & 없음: 단순 토큰 수집
                    StringBuilder rad = new StringBuilder();
                    // 선택적 음수 부호
                    if (i < text.length() && text.charAt(i) == '-') {
                        rad.append('-');
                        i++;
                    }
                    // 괄호식
                    if (i < text.length() && text.charAt(i) == '(') {
                        int depth = 0;
                        while (i < text.length()) {
                            char ch = text.charAt(i);
                            rad.append(ch);
                            if (ch == '(') depth++;
                            else if (ch == ')') {
                                depth--;
                                if (depth == 0) { i++; break; }
                            }
                            i++;
                        }
                    } else if (i < text.length() && Character.isDigit(text.charAt(i))) {
                        // 연속 숫자
                        while (i < text.length() && Character.isDigit(text.charAt(i))) {
                            rad.append(text.charAt(i));
                            i++;
                        }
                    } else if (i < text.length() && Character.isLetter(text.charAt(i))) {
                        // 단일 문자
                        rad.append(text.charAt(i));
                        i++;
                    }

                    if (rad.length() > 0) {
                        sb.append("sqrt{").append(rad).append("}");
                    } else {
                        sb.append("rt"); // 파싱 실패 시 원본 유지
                    }
                }
            } else {
                sb.append(text.charAt(i));
                i++;
            }
        }
        return sb.toString();
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
        // □ (U+25A1) — 빈 답안 상자 자리, 그대로 유지
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
