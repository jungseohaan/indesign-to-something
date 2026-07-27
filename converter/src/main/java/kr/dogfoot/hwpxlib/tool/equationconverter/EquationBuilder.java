package kr.dogfoot.hwpxlib.tool.equationconverter;

import kr.dogfoot.hwpxlib.object.content.section_xml.enumtype.EquationLineMode;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.object.Equation;
import kr.dogfoot.hwpxlib.tool.equationconverter.idml.EHFontGlyphMap;

public class EquationBuilder {
    private static final String DEFAULT_VERSION = "Equation Version 60";
    private static final String DEFAULT_TEXT_COLOR = "#000000";
    private static final int DEFAULT_BASE_UNIT = 1100;
    private static final String DEFAULT_FONT = "HYhwpEQ";
    private static final EquationLineMode DEFAULT_LINE_MODE = EquationLineMode.CHAR;

    /**
     * LaTeX 수식 문자열로부터 HWPX Equation 객체를 생성합니다.
     *
     * @param latex LaTeX 수식 문자열
     * @return 기본값이 설정된 Equation 객체
     * @throws ConvertException LaTeX 변환 실패 시
     */
    public static Equation fromLatex(String latex) throws ConvertException {
        String hwpScript = LatexToHwpConverter.convert(latex);
        return buildEquation(hwpScript);
    }

    /**
     * MathML 수식 문자열로부터 HWPX Equation 객체를 생성합니다.
     *
     * @param mathml MathML(Presentation) 수식 문자열
     * @return 기본값이 설정된 Equation 객체
     * @throws ConvertException MathML 변환 실패 시
     */
    public static Equation fromMathML(String mathml) throws ConvertException {
        String hwpScript = MathMLToHwpConverter.convert(mathml);
        return buildEquation(hwpScript);
    }

    /**
     * HWP 수식 스크립트 문자열로부터 Equation 객체를 생성합니다.
     *
     * @param hwpScript HWP 수식 스크립트 문자열
     * @return 기본값이 설정된 Equation 객체
     */
    public static Equation fromHwpScript(String hwpScript) {
        return buildEquation(sanitizeHwpScript(hwpScript));
    }

    private static Equation buildEquation(String hwpScript) {
        hwpScript = sanitizeHwpScript(hwpScript);
        Equation equation = new Equation();
        equation.versionAnd(DEFAULT_VERSION)
                .textColorAnd(DEFAULT_TEXT_COLOR)
                .baseUnitAnd(DEFAULT_BASE_UNIT)
                .lineModeAnd(DEFAULT_LINE_MODE)
                .fontAnd(DEFAULT_FONT);
        equation.createScript();
        equation.script().addText(hwpScript);
        return equation;
    }

