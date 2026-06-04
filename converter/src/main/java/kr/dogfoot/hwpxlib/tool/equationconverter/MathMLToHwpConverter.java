package kr.dogfoot.hwpxlib.tool.equationconverter;

import kr.dogfoot.hwpxlib.tool.equationconverter.hwpscript.HwpScriptGenerator;
import kr.dogfoot.hwpxlib.tool.equationconverter.latex.ast.AstNode;
import kr.dogfoot.hwpxlib.tool.equationconverter.mathml.MathMLParser;

/**
 * MathML (Presentation MathML) 수식 문자열을 HWP 수식 스크립트로 변환한다.
 *
 * <pre>
 * String hwpScript = MathMLToHwpConverter.convert("&lt;math&gt;&lt;mfrac&gt;&lt;mi&gt;a&lt;/mi&gt;&lt;mi&gt;b&lt;/mi&gt;&lt;/mfrac&gt;&lt;/math&gt;");
 * // → "{a} over {b}"
 * </pre>
 */
public class MathMLToHwpConverter {

    /**
     * MathML 문자열을 HWP 수식 스크립트로 변환한다.
     *
     * @param mathml MathML(Presentation) 문자열
     * @return HWP 수식 스크립트 문자열
     * @throws ConvertException MathML 파싱 또는 변환 실패 시
     */
    public static String convert(String mathml) throws ConvertException {
        if (mathml == null || mathml.trim().length() == 0) {
            return "";
        }
        AstNode ast = new MathMLParser().parse(mathml);
        return new HwpScriptGenerator().generate(ast);
    }
}
