package kr.dogfoot.hwpxlib.tool.idmlconverter.formula;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTEquation;
import org.junit.Assert;
import org.junit.Test;

public class FormulaStyleResolverTest {
    @Test
    public void chemicalFormulaSymbolsAreEmittedUprightInsideEquationScript() {
        ASTEquation eq = new ASTEquation(
                "N_{2}+□H_{2} ~ rarrow ~ □NH_{3}",
                "CHEM_FORMULA");

        Assert.assertEquals(
                "rm N_{2}+□H_{2} ~ rarrow ~ □NH_{3}",
                FormulaStyleResolver.applyChemicalUprightScript(eq, eq.hwpScript()));
    }

    @Test
    public void regularMathEquationScriptIsNotRomanized() {
        ASTEquation eq = new ASTEquation("x^{2}+y^{2}=z^{2}", "EH_FONT");

        Assert.assertEquals(
                "x^{2}+y^{2}=z^{2}",
                FormulaStyleResolver.applyChemicalUprightScript(eq, eq.hwpScript()));
    }

    @Test
    public void chemicalUprightScriptDoesNotDuplicateExistingRomanCommand() {
        Assert.assertEquals(
                "rm H_{2}O ~ rarrow ~ CO_{2}",
                FormulaStyleResolver.applyChemicalUprightScript("rm H_{2}O ~ rarrow ~ CO_{2}"));
    }
}
