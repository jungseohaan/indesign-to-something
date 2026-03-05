package kr.dogfoot.hwpxlib.tool.equationconverter.idml;

import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLCharacterRun;

import java.util.List;

/**
 * EH 수식 폰트 체계의 텍스트를 HWP 수식 스크립트로 변환한다.
 * <p>
 * EH 폰트는 수학교과서(비상교육)에서 사용되는 수식 전용 폰트.
 * 각 폰트 변형(상부자/하부자/수식/분수/루트)의 역할에 따라
 * 글리프를 디코딩하고 HWP 수식 구문으로 조합한다.
 * <p>
 * 예: EH수식 "y=ax" + EH상부자 "Û" + EH수식 "+bx+c"
 * → 디코딩 → "y=ax" + "^{2}" + "+bx+c"
 * → HWP 스크립트: "y=ax^{2}+bx+c"
 */
public class EHFontEquationConverter {

    /**
     * 연속된 EH 수식 폰트 런 그룹을 HWP 수식 스크립트로 변환한다.
     * <p>
     * EH상부자/하부자에서는 문자별로 처리:
     * - 기본 범위(0x20-0x7F): 일반 수식 텍스트 (e.g., "x", "-3")
     * - 확장 범위(0x80-0xFF): 위첨자/아래첨자 글리프 (e.g., Û→^{2})
     * - 백틱(0x60): EH 인코딩의 불가시 여백 글리프 → 제거
     *
     * @param runs 연속된 CharacterRun 목록 (EH 폰트 + 인접 일반 텍스트)
     * @return HWP 수식 스크립트 문자열, 또는 의미 없는 수식이면 null
     */
    public static String convert(List<IDMLCharacterRun> runs) {
        if (runs == null || runs.isEmpty()) return null;

        StringBuilder sb = new StringBuilder();
        for (IDMLCharacterRun run : runs) {
            String text = run.content();
            if (text == null || text.isEmpty()) continue;

            String fontFamily = run.fontFamily();

            if (EHFontGlyphMap.isRootFont(fontFamily)
                    || EHFontGlyphMap.isLineFont(fontFamily)) {
                // 루트/선모음 → 시각적 장식이므로 스킵
                continue;
            }

            if (EHFontGlyphMap.isSuperscriptFont(fontFamily)
                    || EHFontGlyphMap.isSubscriptFont(fontFamily)) {
                // 상부자/하부자: 문자별로 기본/확장 범위 분리 처리
                convertSubSupRun(text, fontFamily, sb);
            } else if (EHFontGlyphMap.isFractionNumeratorFont(fontFamily)) {
                String decoded = EHFontGlyphMap.decodeText(text, fontFamily);
                if (decoded != null && !decoded.isEmpty()) {
                    sb.append("{").append(convertToHwpScript(decoded)).append("}");
                    sb.append(" over ");
                }
            } else if (EHFontGlyphMap.isFractionDenominatorFont(fontFamily)) {
                String decoded = EHFontGlyphMap.decodeText(text, fontFamily);
                if (decoded != null && !decoded.isEmpty()) {
                    sb.append("{").append(convertToHwpScript(decoded)).append("}");
                }
            } else if (run.isEHFont()) {
                // EH수식/EH약물 등: 글리프 디코딩 후 일반 수식 텍스트
                String decoded = EHFontGlyphMap.decodeText(text, fontFamily);
                if (decoded != null && !decoded.isEmpty()) {
                    sb.append(convertToHwpScript(decoded));
                }
            } else if (EHFontGlyphMap.containsEHEncodedChars(text)) {
                // 폰트 미지정이지만 EH 인코딩 패턴 포함 → 상부자로 처리
                convertSubSupRun(text, "EH상부자", sb);
            } else if (EHFontGlyphMap.containsEHFractionPattern(text)) {
                // 폰트 미지정이지만 ;...; 분수 GREP 패턴 포함 → 분수 변환
                convertFractionPatternRun(text, sb);
            } else {
                // 비EH 브릿지 런: 패스스루
                sb.append(convertToHwpScript(text));
            }
        }

        String result = sb.toString().trim();
        if (result.isEmpty()) return null;

        // 순수 한국어만이면 수식 아님
        if (isOnlyKorean(result)) return null;

        // 글자나 숫자가 없으면 수식 아님
        boolean hasLetterOrDigit = false;
        for (int i = 0; i < result.length(); i++) {
            char ch = result.charAt(i);
            if (Character.isLetterOrDigit(ch)) { hasLetterOrDigit = true; break; }
            if (ch >= 0x2460 && ch <= 0x2473) { hasLetterOrDigit = true; break; }
        }
        if (!hasLetterOrDigit) return null;

        return result;
    }

