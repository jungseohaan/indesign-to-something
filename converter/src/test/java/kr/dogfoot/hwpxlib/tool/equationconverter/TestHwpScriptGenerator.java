package kr.dogfoot.hwpxlib.tool.equationconverter;

import kr.dogfoot.hwpxlib.tool.equationconverter.hwpscript.HwpScriptGenerator;
import kr.dogfoot.hwpxlib.tool.equationconverter.latex.ast.*;
import org.junit.Assert;
import org.junit.Test;

public class TestHwpScriptGenerator {
    private final HwpScriptGenerator generator = new HwpScriptGenerator();

    @Test
    public void testNumber() throws Exception {
        String result = generator.generate(new NumberNode("123"));
        Assert.assertEquals("123", result);
    }

    @Test
    public void testSymbolVariable() throws Exception {
        String result = generator.generate(new SymbolNode("x"));
        Assert.assertEquals("x", result);
    }

    @Test
    public void testSymbolGreek() throws Exception {
        String result = generator.generate(new SymbolNode("alpha"));
        Assert.assertEquals("alpha", result);
    }

    @Test
    public void testSymbolUpperGreek() throws Exception {
        String result = generator.generate(new SymbolNode("Gamma"));
        Assert.assertEquals("GAMMA", result);
    }

    @Test
    public void testSymbolInfinity() throws Exception {
        String result = generator.generate(new SymbolNode("infty"));
        Assert.assertEquals("inf", result);
    }

    @Test
    public void testSymbolArrow() throws Exception {
        String result = generator.generate(new SymbolNode("rightarrow"));
        Assert.assertEquals("->", result);
    }

    @Test
    public void testText() throws Exception {
        String result = generator.generate(new TextNode("hello"));
        Assert.assertEquals("\"hello\"", result);
    }

    @Test
    public void testFraction() throws Exception {
        FractionNode node = new FractionNode(
                new SymbolNode("a"),
                new SymbolNode("b"),
                "frac"
        );
        String result = generator.generate(node);
        Assert.assertEquals("{a} over {b}", result);
    }

    @Test
    public void testSmallFraction() throws Exception {
        FractionNode node = new FractionNode(
                new SymbolNode("a"),
                new SymbolNode("b"),
                "tfrac"
        );
        String result = generator.generate(node);
        Assert.assertEquals("{a} smallover {b}", result);
    }

    @Test
    public void testSqrt() throws Exception {
        RootNode node = new RootNode(new SymbolNode("x"), null);
        String result = generator.generate(node);
        Assert.assertEquals("sqrt {x}", result);
    }

    @Test
    public void testNthRoot() throws Exception {
        RootNode node = new RootNode(new SymbolNode("x"), new NumberNode("3"));
        String result = generator.generate(node);
        Assert.assertEquals("root 3 of {x}", result);
    }

    @Test
    public void testSuperscript() throws Exception {
        SuperscriptNode node = new SuperscriptNode(
                new SymbolNode("x"),
                new NumberNode("2")
        );
        String result = generator.generate(node);
        Assert.assertEquals("x ^{2}", result);
    }

    @Test
    public void testSubscript() throws Exception {
        SubscriptNode node = new SubscriptNode(
                new SymbolNode("x"),
                new SymbolNode("i")
        );
        String result = generator.generate(node);
        Assert.assertEquals("x _{i}", result);
    }

    @Test
    public void testSubSuper() throws Exception {
        SubSuperNode node = new SubSuperNode(
                new SymbolNode("x"),
                new SymbolNode("i"),
                new NumberNode("2")
        );
        String result = generator.generate(node);
        Assert.assertEquals("x _{i} ^{2}", result);
    }

    @Test
    public void testBigOperator() throws Exception {
        BigOperatorNode node = new BigOperatorNode(
                "sum",
                new SymbolNode("i"),
                new SymbolNode("n")
        );
        String result = generator.generate(node);
        Assert.assertEquals("sum _{i} ^{n}", result);
    }

    @Test
    public void testBigOperatorNoLimits() throws Exception {
        BigOperatorNode node = new BigOperatorNode("int", null, null);
        String result = generator.generate(node);
        Assert.assertEquals("int", result);
    }

    @Test
    public void testFunction() throws Exception {
        FunctionNode node = new FunctionNode("sin", null);
        String result = generator.generate(node);
        Assert.assertEquals("sin", result);
    }

    @Test
    public void testDelimiter() throws Exception {
        DelimiterNode node = new DelimiterNode(
                "(", ")",
                new SymbolNode("x")
        );
        String result = generator.generate(node);
        Assert.assertEquals("LEFT ( x RIGHT )", result);
    }

    @Test
    public void testDelimiterNone() throws Exception {
        DelimiterNode node = new DelimiterNode(
                ".", ")",
                new SymbolNode("x")
        );
        String result = generator.generate(node);
        Assert.assertEquals("LEFT NONE x RIGHT )", result);
    }

    @Test
    public void testBinom() throws Exception {
        FractionNode node = new FractionNode(
                new SymbolNode("n"),
                new SymbolNode("k"),
                "binom"
        );
        String result = generator.generate(node);
        Assert.assertEquals("LEFT ( {n} atop {k} RIGHT )", result);
    }

    @Test
    public void testAccentHat() throws Exception {
        FunctionNode node = new FunctionNode("hat", new SymbolNode("x"));
        String result = generator.generate(node);
        Assert.assertEquals("HAT x", result);
    }

    @Test
    public void testAccentBar() throws Exception {
        FunctionNode node = new FunctionNode("bar", new SymbolNode("x"));
        String result = generator.generate(node);
        Assert.assertEquals("BAR x", result);
    }

    @Test
    public void testAccentVec() throws Exception {
        FunctionNode node = new FunctionNode("vec", new SymbolNode("v"));
        String result = generator.generate(node);
        Assert.assertEquals("VEC v", result);
    }

    @Test
    public void testSequence() throws Exception {
        SequenceNode seq = new SequenceNode();
        seq.addChild(new SymbolNode("a"));
        seq.addChild(new SymbolNode("+"));
        seq.addChild(new SymbolNode("b"));
        String result = generator.generate(seq);
        Assert.assertEquals("a + b", result);
    }

    @Test
    public void testGroup() throws Exception {
        GroupNode node = new GroupNode(
                new SymbolNode("x")
        );
        String result = generator.generate(node);
        Assert.assertEquals("{x}", result);
    }

    @Test
    public void testDoubleIntegral() throws Exception {
        BigOperatorNode node = new BigOperatorNode("iint", null, null);
        String result = generator.generate(node);
        Assert.assertEquals("dint", result);
    }

    @Test
    public void testPlusMinus() throws Exception {
        String result = generator.generate(new SymbolNode("pm"));
        Assert.assertEquals("+-", result);
    }
}
