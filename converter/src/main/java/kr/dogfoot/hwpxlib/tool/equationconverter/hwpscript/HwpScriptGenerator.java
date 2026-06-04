package kr.dogfoot.hwpxlib.tool.equationconverter.hwpscript;

import kr.dogfoot.hwpxlib.tool.equationconverter.ConvertException;
import kr.dogfoot.hwpxlib.tool.equationconverter.latex.ast.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class HwpScriptGenerator {
    private static final Map<String, String> ACCENT_MAP;

    static {
        ACCENT_MAP = new HashMap<String, String>();
        ACCENT_MAP.put("hat", "HAT");
        ACCENT_MAP.put("widehat", "HAT");
        ACCENT_MAP.put("check", "CHECK");
        ACCENT_MAP.put("tilde", "TILDE");
        ACCENT_MAP.put("widetilde", "TILDE");
        ACCENT_MAP.put("acute", "ACUTE");
        ACCENT_MAP.put("grave", "GRAVE");
        ACCENT_MAP.put("dot", "DOT");
        ACCENT_MAP.put("ddot", "DDOT");
        ACCENT_MAP.put("bar", "BAR");
        ACCENT_MAP.put("vec", "VEC");
        ACCENT_MAP.put("overline", "OVERLINE");
        ACCENT_MAP.put("underline", "UNDERLINE");
        ACCENT_MAP.put("overbrace", "OVERBRACE");
        ACCENT_MAP.put("underbrace", "UNDERBRACE");
        ACCENT_MAP.put("overrightarrow", "OVERARROW");
    }

    public String generate(AstNode node) throws ConvertException {
        HwpScriptBuilder builder = new HwpScriptBuilder();
        visit(node, builder);
        return builder.result();
    }

    private void visit(AstNode node, HwpScriptBuilder b) throws ConvertException {
        if (node == null) {
            return;
        }
        switch (node.nodeType()) {
            case SEQUENCE:
                visitSequence((SequenceNode) node, b);
                break;
            case NUMBER:
                visitNumber((NumberNode) node, b);
                break;
            case SYMBOL:
                visitSymbol((SymbolNode) node, b);
                break;
            case TEXT:
                visitText((TextNode) node, b);
                break;
            case GROUP:
                visitGroup((GroupNode) node, b);
                break;
            case FRACTION:
                visitFraction((FractionNode) node, b);
                break;
            case ROOT:
                visitRoot((RootNode) node, b);
                break;
            case SUPERSCRIPT:
                visitSuperscript((SuperscriptNode) node, b);
                break;
            case SUBSCRIPT:
                visitSubscript((SubscriptNode) node, b);
                break;
            case SUB_SUPER:
                visitSubSuper((SubSuperNode) node, b);
                break;
            case BIG_OPERATOR:
                visitBigOperator((BigOperatorNode) node, b);
                break;
            case FUNCTION:
                visitFunction((FunctionNode) node, b);
                break;
            case DELIMITER:
                visitDelimiter((DelimiterNode) node, b);
                break;
            default:
                throw new ConvertException("Unsupported AST node type: " + node.nodeType());
        }
    }

    private void visitSequence(SequenceNode node, HwpScriptBuilder b) throws ConvertException {
        List<AstNode> children = node.children();
        for (int i = 0; i < children.size(); i++) {
            AstNode child = children.get(i);
            if (i > 0) {
                AstNode prev = children.get(i - 1);
                if (needsSpaceBetween(prev, child)) {
                    b.space();
                }
            }
            visit(child, b);
        }
    }

    private boolean needsSpaceBetween(AstNode prev, AstNode next) {
        // Complex nodes (fraction, root, operator, function, delimiter, script) always need space
        if (isComplexNode(prev) || isComplexNode(next)) {
            return true;
        }
        // Word-like nodes (greek letters, multi-char symbols) need space around them
        if (isWordNode(prev) || isWordNode(next)) {
            return true;
        }
        // Operators like +, -, = need spaces
        if (isOperatorNode(prev) || isOperatorNode(next)) {
            return true;
        }
        return false;
    }

    private boolean isComplexNode(AstNode node) {
        switch (node.nodeType()) {
            case FRACTION:
            case ROOT:
            case BIG_OPERATOR:
            case FUNCTION:
            case DELIMITER:
            case SUPERSCRIPT:
            case SUBSCRIPT:
            case SUB_SUPER:
                return true;
            default:
                return false;
        }
    }

    private boolean isWordNode(AstNode node) {
        if (node.nodeType() == AstNodeType.SYMBOL) {
            String name = ((SymbolNode) node).name();
            return name.length() > 1 && Character.isLetter(name.charAt(0));
        }
        if (node.nodeType() == AstNodeType.TEXT) {
            return true;
        }
        return false;
    }

    private boolean isOperatorNode(AstNode node) {
        if (node.nodeType() == AstNodeType.SYMBOL) {
            String name = ((SymbolNode) node).name();
            if (name.length() == 1) {
                char c = name.charAt(0);
                return c == '+' || c == '-' || c == '=' || c == '<' || c == '>'
                        || c == ',';
            }
        }
        return false;
    }

    private void visitNumber(NumberNode node, HwpScriptBuilder b) {
        b.append(node.value());
    }

    private void visitSymbol(SymbolNode node, HwpScriptBuilder b) {
        String name = node.name();

        if (GreekLetterMap.contains(name)) {
            b.appendWithSpace(GreekLetterMap.toHwp(name));
        } else if (SymbolMap.contains(name)) {
            String hwp = SymbolMap.toHwp(name);
            if (hwp.length() > 0) {
                // Multi-char HWP symbols need surrounding spaces
                if (hwp.length() > 1 && Character.isLetter(hwp.charAt(0))) {
                    b.appendWithSpace(hwp);
                } else {
                    b.append(hwp);
                }
            }
        } else {
            // Single char operators/variables pass through directly
            b.append(name);
        }
    }

    private void visitText(TextNode node, HwpScriptBuilder b) {
        b.appendWithSpace("\"" + node.text() + "\"");
    }

    private void visitGroup(GroupNode node, HwpScriptBuilder b) throws ConvertException {
        b.openBrace();
        visit(node.child(), b);
        b.closeBrace();
    }

    private void visitFraction(FractionNode node, HwpScriptBuilder b) throws ConvertException {
        String hwpOp;
        if ("tfrac".equals(node.variant())) {
            hwpOp = "smallover";
        } else if ("binom".equals(node.variant())) {
            // binom -> LEFT ( {n} ATOP {k} RIGHT )
            b.appendWithSpace("LEFT ( ");
            b.openBrace();
            visit(node.numerator(), b);
            b.closeBrace();
            b.appendWithSpace("atop");
            b.space();
            b.openBrace();
            visit(node.denominator(), b);
            b.closeBrace();
            b.appendWithSpace("RIGHT )");
            return;
        } else {
            hwpOp = "over";
        }

        b.openBrace();
        visit(node.numerator(), b);
        b.closeBrace();
        b.appendWithSpace(hwpOp);
        b.space();
        b.openBrace();
        visit(node.denominator(), b);
        b.closeBrace();
    }

    private void visitRoot(RootNode node, HwpScriptBuilder b) throws ConvertException {
        if (node.index() == null) {
            b.appendWithSpace("sqrt");
            b.space();
            b.openBrace();
            visit(node.radicand(), b);
            b.closeBrace();
        } else {
            b.appendWithSpace("root");
            b.space();
            visit(node.index(), b);
            b.appendWithSpace("of");
            b.space();
            b.openBrace();
            visit(node.radicand(), b);
            b.closeBrace();
        }
    }

    private void visitSuperscript(SuperscriptNode node, HwpScriptBuilder b) throws ConvertException {
        visit(node.base(), b);
        b.space();
        b.append("^");
        b.openBrace();
        visit(node.exponent(), b);
        b.closeBrace();
    }

    private void visitSubscript(SubscriptNode node, HwpScriptBuilder b) throws ConvertException {
        visit(node.base(), b);
        b.space();
        b.append("_");
        b.openBrace();
        visit(node.subscript(), b);
        b.closeBrace();
    }

    private void visitSubSuper(SubSuperNode node, HwpScriptBuilder b) throws ConvertException {
        visit(node.base(), b);
        b.space();
        b.append("_");
        b.openBrace();
        visit(node.subscript(), b);
        b.closeBrace();
        b.space();
        b.append("^");
        b.openBrace();
        visit(node.superscript(), b);
        b.closeBrace();
    }

    private void visitBigOperator(BigOperatorNode node, HwpScriptBuilder b) throws ConvertException {
        String hwpOp = BigOperatorMap.toHwp(node.operator());
        if (hwpOp == null) {
            hwpOp = node.operator();
        }
        b.appendWithSpace(hwpOp);

        if (node.lower() != null) {
            b.space();
            b.append("_");
            b.openBrace();
            visit(node.lower(), b);
            b.closeBrace();
        }
        if (node.upper() != null) {
            b.space();
            b.append("^");
            b.openBrace();
            visit(node.upper(), b);
            b.closeBrace();
        }
    }

    private void visitFunction(FunctionNode node, HwpScriptBuilder b) throws ConvertException {
        String name = node.name();

        // Check if this is an accent command
        if (ACCENT_MAP.containsKey(name)) {
            String hwpAccent = ACCENT_MAP.get(name);
            b.appendWithSpace(hwpAccent);
            b.space();
            if (node.argument() != null) {
                visit(node.argument(), b);
            }
            return;
        }

        // Regular function
        String hwpFunc = FunctionNameMap.toHwp(name);
        if (hwpFunc == null) {
            hwpFunc = name;
        }
        b.appendWithSpace(hwpFunc);

        if (node.argument() != null) {
            b.space();
            visit(node.argument(), b);
        }
    }

    private void visitDelimiter(DelimiterNode node, HwpScriptBuilder b) throws ConvertException {
        b.appendWithSpace("LEFT");
        b.space();
        b.append(mapDelimiter(node.leftDelim()));
        b.space();
        visit(node.content(), b);
        b.space();
        b.append("RIGHT");
        b.space();
        b.append(mapDelimiter(node.rightDelim()));
    }

    private String mapDelimiter(String delim) {
        if (".".equals(delim)) {
            return "NONE";
        }
        if ("\\{".equals(delim)) {
            return "lbrace";
        }
        if ("\\}".equals(delim)) {
            return "rbrace";
        }
        if ("lfloor".equals(delim) || "rfloor".equals(delim)
                || "lceil".equals(delim) || "rceil".equals(delim)) {
            return delim;
        }
        if ("<".equals(delim)) {
            return "<";
        }
        if (">".equals(delim)) {
            return ">";
        }
        if ("Vert".equals(delim)) {
            return "||";
        }
        return delim;
    }
}
