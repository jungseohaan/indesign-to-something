package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.math;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTEquation;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTParagraph;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTextRun;
import org.junit.Assert;
import org.junit.Test;

public class MathSpanPlannerTest {

    // SPEC-084: 고립 단일 라틴 강등 폐기 — 인접 수식이 없는 단일 대문자 수식도
    // finalize(CONVERTED_ITEMS)를 거쳐 수식으로 유지된다 (기하 점 라벨 "점 A").
    @Test
    public void isolatedUppercaseEquationRemainsMathAfterFinalize() {
        ASTParagraph paragraph = new ASTParagraph();
        ASTEquation initial = new ASTEquation("A", "EH_FONT");
        paragraph.addItem(initial);

        MathPipeline.finalizeParagraph(paragraph, MathPipeline.SpanPolicy.CONVERTED_ITEMS);

        Assert.assertTrue(paragraph.items().get(0) instanceof ASTEquation);
        Assert.assertEquals("A",
                ((ASTEquation) paragraph.items().get(0)).hwpScript());
    }

    @Test
    public void mergedNpAlgebraRunBecomesEquation() {
        ASTParagraph paragraph = new ASTParagraph();
        ASTTextRun run = new ASTTextRun();
        run.text("2x+y=9");
        run.fontFamily("NP_IE");
        paragraph.addItem(run);

        MathPipeline.finalizeParagraph(paragraph, MathPipeline.SpanPolicy.SOURCE_TEXT);

        Assert.assertTrue(paragraph.items().get(0) instanceof ASTEquation);
        Assert.assertEquals("2x+y=9",
                ((ASTEquation) paragraph.items().get(0)).hwpScript());
    }

    @Test
    public void algebraTextWithoutSourceTypographyRemainsText() {
        ASTParagraph paragraph = new ASTParagraph();
        ASTTextRun run = new ASTTextRun();
        run.text("ModelX=9");
        run.fontFamily("Helvetica");
        paragraph.addItem(run);

        MathPipeline.finalizeParagraph(paragraph, MathPipeline.SpanPolicy.SOURCE_TEXT);

        Assert.assertTrue(paragraph.items().get(0) instanceof ASTTextRun);
    }

    @Test
    public void npGrepDigitTokenBecomesEquation() {
        ASTParagraph paragraph = new ASTParagraph();
        ASTTextRun run = new ASTTextRun();
        run.text("25");
        run.fontFamily("NP_IE");
        run.grepMathFont(true);
        paragraph.addItem(run);

        MathPipeline.finalizeParagraph(paragraph, MathPipeline.SpanPolicy.SOURCE_TEXT);

        Assert.assertTrue(paragraph.items().get(0) instanceof ASTEquation);
        Assert.assertEquals("25",
                ((ASTEquation) paragraph.items().get(0)).hwpScript());
    }
}
