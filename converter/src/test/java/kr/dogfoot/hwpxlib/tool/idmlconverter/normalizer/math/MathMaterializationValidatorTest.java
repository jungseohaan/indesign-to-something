package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.math;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTEquation;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTParagraph;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTextRun;
import org.junit.Assert;
import org.junit.Test;

import java.util.List;

public class MathMaterializationValidatorTest {

    @Test
    public void validEquationHasNoViolations() {
        ASTParagraph paragraph = new ASTParagraph();
        paragraph.addItem(new ASTEquation("a+b", "EH_FONT"));

        Assert.assertTrue(MathMaterializationValidator.validate(paragraph).isEmpty());
    }

    @Test
    public void emptyScriptAndMissingSourceTypeAreReportedIndependently() {
        ASTParagraph paragraph = new ASTParagraph();
        paragraph.addItem(new ASTEquation(" ", null));

        List<MathMaterializationValidator.Violation> violations =
                MathMaterializationValidator.validate(paragraph);

        Assert.assertEquals(2, violations.size());
        Assert.assertEquals(MathMaterializationValidator.EMPTY_SCRIPT,
                violations.get(0).code());
        Assert.assertEquals(MathMaterializationValidator.MISSING_SOURCE_TYPE,
                violations.get(1).code());
        Assert.assertEquals(0, violations.get(0).itemIndex());
        Assert.assertEquals(0, violations.get(1).itemIndex());
    }

    @Test
    public void equationControlCharactersAreReported() {
        ASTParagraph paragraph = new ASTParagraph();
        paragraph.addItem(new ASTEquation("a\t+\nb", "EH_FONT"));

        List<MathMaterializationValidator.Violation> violations =
                MathMaterializationValidator.validate(paragraph);

        Assert.assertEquals(1, violations.size());
        Assert.assertEquals(MathMaterializationValidator.SCRIPT_CONTROL_CHARACTER,
                violations.get(0).code());
    }

    @Test
    public void validationDoesNotMutateParagraph() {
        ASTParagraph paragraph = new ASTParagraph();
        ASTTextRun text = new ASTTextRun();
        text.text("앞");
        ASTEquation equation = new ASTEquation("a+b", "EH_FONT");
        paragraph.addItem(text);
        paragraph.addItem(equation);

        MathMaterializationValidator.validate(paragraph);

        Assert.assertEquals(2, paragraph.items().size());
        Assert.assertSame(text, paragraph.items().get(0));
        Assert.assertSame(equation, paragraph.items().get(1));
        Assert.assertEquals("a+b", equation.hwpScript());
        Assert.assertEquals("EH_FONT", equation.sourceType());
    }
}
