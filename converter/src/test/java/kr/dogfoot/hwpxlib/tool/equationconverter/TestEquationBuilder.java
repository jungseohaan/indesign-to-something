package kr.dogfoot.hwpxlib.tool.equationconverter;

import kr.dogfoot.hwpxlib.object.content.section_xml.enumtype.EquationLineMode;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.object.Equation;
import org.junit.Assert;
import org.junit.Test;

public class TestEquationBuilder {

    @Test
    public void testFromLatex() throws Exception {
        Equation eq = EquationBuilder.fromLatex("\\frac{a}{b}");

        Assert.assertNotNull(eq);
        Assert.assertEquals("Equation Version 60", eq.version());
        Assert.assertEquals("#000000", eq.textColor());
        Assert.assertEquals(Integer.valueOf(1100), eq.baseUnit());
        Assert.assertEquals(EquationLineMode.CHAR, eq.lineMode());
        Assert.assertEquals("HYhwpEQ", eq.font());
        Assert.assertNotNull(eq.script());
        Assert.assertEquals("{a} over {b}", eq.script().text());
    }

    @Test
    public void testFromHwpScript() throws Exception {
        Equation eq = EquationBuilder.fromHwpScript("sqrt {x}");

        Assert.assertNotNull(eq);
        Assert.assertEquals("Equation Version 60", eq.version());
        Assert.assertEquals("#000000", eq.textColor());
        Assert.assertEquals(Integer.valueOf(1100), eq.baseUnit());
        Assert.assertEquals(EquationLineMode.CHAR, eq.lineMode());
        Assert.assertEquals("HYhwpEQ", eq.font());
        Assert.assertNotNull(eq.script());
        Assert.assertEquals("sqrt {x}", eq.script().text());
    }

    @Test
    public void testFromLatexComplex() throws Exception {
        Equation eq = EquationBuilder.fromLatex("\\sum_{i=1}^{n} \\frac{1}{i^{2}}");

        Assert.assertNotNull(eq);
        Assert.assertNotNull(eq.script());
        Assert.assertEquals("sum _{i = 1} ^{n} {1} over {i ^{2}}", eq.script().text());
    }

    @Test
    public void testFromLatexEmpty() throws Exception {
        Equation eq = EquationBuilder.fromLatex("");

        Assert.assertNotNull(eq);
        Assert.assertNotNull(eq.script());
        Assert.assertEquals("", eq.script().text());
    }

    @Test
    public void testFromLatexGreek() throws Exception {
        Equation eq = EquationBuilder.fromLatex("\\alpha + \\beta = \\gamma");

        Assert.assertNotNull(eq);
        Assert.assertEquals("alpha + beta = gamma", eq.script().text());
    }

    @Test
    public void testFromLatexQuadratic() throws Exception {
        Equation eq = EquationBuilder.fromLatex("x = \\frac{-b \\pm \\sqrt{b^{2}-4ac}}{2a}");

        Assert.assertNotNull(eq);
        String script = eq.script().text();
        Assert.assertTrue(script.contains("over"));
        Assert.assertTrue(script.contains("+-"));
        Assert.assertTrue(script.contains("sqrt"));
    }
}
