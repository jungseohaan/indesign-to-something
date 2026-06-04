package kr.dogfoot.hwpxlib.tool.equationconverter;

import org.junit.Assert;
import org.junit.Test;

public class TestLatexToHwpConverter {

    @Test
    public void testEmptyInput() throws Exception {
        Assert.assertEquals("", LatexToHwpConverter.convert(""));
    }

    @Test
    public void testNullInput() throws Exception {
        Assert.assertEquals("", LatexToHwpConverter.convert(null));
    }

    @Test
    public void testSimpleFraction() throws Exception {
        String result = LatexToHwpConverter.convert("\\frac{a}{b}");
        Assert.assertEquals("{a} over {b}", result);
    }

    @Test
    public void testSmallFraction() throws Exception {
        String result = LatexToHwpConverter.convert("\\tfrac{a}{b}");
        Assert.assertEquals("{a} smallover {b}", result);
    }

    @Test
    public void testFractionWithExpression() throws Exception {
        String result = LatexToHwpConverter.convert("\\frac{x+1}{y-2}");
        Assert.assertEquals("{x + 1} over {y - 2}", result);
    }

    @Test
    public void testSimpleSqrt() throws Exception {
        String result = LatexToHwpConverter.convert("\\sqrt{x}");
        Assert.assertEquals("sqrt {x}", result);
    }

    @Test
    public void testCubeRoot() throws Exception {
        String result = LatexToHwpConverter.convert("\\sqrt[3]{x}");
        Assert.assertEquals("root 3 of {x}", result);
    }

    @Test
    public void testSuperscript() throws Exception {
        String result = LatexToHwpConverter.convert("x^{2}");
        Assert.assertEquals("x ^{2}", result);
    }

    @Test
    public void testSubscript() throws Exception {
        String result = LatexToHwpConverter.convert("x_{i}");
        Assert.assertEquals("x _{i}", result);
    }

    @Test
    public void testSubSuperscript() throws Exception {
        String result = LatexToHwpConverter.convert("x_{i}^{2}");
        Assert.assertEquals("x _{i} ^{2}", result);
    }

    @Test
    public void testSumWithLimits() throws Exception {
        String result = LatexToHwpConverter.convert("\\sum_{i=1}^{n}");
        Assert.assertEquals("sum _{i = 1} ^{n}", result);
    }

    @Test
    public void testIntegralWithLimits() throws Exception {
        String result = LatexToHwpConverter.convert("\\int_{a}^{b}");
        Assert.assertEquals("int _{a} ^{b}", result);
    }

    @Test
    public void testGreekExpression() throws Exception {
        String result = LatexToHwpConverter.convert("\\alpha + \\beta");
        Assert.assertEquals("alpha + beta", result);
    }

    @Test
    public void testUpperGreek() throws Exception {
        String result = LatexToHwpConverter.convert("\\Gamma");
        Assert.assertEquals("GAMMA", result);
    }

    @Test
    public void testInfinity() throws Exception {
        String result = LatexToHwpConverter.convert("\\infty");
        Assert.assertEquals("inf", result);
    }

    @Test
    public void testArrow() throws Exception {
        String result = LatexToHwpConverter.convert("\\rightarrow");
        Assert.assertEquals("->", result);
    }

    @Test
    public void testTimes() throws Exception {
        String result = LatexToHwpConverter.convert("a \\times b");
        Assert.assertEquals("a times b", result);
    }

    @Test
    public void testPlusMinus() throws Exception {
        String result = LatexToHwpConverter.convert("\\pm");
        Assert.assertEquals("+-", result);
    }

    @Test
    public void testText() throws Exception {
        String result = LatexToHwpConverter.convert("\\text{hello}");
        Assert.assertEquals("\"hello\"", result);
    }

    @Test
    public void testLeftRightParen() throws Exception {
        String result = LatexToHwpConverter.convert("\\left( \\frac{a}{b} \\right)");
        Assert.assertEquals("LEFT ( {a} over {b} RIGHT )", result);
    }

    @Test
    public void testLeftNone() throws Exception {
        String result = LatexToHwpConverter.convert("\\left. x \\right)");
        Assert.assertEquals("LEFT NONE x RIGHT )", result);
    }

    @Test
    public void testNestedFraction() throws Exception {
        String result = LatexToHwpConverter.convert("\\frac{\\frac{a}{b}}{c}");
        Assert.assertEquals("{{a} over {b}} over {c}", result);
    }

    @Test
    public void testQuadraticFormula() throws Exception {
        String result = LatexToHwpConverter.convert("\\frac{-b \\pm \\sqrt{b^{2}-4ac}}{2a}");
        Assert.assertEquals("{- b +- sqrt {b ^{2} - 4ac}} over {2a}", result);
    }

    @Test
    public void testSumWithBody() throws Exception {
        String result = LatexToHwpConverter.convert("\\sum_{i=1}^{n} x_{i}");
        Assert.assertEquals("sum _{i = 1} ^{n} x _{i}", result);
    }

    @Test
    public void testDoubleIntegral() throws Exception {
        String result = LatexToHwpConverter.convert("\\iint");
        Assert.assertEquals("dint", result);
    }

    @Test
    public void testTripleIntegral() throws Exception {
        String result = LatexToHwpConverter.convert("\\iiint");
        Assert.assertEquals("tint", result);
    }

    @Test
    public void testHatAccent() throws Exception {
        String result = LatexToHwpConverter.convert("\\hat{x}");
        Assert.assertEquals("HAT x", result);
    }

    @Test
    public void testBarAccent() throws Exception {
        String result = LatexToHwpConverter.convert("\\bar{x}");
        Assert.assertEquals("BAR x", result);
    }

    @Test
    public void testVecAccent() throws Exception {
        String result = LatexToHwpConverter.convert("\\vec{v}");
        Assert.assertEquals("VEC v", result);
    }

    @Test
    public void testOverline() throws Exception {
        String result = LatexToHwpConverter.convert("\\overline{AB}");
        Assert.assertEquals("OVERLINE AB", result);
    }

    @Test
    public void testBinom() throws Exception {
        String result = LatexToHwpConverter.convert("\\binom{n}{k}");
        Assert.assertEquals("LEFT ( {n} atop {k} RIGHT )", result);
    }

    @Test
    public void testEMC2() throws Exception {
        String result = LatexToHwpConverter.convert("E=mc^{2}");
        Assert.assertEquals("E = mc ^{2}", result);
    }

    @Test
    public void testSinFunction() throws Exception {
        String result = LatexToHwpConverter.convert("\\sin x");
        Assert.assertEquals("sin x", result);
    }

    @Test
    public void testLimitExpression() throws Exception {
        String result = LatexToHwpConverter.convert("\\lim_{x \\to 0}");
        Assert.assertEquals("lim _{x -> 0}", result);
    }

    @Test
    public void testComplexSqrt() throws Exception {
        String result = LatexToHwpConverter.convert("\\sqrt{b^{2} - 4ac}");
        Assert.assertEquals("sqrt {b ^{2} - 4ac}", result);
    }
}
