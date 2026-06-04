package kr.dogfoot.hwpxlib.tool.equationconverter.mathml;

import kr.dogfoot.hwpxlib.tool.equationconverter.ConvertException;
import kr.dogfoot.hwpxlib.tool.equationconverter.latex.ast.*;
import org.w3c.dom.*;
import org.xml.sax.InputSource;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.StringReader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * MathML (Presentation MathML) 을 AST 로 변환하는 파서.
 * 기존 LaTeX 파서와 동일한 AST 노드를 재사용하여
 * HwpScriptGenerator 로 HWP 수식 스크립트를 생성할 수 있다.
 */
public class MathMLParser {

    private static final Map<String, String> ENTITY_MAP;

    static {
        ENTITY_MAP = new HashMap<String, String>();
        // 그리스 소문자
        ENTITY_MAP.put("\u03B1", "alpha");   // α
        ENTITY_MAP.put("\u03B2", "beta");    // β
        ENTITY_MAP.put("\u03B3", "gamma");   // γ
        ENTITY_MAP.put("\u03B4", "delta");   // δ
        ENTITY_MAP.put("\u03B5", "epsilon"); // ε
        ENTITY_MAP.put("\u03B6", "zeta");    // ζ
        ENTITY_MAP.put("\u03B7", "eta");     // η
        ENTITY_MAP.put("\u03B8", "theta");   // θ
        ENTITY_MAP.put("\u03B9", "iota");    // ι
        ENTITY_MAP.put("\u03BA", "kappa");   // κ
        ENTITY_MAP.put("\u03BB", "lambda");  // λ
        ENTITY_MAP.put("\u03BC", "mu");      // μ
        ENTITY_MAP.put("\u03BD", "nu");      // ν
        ENTITY_MAP.put("\u03BE", "xi");      // ξ
        ENTITY_MAP.put("\u03C0", "pi");      // π
        ENTITY_MAP.put("\u03C1", "rho");     // ρ
        ENTITY_MAP.put("\u03C3", "sigma");   // σ
        ENTITY_MAP.put("\u03C4", "tau");     // τ
        ENTITY_MAP.put("\u03C5", "upsilon"); // υ
        ENTITY_MAP.put("\u03C6", "phi");     // φ
        ENTITY_MAP.put("\u03C7", "chi");     // χ
        ENTITY_MAP.put("\u03C8", "psi");     // ψ
        ENTITY_MAP.put("\u03C9", "omega");   // ω

        // 그리스 대문자
        ENTITY_MAP.put("\u0393", "Gamma");
        ENTITY_MAP.put("\u0394", "Delta");
        ENTITY_MAP.put("\u0398", "Theta");
        ENTITY_MAP.put("\u039B", "Lambda");
        ENTITY_MAP.put("\u039E", "Xi");
        ENTITY_MAP.put("\u03A0", "Pi");
        ENTITY_MAP.put("\u03A3", "Sigma");
        ENTITY_MAP.put("\u03A5", "Upsilon");
        ENTITY_MAP.put("\u03A6", "Phi");
        ENTITY_MAP.put("\u03A8", "Psi");
        ENTITY_MAP.put("\u03A9", "Omega");

        // 특수 기호
        ENTITY_MAP.put("\u221E", "infty");        // ∞
        ENTITY_MAP.put("\u2202", "partial");       // ∂
        ENTITY_MAP.put("\u2207", "nabla");         // ∇
        ENTITY_MAP.put("\u2200", "forall");        // ∀
        ENTITY_MAP.put("\u2203", "exists");        // ∃
        ENTITY_MAP.put("\u2205", "emptyset");      // ∅
        ENTITY_MAP.put("\u00AC", "neg");           // ¬

        // 연산자
        ENTITY_MAP.put("\u00D7", "times");         // ×
        ENTITY_MAP.put("\u00F7", "div");           // ÷
        ENTITY_MAP.put("\u00B1", "pm");            // ±
        ENTITY_MAP.put("\u2213", "mp");            // ∓
        ENTITY_MAP.put("\u22C5", "cdot");          // ⋅
        ENTITY_MAP.put("\u2218", "circ");          // ∘

        // 관계
        ENTITY_MAP.put("\u2264", "leq");           // ≤
        ENTITY_MAP.put("\u2265", "geq");           // ≥
        ENTITY_MAP.put("\u2260", "neq");           // ≠
        ENTITY_MAP.put("\u2248", "approx");        // ≈
        ENTITY_MAP.put("\u2261", "equiv");         // ≡
        ENTITY_MAP.put("\u223C", "sim");           // ∼
        ENTITY_MAP.put("\u221D", "propto");        // ∝
        ENTITY_MAP.put("\u226A", "ll");            // ≪
        ENTITY_MAP.put("\u226B", "gg");            // ≫

        // 집합
        ENTITY_MAP.put("\u2208", "in");            // ∈
        ENTITY_MAP.put("\u220B", "ni");            // ∋
        ENTITY_MAP.put("\u2209", "notin");         // ∉
        ENTITY_MAP.put("\u2282", "subset");        // ⊂
        ENTITY_MAP.put("\u2283", "supset");        // ⊃
        ENTITY_MAP.put("\u2286", "subseteq");      // ⊆
        ENTITY_MAP.put("\u2287", "supseteq");      // ⊇
        ENTITY_MAP.put("\u2229", "cap");           // ∩
        ENTITY_MAP.put("\u222A", "cup");           // ∪

        // 화살표
        ENTITY_MAP.put("\u2192", "rightarrow");    // →
        ENTITY_MAP.put("\u2190", "leftarrow");     // ←
        ENTITY_MAP.put("\u2194", "leftrightarrow");// ↔
        ENTITY_MAP.put("\u21D2", "Rightarrow");    // ⇒
        ENTITY_MAP.put("\u21D0", "Leftarrow");     // ⇐
        ENTITY_MAP.put("\u21D4", "Leftrightarrow");// ⇔
        ENTITY_MAP.put("\u2191", "uparrow");       // ↑
        ENTITY_MAP.put("\u2193", "downarrow");     // ↓
        ENTITY_MAP.put("\u21A6", "mapsto");        // ↦

        // 점
        ENTITY_MAP.put("\u2026", "ldots");         // …
        ENTITY_MAP.put("\u22EF", "cdots");         // ⋯
        ENTITY_MAP.put("\u22EE", "vdots");         // ⋮
        ENTITY_MAP.put("\u22F1", "ddots");         // ⋱

        // 논리
        ENTITY_MAP.put("\u2228", "vee");           // ∨
        ENTITY_MAP.put("\u2227", "wedge");         // ∧
        ENTITY_MAP.put("\u2234", "therefore");     // ∴
        ENTITY_MAP.put("\u2235", "because");       // ∵
        ENTITY_MAP.put("\u22A2", "vdash");         // ⊢

        // 기타
        ENTITY_MAP.put("\u210F", "hbar");          // ℏ
        ENTITY_MAP.put("\u2113", "ell");           // ℓ
        ENTITY_MAP.put("\u2032", "prime");         // ′
        ENTITY_MAP.put("\u2220", "angle");         // ∠
    }

