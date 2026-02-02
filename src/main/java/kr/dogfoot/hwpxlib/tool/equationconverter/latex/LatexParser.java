package kr.dogfoot.hwpxlib.tool.equationconverter.latex;

import kr.dogfoot.hwpxlib.tool.equationconverter.ConvertException;
import kr.dogfoot.hwpxlib.tool.equationconverter.latex.ast.*;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class LatexParser {
    private final List<LatexToken> tokens;
    private int pos;

    private static final Set<String> BIG_OPERATORS;
    private static final Set<String> FUNCTION_NAMES;
    private static final Set<String> GREEK_LETTERS;
    private static final Set<String> SYMBOL_COMMANDS;
    private static final Set<String> FRACTION_COMMANDS;
    private static final Set<String> ACCENT_COMMANDS;

    static {
        BIG_OPERATORS = new HashSet<String>();
        BIG_OPERATORS.add("sum");
        BIG_OPERATORS.add("prod");
        BIG_OPERATORS.add("coprod");
        BIG_OPERATORS.add("int");
        BIG_OPERATORS.add("oint");
        BIG_OPERATORS.add("iint");
        BIG_OPERATORS.add("iiint");
        BIG_OPERATORS.add("bigcup");
        BIG_OPERATORS.add("bigcap");
        BIG_OPERATORS.add("bigoplus");
        BIG_OPERATORS.add("bigotimes");
        BIG_OPERATORS.add("bigvee");
        BIG_OPERATORS.add("bigwedge");

        FUNCTION_NAMES = new HashSet<String>();
        FUNCTION_NAMES.add("sin");
        FUNCTION_NAMES.add("cos");
        FUNCTION_NAMES.add("tan");
        FUNCTION_NAMES.add("cot");
        FUNCTION_NAMES.add("sec");
        FUNCTION_NAMES.add("csc");
        FUNCTION_NAMES.add("arcsin");
        FUNCTION_NAMES.add("arccos");
        FUNCTION_NAMES.add("arctan");
        FUNCTION_NAMES.add("sinh");
        FUNCTION_NAMES.add("cosh");
        FUNCTION_NAMES.add("tanh");
        FUNCTION_NAMES.add("coth");
        FUNCTION_NAMES.add("log");
        FUNCTION_NAMES.add("ln");
        FUNCTION_NAMES.add("lg");
        FUNCTION_NAMES.add("exp");
        FUNCTION_NAMES.add("det");
        FUNCTION_NAMES.add("gcd");
        FUNCTION_NAMES.add("max");
        FUNCTION_NAMES.add("min");
        FUNCTION_NAMES.add("sup");
        FUNCTION_NAMES.add("inf");
        FUNCTION_NAMES.add("lim");
        FUNCTION_NAMES.add("limsup");
        FUNCTION_NAMES.add("liminf");
        FUNCTION_NAMES.add("arg");
        FUNCTION_NAMES.add("deg");
        FUNCTION_NAMES.add("dim");
        FUNCTION_NAMES.add("hom");
        FUNCTION_NAMES.add("ker");
        FUNCTION_NAMES.add("Pr");
        FUNCTION_NAMES.add("mod");

        GREEK_LETTERS = new HashSet<String>();
        // lowercase
        GREEK_LETTERS.add("alpha");
        GREEK_LETTERS.add("beta");
        GREEK_LETTERS.add("gamma");
        GREEK_LETTERS.add("delta");
        GREEK_LETTERS.add("epsilon");
        GREEK_LETTERS.add("varepsilon");
        GREEK_LETTERS.add("zeta");
        GREEK_LETTERS.add("eta");
        GREEK_LETTERS.add("theta");
        GREEK_LETTERS.add("vartheta");
        GREEK_LETTERS.add("iota");
        GREEK_LETTERS.add("kappa");
        GREEK_LETTERS.add("lambda");
        GREEK_LETTERS.add("mu");
        GREEK_LETTERS.add("nu");
        GREEK_LETTERS.add("xi");
        GREEK_LETTERS.add("pi");
        GREEK_LETTERS.add("varpi");
        GREEK_LETTERS.add("rho");
        GREEK_LETTERS.add("varrho");
        GREEK_LETTERS.add("sigma");
        GREEK_LETTERS.add("varsigma");
        GREEK_LETTERS.add("tau");
        GREEK_LETTERS.add("upsilon");
        GREEK_LETTERS.add("phi");
        GREEK_LETTERS.add("varphi");
        GREEK_LETTERS.add("chi");
        GREEK_LETTERS.add("psi");
        GREEK_LETTERS.add("omega");
        // uppercase
        GREEK_LETTERS.add("Gamma");
        GREEK_LETTERS.add("Delta");
        GREEK_LETTERS.add("Theta");
        GREEK_LETTERS.add("Lambda");
        GREEK_LETTERS.add("Xi");
        GREEK_LETTERS.add("Pi");
        GREEK_LETTERS.add("Sigma");
        GREEK_LETTERS.add("Upsilon");
        GREEK_LETTERS.add("Phi");
        GREEK_LETTERS.add("Psi");
        GREEK_LETTERS.add("Omega");

        SYMBOL_COMMANDS = new HashSet<String>();
        // operators
        SYMBOL_COMMANDS.add("times");
        SYMBOL_COMMANDS.add("cdot");
        SYMBOL_COMMANDS.add("div");
        SYMBOL_COMMANDS.add("pm");
        SYMBOL_COMMANDS.add("mp");
        SYMBOL_COMMANDS.add("circ");
        SYMBOL_COMMANDS.add("bullet");
        SYMBOL_COMMANDS.add("ast");
        SYMBOL_COMMANDS.add("star");
        // relations
        SYMBOL_COMMANDS.add("leq");
        SYMBOL_COMMANDS.add("le");
        SYMBOL_COMMANDS.add("geq");
        SYMBOL_COMMANDS.add("ge");
        SYMBOL_COMMANDS.add("neq");
        SYMBOL_COMMANDS.add("ne");
        SYMBOL_COMMANDS.add("approx");
        SYMBOL_COMMANDS.add("equiv");
        SYMBOL_COMMANDS.add("sim");
        SYMBOL_COMMANDS.add("simeq");
        SYMBOL_COMMANDS.add("cong");
        SYMBOL_COMMANDS.add("propto");
        SYMBOL_COMMANDS.add("ll");
        SYMBOL_COMMANDS.add("gg");
        SYMBOL_COMMANDS.add("prec");
        SYMBOL_COMMANDS.add("succ");
        SYMBOL_COMMANDS.add("doteq");
        SYMBOL_COMMANDS.add("asymp");
        // set theory
        SYMBOL_COMMANDS.add("in");
        SYMBOL_COMMANDS.add("ni");
        SYMBOL_COMMANDS.add("notin");
        SYMBOL_COMMANDS.add("subset");
        SYMBOL_COMMANDS.add("supset");
        SYMBOL_COMMANDS.add("subseteq");
        SYMBOL_COMMANDS.add("supseteq");
        SYMBOL_COMMANDS.add("cap");
        SYMBOL_COMMANDS.add("cup");
        SYMBOL_COMMANDS.add("emptyset");
        SYMBOL_COMMANDS.add("varnothing");
        // logic
        SYMBOL_COMMANDS.add("forall");
        SYMBOL_COMMANDS.add("exists");
        SYMBOL_COMMANDS.add("neg");
        SYMBOL_COMMANDS.add("lnot");
        SYMBOL_COMMANDS.add("vee");
        SYMBOL_COMMANDS.add("wedge");
        SYMBOL_COMMANDS.add("therefore");
        SYMBOL_COMMANDS.add("because");
        SYMBOL_COMMANDS.add("vdash");
        SYMBOL_COMMANDS.add("models");
        SYMBOL_COMMANDS.add("bot");
        SYMBOL_COMMANDS.add("top");
        SYMBOL_COMMANDS.add("perp");
        // calculus / misc
        SYMBOL_COMMANDS.add("partial");
        SYMBOL_COMMANDS.add("nabla");
        SYMBOL_COMMANDS.add("infty");
        SYMBOL_COMMANDS.add("prime");
        SYMBOL_COMMANDS.add("angle");
        SYMBOL_COMMANDS.add("triangle");
        SYMBOL_COMMANDS.add("diamond");
        SYMBOL_COMMANDS.add("dagger");
        SYMBOL_COMMANDS.add("ddagger");
        SYMBOL_COMMANDS.add("aleph");
        SYMBOL_COMMANDS.add("hbar");
        SYMBOL_COMMANDS.add("imath");
        SYMBOL_COMMANDS.add("jmath");
        SYMBOL_COMMANDS.add("ell");
        SYMBOL_COMMANDS.add("wp");
        SYMBOL_COMMANDS.add("Re");
        SYMBOL_COMMANDS.add("Im");
        // arrows
        SYMBOL_COMMANDS.add("rightarrow");
        SYMBOL_COMMANDS.add("to");
        SYMBOL_COMMANDS.add("leftarrow");
        SYMBOL_COMMANDS.add("gets");
        SYMBOL_COMMANDS.add("leftrightarrow");
        SYMBOL_COMMANDS.add("Rightarrow");
        SYMBOL_COMMANDS.add("Leftarrow");
        SYMBOL_COMMANDS.add("Leftrightarrow");
        SYMBOL_COMMANDS.add("uparrow");
        SYMBOL_COMMANDS.add("downarrow");
        SYMBOL_COMMANDS.add("Uparrow");
        SYMBOL_COMMANDS.add("Downarrow");
        SYMBOL_COMMANDS.add("nearrow");
        SYMBOL_COMMANDS.add("searrow");
        SYMBOL_COMMANDS.add("nwarrow");
        SYMBOL_COMMANDS.add("swarrow");
        SYMBOL_COMMANDS.add("mapsto");
        SYMBOL_COMMANDS.add("hookleftarrow");
        SYMBOL_COMMANDS.add("hookrightarrow");
        // dots
        SYMBOL_COMMANDS.add("ldots");
        SYMBOL_COMMANDS.add("cdots");
        SYMBOL_COMMANDS.add("vdots");
        SYMBOL_COMMANDS.add("ddots");
        SYMBOL_COMMANDS.add("dots");
        // misc operators
        SYMBOL_COMMANDS.add("oplus");
        SYMBOL_COMMANDS.add("ominus");
        SYMBOL_COMMANDS.add("otimes");
        SYMBOL_COMMANDS.add("odot");
        SYMBOL_COMMANDS.add("oslash");
        // spacing commands (treated as symbols that generate space)
        SYMBOL_COMMANDS.add(",");
        SYMBOL_COMMANDS.add(";");
        SYMBOL_COMMANDS.add("!");
        SYMBOL_COMMANDS.add(" ");

        FRACTION_COMMANDS = new HashSet<String>();
        FRACTION_COMMANDS.add("frac");
        FRACTION_COMMANDS.add("dfrac");
        FRACTION_COMMANDS.add("tfrac");

        ACCENT_COMMANDS = new HashSet<String>();
        ACCENT_COMMANDS.add("hat");
        ACCENT_COMMANDS.add("check");
        ACCENT_COMMANDS.add("tilde");
        ACCENT_COMMANDS.add("acute");
        ACCENT_COMMANDS.add("grave");
        ACCENT_COMMANDS.add("dot");
        ACCENT_COMMANDS.add("ddot");
        ACCENT_COMMANDS.add("bar");
        ACCENT_COMMANDS.add("vec");
        ACCENT_COMMANDS.add("overline");
        ACCENT_COMMANDS.add("underline");
        ACCENT_COMMANDS.add("overbrace");
        ACCENT_COMMANDS.add("underbrace");
        ACCENT_COMMANDS.add("overrightarrow");
        ACCENT_COMMANDS.add("widehat");
        ACCENT_COMMANDS.add("widetilde");
    }

    public LatexParser(List<LatexToken> tokens) {
        this.tokens = tokens;
        this.pos = 0;
    }

    public AstNode parse() throws ConvertException {
        AstNode result = parseSequence();
        if (!isAtEnd()) {
            LatexToken t = peek();
            if (t.type() != LatexTokenType.EOF) {
                throw new ConvertException("Unexpected token: " + t.value(),
                        "", t.position());
            }
        }
        return result;
    }

    private AstNode parseSequence() throws ConvertException {
        SequenceNode seq = new SequenceNode();
        while (!isAtEnd() && !isSequenceTerminator()) {
            skipWhitespace();
            if (isAtEnd() || isSequenceTerminator()) {
                break;
            }
            AstNode element = parseElement();
            if (element != null) {
                seq.addChild(element);
            }
        }
        if (seq.children().size() == 1) {
            return seq.children().get(0);
        }
        return seq;
    }

    private boolean isSequenceTerminator() {
        LatexToken token = peek();
        LatexTokenType type = token.type();
        if (type == LatexTokenType.CLOSE_BRACE
                || type == LatexTokenType.CLOSE_BRACKET
                || type == LatexTokenType.EOF
                || type == LatexTokenType.AMPERSAND
                || type == LatexTokenType.DOUBLE_BACKSLASH) {
            return true;
        }
        // \right terminates a \left...\right sequence
        if (type == LatexTokenType.COMMAND && "right".equals(token.value())) {
            return true;
        }
        return false;
    }

    private AstNode parseElement() throws ConvertException {
        AstNode atom = parseAtom();
        if (atom == null) {
            return null;
        }
        return parsePostfix(atom);
    }

    private AstNode parsePostfix(AstNode base) throws ConvertException {
        skipWhitespace();
        if (isAtEnd()) {
            return base;
        }

        LatexTokenType type = peek().type();
        if (type == LatexTokenType.SUPERSCRIPT) {
            advance(); // consume ^
            AstNode exponent = parseRequiredArgument();
            skipWhitespace();
            // Check for trailing subscript: base^{sup}_{sub} -> SubSuperNode
            if (!isAtEnd() && peek().type() == LatexTokenType.SUBSCRIPT) {
                advance();
                AstNode subscript = parseRequiredArgument();
                return new SubSuperNode(base, subscript, exponent);
            }
            return new SuperscriptNode(base, exponent);
        } else if (type == LatexTokenType.SUBSCRIPT) {
            advance(); // consume _
            AstNode subscript = parseRequiredArgument();
            skipWhitespace();
            // Check for trailing superscript: base_{sub}^{sup} -> SubSuperNode
            if (!isAtEnd() && peek().type() == LatexTokenType.SUPERSCRIPT) {
                advance();
                AstNode superscript = parseRequiredArgument();
                return new SubSuperNode(base, subscript, superscript);
            }
            return new SubscriptNode(base, subscript);
        }
        return base;
    }

    private AstNode parseAtom() throws ConvertException {
        if (isAtEnd() || isSequenceTerminator()) {
            return null;
        }

        LatexToken token = peek();
        switch (token.type()) {
            case OPEN_BRACE:
                return parseGroup();
            case COMMAND:
                return parseCommand();
            case TEXT:
                return parseTextToken();
            case NUMBER:
                advance();
                return new NumberNode(token.value());
            case OPERATOR:
                advance();
                return new SymbolNode(token.value());
            case OPEN_PAREN:
                advance();
                return new SymbolNode("(");
            case CLOSE_PAREN:
                advance();
                return new SymbolNode(")");
            case OPEN_BRACKET:
                advance();
                return new SymbolNode("[");
            case CLOSE_BRACKET:
                advance();
                return new SymbolNode("]");
            case PIPE:
                advance();
                return new SymbolNode("|");
            case WHITESPACE:
                skipWhitespace();
                return parseAtom();
            default:
                throw new ConvertException("Unexpected token: " + token.value(),
                        "", token.position());
        }
    }

    private AstNode parseTextToken() throws ConvertException {
        LatexToken token = advance();
        String text = token.value();
        // Single letter -> symbol (variable)
        if (text.length() == 1) {
            return new SymbolNode(text);
        }
        // Multi-letter text token: treat each letter as a separate symbol
        SequenceNode seq = new SequenceNode();
        for (int i = 0; i < text.length(); i++) {
            seq.addChild(new SymbolNode(String.valueOf(text.charAt(i))));
        }
        return seq;
    }

    private AstNode parseGroup() throws ConvertException {
        expect(LatexTokenType.OPEN_BRACE);
        AstNode content = parseSequence();
        expect(LatexTokenType.CLOSE_BRACE);
        return new GroupNode(content);
    }

    private AstNode parseCommand() throws ConvertException {
        LatexToken token = advance();
        String name = token.value();

        if (FRACTION_COMMANDS.contains(name)) {
            return parseFraction(name);
        }
        if ("sqrt".equals(name)) {
            return parseSqrt();
        }
        if (BIG_OPERATORS.contains(name)) {
            return parseBigOperator(name);
        }
        if (FUNCTION_NAMES.contains(name)) {
            return parseFunction(name);
        }
        if ("left".equals(name)) {
            return parseLeftRight();
        }
        if ("text".equals(name) || "mathrm".equals(name) || "textrm".equals(name)) {
            return parseTextCommand();
        }
        if ("mathbf".equals(name) || "mathit".equals(name) || "boldsymbol".equals(name)) {
            return parseRequiredGroup();
        }
        if (ACCENT_COMMANDS.contains(name)) {
            return parseAccent(name);
        }
        if ("binom".equals(name)) {
            return parseBinom();
        }
        if (GREEK_LETTERS.contains(name)) {
            return new SymbolNode(name);
        }
        if (SYMBOL_COMMANDS.contains(name)) {
            return new SymbolNode(name);
        }
        if ("right".equals(name)) {
            // This should be handled by parseLeftRight; if we encounter it here,
            // it means mismatched delimiters. Return as symbol to be lenient.
            return new SymbolNode("right");
        }

        // Unknown command: pass through as symbol
        return new SymbolNode(name);
    }

    private AstNode parseFraction(String variant) throws ConvertException {
        AstNode numerator = parseRequiredGroup();
        AstNode denominator = parseRequiredGroup();
        return new FractionNode(numerator, denominator, variant);
    }

    private AstNode parseSqrt() throws ConvertException {
        skipWhitespace();
        AstNode index = null;
        // Check for optional [n]
        if (!isAtEnd() && peek().type() == LatexTokenType.OPEN_BRACKET) {
            advance(); // consume [
            index = parseSequence();
            expect(LatexTokenType.CLOSE_BRACKET);
        }
        AstNode radicand = parseRequiredGroup();
        return new RootNode(radicand, index);
    }

    private AstNode parseBigOperator(String operatorName) throws ConvertException {
        skipWhitespace();
        AstNode lower = null;
        AstNode upper = null;

        // Parse optional _{lower}
        if (!isAtEnd() && peek().type() == LatexTokenType.SUBSCRIPT) {
            advance();
            lower = parseRequiredArgument();
            skipWhitespace();
        }
        // Parse optional ^{upper}
        if (!isAtEnd() && peek().type() == LatexTokenType.SUPERSCRIPT) {
            advance();
            upper = parseRequiredArgument();
            skipWhitespace();
        }
        // Also handle ^{upper}_{lower} order
        if (lower == null && !isAtEnd() && peek().type() == LatexTokenType.SUBSCRIPT) {
            advance();
            lower = parseRequiredArgument();
        }

        return new BigOperatorNode(operatorName, lower, upper);
    }

    private AstNode parseFunction(String functionName) throws ConvertException {
        skipWhitespace();
        // Functions can have subscripts/superscripts (e.g., \lim_{x \to 0})
        // but we handle that in parsePostfix, so just return a FunctionNode
        // with no argument -- the body follows naturally in the sequence
        return new FunctionNode(functionName, null);
    }

    private AstNode parseLeftRight() throws ConvertException {
        skipWhitespace();
        String leftDelim = parseDelimiterSymbol();

        AstNode content = parseSequence();

        // Expect \right (which is a sequence terminator, so parseSequence stopped before it)
        skipWhitespace();
        LatexToken token = peek();
        if (token.type() == LatexTokenType.COMMAND && "right".equals(token.value())) {
            advance(); // consume \right
            skipWhitespace();
            String rightDelim = parseDelimiterSymbol();
            return new DelimiterNode(leftDelim, rightDelim, content);
        }
        // Missing \right -- be lenient
        return new DelimiterNode(leftDelim, ")", content);
    }

    private String parseDelimiterSymbol() throws ConvertException {
        if (isAtEnd()) {
            throw new ConvertException("Expected delimiter symbol", "", pos);
        }
        LatexToken token = peek();
        switch (token.type()) {
            case OPEN_PAREN:
                advance();
                return "(";
            case CLOSE_PAREN:
                advance();
                return ")";
            case OPEN_BRACKET:
                advance();
                return "[";
            case CLOSE_BRACKET:
                advance();
                return "]";
            case PIPE:
                advance();
                return "|";
            case OPERATOR:
                if (".".equals(token.value())) {
                    advance();
                    return ".";
                }
                if ("<".equals(token.value())) {
                    advance();
                    return "<";
                }
                if (">".equals(token.value())) {
                    advance();
                    return ">";
                }
                advance();
                return token.value();
            case COMMAND:
                advance();
                String val = token.value();
                if ("{".equals(val) || "lbrace".equals(val)) {
                    return "\\{";
                }
                if ("}".equals(val) || "rbrace".equals(val)) {
                    return "\\}";
                }
                if ("|".equals(val) || "Vert".equals(val) || "vert".equals(val)) {
                    return val;
                }
                if ("langle".equals(val)) {
                    return "<";
                }
                if ("rangle".equals(val)) {
                    return ">";
                }
                if ("lfloor".equals(val)) {
                    return "lfloor";
                }
                if ("rfloor".equals(val)) {
                    return "rfloor";
                }
                if ("lceil".equals(val)) {
                    return "lceil";
                }
                if ("rceil".equals(val)) {
                    return "rceil";
                }
                return val;
            default:
                throw new ConvertException("Expected delimiter symbol, got: " + token.value(),
                        "", token.position());
        }
    }

    private AstNode parseTextCommand() throws ConvertException {
        skipWhitespace();
        expect(LatexTokenType.OPEN_BRACE);
        StringBuilder sb = new StringBuilder();
        while (!isAtEnd() && peek().type() != LatexTokenType.CLOSE_BRACE) {
            LatexToken t = advance();
            sb.append(t.value());
        }
        expect(LatexTokenType.CLOSE_BRACE);
        return new TextNode(sb.toString());
    }

    private AstNode parseAccent(String accentName) throws ConvertException {
        skipWhitespace();
        AstNode argument = parseRequiredArgument();
        // Represent as a FunctionNode with the accent name
        // The HwpScriptGenerator will handle translating accent names
        return new FunctionNode(accentName, argument);
    }

    private AstNode parseBinom() throws ConvertException {
        AstNode top = parseRequiredGroup();
        AstNode bottom = parseRequiredGroup();
        // Represent as LEFT ( top ATOP bottom RIGHT ) or similar
        // For simplicity, use a FractionNode with "binom" variant
        return new FractionNode(top, bottom, "binom");
    }

    private AstNode parseRequiredArgument() throws ConvertException {
        skipWhitespace();
        if (isAtEnd()) {
            throw new ConvertException("Expected argument", "", pos);
        }
        if (peek().type() == LatexTokenType.OPEN_BRACE) {
            return parseRequiredGroup();
        }
        // Single token as argument
        return parseAtom();
    }

    private AstNode parseRequiredGroup() throws ConvertException {
        skipWhitespace();
        if (isAtEnd()) {
            throw new ConvertException("Expected '{'", "", pos);
        }
        if (peek().type() == LatexTokenType.OPEN_BRACE) {
            expect(LatexTokenType.OPEN_BRACE);
            AstNode content = parseSequence();
            expect(LatexTokenType.CLOSE_BRACE);
            return content;
        }
        // If not a brace group, parse single atom
        return parseAtom();
    }

    // --- Helper methods ---

    private LatexToken peek() {
        if (pos >= tokens.size()) {
            return new LatexToken(LatexTokenType.EOF, "", -1);
        }
        return tokens.get(pos);
    }

    private LatexToken advance() {
        LatexToken token = tokens.get(pos);
        pos++;
        return token;
    }

    private LatexToken expect(LatexTokenType type) throws ConvertException {
        if (isAtEnd()) {
            throw new ConvertException("Expected " + type + " but reached end of input", "", pos);
        }
        LatexToken token = peek();
        if (token.type() != type) {
            throw new ConvertException("Expected " + type + " but got " + token.type()
                    + " ('" + token.value() + "')", "", token.position());
        }
        return advance();
    }

    private boolean isAtEnd() {
        return pos >= tokens.size() || peek().type() == LatexTokenType.EOF;
    }

    private void skipWhitespace() {
        while (!isAtEnd() && peek().type() == LatexTokenType.WHITESPACE) {
            advance();
        }
    }
}
