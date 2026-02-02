package kr.dogfoot.hwpxlib.tool.equationconverter;

import kr.dogfoot.hwpxlib.tool.equationconverter.latex.LatexParser;
import kr.dogfoot.hwpxlib.tool.equationconverter.latex.LatexToken;
import kr.dogfoot.hwpxlib.tool.equationconverter.latex.LatexTokenizer;
import kr.dogfoot.hwpxlib.tool.equationconverter.latex.ast.*;
import org.junit.Assert;
import org.junit.Test;

import java.util.List;

public class TestLatexParser {

    private AstNode parse(String latex) throws ConvertException {
        List<LatexToken> tokens = new LatexTokenizer(latex).tokenize();
        return new LatexParser(tokens).parse();
    }

    @Test
    public void testSimpleNumber() throws Exception {
        AstNode node = parse("123");
        Assert.assertEquals(AstNodeType.NUMBER, node.nodeType());
        Assert.assertEquals("123", ((NumberNode) node).value());
    }

    @Test
    public void testSimpleVariable() throws Exception {
        AstNode node = parse("x");
        Assert.assertEquals(AstNodeType.SYMBOL, node.nodeType());
        Assert.assertEquals("x", ((SymbolNode) node).name());
    }

    @Test
    public void testGroup() throws Exception {
        AstNode node = parse("{x}");
        Assert.assertEquals(AstNodeType.GROUP, node.nodeType());
        AstNode child = ((GroupNode) node).child();
        Assert.assertEquals(AstNodeType.SYMBOL, child.nodeType());
    }

    @Test
    public void testFraction() throws Exception {
        AstNode node = parse("\\frac{a}{b}");
        Assert.assertEquals(AstNodeType.FRACTION, node.nodeType());
        FractionNode frac = (FractionNode) node;
        Assert.assertEquals("frac", frac.variant());
        Assert.assertEquals(AstNodeType.SYMBOL, frac.numerator().nodeType());
        Assert.assertEquals(AstNodeType.SYMBOL, frac.denominator().nodeType());
    }

    @Test
    public void testSqrt() throws Exception {
        AstNode node = parse("\\sqrt{x}");
        Assert.assertEquals(AstNodeType.ROOT, node.nodeType());
        RootNode root = (RootNode) node;
        Assert.assertNull(root.index());
        Assert.assertEquals(AstNodeType.SYMBOL, root.radicand().nodeType());
    }

    @Test
    public void testSqrtWithIndex() throws Exception {
        AstNode node = parse("\\sqrt[3]{x}");
        Assert.assertEquals(AstNodeType.ROOT, node.nodeType());
        RootNode root = (RootNode) node;
        Assert.assertNotNull(root.index());
        Assert.assertEquals(AstNodeType.NUMBER, root.index().nodeType());
        Assert.assertEquals("3", ((NumberNode) root.index()).value());
    }

    @Test
    public void testSuperscript() throws Exception {
        AstNode node = parse("x^{2}");
        Assert.assertEquals(AstNodeType.SUPERSCRIPT, node.nodeType());
        SuperscriptNode sup = (SuperscriptNode) node;
        Assert.assertEquals(AstNodeType.SYMBOL, sup.base().nodeType());
        Assert.assertEquals(AstNodeType.NUMBER, sup.exponent().nodeType());
    }

    @Test
    public void testSubscript() throws Exception {
        AstNode node = parse("x_{i}");
        Assert.assertEquals(AstNodeType.SUBSCRIPT, node.nodeType());
        SubscriptNode sub = (SubscriptNode) node;
        Assert.assertEquals(AstNodeType.SYMBOL, sub.base().nodeType());
        Assert.assertEquals(AstNodeType.SYMBOL, sub.subscript().nodeType());
    }

    @Test
    public void testSubSuperscript() throws Exception {
        AstNode node = parse("x_{i}^{2}");
        Assert.assertEquals(AstNodeType.SUB_SUPER, node.nodeType());
        SubSuperNode ss = (SubSuperNode) node;
        Assert.assertEquals("x", ((SymbolNode) ss.base()).name());
        Assert.assertEquals("i", ((SymbolNode) ss.subscript()).name());
        Assert.assertEquals("2", ((NumberNode) ss.superscript()).value());
    }