    private static final Map<String, String> BIG_OP_MAP;

    static {
        BIG_OP_MAP = new HashMap<String, String>();
        BIG_OP_MAP.put("\u2211", "sum");       // ∑
        BIG_OP_MAP.put("\u220F", "prod");      // ∏
        BIG_OP_MAP.put("\u2210", "coprod");    // ∐
        BIG_OP_MAP.put("\u222B", "int");       // ∫
        BIG_OP_MAP.put("\u222E", "oint");      // ∮
        BIG_OP_MAP.put("\u222C", "iint");      // ∬
        BIG_OP_MAP.put("\u222D", "iiint");     // ∭
        BIG_OP_MAP.put("\u22C3", "bigcup");    // ⋃
        BIG_OP_MAP.put("\u22C2", "bigcap");    // ⋂
        BIG_OP_MAP.put("\u2A01", "bigoplus");  // ⨁
        BIG_OP_MAP.put("\u2A02", "bigotimes"); // ⨂
        BIG_OP_MAP.put("\u22C1", "bigvee");    // ⋁
        BIG_OP_MAP.put("\u22C0", "bigwedge");  // ⋀
    }

    private static final Map<String, String> FUNC_MAP;

    static {
        FUNC_MAP = new HashMap<String, String>();
        String[] funcs = {"sin", "cos", "tan", "cot", "sec", "csc",
                "arcsin", "arccos", "arctan",
                "sinh", "cosh", "tanh", "coth",
                "log", "ln", "lg", "exp",
                "det", "gcd", "max", "min",
                "sup", "inf", "lim", "limsup", "liminf",
                "arg", "deg", "dim", "hom", "ker", "mod"};
        for (String f : funcs) {
            FUNC_MAP.put(f, f);
        }
    }

