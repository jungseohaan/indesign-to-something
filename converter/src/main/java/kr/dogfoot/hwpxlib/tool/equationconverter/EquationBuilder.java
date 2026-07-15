package kr.dogfoot.hwpxlib.tool.equationconverter;

import kr.dogfoot.hwpxlib.object.content.section_xml.enumtype.EquationLineMode;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.object.Equation;

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

    private static boolean isXml10Character(int cp) {
        return cp == 0x9
                || cp == 0xA
                || cp == 0xD
                || (cp >= 0x20 && cp <= 0xD7FF)
                || (cp >= 0xE000 && cp <= 0xFFFD)
                || (cp >= 0x10000 && cp <= 0x10FFFF);
    }
}