    public static String sanitizeHwpScript(String hwpScript) {
        if (hwpScript == null || hwpScript.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder(hwpScript.length());
        for (int i = 0; i < hwpScript.length(); ) {
            int cp = hwpScript.codePointAt(i);
            i += Character.charCount(cp);
            if (cp == 0x001A) {
                out.append(" rarrow ");
            } else if (isXml10Character(cp)) {
                out.appendCodePoint(cp);
            }
        }
        String result = out.toString()
                .replace('\t', ' ')
                .replace('\r', ' ')
                .replace('\f', ' ')
                .replace('\u000B', ' ')
                .replaceAll(" +", " ")
                .trim();
        result = normalizeStrayEHGlyphs(result);
        result = decodeResidualGrepFraction(result);
        result = ejectUnitFromRadicand(result);
        return ensureSqrtBoundary(result);
    }

    /**
     * HWP 수식 예약어 sqrt 앞에 경계 공백을 보장한다.
     *
     * <p>sqrt 가 앞 토큰에 붙으면(예: asqrt{4b}, 2sqrt{5}, TIMESsqrt{2}) 한글 수식
     * 파서가 sqrt 를 예약어로 인식하지 못해 근호 대신 리터럴 "sqrt" 로 렌더링된다
     * (실측: 수학 1단원 asqrt/bsqrt). EH/BT/NP 세 폰트 경로가 모두 이 sanitize
     * 관문을 거치므로 여기서 공통 보정한다.
     *
     * <p>앞 문자가 단어문자/닫는 괄호(}, ), ])일 때만 공백을 넣는다. 이미 공백이거나
     * 문자열 시작이면 건드리지 않는다.
     */
    private static String ensureSqrtBoundary(String script) {
        if (script == null || script.indexOf("sqrt") < 0) return script;
        // 앞이 단어문자/닫는 괄호면 공백 삽입.
        script = script.replaceAll("(?<=[\\p{Alnum}}\\])])sqrt", " sqrt");
        // 첨자 연산자(_ ^) 바로 뒤의 sqrt 는 중괄호 없는 첨자 대상이 될 수 없다.
        // "2_sqrt{3}" 같은 잘못된 결합은 곱셈(2√3)이 의도이므로 공백으로 분리한다.
        script = script.replaceAll("(?<=[_^])sqrt", " sqrt");
        // 빈 근호 뒤로 밀린 분수 피근호를 근호 안으로 흡수한다.
        // "sqrt{ }{A} over {B}" → "sqrt{{A} over {B}}" (실측: 수학 1단원 a√(4b/a)).
        script = script.replaceAll(
                "sqrt\\{ \\}(\\{[^{}]*\\}) over (\\{[^{}]*\\})",
                "sqrt{$1 over $2}");
        return script.replaceAll(" +", " ");
    }

    /**
     * EH 폰트 해킹 라틴-1 글리프가 수식 스크립트에 새어 든 것을 마지막 관문에서 정리한다
     * (SPEC-081). EH/BT/NP/INLINE_FRACTION 경로가 각기 다른 디코더를 쓰다 보니 문맥 전환
     * 지점의 글리프가 디코딩을 못 타고 raw 로 남는다. 이 글리프들은 정상 HWP 수식엔
     * 나올 수 없으므로(0x80–0xFF 라틴-1 확장) 여기서 의미 기호로 통일 치환한다.
     * <ul>
     *   <li>{@code ù}(0xF9)·{@code ¿}(0xBF) → {@code °} (도). 실측: 4단원 90ù, sin~60ù</li>
     *   <li>{@code Ó}(0xD3)·{@code Û}(0xDB) → {@code ^{2}} (제곱). 실측: 4단원 BCÓ, ACÓ</li>
     *   <li>{@code Ü}(0xDC) → {@code ^{3}} (세제곱)</li>
     *   <li>{@code Ñ}(0xD1) → {@code +-} (±). 실측: 2단원 근의공식 -bÑsqrt</li>
     *   <li>{@code Ã}(0xC3) → 제거 (근호 폭선택자 장식). 실측: 2단원 sqrt&#123;Ãb2-4ac&#125;</li>
     * </ul>
     * {@code sqrt&#123;Ó&#125;} 처럼 제곱 글리프가 근호 radicand 로 잘못 묶인 경우
     * {@code sqrt&#123;...&#125;} 를 벗기고 지수로 되돌린다.
     */
    private static String normalizeStrayEHGlyphs(String s) {
        if (s == null || s.isEmpty()) return s;
        // ^{V} = EH상부자 곱셈(×) 글리프 V 가 위첨자로 오해된 것. 전수 조사(SPEC-081)에서
        // ^{V} 45건은 전부 피연산자 사이 곱셈(2^{V}3=2×3, 8^{V}12=96, 10^{V}sin~60°,
        // 삼각형 넓이 ½^{V}4^{V}6). 곱셈 기호로 통일한다.
        if (s.contains("^{V}")) {
            s = s.replace("^{V}", " TIMES ");
        }
        boolean has = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x80 && c <= 0xFF) { has = true; break; }
        }
        if (!has) return s;
        // 제곱 글리프가 근호 안에 단독으로 잘못 묶인 경우(√{Ó}) 벗겨 지수로.
        s = s.replaceAll("sqrt\\{\\s*[ÓÛ]\\s*\\}", "^{2}");
        s = s.replaceAll("sqrt\\{\\s*Ü\\s*\\}", "^{3}");
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case 'ù': case '¿': b.append('°'); break;      // 도
                case 'Ó': case 'Û': b.append("^{2}"); break;   // 제곱
                case 'Ü': b.append("^{3}"); break;              // 세제곱
                case 'Ñ': b.append("+-"); break;                // ±
                case 'Ã': break;                                 // 폭선택자 장식 → 제거
                default:
                    // 정상 수식 기호(°·±·×·÷ 등)는 보존. 그 외 EH 해킹 라틴-1 대문자
                    // 확장(0xC0–0xFF 중 미매핑)은 장식/스페이서이므로 제거.
                    b.append(c);
            }
        }
        return b.toString().replaceAll(" +", " ");
    }

    /**
     * GREP 분수 원문(;inner;)이 디코딩을 못 타고 스크립트에 남은 것을 마지막 관문에서
     * {num} over {denom} 으로 되살린다 (SPEC-081 클래스 E). 경사(y=-;4!;x=y=-1/4 x)·
     * 나열(;4!;,~0,~-2) 등 GREP 분수를 EH 경로 밖에서 만난 경우다. decodeFractionInner
     * 로 분자/분모를 복원한다.
     */
    private static String decodeResidualGrepFraction(String s) {
        if (s == null || s.indexOf(';') < 0) return s;
        StringBuilder out = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == ';') {
                int close = s.indexOf(';', i + 1);
                if (close > i) {
                    String inner = s.substring(i + 1, close);
                    int len = inner.length();
                    if (len >= 1 && len <= 10) {
                        String[] nd = EHFontGlyphMap.decodeFractionInner(inner);
                        if (nd != null && nd.length == 2
                                && !nd[0].isEmpty() && !nd[1].isEmpty()) {
                            out.append("{").append(nd[0]).append("} over {")
                               .append(nd[1]).append("}");
                            i = close + 1;
                            continue;
                        }
                    }
                }
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    /**
     * 근호 radicand 안에 단위(cm·mm·m…)가 딸려 들어간 것을 근호 밖으로 밀어낸다
     * (SPEC-081, 실측 p19 √3 cm 이 sqrt&#123;3cm&#125; 로). √는 수치에만 걸리고 단위는
     * 밖이다: sqrt&#123;3cm&#125; → sqrt&#123;3&#125; cm. 단위 뒤가 } 나 문자열 끝이어야
     * 오적용을 막는다(radicand 안의 변수 m 등은 건드리지 않음).
     */
    private static String ejectUnitFromRadicand(String s) {
        if (s == null || s.indexOf("sqrt{") < 0) return s;
        // sqrt{<수치><단위>} → sqrt{<수치>} <단위>. 수치는 숫자·소수점만(변수 없는 경우).
        return s.replaceAll(
                "sqrt\\{(\\d+(?:\\.\\d+)?)(cm|mm|km|kg|m|g|L|t)\\}",
                "sqrt{$1} $2");
    }

    private static boolean isXml10Character(int cp) {
        return cp == 0x9
                || cp == 0xA
                || cp == 0xD
                || (cp >= 0x20 && cp <= 0xD7FF)
                || (cp >= 0xE000 && cp <= 0xFFFD)
                || (cp >= 0x10000 && cp <= 0x10FFFF);
    }
}