    private static final Map<String, String> ACCENT_MAP;

    static {
        ACCENT_MAP = new HashMap<String, String>();
        ACCENT_MAP.put("\u005E", "hat");       // ^
        ACCENT_MAP.put("\u02C7", "check");     // ˇ
        ACCENT_MAP.put("\u007E", "tilde");     // ~
        ACCENT_MAP.put("\u02DC", "tilde");     // ˜
        ACCENT_MAP.put("\u00B4", "acute");     // ´
        ACCENT_MAP.put("\u0060", "grave");     // `
        ACCENT_MAP.put("\u02D9", "dot");       // ˙
        ACCENT_MAP.put("\u0307", "dot");       // combining dot above
        ACCENT_MAP.put("\u00A8", "ddot");      // ¨
        ACCENT_MAP.put("\u0308", "ddot");      // combining diaeresis
        ACCENT_MAP.put("\u00AF", "bar");       // ¯
        ACCENT_MAP.put("\u0304", "bar");       // combining macron
        ACCENT_MAP.put("\u2192", "overrightarrow"); // → (in mover context)
        ACCENT_MAP.put("\u20D7", "vec");       // combining arrow
    }

    /**
     * MathML 문자열을 파싱하여 AST를 생성한다.
     */
    public AstNode parse(String mathml) throws ConvertException {
        if (mathml == null || mathml.trim().length() == 0) {
            return new SequenceNode();
        }
        try {
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setNamespaceAware(false);
            // Disable DTD loading to avoid entity resolution issues
            factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
            DocumentBuilder builder = factory.newDocumentBuilder();
            Document doc = builder.parse(new InputSource(new StringReader(mathml)));
            Element root = doc.getDocumentElement();
            return parseElement(root);
        } catch (ConvertException e) {
            throw e;
        } catch (Exception e) {
            throw new ConvertException("Failed to parse MathML: " + e.getMessage());
        }
    }

    private AstNode parseElement(Element elem) throws ConvertException {
        String tag = localName(elem);

        if ("math".equals(tag)) {
            return parseMathChildren(elem);
        } else if ("mrow".equals(tag)) {
            return parseMrow(elem);
        } else if ("mn".equals(tag)) {
            return new NumberNode(textContent(elem));
        } else if ("mi".equals(tag)) {
            return parseMi(elem);
        } else if ("mo".equals(tag)) {
            return parseMo(elem);
        } else if ("mtext".equals(tag)) {
            return new TextNode(textContent(elem));
        } else if ("mfrac".equals(tag)) {
            return parseMfrac(elem);
        } else if ("msqrt".equals(tag)) {
            return parseMsqrt(elem);
        } else if ("mroot".equals(tag)) {
            return parseMroot(elem);
        } else if ("msup".equals(tag)) {
            return parseMsup(elem);
        } else if ("msub".equals(tag)) {
            return parseMsub(elem);
        } else if ("msubsup".equals(tag)) {
            return parseMsubsup(elem);
        } else if ("munder".equals(tag)) {
            return parseMunder(elem);
        } else if ("mover".equals(tag)) {
            return parseMover(elem);
        } else if ("munderover".equals(tag)) {
            return parseMunderover(elem);
        } else if ("mfenced".equals(tag)) {
            return parseMfenced(elem);
        } else if ("menclose".equals(tag)) {
            return parseMenclose(elem);
        } else if ("mpadded".equals(tag) || "mstyle".equals(tag) || "mphantom".equals(tag)
                || "semantics".equals(tag)) {
            return parseMathChildren(elem);
        } else if ("annotation".equals(tag) || "annotation-xml".equals(tag)) {
            // 무시
            return new SequenceNode();
        } else {
            // 알 수 없는 태그는 자식을 순회
            return parseMathChildren(elem);
        }
    }

    private AstNode parseMathChildren(Element elem) throws ConvertException {
        List<Element> children = childElements(elem);
        if (children.isEmpty()) {
            String text = textContent(elem).trim();
            if (text.length() > 0) {
                return parseTextContent(text);
            }
            return new SequenceNode();
        }
        if (children.size() == 1) {
            return parseElement(children.get(0));
        }
        SequenceNode seq = new SequenceNode();
        for (Element child : children) {
            AstNode node = parseElement(child);
            if (node != null) {
                seq.addChild(node);
            }
        }
        return seq;
    }

    private AstNode parseMrow(Element elem) throws ConvertException {
        return parseMathChildren(elem);
    }

