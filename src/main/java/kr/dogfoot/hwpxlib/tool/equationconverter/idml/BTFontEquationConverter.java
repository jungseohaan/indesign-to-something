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
 * - -<: ≤
 * - /<: ⊂
 * - not=: ≠
 * - ^-XX^-: bar (윗줄)
 * - r1par/r2par: 풀이 단계 번호 (변환하지 않음)
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

        // 2. 원시 텍스트에 수식 마커가 있는지 확인 (순수 라틴 텍스트 필터링)
        if (!hasMathContent(raw)) return null;

        // 3. 키워드 + 마커 기반 변환
        String hwpScript = convertRawToHwpScript(raw);

        // 4. 의미 없는 수식 필터링
        if (hwpScript == null || hwpScript.trim().isEmpty()) return null;
        String trimmed = hwpScript.trim();
        if (trimmed.length() <= 1) return null;
        if (trimmed.matches("[a-zA-Z]")) return null;
        if (trimmed.matches("[<>=+\\-*/.,!?%]+")) return null;

        return trimmed;
    }

    /**
     * 원시 BT수식M 텍스트에 수식 마커/키워드가 포함되어 있는지 확인.
     * BT수식M 폰트가 수식이 아닌 라틴 텍스트(이름 등)에 사용된 경우를 필터링한다.
     */
    private static boolean hasMathContent(String raw) {
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            // BT수식M 위치 마커 및 특수 글리프
            if (c == '_' || c == '&' || c == '\\' || c == '`' || c == '!') return true;
            // ^- (overline 시작)
            if (c == '^' && i + 1 < raw.length() && raw.charAt(i + 1) == '-') return true;
        }
        // 다문자 키워드
        if (raw.contains("cup") || raw.contains("hap") || raw.contains("cap")) return true;
        if (raw.contains("div")) return true;
        if (raw.contains("not=") || raw.contains("-<") || raw.contains("/<")) return true;
        return false;
    }

    /**
     * 원시 BT수식M 텍스트를 HWP 수식 스크립트로 변환.
     */
    static String convertRawToHwpScript(String raw) {
        // r1par, r2par 등 풀이 단계 번호는 제거
        raw = raw.replaceAll("r\\d+par\\s*", "");

        // 단계별 변환
        String result = raw;

        // ^-XX^- → bar XX (overline) — 먼저 처리 (^가 다른 규칙에 영향)
        result = convertOverlines(result);

        // 키워드 치환 (긴 것부터)
        result = replaceKeywords(result);

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
        text = text.replace("-<", " <= ");
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
     * _x → _{x} (아래첨자)
     * & → (위치 리셋, 아래첨자 종료)
     */
    private static String convertPositionMarkers(String text) {
        StringBuilder sb = new StringBuilder();
        boolean inSubscript = false;
        int i = 0;

        while (i < text.length()) {
            char c = text.charAt(i);

            if (c == '_' && i + 1 < text.length()) {
                // 아래첨자 시작
                if (inSubscript) {
                    sb.append("} _{");
                } else {
                    sb.append(" _{");
                    inSubscript = true;
                }
                i++;
                // 아래첨자 내용 수집 (다음 & 또는 _ 또는 특수문자까지)
                while (i < text.length()) {
                    char sc = text.charAt(i);
                    if (sc == '&' || sc == '_') break;
                    // 괄호, 연산자 등에서 아래첨자 종료
                    if (sc == '(' || sc == ')' || sc == '=' || sc == '+' || sc == ' ') {
                        break;
                    }
                    sb.append(sc);
                    i++;
                }
                // 아래첨자 닫기 (& 또는 다음 토큰에서 자동 처리)
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
            } else if (c == '&') {
                // 위치 리셋 (아래첨자 종료)
                if (inSubscript) {
                    sb.append("}");
                    inSubscript = false;
                }
                i++;
            } else {
                sb.append(c);
                i++;
            }
        }

        if (inSubscript) {
            sb.append("}");
        }

        return sb.toString();
    }

    /**
     * 폰트별 특수 글리프 변환.
     */
    private static String convertGlyphs(String text) {
        // \ → TIMES (곱셈)
        text = text.replace("\\", " TIMES ");
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
        // _{a} _{b} → _{a b} (연속 아래첨자 병합)
        script = script.replaceAll("\\} _\\{", " ");
        // ^{a} ^{b} → ^{a b} (연속 위첨자 병합)
        script = script.replaceAll("\\} \\^\\{", " ");
        return script.trim();
    }
}