    /**
     * EH상부자/하부자 런을 문자별로 처리.
     * 확장 범위(0x80-0xFF) 연속 문자 → 위첨자/아래첨자 그룹.
     * 기본 범위(0x20-0x7F) → 일반 수식 텍스트.
     * 백틱(0x60) → EH 인코딩 여백 글리프, 제거.
     */
    private static void convertSubSupRun(String text, String fontFamily,
                                           StringBuilder sb) {
        boolean isSuper = EHFontGlyphMap.isSuperscriptFont(fontFamily);
        String wrap = isSuper ? "^" : "_";

        StringBuilder extBuf = new StringBuilder(); // 확장 범위 문자 버퍼
        StringBuilder baseBuf = new StringBuilder(); // 기본 범위 문자 버퍼

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);

            // 백틱(0x60) = EH 불가시 여백 글리프 → 스킵
            if (c == 0x60) continue;

            if (c >= 0x80) {
                // 확장 범위 → 먼저 기본 범위 버퍼 플러시
                if (baseBuf.length() > 0) {
                    sb.append(convertToHwpScript(baseBuf.toString()));
                    baseBuf.setLength(0);
                }
                // 디코딩하여 확장 버퍼에 추가
                char decoded = EHFontGlyphMap.decodeSubSupGlyph(c);
                if (decoded != c) {
                    extBuf.append(decoded);
                }
                // 매핑 없는 확장 문자는 스킵
            } else {
                // 기본 범위 → 먼저 확장 범위 버퍼 플러시
                if (extBuf.length() > 0) {
                    sb.append(wrap).append("{")
                      .append(convertToHwpScript(extBuf.toString()))
                      .append("}");
                    extBuf.setLength(0);
                }
                baseBuf.append(c);
            }
        }

        // 남은 버퍼 플러시
        if (extBuf.length() > 0) {
            sb.append(wrap).append("{")
              .append(convertToHwpScript(extBuf.toString()))
              .append("}");
        }
        if (baseBuf.length() > 0) {
            sb.append(convertToHwpScript(baseBuf.toString()));
        }
    }

    /**
     * 폰트 미지정 런에서 EH 분수 GREP 패턴 (;...;) 을 감지하여 변환.
     * ParagraphStyle의 GREP 규칙이 분수소문자 폰트를 동적 적용하는 경우,
     * IDML에는 폰트 정보 없이 ;numerator_denominator; 패턴만 남는다.
     * <p>
     * 분수 인코딩: ; + [분자/분모 혼합 문자] + ;
     * - 분자 숫자: !@#$%^&*() → 1234567890 (Shift-digit)
     * - 분자 소문자: A-Z → a-z
     * - 분모 숫자: 0-9 → 그대로
     * - 분모 소문자: a-z → 그대로
     * - 구조: ; = 분수선, [] {} = 괄호(스킵)
     */
    private static void convertFractionPatternRun(String text, StringBuilder sb) {
        int i = 0;
        while (i < text.length()) {
            // ;...; 패턴 탐색
            int semiStart = text.indexOf(';', i);
            if (semiStart < 0) {
                // 남은 텍스트는 일반 수식으로 처리
                sb.append(convertToHwpScript(text.substring(i)));
                break;
            }

            // ; 이전 텍스트 처리
            if (semiStart > i) {
                sb.append(convertToHwpScript(text.substring(i, semiStart)));
            }

            // 두 번째 ; 찾기
            int semiEnd = text.indexOf(';', semiStart + 1);
            if (semiEnd < 0) {
                // 닫는 ; 없으면 나머지 전부 일반 처리
                sb.append(convertToHwpScript(text.substring(semiStart)));
                break;
            }

            // ;...; 내용 추출 및 분수 디코딩
            String inner = text.substring(semiStart + 1, semiEnd);
            String[] fracParts = EHFontGlyphMap.decodeFractionInner(inner);
            if (fracParts != null && (fracParts[0].length() > 0 || fracParts[1].length() > 0)) {
                String numer = fracParts[0];
                String denom = fracParts[1];
                if (numer.length() > 0 && denom.length() > 0) {
                    sb.append("{").append(convertToHwpScript(numer)).append("}")
                      .append(" over ")
                      .append("{").append(convertToHwpScript(denom)).append("}");
                } else if (numer.length() > 0) {
                    // 분자만 있는 경우 (분모 없음)
                    sb.append("{").append(convertToHwpScript(numer)).append("} over { }");
                } else {
                    // 분모만 있는 경우
                    sb.append("{ } over {").append(convertToHwpScript(denom)).append("}");
                }
            } else {
                // 디코딩 실패 → 원본 패스스루
                sb.append(';').append(inner).append(';');
            }

            i = semiEnd + 1;
        }
    }

    /**
     * 유니코드 텍스트를 HWP 수식 스크립트로 변환.
     * 유니코드 수학 기호를 HWP 키워드로 매핑한다.
     */
    public static String convertToHwpScript(String raw) {
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < raw.length()) {
            char c = raw.charAt(i);

            // 유니코드 수학 기호 → HWP 키워드
            String mapped = mapUnicodeToHwp(c);
            if (mapped != null) {
                sb.append(' ').append(mapped).append(' ');
                i++;
                continue;
            }

            // 유니코드 첨자 → HWP 첨자 구문
            if (c >= 0x2080 && c <= 0x2089) {
                // 아래첨자 숫자 ₀-₉
                sb.append("_{");
                while (i < raw.length() && raw.charAt(i) >= 0x2080 && raw.charAt(i) <= 0x2089) {
                    sb.append((char) ('0' + (raw.charAt(i) - 0x2080)));
                    i++;
                }
                sb.append("}");
                continue;
            }
            if (c >= 0x2070 && c <= 0x2079 && c != 0x2071) {
                sb.append("^{");
                while (i < raw.length()) {
                    char sc = raw.charAt(i);
                    if (sc >= 0x2074 && sc <= 0x2079) {
                        sb.append((char) ('0' + (sc - 0x2070)));
                        i++;
                    } else if (sc == 0x2070) {
                        sb.append('0');
                        i++;
                    } else {
                        break;
                    }
                }
                sb.append("}");
                continue;
            }
            if (c == 0x00B2) { // ²
                sb.append("^{2}");
                i++;
                continue;
            }
            if (c == 0x00B3) { // ³
                sb.append("^{3}");
                i++;
                continue;
            }
            if (c == 0x00B9) { // ¹
                sb.append("^{1}");
                i++;
                continue;
            }

            // 유니코드 그리스 소문자 → HWP 그리스 문자 키워드
            String greek = mapGreekToHwp(c);
            if (greek != null) {
                sb.append(' ').append(greek).append(' ');
                i++;
                continue;
            }

            // 일반 문자 패스스루
            sb.append(c);
            i++;
        }

        // 정리: 연속 공백
        return sb.toString().replaceAll("\\s+", " ").trim();
    }

    /**
     * 유니코드 수학 기호를 HWP 수식 키워드로 매핑.
     */
    private static String mapUnicodeToHwp(char c) {
        switch (c) {
            case '\u00B1': return "+-";         // ±
            case '\u00D7': return "TIMES";       // ×
            case '\u00F7': return "div";         // ÷
            case '\u2103': return "DEG C";       // ℃
            case '\u00B0': return "DEG";         // °
            case '\u2260': return "!=";          // ≠
            case '\u2264': return "LEQ";         // ≤
            case '\u2265': return "GEQ";         // ≥
            case '\u221A': return "sqrt";        // √
            case '\u221E': return "INF";         // ∞
            case '\u2208': return "IN";          // ∈
            case '\u2209': return "notin";       // ∉
            case '\u2282': return "SUBSET";      // ⊂
            case '\u2283': return "SUPSET";      // ⊃
            case '\u2286': return "SUBSETEQ";    // ⊆
            case '\u2287': return "SUPSETEQ";    // ⊇
            case '\u222A': return "CUP";         // ∪
            case '\u2229': return "CAP";         // ∩
            case '\u2227': return "land";        // ∧
            case '\u2228': return "lor";         // ∨
            case '\u2200': return "forall";      // ∀
            case '\u2203': return "exists";      // ∃
            case '\u2234': return "therefore";   // ∴
            case '\u2235': return "because";     // ∵
            case '\u222B': return "int";         // ∫
            case '\u2211': return "sum";         // Σ (summation)
            case '\u220F': return "prod";        // ∏ (product)
            case '\u2026': return "CDOTS";       // …
            case '\u22EF': return "CDOTS";       // ⋯
            case '\u22EE': return "VDOTS";       // ⋮
            case '\u22F1': return "DDOTS";       // ⋱
            case '\u2190': return "LEFT";        // ←
            case '\u2192': return "RIGHT";       // →
            case '\u2194': return "LEFTRIGHTARROW"; // ↔
            case '\u21D2': return "DARROW";      // ⇒
            case '\u21D4': return "DLARROW";     // ⇔
            case '\u2220': return "angle";       // ∠
            case '\u22A5': return "bot";         // ⊥
            case '\u2225': return "parallel";    // ∥
            case '\u2261': return "equiv";       // ≡
            case '\u2248': return "approx";      // ≈
            case '\u221D': return "propto";      // ∝
            default: return null;
        }
    }

    /**
     * 유니코드 그리스 문자를 HWP 수식 키워드로 매핑.
     */
    private static String mapGreekToHwp(char c) {
        switch (c) {
            // 소문자
            case '\u03B1': return "alpha";
            case '\u03B2': return "beta";
            case '\u03B3': return "gamma";
            case '\u03B4': return "delta";
            case '\u03B5': return "epsilon";
            case '\u03B6': return "zeta";
            case '\u03B7': return "eta";
            case '\u03B8': return "theta";
            case '\u03B9': return "iota";
            case '\u03BA': return "kappa";
            case '\u03BB': return "lambda";
            case '\u03BC': return "mu";
            case '\u03BD': return "nu";
            case '\u03BE': return "xi";
            case '\u03BF': return "omicron";
            case '\u03C0': return "pi";
            case '\u03C1': return "rho";
            case '\u03C3': return "sigma";
            case '\u03C4': return "tau";
            case '\u03C5': return "upsilon";
            case '\u03C6': return "phi";
            case '\u03C7': return "chi";
            case '\u03C8': return "psi";
            case '\u03C9': return "omega";
            // 대문자
            case '\u0391': return "ALPHA";
            case '\u0392': return "BETA";
            case '\u0393': return "GAMMA";
            case '\u0394': return "DELTA";
            case '\u0398': return "THETA";
            case '\u039B': return "LAMBDA";
            case '\u03A0': return "PI";
            case '\u03A3': return "SIGMA";
            case '\u03A6': return "PHI";
            case '\u03A8': return "PSI";
            case '\u03A9': return "OMEGA";
            default: return null;
        }
    }

    /**
     * 한국어만 포함된 텍스트인지 확인.
     */
    private static boolean isOnlyKorean(String text) {
        boolean hasKorean = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c >= 0xAC00 && c <= 0xD7AF || c >= 0x3131 && c <= 0x318E) {
                hasKorean = true;
            } else if (Character.isLetterOrDigit(c)) {
                return false;
            } else if ("+-*/=<>()[]{}|!".indexOf(c) >= 0) {
                return false;
            }
        }
        return hasKorean;
    }

}
