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
        return out.toString()
                .replace('\t', ' ')
                .replace('\r', ' ')
                .replace('\f', ' ')
                .replace('\u000B', ' ')
                .replaceAll(" +", " ")
                .trim();
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