    private AstNode parseMi(Element elem) throws ConvertException {
        String text = textContent(elem).trim();
        if (text.length() == 0) {
            return new SymbolNode("");
        }

        // Unicode → LaTeX 변환 (그리스 등)
        String mapped = ENTITY_MAP.get(text);
        if (mapped != null) {
            return new SymbolNode(mapped);
        }

        // 함수명 체크
        if (FUNC_MAP.containsKey(text)) {
            return new FunctionNode(text, null);
        }

        // 일반 변수 (단일 문자)
        return new SymbolNode(text);
    }

    private AstNode parseMo(Element elem) throws ConvertException {
        String text = textContent(elem).trim();
        if (text.length() == 0) {
            return new SymbolNode("");
        }

        // 대형 연산자 체크
        String bigOp = BIG_OP_MAP.get(text);
        if (bigOp != null) {
            return new BigOperatorNode(bigOp, null, null);
        }

        // Unicode → LaTeX 기호 매핑
        String mapped = ENTITY_MAP.get(text);
        if (mapped != null) {
            return new SymbolNode(mapped);
        }

        // 함수명 체크 (mo 안에 오는 경우도 있음)
        if (FUNC_MAP.containsKey(text)) {
            return new FunctionNode(text, null);
        }

        // 그대로 전달 (+ - = 등)
        return new SymbolNode(text);
    }

    private AstNode parseMfrac(Element elem) throws ConvertException {
        List<Element> children = childElements(elem);
        if (children.size() < 2) {
            throw new ConvertException("mfrac requires 2 children");
        }
        AstNode num = parseElement(children.get(0));
        AstNode den = parseElement(children.get(1));
        return new FractionNode(num, den, "frac");
    }

    private AstNode parseMsqrt(Element elem) throws ConvertException {
        AstNode content = parseMathChildren(elem);
        return new RootNode(content, null);
    }

    private AstNode parseMroot(Element elem) throws ConvertException {
        List<Element> children = childElements(elem);
        if (children.size() < 2) {
            throw new ConvertException("mroot requires 2 children");
        }
        AstNode radicand = parseElement(children.get(0));
        AstNode index = parseElement(children.get(1));
        return new RootNode(radicand, index);
    }

    private AstNode parseMsup(Element elem) throws ConvertException {
        List<Element> children = childElements(elem);
        if (children.size() < 2) {
            throw new ConvertException("msup requires 2 children");
        }
        AstNode base = parseElement(children.get(0));
        AstNode sup = parseElement(children.get(1));

        // base가 BigOperator인 경우 → upper limit 설정
        if (base instanceof BigOperatorNode) {
            BigOperatorNode op = (BigOperatorNode) base;
            return new BigOperatorNode(op.operator(), op.lower(), sup);
        }

        return new SuperscriptNode(base, sup);
    }

    private AstNode parseMsub(Element elem) throws ConvertException {
        List<Element> children = childElements(elem);
        if (children.size() < 2) {
            throw new ConvertException("msub requires 2 children");
        }
        AstNode base = parseElement(children.get(0));
        AstNode sub = parseElement(children.get(1));

        // base가 BigOperator인 경우 → lower limit 설정
        if (base instanceof BigOperatorNode) {
            BigOperatorNode op = (BigOperatorNode) base;
            return new BigOperatorNode(op.operator(), sub, op.upper());
        }

        // base가 Function인 경우 (e.g. log_2)
        if (base instanceof FunctionNode) {
            FunctionNode fn = (FunctionNode) base;
            return new SubscriptNode(new SymbolNode(fn.name()), sub);
        }

        return new SubscriptNode(base, sub);
    }

    private AstNode parseMsubsup(Element elem) throws ConvertException {
        List<Element> children = childElements(elem);
        if (children.size() < 3) {
            throw new ConvertException("msubsup requires 3 children");
        }
        AstNode base = parseElement(children.get(0));
        AstNode sub = parseElement(children.get(1));
        AstNode sup = parseElement(children.get(2));

        // base가 BigOperator인 경우 → limits 설정
        if (base instanceof BigOperatorNode) {
            BigOperatorNode op = (BigOperatorNode) base;
            return new BigOperatorNode(op.operator(), sub, sup);
        }

        return new SubSuperNode(base, sub, sup);
    }

