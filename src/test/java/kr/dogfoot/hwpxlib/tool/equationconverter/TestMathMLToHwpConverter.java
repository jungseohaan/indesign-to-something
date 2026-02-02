package kr.dogfoot.hwpxlib.tool.equationconverter;

import org.junit.Assert;
import org.junit.Test;

public class TestMathMLToHwpConverter {

    @Test
    public void testEmptyInput() throws Exception {
        Assert.assertEquals("", MathMLToHwpConverter.convert(""));
    }

    @Test
    public void testNullInput() throws Exception {
        Assert.assertEquals("", MathMLToHwpConverter.convert(null));
    }

    @Test
    public void testSimpleNumber() throws Exception {
        String result = MathMLToHwpConverter.convert("<math><mn>42</mn></math>");
        Assert.assertEquals("42", result);
    }

    @Test
    public void testSimpleVariable() throws Exception {
        String result = MathMLToHwpConverter.convert("<math><mi>x</mi></math>");
        Assert.assertEquals("x", result);
    }

    @Test
    public void testGreekLetter() throws Exception {
        String result = MathMLToHwpConverter.convert("<math><mi>\u03B1</mi></math>");
        Assert.assertEquals("alpha", result);
    }

    @Test
    public void testUpperGreek() throws Exception {
        String result = MathMLToHwpConverter.convert("<math><mi>\u0393</mi></math>");
        Assert.assertEquals("GAMMA", result);
    }

    @Test
    public void testSimpleFraction() throws Exception {
        String result = MathMLToHwpConverter.convert(
                "<math><mfrac><mi>a</mi><mi>b</mi></mfrac></math>");
        Assert.assertEquals("{a} over {b}", result);
    }

    @Test
    public void testSqrt() throws Exception {
        String result = MathMLToHwpConverter.convert(
                "<math><msqrt><mi>x</mi></msqrt></math>");
        Assert.assertEquals("sqrt {x}", result);
    }

    @Test
    public void testCubeRoot() throws Exception {
        String result = MathMLToHwpConverter.convert(
                "<math><mroot><mi>x</mi><mn>3</mn></mroot></math>");
        Assert.assertEquals("root 3 of {x}", result);
    }

    @Test
    public void testSuperscript() throws Exception {
        String result = MathMLToHwpConverter.convert(
                "<math><msup><mi>x</mi><mn>2</mn></msup></math>");
        Assert.assertEquals("x ^{2}", result);
    }

    @Test
    public void testSubscript() throws Exception {
        String result = MathMLToHwpConverter.convert(
                "<math><msub><mi>x</mi><mi>i</mi></msub></math>");
        Assert.assertEquals("x _{i}", result);
    }

    @Test
    public void testSubSuperscript() throws Exception {
        String result = MathMLToHwpConverter.convert(
                "<math><msubsup><mi>x</mi><mi>i</mi><mn>2</mn></msubsup></math>");
        Assert.assertEquals("x _{i} ^{2}", result);
    }

    @Test
    public void testSum() throws Exception {
        String result = MathMLToHwpConverter.convert(
                "<math><munderover><mo>\u2211</mo><mrow><mi>i</mi><mo>=</mo><mn>1</mn></mrow><mi>n</mi></munderover></math>");
        Assert.assertEquals("sum _{i = 1} ^{n}", result);
    }

    @Test
    public void testIntegral() throws Exception {
        String result = MathMLToHwpConverter.convert(
                "<math><msubsup><mo>\u222B</mo><mi>a</mi><mi>b</mi></msubsup></math>");
        Assert.assertEquals("int _{a} ^{b}", result);
    }

    @Test
    public void testSinFunction() throws Exception {
        String result = MathMLToHwpConverter.convert(
                "<math><mi>sin</mi><mi>x</mi></math>");
        Assert.assertEquals("sin x", result);
    }

    @Test
    public void testInfinity() throws Exception {
        String result = MathMLToHwpConverter.convert(
                "<math><mo>\u221E</mo></math>");
        Assert.assertEquals("inf", result);
    }

    @Test
    public void testPlusMinus() throws Exception {
        String result = MathMLToHwpConverter.convert(
                "<math><mo>\u00B1</mo></math>");
        Assert.assertEquals("+-", result);
    }

