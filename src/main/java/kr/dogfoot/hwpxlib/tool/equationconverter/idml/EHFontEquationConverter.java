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

        // 3단계 파이프라인: Tokenize → Build IR → Emit hwpScript
        List<EHToken> tokens = EHTokenizer.tokenize(runs);
        if (tokens.isEmpty()) return null;

        EHNode.Group tree = EHIRBuilder.build(tokens);
        if (tree == null) return null;

        return EHHwpScriptEmitter.emit(tree);
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
            case '_': return "TIMES";             // EH상부자 _ → × (곱셈 기호)
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

}