    private AstNode parseMunder(Element elem) throws ConvertException {
        List<Element> children = childElements(elem);
        if (children.size() < 2) {
            throw new ConvertException("munder requires 2 children");
        }
        AstNode base = parseElement(children.get(0));
        AstNode under = parseElement(children.get(1));

        // base가 BigOperator인 경우
        if (base instanceof BigOperatorNode) {
            BigOperatorNode op = (BigOperatorNode) base;
            return new BigOperatorNode(op.operator(), under, op.upper());
        }

        // underbrace 등 → SubscriptNode로 처리
        return new SubscriptNode(base, under);
    }

    private AstNode parseMover(Element elem) throws ConvertException {
        List<Element> children = childElements(elem);
        if (children.size() < 2) {
            throw new ConvertException("mover requires 2 children");
        }
        AstNode base = parseElement(children.get(0));
        AstNode over = parseElement(children.get(1));

        // base가 BigOperator인 경우
        if (base instanceof BigOperatorNode) {
            BigOperatorNode op = (BigOperatorNode) base;
            return new BigOperatorNode(op.operator(), op.lower(), over);
        }

        // over 가 accent 문자인 경우
        if (over instanceof SymbolNode) {
            String overText = ((SymbolNode) over).name();
            String accent = ACCENT_MAP.get(overText);
            if (accent != null) {
                return new FunctionNode(accent, base);
            }
            // overline (¯ 또는 bar)
            if ("overline".equals(overText) || "\u00AF".equals(overText) || "\u0304".equals(overText)) {
                return new FunctionNode("overline", base);
            }
        }

        // 일반 → 위첨자
        return new SuperscriptNode(base, over);
    }

    private AstNode parseMunderover(Element elem) throws ConvertException {
        List<Element> children = childElements(elem);
        if (children.size() < 3) {
            throw new ConvertException("munderover requires 3 children");
        }
        AstNode base = parseElement(children.get(0));
        AstNode under = parseElement(children.get(1));
        AstNode over = parseElement(children.get(2));

        // base가 BigOperator인 경우
        if (base instanceof BigOperatorNode) {
            BigOperatorNode op = (BigOperatorNode) base;
            return new BigOperatorNode(op.operator(), under, over);
        }

        return new SubSuperNode(base, under, over);
    }

    private AstNode parseMfenced(Element elem) throws ConvertException {
        String open = elem.getAttribute("open");
        String close = elem.getAttribute("close");
        if (open == null || open.length() == 0) open = "(";
        if (close == null || close.length() == 0) close = ")";

        AstNode content = parseMathChildren(elem);
        return new DelimiterNode(open, close, content);
    }

    private AstNode parseMenclose(Element elem) throws ConvertException {
        // menclose → overline 등
        String notation = elem.getAttribute("notation");
        AstNode content = parseMathChildren(elem);
        if ("top".equals(notation) || "overline".equals(notation)) {
            return new FunctionNode("overline", content);
        }
        if ("bottom".equals(notation) || "underline".equals(notation)) {
            return new FunctionNode("underline", content);
        }
        return content;
    }

    private AstNode parseTextContent(String text) {
        // 숫자인지 체크
        boolean allDigits = true;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!Character.isDigit(c) && c != '.') {
                allDigits = false;
                break;
            }
        }
        if (allDigits && text.length() > 0) {
            return new NumberNode(text);
        }

        // Unicode 기호 매핑 체크
        String mapped = ENTITY_MAP.get(text);
        if (mapped != null) {
            return new SymbolNode(mapped);
        }

        // 대형 연산자 체크
        String bigOp = BIG_OP_MAP.get(text);
        if (bigOp != null) {
            return new BigOperatorNode(bigOp, null, null);
        }

        return new SymbolNode(text);
    }

    // --- Utility ---

    private String localName(Element elem) {
        String name = elem.getLocalName();
        if (name == null) {
            name = elem.getTagName();
        }
        // 네임스페이스 접두사 제거
        int colon = name.indexOf(':');
        if (colon >= 0) {
            name = name.substring(colon + 1);
        }
        return name;
    }

    private List<Element> childElements(Element elem) {
        List<Element> result = new ArrayList<Element>();
        NodeList children = elem.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.ELEMENT_NODE) {
                result.add((Element) child);
            }
        }
        return result;
    }

    private String textContent(Element elem) {
        StringBuilder sb = new StringBuilder();
        NodeList children = elem.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (child.getNodeType() == Node.TEXT_NODE
                    || child.getNodeType() == Node.CDATA_SECTION_NODE) {
                sb.append(child.getNodeValue());
            }
        }
        return sb.toString();
    }
}