    @Test
    public void testArrow() throws Exception {
        String result = MathMLToHwpConverter.convert(
                "<math><mo>\u2192</mo></math>");
        Assert.assertEquals("->", result);
    }

    @Test
    public void testTimes() throws Exception {
        String result = MathMLToHwpConverter.convert(
                "<math><mi>a</mi><mo>\u00D7</mo><mi>b</mi></math>");
        Assert.assertEquals("a times b", result);
    }

    @Test
    public void testFenced() throws Exception {
        String result = MathMLToHwpConverter.convert(
                "<math><mfenced open='(' close=')'><mi>x</mi></mfenced></math>");
        Assert.assertEquals("LEFT ( x RIGHT )", result);
    }

    @Test
    public void testText() throws Exception {
        String result = MathMLToHwpConverter.convert(
                "<math><mtext>hello</mtext></math>");
        Assert.assertEquals("\"hello\"", result);
    }

    @Test
    public void testNestedFraction() throws Exception {
        String result = MathMLToHwpConverter.convert(
                "<math><mfrac><mfrac><mi>a</mi><mi>b</mi></mfrac><mi>c</mi></mfrac></math>");
        Assert.assertEquals("{{a} over {b}} over {c}", result);
    }

    @Test
    public void testQuadraticFormula() throws Exception {
        // x = (-b ± sqrt(b²-4ac)) / 2a
        String mathml = "<math><mi>x</mi><mo>=</mo>" +
                "<mfrac><mrow><mo>-</mo><mi>b</mi><mo>\u00B1</mo>" +
                "<msqrt><mrow><msup><mi>b</mi><mn>2</mn></msup><mo>-</mo>" +
                "<mn>4</mn><mi>a</mi><mi>c</mi></mrow></msqrt></mrow>" +
                "<mrow><mn>2</mn><mi>a</mi></mrow></mfrac></math>";
        String result = MathMLToHwpConverter.convert(mathml);
        Assert.assertTrue(result.contains("over"));
        Assert.assertTrue(result.contains("+-"));
        Assert.assertTrue(result.contains("sqrt"));
    }

    @Test
    public void testEulerIdentity() throws Exception {
        // e^(iπ) + 1 = 0
        String mathml = "<math><msup><mi>e</mi><mrow><mi>i</mi><mi>\u03C0</mi></mrow></msup>" +
                "<mo>+</mo><mn>1</mn><mo>=</mo><mn>0</mn></math>";
        String result = MathMLToHwpConverter.convert(mathml);
        Assert.assertTrue(result.contains("e ^{i pi}"));
    }

    @Test
    public void testPythagorean() throws Exception {
        // a² + b² = c²
        String mathml = "<math><msup><mi>a</mi><mn>2</mn></msup><mo>+</mo>" +
                "<msup><mi>b</mi><mn>2</mn></msup><mo>=</mo>" +
                "<msup><mi>c</mi><mn>2</mn></msup></math>";
        String result = MathMLToHwpConverter.convert(mathml);
        Assert.assertEquals("a ^{2} + b ^{2} = c ^{2}", result);
    }

    @Test
    public void testMrowSequence() throws Exception {
        String result = MathMLToHwpConverter.convert(
                "<math><mrow><mi>a</mi><mo>+</mo><mi>b</mi></mrow></math>");
        Assert.assertEquals("a + b", result);
    }

    @Test
    public void testDoubleIntegral() throws Exception {
        String result = MathMLToHwpConverter.convert(
                "<math><mo>\u222C</mo></math>");
        Assert.assertEquals("dint", result);
    }

    @Test
    public void testLeq() throws Exception {
        String result = MathMLToHwpConverter.convert(
                "<math><mi>a</mi><mo>\u2264</mo><mi>b</mi></math>");
        Assert.assertEquals("a leq b", result);
    }

    @Test
    public void testSubsetOf() throws Exception {
        String result = MathMLToHwpConverter.convert(
                "<math><mi>A</mi><mo>\u2282</mo><mi>B</mi></math>");
        Assert.assertEquals("A subset B", result);
    }
}
