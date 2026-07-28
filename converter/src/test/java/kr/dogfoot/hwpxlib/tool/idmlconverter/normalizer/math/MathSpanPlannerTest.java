package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.math;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTEquation;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTParagraph;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTextRun;
import org.junit.Assert;
import org.junit.Test;

public class MathSpanPlannerTest {

    @Test
    public void isolatedUppercaseEquationIsPlannedAsTextWithReason() {
        ASTParagraph paragraph = new ASTParagraph();
        ASTEquation initial = new ASTEquation("A", "EH_FONT");
        initial.preferredBaseUnit(900);
        initial.preferredFontFamily("본문");
        initial.textColor("#123456");
        paragraph.addItem(initial);

        MathSpanPlan plan = MathSpanPlanner.planConvertedItems(paragraph);

        Assert.assertEquals(1, plan.spans().size());
        Assert.assertEquals(MathSpanPlan.Classification.TEXT,
                plan.spans().get(0).classification());
        Assert.assertEquals(
                MathSpanPlanner.REASON_ISOLATED_SINGLE_LATIN_WITHOUT_MATH_CONTEXT,
                plan.spans().get(0).reason());
        Assert.assertTrue("planning must not mutate the AST",
                paragraph.items().get(0) instanceof ASTEquation);

        MathPlanConverter.materialize(paragraph, plan);

        Assert.assertTrue(paragraph.items().get(0) instanceof ASTTextRun);
        ASTTextRun text = (ASTTextRun) paragraph.items().get(0);
        Assert.assertEquals("A", text.text());
        Assert.assertEquals("Italic", text.fontStyle());
        Assert.assertEquals(Integer.valueOf(900), text.fontSizeHwpunits());
        Assert.assertEquals("본문", text.fontFamily());
        Assert.assertEquals("#123456", text.textColor());
    }

    @Test
    public void lowercaseSourceItalicAndAdjacentEquationsRemainMath() {
        ASTParagraph lowercase = new ASTParagraph();
        ASTEquation variable = new ASTEquation("a", "EH_FONT");
        variable.sourceItalic(true);
        lowercase.addItem(variable);
        Assert.assertTrue(MathSpanPlanner.planConvertedItems(lowercase).isEmpty());

        ASTParagraph adjacent = new ASTParagraph();
        adjacent.addItem(new ASTEquation("A", "EH_FONT"));
        ASTTextRun space = new ASTTextRun();
        space.text(" ");
        adjacent.addItem(space);
        adjacent.addItem(new ASTEquation("=1", "EH_FONT"));
        Assert.assertTrue(MathSpanPlanner.planConvertedItems(adjacent).isEmpty());
    }
}
