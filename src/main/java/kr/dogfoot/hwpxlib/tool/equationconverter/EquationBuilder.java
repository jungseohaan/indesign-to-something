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
        return buildEquation(hwpScript);
    }

    private static Equation buildEquation(String hwpScript) {
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
}
