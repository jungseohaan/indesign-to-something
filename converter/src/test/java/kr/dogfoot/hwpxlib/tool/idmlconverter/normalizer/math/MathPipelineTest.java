package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.math;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTEquation;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTParagraph;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTextRun;
import org.junit.Assert;
import org.junit.Test;

public class MathPipelineTest {

    @Test
    public void sourcePolicyRunsClassificationBeforeStructureMaterialization() {
        ASTParagraph paragraph = new ASTParagraph();
        ASTTextRun variable = text("a");
        variable.fontStyle("Italic");
        paragraph.addItem(variable);
        paragraph.addItem(text("의 넓이는 3cm"));
        paragraph.addItem(new ASTEquation("^{2}", "EH_FONT"));

        MathPipeline.finalizeParagraph(
                paragraph, MathPipeline.SpanPolicy.SOURCE_TEXT);

        Assert.assertTrue(paragraph.items().get(0) instanceof ASTEquation);
        Assert.assertEquals("a",
                ((ASTEquation) paragraph.items().get(0)).hwpScript());
        Assert.assertEquals("의 넓이는 3",
                ((ASTTextRun) paragraph.items().get(1)).text());
        Assert.assertEquals("rm cm^{2}",
                ((ASTEquation) paragraph.items().get(2)).hwpScript());
    }

    @Test
    public void convertedPolicyDemotesPlainInitialButPreservesTriangleLabel() {
        ASTParagraph plain = new ASTParagraph();
        plain.addItem(new ASTEquation("A", "EH_FONT"));

        MathPipeline.finalizeParagraph(
                plain, MathPipeline.SpanPolicy.CONVERTED_ITEMS);

        Assert.assertTrue(plain.items().get(0) instanceof ASTTextRun);

        ASTParagraph triangle = new ASTParagraph();
        triangle.addItem(text("△"));
        triangle.addItem(new ASTEquation("A", "EH_FONT"));
        triangle.addItem(new ASTEquation("BC", "EH_FONT"));

        MathPipeline.finalizeParagraph(
                triangle, MathPipeline.SpanPolicy.CONVERTED_ITEMS);

        Assert.assertEquals(2, triangle.items().size());
        Assert.assertEquals("ABC",
                ((ASTEquation) triangle.items().get(1)).hwpScript());
    }

    @Test
    public void sourceSpanOnlyEntryDoesNotRunFinalStructurePass() {
        ASTParagraph paragraph = new ASTParagraph();
        paragraph.addItem(text("3cm"));
        paragraph.addItem(new ASTEquation("^{2}", "EH_FONT"));

        MathPipeline.materializeSourceSpans(paragraph);

        Assert.assertEquals(2, paragraph.items().size());
        Assert.assertEquals("3cm",
                ((ASTTextRun) paragraph.items().get(0)).text());
        Assert.assertEquals("^{2}",
                ((ASTEquation) paragraph.items().get(1)).hwpScript());
    }

    private static ASTTextRun text(String value) {
        ASTTextRun run = new ASTTextRun();
        run.text(value);
        return run;
    }
}
