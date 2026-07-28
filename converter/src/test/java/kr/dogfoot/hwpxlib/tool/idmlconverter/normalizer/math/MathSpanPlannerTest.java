package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.math;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTEquation;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTParagraph;
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
}
