package kr.dogfoot.hwpxlib.tool.equationconverter;

import kr.dogfoot.hwpxlib.tool.equationconverter.hwpscript.HwpScriptGenerator;
import kr.dogfoot.hwpxlib.tool.equationconverter.latex.LatexParser;
import kr.dogfoot.hwpxlib.tool.equationconverter.latex.LatexToken;
import kr.dogfoot.hwpxlib.tool.equationconverter.latex.LatexTokenizer;
import kr.dogfoot.hwpxlib.tool.equationconverter.latex.ast.AstNode;

import java.util.List;

public class LatexToHwpConverter {

    /**
     * LaTeX 수식 문자열을 HWP 수식 스크립트로 변환합니다.
     *
     * @param latex LaTeX 수식 문자열 ($..$ 구분자 없이)
     * @return HWP 수식 스크립트 문자열
     * @throws ConvertException LaTeX 수식을 파싱할 수 없는 경우
     */
    public static String convert(String latex) throws ConvertException {
        if (latex == null || latex.trim().length() == 0) {
            return "";
        }

        List<LatexToken> tokens = new LatexTokenizer(latex).tokenize();
        AstNode ast = new LatexParser(tokens).parse();
        return new HwpScriptGenerator().generate(ast);
    }
}
