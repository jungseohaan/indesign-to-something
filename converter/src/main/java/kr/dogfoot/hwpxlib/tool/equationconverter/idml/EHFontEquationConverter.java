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

        // 재작성 3단계: Lex(글리프 의미 보존) → Parse(재귀하강 문법) → Emit.
        // 센티넬(box{~}·U+241C·U+0307)은 lexeme/노드로 내부화 → 후처리(restoreBoxBraces/
        // applyRecurringDecimalDots) 불필요.
        java.util.List<EHLexeme> lexemes = EHLexer.lex(runs);
        if (lexemes.isEmpty()) return null;

        EHNode.Group tree = EHParser.build(lexemes);
        if (tree == null) return null;

        return EHHwpScriptEmitter.emit(tree);
    }

    /**
     * HWP 수식 박스 명령의 중괄호 복원: box(~) → box{~}.
     *
     * <p>빈 답란 박스를 근호 안 radicand 로 넣을 때 box{~} 를 쓰는데(실측: 1단원 p32
     * √□), EH 토크나이저의 { → ( 치환이 box{~} 를 box(~) 로 바꿔 한글이 소괄호로
     * 렌더한다. box·dashbox 명령의 소괄호를 중괄호로 되돌린다.
     */
    private static String restoreBoxBraces(String script) {
        if (script == null || script.indexOf("box(") < 0) return script;
        return script.replace("box(~)", "box{~}")
                .replace("dashbox(~)", "dashbox{~}");
    }

    /**
     * 순환소수 순환마디 점(overdot) 센티넬(U+0307)을 HWP 수식 dot 구문으로 변환.
     *
     * <p>EHTokenizer 가 EH약물 H 를 결합 점 U+0307 로 바꿔 두면, 최종 스크립트에서
     * "0.̇4" 처럼 점 뒤에 숫자가 온다. 이를 "0.dot{4}" 로 만들어 4 위에 점이 찍히게
     * 한다(실측: 1단원 0.H4=0.4̇, 1.H3=1.3̇).
     */
    private static String applyRecurringDecimalDots(String script) {
        if (script == null || script.indexOf('̇') < 0) return script;
        StringBuilder sb = new StringBuilder(script.length());
        for (int i = 0; i < script.length(); i++) {
            char c = script.charAt(i);
            if (c == '̇') {
                // 센티넬 뒤 첫 숫자를 dot{N} 으로. 사이 공백은 건너뛴다.
                int j = i + 1;
                while (j < script.length() && script.charAt(j) == ' ') j++;
                if (j < script.length() && Character.isDigit(script.charAt(j))) {
                    sb.append("dot{").append(script.charAt(j)).append('}');
                    i = j;
                }
                // 뒤에 숫자가 없는 고아 마커는 버린다(런 경계에서 잘린 경우).
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
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

            // 유효하지 않은 XML 문자 제거 (U+0008 Indent to Here, U+FFFC 인라인 앵커 등)
            if (c < 0x20 && c != '\t' && c != '\n' && c != '\r') { i++; continue; }
            if (c == '\uFFFC') { i++; continue; }
            // 근호 종료+항 간격 센티넬(U+241C) — radicand 경계 표시이자, 원본에서 항
            // 사이 간격을 만들던 투명 스페이서 Rectangle(28.3pt) 자리. 다른 근호 나열
            // 문단과 동일하게 EM SPACE(U+2003)로 간격을 살린다(실측: 1단원 p22
            // √15 √0.81 √(9/144) √7-2 3+√16 사이 간격).
            if (c == '\u241C') { sb.append('\u2003'); i++; continue; }
            // backtick(0x60) = EH상부자 위첨자 마커 (thin space) → 수식에서 제거
            if (c == '`') { i++; continue; }

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
            // EH상부자 raw 위첨자 글리프(decodeSubSupGlyph 미적용 경로): Û→², Ü→³.
            // 본문 폰트에 섞인 EH 인코딩이 EH 변환 경로를 못 타면 여기로 온다
            // (실측: 3단원 p104 y=xÛ 이 y=x² 로 안 바뀜).
            if (c == 0x00DB) { // Û → ²
                sb.append("^{2}");
                i++;
                continue;
            }
            if (c == 0x00DC) { // Ü → ³
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

        // 정리: 연속 공백은 단일 공백으로. 단, 탭(\t)은 선택지 구분자이므로 보존한다
        // (실측: 3단원 ⑶ f(1/2) \t\t ⑷ f(2) 의 항 사이 탭이 공백으로 뭉개짐).
        // 탭 아닌 공백류만 합치고, 탭 주변의 잉여 공백은 제거한다.
        String s = sb.toString().replaceAll("[^\\S\\t]+", " ");
        s = s.replaceAll(" *\\t *", "\t"); // 탭 주변 공백 정리
        return s.trim();
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
            case '\u00D6': return "div";         // Ö (EH상부자 raw, decodeSubSupGlyph 미적용 시)
            case '\u2103': return "DEG C";       // ℃
            case '\u00B0': return "DEG";         // °
            case '\u00F9': return "DEG";         // ù raw 도, decodeSubSupGlyph 미적용 시
            case '\u00B5': return "\u2312";      // µ raw 호(⌒)
            case '\u00AA': return "equiv";       // ª raw 합동
            case '\u2312': return "\u2312";      // ⌒ 호(그대로)
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
            case '\u2190': return "larrow";     // ←
            case '\u2192': return "rarrow";     // →
            case '\u2194': return "lrarrow";    // ↔
            case '\u21D2': return "rarrow";     // ⇒
            case '\u21D4': return "lrarrow";    // ⇔
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
