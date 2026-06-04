package kr.dogfoot.hwpxlib.tool.equationconverter;

import kr.dogfoot.hwpxlib.tool.equationconverter.latex.LatexToken;
import kr.dogfoot.hwpxlib.tool.equationconverter.latex.LatexTokenType;
import kr.dogfoot.hwpxlib.tool.equationconverter.latex.LatexTokenizer;
import org.junit.Assert;
import org.junit.Test;

import java.util.List;

public class TestLatexTokenizer {

    @Test
    public void testEmptyInput() throws Exception {
        List<LatexToken> tokens = new LatexTokenizer("").tokenize();
        Assert.assertEquals(1, tokens.size());
        Assert.assertEquals(LatexTokenType.EOF, tokens.get(0).type());
    }

    @Test
    public void testNullInput() throws Exception {
        List<LatexToken> tokens = new LatexTokenizer(null).tokenize();
        Assert.assertEquals(1, tokens.size());
        Assert.assertEquals(LatexTokenType.EOF, tokens.get(0).type());
    }

    @Test
    public void testSimpleText() throws Exception {
        List<LatexToken> tokens = new LatexTokenizer("abc").tokenize();
        Assert.assertEquals(2, tokens.size());
        Assert.assertEquals(LatexTokenType.TEXT, tokens.get(0).type());
        Assert.assertEquals("abc", tokens.get(0).value());
    }

    @Test
    public void testNumbers() throws Exception {
        List<LatexToken> tokens = new LatexTokenizer("123").tokenize();
        Assert.assertEquals(2, tokens.size());
        Assert.assertEquals(LatexTokenType.NUMBER, tokens.get(0).type());
        Assert.assertEquals("123", tokens.get(0).value());
    }

    @Test
    public void testDecimalNumber() throws Exception {
        List<LatexToken> tokens = new LatexTokenizer("3.14").tokenize();
        Assert.assertEquals(2, tokens.size());
        Assert.assertEquals(LatexTokenType.NUMBER, tokens.get(0).type());
        Assert.assertEquals("3.14", tokens.get(0).value());
    }

    @Test
    public void testCommand() throws Exception {
        List<LatexToken> tokens = new LatexTokenizer("\\frac").tokenize();
        Assert.assertEquals(2, tokens.size());
        Assert.assertEquals(LatexTokenType.COMMAND, tokens.get(0).type());
        Assert.assertEquals("frac", tokens.get(0).value());
    }

    @Test
    public void testGreekCommand() throws Exception {
        List<LatexToken> tokens = new LatexTokenizer("\\alpha").tokenize();
        Assert.assertEquals(2, tokens.size());
        Assert.assertEquals(LatexTokenType.COMMAND, tokens.get(0).type());
        Assert.assertEquals("alpha", tokens.get(0).value());
    }

    @Test
    public void testBraces() throws Exception {
        List<LatexToken> tokens = new LatexTokenizer("{}").tokenize();
        Assert.assertEquals(3, tokens.size());
        Assert.assertEquals(LatexTokenType.OPEN_BRACE, tokens.get(0).type());
        Assert.assertEquals(LatexTokenType.CLOSE_BRACE, tokens.get(1).type());
    }

    @Test
    public void testSuperscriptSubscript() throws Exception {
        List<LatexToken> tokens = new LatexTokenizer("x^{2}_i").tokenize();
        Assert.assertEquals(LatexTokenType.TEXT, tokens.get(0).type());
        Assert.assertEquals("x", tokens.get(0).value());
        Assert.assertEquals(LatexTokenType.SUPERSCRIPT, tokens.get(1).type());
        Assert.assertEquals(LatexTokenType.OPEN_BRACE, tokens.get(2).type());
        Assert.assertEquals(LatexTokenType.NUMBER, tokens.get(3).type());
        Assert.assertEquals(LatexTokenType.CLOSE_BRACE, tokens.get(4).type());
        Assert.assertEquals(LatexTokenType.SUBSCRIPT, tokens.get(5).type());
        Assert.assertEquals(LatexTokenType.TEXT, tokens.get(6).type());
    }

    @Test
    public void testOperators() throws Exception {
        List<LatexToken> tokens = new LatexTokenizer("a + b = c").tokenize();
        Assert.assertEquals(LatexTokenType.TEXT, tokens.get(0).type());
        Assert.assertEquals(LatexTokenType.WHITESPACE, tokens.get(1).type());
        Assert.assertEquals(LatexTokenType.OPERATOR, tokens.get(2).type());
        Assert.assertEquals("+", tokens.get(2).value());
    }

    @Test
    public void testDoubleBackslash() throws Exception {
        List<LatexToken> tokens = new LatexTokenizer("\\\\").tokenize();
        Assert.assertEquals(2, tokens.size());
        Assert.assertEquals(LatexTokenType.DOUBLE_BACKSLASH, tokens.get(0).type());
    }

    @Test
    public void testEscapedBrace() throws Exception {
        List<LatexToken> tokens = new LatexTokenizer("\\{").tokenize();
        Assert.assertEquals(2, tokens.size());
        Assert.assertEquals(LatexTokenType.COMMAND, tokens.get(0).type());
        Assert.assertEquals("{", tokens.get(0).value());
    }

    @Test
    public void testComplex() throws Exception {
        List<LatexToken> tokens = new LatexTokenizer("\\frac{a+1}{b}").tokenize();
        Assert.assertEquals(LatexTokenType.COMMAND, tokens.get(0).type());
        Assert.assertEquals("frac", tokens.get(0).value());
        Assert.assertEquals(LatexTokenType.OPEN_BRACE, tokens.get(1).type());
        Assert.assertEquals(LatexTokenType.TEXT, tokens.get(2).type());
        Assert.assertEquals("a", tokens.get(2).value());
        Assert.assertEquals(LatexTokenType.OPERATOR, tokens.get(3).type());
        Assert.assertEquals("+", tokens.get(3).value());
        Assert.assertEquals(LatexTokenType.NUMBER, tokens.get(4).type());
        Assert.assertEquals("1", tokens.get(4).value());
        Assert.assertEquals(LatexTokenType.CLOSE_BRACE, tokens.get(5).type());
        Assert.assertEquals(LatexTokenType.OPEN_BRACE, tokens.get(6).type());
        Assert.assertEquals(LatexTokenType.TEXT, tokens.get(7).type());
        Assert.assertEquals("b", tokens.get(7).value());
        Assert.assertEquals(LatexTokenType.CLOSE_BRACE, tokens.get(8).type());
    }

    @Test
    public void testPipe() throws Exception {
        List<LatexToken> tokens = new LatexTokenizer("|x|").tokenize();
        Assert.assertEquals(LatexTokenType.PIPE, tokens.get(0).type());
        Assert.assertEquals(LatexTokenType.TEXT, tokens.get(1).type());
        Assert.assertEquals(LatexTokenType.PIPE, tokens.get(2).type());
    }

    @Test
    public void testSpacingCommand() throws Exception {
        List<LatexToken> tokens = new LatexTokenizer("\\,").tokenize();
        Assert.assertEquals(2, tokens.size());
        Assert.assertEquals(LatexTokenType.COMMAND, tokens.get(0).type());
        Assert.assertEquals(",", tokens.get(0).value());
    }
}