    @Test
    public void testSumWithLimits() throws Exception {
        AstNode node = parse("\\sum_{i=0}^{n}");
        Assert.assertEquals(AstNodeType.BIG_OPERATOR, node.nodeType());
        BigOperatorNode bigOp = (BigOperatorNode) node;
        Assert.assertEquals("sum", bigOp.operator());
        Assert.assertNotNull(bigOp.lower());
        Assert.assertNotNull(bigOp.upper());
    }

    @Test
    public void testIntegral() throws Exception {
        AstNode node = parse("\\int_{a}^{b}");
        Assert.assertEquals(AstNodeType.BIG_OPERATOR, node.nodeType());
        BigOperatorNode bigOp = (BigOperatorNode) node;
        Assert.assertEquals("int", bigOp.operator());
    }

    @Test
    public void testFunction() throws Exception {
        AstNode root = parse("\\sin x");
        // Should be a sequence: [FunctionNode("sin"), SymbolNode("x")]
        Assert.assertEquals(AstNodeType.SEQUENCE, root.nodeType());
        SequenceNode seq = (SequenceNode) root;
        Assert.assertEquals(AstNodeType.FUNCTION, seq.children().get(0).nodeType());
        Assert.assertEquals("sin", ((FunctionNode) seq.children().get(0)).name());
    }

    @Test
    public void testLeftRight() throws Exception {
        AstNode node = parse("\\left( x \\right)");
        Assert.assertEquals(AstNodeType.DELIMITER, node.nodeType());
        DelimiterNode delim = (DelimiterNode) node;
        Assert.assertEquals("(", delim.leftDelim());
        Assert.assertEquals(")", delim.rightDelim());
    }

    @Test
    public void testGreekLetters() throws Exception {
        AstNode node = parse("\\alpha");
        Assert.assertEquals(AstNodeType.SYMBOL, node.nodeType());
        Assert.assertEquals("alpha", ((SymbolNode) node).name());
    }

    @Test
    public void testSymbols() throws Exception {
        AstNode node = parse("\\times");
        Assert.assertEquals(AstNodeType.SYMBOL, node.nodeType());
        Assert.assertEquals("times", ((SymbolNode) node).name());
    }

    @Test
    public void testText() throws Exception {
        AstNode node = parse("\\text{hello}");
        Assert.assertEquals(AstNodeType.TEXT, node.nodeType());
        Assert.assertEquals("hello", ((TextNode) node).text());
    }

    @Test
    public void testNestedFractions() throws Exception {
        AstNode node = parse("\\frac{\\frac{a}{b}}{c}");
        Assert.assertEquals(AstNodeType.FRACTION, node.nodeType());
        FractionNode outer = (FractionNode) node;
        Assert.assertEquals(AstNodeType.FRACTION, outer.numerator().nodeType());
        Assert.assertEquals(AstNodeType.SYMBOL, outer.denominator().nodeType());
    }

    @Test
    public void testSequence() throws Exception {
        AstNode node = parse("a + b");
        Assert.assertEquals(AstNodeType.SEQUENCE, node.nodeType());
        SequenceNode seq = (SequenceNode) node;
        Assert.assertEquals(3, seq.children().size());
    }

    @Test
    public void testAccent() throws Exception {
        AstNode node = parse("\\hat{x}");
        Assert.assertEquals(AstNodeType.FUNCTION, node.nodeType());
        FunctionNode fn = (FunctionNode) node;
        Assert.assertEquals("hat", fn.name());
        Assert.assertNotNull(fn.argument());
    }

    @Test
    public void testBinom() throws Exception {
        AstNode node = parse("\\binom{n}{k}");
        Assert.assertEquals(AstNodeType.FRACTION, node.nodeType());
        FractionNode frac = (FractionNode) node;
        Assert.assertEquals("binom", frac.variant());
    }
}
