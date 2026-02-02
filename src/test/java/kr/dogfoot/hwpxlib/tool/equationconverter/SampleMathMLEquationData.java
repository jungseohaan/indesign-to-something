package kr.dogfoot.hwpxlib.tool.equationconverter;

/**
 * 300개 유명 수학 공식의 MathML 데이터.
 * 각 항목은 {이름, MathML 문자열} 쌍이다.
 */
public class SampleMathMLEquationData {

    // 유틸리티: 간결한 MathML 태그 생성
    private static String mi(String v) { return "<mi>" + v + "</mi>"; }
    private static String mn(String v) { return "<mn>" + v + "</mn>"; }
    private static String mo(String v) { return "<mo>" + v + "</mo>"; }
    private static String mrow(String... parts) {
        StringBuilder sb = new StringBuilder("<mrow>");
        for (String p : parts) sb.append(p);
        sb.append("</mrow>");
        return sb.toString();
    }
    private static String mfrac(String num, String den) {
        return "<mfrac>" + num + den + "</mfrac>";
    }
    private static String msup(String base, String sup) {
        return "<msup>" + base + sup + "</msup>";
    }
    private static String msub(String base, String sub) {
        return "<msub>" + base + sub + "</msub>";
    }
    private static String msubsup(String base, String sub, String sup) {
        return "<msubsup>" + base + sub + sup + "</msubsup>";
    }
    private static String msqrt(String content) {
        return "<msqrt>" + content + "</msqrt>";
    }
    private static String mroot(String content, String idx) {
        return "<mroot>" + content + idx + "</mroot>";
    }
    private static String munderover(String base, String under, String over) {
        return "<munderover>" + base + under + over + "</munderover>";
    }
    private static String munder(String base, String under) {
        return "<munder>" + base + under + "</munder>";
    }
    private static String mover(String base, String over) {
        return "<mover>" + base + over + "</mover>";
    }
    private static String mfenced(String open, String close, String content) {
        return "<mfenced open='" + open + "' close='" + close + "'>" + content + "</mfenced>";
    }
    private static String math(String content) {
        return "<math>" + content + "</math>";
    }
    private static String mtext(String v) { return "<mtext>" + v + "</mtext>"; }

    // Unicode constants
    private static final String ALPHA = "\u03B1", BETA = "\u03B2", GAMMA = "\u03B3",
            DELTA = "\u03B4", EPSILON = "\u03B5", ZETA = "\u03B6", ETA = "\u03B7",
            THETA = "\u03B8", IOTA = "\u03B9", KAPPA = "\u03BA", LAMBDA = "\u03BB",
            MU = "\u03BC", NU = "\u03BD", XI = "\u03BE", PI = "\u03C0",
            RHO = "\u03C1", SIGMA_L = "\u03C3", TAU = "\u03C4", UPSILON = "\u03C5",
            PHI = "\u03C6", CHI = "\u03C7", PSI = "\u03C8", OMEGA_L = "\u03C9";
    private static final String UGAMMA = "\u0393", UDELTA = "\u0394", UTHETA = "\u0398",
            ULAMBDA = "\u039B", UXI = "\u039E", UPI = "\u03A0", USIGMA = "\u03A3",
            UUPSILON = "\u03A5", UPHI = "\u03A6", UPSI = "\u03A8", UOMEGA = "\u03A9";
    private static final String SUM = "\u2211", PROD = "\u220F", INT = "\u222B",
            OINT = "\u222E", IINT = "\u222C", IIINT = "\u222D";
    private static final String INF = "\u221E", PARTIAL = "\u2202", NABLA = "\u2207",
            FORALL = "\u2200", EXISTS = "\u2203", EMPTYSET = "\u2205";
    private static final String TIMES = "\u00D7", CDOT = "\u22C5", PM = "\u00B1",
            DIV = "\u00F7", MP = "\u2213";
    private static final String LEQ = "\u2264", GEQ = "\u2265", NEQ = "\u2260",
            APPROX = "\u2248", EQUIV = "\u2261", SIM = "\u223C",
            PROPTO = "\u221D", IN = "\u2208", SUBSET = "\u2282",
            SUBSETEQ = "\u2286", CUP = "\u222A", CAP = "\u2229";
    private static final String RARR = "\u2192", LARR = "\u2190", DARR = "\u21D2",
            LRARR = "\u2194", DARR2 = "\u21D0", DLRARR = "\u21D4";
    private static final String HBAR = "\u210F", PRIME = "\u2032",
            LDOTS = "\u2026", CDOTS = "\u22EF";

    public static String[][] getEquations() {
        return new String[][] {
            // ============================================================
            // 1. 기본 대수 (1-30)
            // ============================================================
            {"Quadratic formula", math(mi("x") + mo("=") + mfrac(mrow(mo("-") + mi("b") + mo(PM) + msqrt(mrow(msup(mi("b"), mn("2")) + mo("-") + mn("4") + mi("a") + mi("c")))), mrow(mn("2") + mi("a"))))},
            {"Pythagorean theorem", math(msup(mi("a"), mn("2")) + mo("+") + msup(mi("b"), mn("2")) + mo("=") + msup(mi("c"), mn("2")))},
            {"Binomial square", math(msup(mrow(mi("a") + mo("+") + mi("b")), mn("2")) + mo("=") + msup(mi("a"), mn("2")) + mo("+") + mn("2") + mi("a") + mi("b") + mo("+") + msup(mi("b"), mn("2")))},
            {"Difference of squares", math(msup(mi("a"), mn("2")) + mo("-") + msup(mi("b"), mn("2")) + mo("=") + mrow(mi("a") + mo("+") + mi("b")) + mrow(mi("a") + mo("-") + mi("b")))},
            {"Cube sum", math(msup(mi("a"), mn("3")) + mo("+") + msup(mi("b"), mn("3")) + mo("=") + mrow(mi("a") + mo("+") + mi("b")) + mrow(msup(mi("a"), mn("2")) + mo("-") + mi("a") + mi("b") + mo("+") + msup(mi("b"), mn("2"))))},
            {"Power rule", math(msup(mrow(msup(mi("a"), mi("m"))), mi("n")) + mo("=") + msup(mi("a"), mrow(mi("m") + mi("n"))))},
            {"Exponential sum", math(msup(mi("e"), mrow(mi("a") + mo("+") + mi("b"))) + mo("=") + msup(mi("e"), mi("a")) + mo(CDOT) + msup(mi("e"), mi("b")))},
            {"Log product rule", math(msub(mi("log"), mi("a")) + mi("x") + mi("y") + mo("=") + msub(mi("log"), mi("a")) + mi("x") + mo("+") + msub(mi("log"), mi("a")) + mi("y"))},
            {"Geometric series sum", math(msub(mi("S"), mi("n")) + mo("=") + mfrac(mrow(mi("a") + mrow(mn("1") + mo("-") + msup(mi("r"), mi("n")))), mrow(mn("1") + mo("-") + mi("r"))))},
            {"Arithmetic series sum", math(msub(mi("S"), mi("n")) + mo("=") + mfrac(mrow(mi("n") + mrow(msub(mi("a"), mn("1")) + mo("+") + msub(mi("a"), mi("n")))), mn("2")))},
            {"Absolute value product", math(mo("|") + mi("a") + mo(CDOT) + mi("b") + mo("|") + mo("=") + mo("|") + mi("a") + mo("|") + mo(CDOT) + mo("|") + mi("b") + mo("|"))},
            {"Square root product", math(msqrt(mrow(mi("a") + mi("b"))) + mo("=") + msqrt(mi("a")) + mo(CDOT) + msqrt(mi("b")))},
            {"Negative exponent", math(msup(mi("a"), mrow(mo("-") + mi("n"))) + mo("=") + mfrac(mn("1"), msup(mi("a"), mi("n"))))},
            {"Zero exponent", math(msup(mi("a"), mn("0")) + mo("=") + mn("1"))},
            {"Log quotient rule", math(mi("log") + mfrac(mi("a"), mi("b")) + mo("=") + mi("log") + mi("a") + mo("-") + mi("log") + mi("b"))},
            {"Change of base", math(msub(mi("log"), mi("b")) + mi("x") + mo("=") + mfrac(mrow(mi("ln") + mi("x")), mrow(mi("ln") + mi("b"))))},
            {"Proportion", math(mfrac(mi("a"), mi("b")) + mo("=") + mfrac(mi("c"), mi("d")))},
            {"AM-GM inequality", math(mfrac(mrow(mi("a") + mo("+") + mi("b")), mn("2")) + mo(GEQ) + msqrt(mrow(mi("a") + mi("b"))))},
            {"Cauchy-Schwarz", math(msup(mrow(munderover(mo(SUM), mrow(mi("i") + mo("=") + mn("1")), mi("n")) + msub(mi("a"), mi("i")) + msub(mi("b"), mi("i"))), mn("2")) + mo(LEQ) + mrow(munderover(mo(SUM), mrow(mi("i") + mo("=") + mn("1")), mi("n")) + msup(msub(mi("a"), mi("i")), mn("2"))) + mrow(munderover(mo(SUM), mrow(mi("i") + mo("=") + mn("1")), mi("n")) + msup(msub(mi("b"), mi("i")), mn("2"))))},
            {"Triangle inequality", math(mo("|") + mi("a") + mo("+") + mi("b") + mo("|") + mo(LEQ) + mo("|") + mi("a") + mo("|") + mo("+") + mo("|") + mi("b") + mo("|"))},
            {"Factorial definition", math(mi("n") + mo("!") + mo("=") + munderover(mo(PROD), mrow(mi("k") + mo("=") + mn("1")), mi("n")) + mi("k"))},
            {"Simple fraction", math(mfrac(mi("a"), mi("b")))},
            {"Nested fraction", math(mfrac(mfrac(mi("a"), mi("b")), mi("c")))},
            {"Continued fraction", math(mn("1") + mo("+") + mfrac(mn("1"), mrow(mn("1") + mo("+") + mfrac(mn("1"), mrow(mn("1") + mo("+") + mi("x"))))))},
            {"Golden ratio", math(mi(PHI) + mo("=") + mfrac(mrow(mn("1") + mo("+") + msqrt(mn("5"))), mn("2")))},
            {"Completing square", math(msup(mi("x"), mn("2")) + mo("+") + mi("b") + mi("x") + mo("=") + msup(mrow(mi("x") + mo("+") + mfrac(mi("b"), mn("2"))), mn("2")) + mo("-") + mfrac(msup(mi("b"), mn("2")), mn("4")))},
            {"Vieta's formulas", math(msub(mi("x"), mn("1")) + mo("+") + msub(mi("x"), mn("2")) + mo("=") + mo("-") + mfrac(mi("b"), mi("a")))},
            {"Sum of cubes", math(munderover(mo(SUM), mrow(mi("k") + mo("=") + mn("1")), mi("n")) + msup(mi("k"), mn("3")) + mo("=") + msup(mrow(mfrac(mrow(mi("n") + mrow(mi("n") + mo("+") + mn("1"))), mn("2"))), mn("2")))},
            {"Sum of squares", math(munderover(mo(SUM), mrow(mi("k") + mo("=") + mn("1")), mi("n")) + msup(mi("k"), mn("2")) + mo("=") + mfrac(mrow(mi("n") + mrow(mi("n") + mo("+") + mn("1")) + mrow(mn("2") + mi("n") + mo("+") + mn("1"))), mn("6")))},
            {"Sum of integers", math(munderover(mo(SUM), mrow(mi("k") + mo("=") + mn("1")), mi("n")) + mi("k") + mo("=") + mfrac(mrow(mi("n") + mrow(mi("n") + mo("+") + mn("1"))), mn("2")))},

            // ============================================================
            // 2. 삼각법 (31-60)
            // ============================================================
            {"Pythagorean trig identity", math(msup(mi("sin"), mn("2")) + mi(THETA) + mo("+") + msup(mi("cos"), mn("2")) + mi(THETA) + mo("=") + mn("1"))},
            {"Tangent definition", math(mi("tan") + mi(THETA) + mo("=") + mfrac(mrow(mi("sin") + mi(THETA)), mrow(mi("cos") + mi(THETA))))},
            {"Cotangent definition", math(mi("cot") + mi(THETA) + mo("=") + mfrac(mrow(mi("cos") + mi(THETA)), mrow(mi("sin") + mi(THETA))))},
            {"Secant identity", math(msup(mi("sec"), mn("2")) + mi(THETA) + mo("=") + mn("1") + mo("+") + msup(mi("tan"), mn("2")) + mi(THETA))},
            {"Cosecant identity", math(msup(mi("csc"), mn("2")) + mi(THETA) + mo("=") + mn("1") + mo("+") + msup(mi("cot"), mn("2")) + mi(THETA))},
            {"Double angle sin", math(mi("sin") + mn("2") + mi(THETA) + mo("=") + mn("2") + mi("sin") + mi(THETA) + mi("cos") + mi(THETA))},
            {"Double angle cos", math(mi("cos") + mn("2") + mi(THETA) + mo("=") + msup(mi("cos"), mn("2")) + mi(THETA) + mo("-") + msup(mi("sin"), mn("2")) + mi(THETA))},
            {"Half angle sin", math(mi("sin") + mfrac(mi(THETA), mn("2")) + mo("=") + mo(PM) + msqrt(mfrac(mrow(mn("1") + mo("-") + mi("cos") + mi(THETA)), mn("2"))))},
            {"Half angle cos", math(mi("cos") + mfrac(mi(THETA), mn("2")) + mo("=") + mo(PM) + msqrt(mfrac(mrow(mn("1") + mo("+") + mi("cos") + mi(THETA)), mn("2"))))},
            {"Sin addition", math(mi("sin") + mrow(mi(ALPHA) + mo("+") + mi(BETA)) + mo("=") + mi("sin") + mi(ALPHA) + mi("cos") + mi(BETA) + mo("+") + mi("cos") + mi(ALPHA) + mi("sin") + mi(BETA))},
            {"Cos addition", math(mi("cos") + mrow(mi(ALPHA) + mo("+") + mi(BETA)) + mo("=") + mi("cos") + mi(ALPHA) + mi("cos") + mi(BETA) + mo("-") + mi("sin") + mi(ALPHA) + mi("sin") + mi(BETA))},
            {"Tan addition", math(mi("tan") + mrow(mi(ALPHA) + mo("+") + mi(BETA)) + mo("=") + mfrac(mrow(mi("tan") + mi(ALPHA) + mo("+") + mi("tan") + mi(BETA)), mrow(mn("1") + mo("-") + mi("tan") + mi(ALPHA) + mi("tan") + mi(BETA))))},
            {"Law of sines", math(mfrac(mi("a"), mrow(mi("sin") + mi("A"))) + mo("=") + mfrac(mi("b"), mrow(mi("sin") + mi("B"))) + mo("=") + mfrac(mi("c"), mrow(mi("sin") + mi("C"))))},
            {"Law of cosines", math(msup(mi("c"), mn("2")) + mo("=") + msup(mi("a"), mn("2")) + mo("+") + msup(mi("b"), mn("2")) + mo("-") + mn("2") + mi("a") + mi("b") + mi("cos") + mi("C"))},
            {"Euler's formula", math(msup(mi("e"), mrow(mi("i") + mi(THETA))) + mo("=") + mi("cos") + mi(THETA) + mo("+") + mi("i") + mi("sin") + mi(THETA))},
            {"De Moivre's theorem", math(msup(mrow(mi("cos") + mi(THETA) + mo("+") + mi("i") + mi("sin") + mi(THETA)), mi("n")) + mo("=") + mi("cos") + mi("n") + mi(THETA) + mo("+") + mi("i") + mi("sin") + mi("n") + mi(THETA))},
            {"Product to sum sin", math(mi("sin") + mi(ALPHA) + mi("sin") + mi(BETA) + mo("=") + mfrac(mn("1"), mn("2")) + mrow(mi("cos") + mrow(mi(ALPHA) + mo("-") + mi(BETA)) + mo("-") + mi("cos") + mrow(mi(ALPHA) + mo("+") + mi(BETA))))},
            {"Sin triple angle", math(mi("sin") + mn("3") + mi(THETA) + mo("=") + mn("3") + mi("sin") + mi(THETA) + mo("-") + mn("4") + msup(mi("sin"), mn("3")) + mi(THETA))},
            {"Cos triple angle", math(mi("cos") + mn("3") + mi(THETA) + mo("=") + mn("4") + msup(mi("cos"), mn("3")) + mi(THETA) + mo("-") + mn("3") + mi("cos") + mi(THETA))},
            {"Tan double angle", math(mi("tan") + mn("2") + mi(THETA) + mo("=") + mfrac(mrow(mn("2") + mi("tan") + mi(THETA)), mrow(mn("1") + mo("-") + msup(mi("tan"), mn("2")) + mi(THETA))))},
            {"Arctan derivative", math(mfrac(mi("d"), mrow(mi("d") + mi("x"))) + mi("arctan") + mi("x") + mo("=") + mfrac(mn("1"), mrow(mn("1") + mo("+") + msup(mi("x"), mn("2")))))},
            {"Arcsin derivative", math(mfrac(mi("d"), mrow(mi("d") + mi("x"))) + mi("arcsin") + mi("x") + mo("=") + mfrac(mn("1"), msqrt(mrow(mn("1") + mo("-") + msup(mi("x"), mn("2"))))))},
            {"Sinh definition", math(mi("sinh") + mi("x") + mo("=") + mfrac(mrow(msup(mi("e"), mi("x")) + mo("-") + msup(mi("e"), mrow(mo("-") + mi("x")))), mn("2")))},
            {"Cosh definition", math(mi("cosh") + mi("x") + mo("=") + mfrac(mrow(msup(mi("e"), mi("x")) + mo("+") + msup(mi("e"), mrow(mo("-") + mi("x")))), mn("2")))},
            {"Hyperbolic identity", math(msup(mi("cosh"), mn("2")) + mi("x") + mo("-") + msup(mi("sinh"), mn("2")) + mi("x") + mo("=") + mn("1"))},
            {"Sin Taylor zero", math(mi("sin") + mn("0") + mo("=") + mn("0"))},
            {"Cos Taylor zero", math(mi("cos") + mn("0") + mo("=") + mn("1"))},
            {"Tan period", math(mi("tan") + mrow(mi(THETA) + mo("+") + mi(PI)) + mo("=") + mi("tan") + mi(THETA))},
            {"Sin odd function", math(mi("sin") + mrow(mo("-") + mi(THETA)) + mo("=") + mo("-") + mi("sin") + mi(THETA))},
            {"Cos even function", math(mi("cos") + mrow(mo("-") + mi(THETA)) + mo("=") + mi("cos") + mi(THETA))},

            // ============================================================
            // 3. 미적분 (61-110)
            // ============================================================
            {"Derivative definition", math(msup(mi("f"), mo(PRIME)) + mrow(mi("x")) + mo("=") + munder(mi("lim"), mrow(mi("h") + mo(RARR) + mn("0"))) + mfrac(mrow(mi("f") + mrow(mi("x") + mo("+") + mi("h")) + mo("-") + mi("f") + mrow(mi("x"))), mi("h")))},
            {"Power rule derivative", math(mfrac(mi("d"), mrow(mi("d") + mi("x"))) + msup(mi("x"), mi("n")) + mo("=") + mi("n") + msup(mi("x"), mrow(mi("n") + mo("-") + mn("1"))))},
            {"Chain rule", math(mfrac(mrow(mi("d") + mi("y")), mrow(mi("d") + mi("x"))) + mo("=") + mfrac(mrow(mi("d") + mi("y")), mrow(mi("d") + mi("u"))) + mo(CDOT) + mfrac(mrow(mi("d") + mi("u")), mrow(mi("d") + mi("x"))))},
            {"Product rule", math(mrow(mi("f") + mi("g")) + mo(PRIME) + mo("=") + msup(mi("f"), mo(PRIME)) + mi("g") + mo("+") + mi("f") + msup(mi("g"), mo(PRIME)))},
            {"Quotient rule", math(msup(mfrac(mi("f"), mi("g")), mo(PRIME)) + mo("=") + mfrac(mrow(msup(mi("f"), mo(PRIME)) + mi("g") + mo("-") + mi("f") + msup(mi("g"), mo(PRIME))), msup(mi("g"), mn("2"))))},
            {"Exponential derivative", math(mfrac(mi("d"), mrow(mi("d") + mi("x"))) + msup(mi("e"), mi("x")) + mo("=") + msup(mi("e"), mi("x")))},
            {"Ln derivative", math(mfrac(mi("d"), mrow(mi("d") + mi("x"))) + mi("ln") + mi("x") + mo("=") + mfrac(mn("1"), mi("x")))},
            {"Sin derivative", math(mfrac(mi("d"), mrow(mi("d") + mi("x"))) + mi("sin") + mi("x") + mo("=") + mi("cos") + mi("x"))},
            {"Cos derivative", math(mfrac(mi("d"), mrow(mi("d") + mi("x"))) + mi("cos") + mi("x") + mo("=") + mo("-") + mi("sin") + mi("x"))},
            {"Fundamental theorem", math(msubsup(mo(INT), mi("a"), mi("b")) + mi("f") + mrow(mi("x")) + mi("d") + mi("x") + mo("=") + mi("F") + mrow(mi("b")) + mo("-") + mi("F") + mrow(mi("a")))},
            {"Integration by parts", math(msubsup(mo(INT), mi("a"), mi("b")) + mi("u") + mi("d") + mi("v") + mo("=") + mi("u") + mi("v") + mo("-") + msubsup(mo(INT), mi("a"), mi("b")) + mi("v") + mi("d") + mi("u"))},
            {"Taylor series", math(mi("f") + mrow(mi("x")) + mo("=") + munderover(mo(SUM), mrow(mi("n") + mo("=") + mn("0")), mo(INF)) + mfrac(mrow(msup(mi("f"), mrow(mrow(mi("n")))) + mrow(mi("a"))), mrow(mi("n") + mo("!"))) + msup(mrow(mi("x") + mo("-") + mi("a")), mi("n")))},
            {"Maclaurin e^x", math(msup(mi("e"), mi("x")) + mo("=") + munderover(mo(SUM), mrow(mi("n") + mo("=") + mn("0")), mo(INF)) + mfrac(msup(mi("x"), mi("n")), mrow(mi("n") + mo("!"))))},
            {"Maclaurin sin", math(mi("sin") + mi("x") + mo("=") + munderover(mo(SUM), mrow(mi("n") + mo("=") + mn("0")), mo(INF)) + mfrac(msup(mrow(mo("-") + mn("1")), mi("n")), mrow(mrow(mn("2") + mi("n") + mo("+") + mn("1")) + mo("!"))) + msup(mi("x"), mrow(mn("2") + mi("n") + mo("+") + mn("1"))))},
            {"Maclaurin cos", math(mi("cos") + mi("x") + mo("=") + munderover(mo(SUM), mrow(mi("n") + mo("=") + mn("0")), mo(INF)) + mfrac(msup(mrow(mo("-") + mn("1")), mi("n")), mrow(mrow(mn("2") + mi("n")) + mo("!"))) + msup(mi("x"), mrow(mn("2") + mi("n"))))},
            {"Maclaurin ln(1+x)", math(mi("ln") + mrow(mn("1") + mo("+") + mi("x")) + mo("=") + munderover(mo(SUM), mrow(mi("n") + mo("=") + mn("1")), mo(INF)) + msup(mrow(mo("-") + mn("1")), mrow(mi("n") + mo("+") + mn("1"))) + mfrac(msup(mi("x"), mi("n")), mi("n")))},
            {"Geometric series", math(munderover(mo(SUM), mrow(mi("n") + mo("=") + mn("0")), mo(INF)) + msup(mi("r"), mi("n")) + mo("=") + mfrac(mn("1"), mrow(mn("1") + mo("-") + mi("r"))))},
            {"Integral of 1/x", math(mo(INT) + mfrac(mn("1"), mi("x")) + mi("d") + mi("x") + mo("=") + mi("ln") + mo("|") + mi("x") + mo("|") + mo("+") + mi("C"))},
            {"Integral of e^x", math(mo(INT) + msup(mi("e"), mi("x")) + mi("d") + mi("x") + mo("=") + msup(mi("e"), mi("x")) + mo("+") + mi("C"))},
            {"Integral of sin", math(mo(INT) + mi("sin") + mi("x") + mi("d") + mi("x") + mo("=") + mo("-") + mi("cos") + mi("x") + mo("+") + mi("C"))},
            {"Integral of cos", math(mo(INT) + mi("cos") + mi("x") + mi("d") + mi("x") + mo("=") + mi("sin") + mi("x") + mo("+") + mi("C"))},
            {"Gaussian integral", math(msubsup(mo(INT), mrow(mo("-") + mo(INF)), mo(INF)) + msup(mi("e"), mrow(mo("-") + msup(mi("x"), mn("2")))) + mi("d") + mi("x") + mo("=") + msqrt(mi(PI)))},
            {"Partial derivative", math(mfrac(mrow(mo(PARTIAL) + mi("f")), mrow(mo(PARTIAL) + mi("x"))) + mo("+") + mfrac(mrow(mo(PARTIAL) + mi("f")), mrow(mo(PARTIAL) + mi("y"))) + mo("=") + mn("0"))},
            {"Gradient", math(mo(NABLA) + mi("f") + mo("=") + mfrac(mrow(mo(PARTIAL) + mi("f")), mrow(mo(PARTIAL) + mi("x"))) + mi("i") + mo("+") + mfrac(mrow(mo(PARTIAL) + mi("f")), mrow(mo(PARTIAL) + mi("y"))) + mi("j"))},
            {"Laplacian", math(msup(mo(NABLA), mn("2")) + mi("f") + mo("=") + mfrac(mrow(msup(mo(PARTIAL), mn("2")) + mi("f")), mrow(mo(PARTIAL) + msup(mi("x"), mn("2")))) + mo("+") + mfrac(mrow(msup(mo(PARTIAL), mn("2")) + mi("f")), mrow(mo(PARTIAL) + msup(mi("y"), mn("2")))))},
            {"L'Hopital's rule", math(munder(mi("lim"), mrow(mi("x") + mo(RARR) + mi("c"))) + mfrac(mrow(mi("f") + mrow(mi("x"))), mrow(mi("g") + mrow(mi("x")))) + mo("=") + munder(mi("lim"), mrow(mi("x") + mo(RARR) + mi("c"))) + mfrac(mrow(msup(mi("f"), mo(PRIME)) + mrow(mi("x"))), mrow(msup(mi("g"), mo(PRIME)) + mrow(mi("x")))))},
            {"MVT", math(msup(mi("f"), mo(PRIME)) + mrow(mi("c")) + mo("=") + mfrac(mrow(mi("f") + mrow(mi("b")) + mo("-") + mi("f") + mrow(mi("a"))), mrow(mi("b") + mo("-") + mi("a"))))},
            {"Rolle's theorem", math(msup(mi("f"), mo(PRIME)) + mrow(mi("c")) + mo("=") + mn("0"))},
            {"Leibniz integral", math(mfrac(mi("d"), mrow(mi("d") + mi("x"))) + msubsup(mo(INT), mrow(mi("a") + mrow(mi("x"))), mrow(mi("b") + mrow(mi("x")))) + mi("f") + mrow(mi("t")) + mi("d") + mi("t"))},
            {"Line integral", math(msub(mo(INT), mi("C")) + mi("F") + mo(CDOT) + mi("d") + mi("r"))},
            {"Double integral", math(msub(mo(IINT), mi("D")) + mi("f") + mrow(mi("x") + mo(",") + mi("y")) + mi("d") + mi("A"))},
            {"Triple integral", math(msub(mo(IIINT), mi("V")) + mi("f") + mi("d") + mi("V"))},
            {"Surface integral", math(msub(mo(IINT), mi("S")) + mi("F") + mo(CDOT) + mi("d") + mi("S"))},
            {"Divergence theorem", math(msub(mo(IIINT), mi("V")) + mo(NABLA) + mo(CDOT) + mi("F") + mi("d") + mi("V") + mo("=") + msub(mo(IINT), mi("S")) + mi("F") + mo(CDOT) + mi("d") + mi("S"))},
            {"Stokes' theorem", math(msub(mo(IINT), mi("S")) + mrow(mo(NABLA) + mo(TIMES) + mi("F")) + mo(CDOT) + mi("d") + mi("S") + mo("=") + msub(mo(OINT), mrow(mo(PARTIAL) + mi("S"))) + mi("F") + mo(CDOT) + mi("d") + mi("r"))},
            {"Cauchy integral", math(mi("f") + mrow(mi("a")) + mo("=") + mfrac(mn("1"), mrow(mn("2") + mi(PI) + mi("i"))) + msub(mo(OINT), mi("C")) + mfrac(mrow(mi("f") + mrow(mi("z"))), mrow(mi("z") + mo("-") + mi("a"))) + mi("d") + mi("z"))},
            {"Residue theorem", math(msub(mo(OINT), mi("C")) + mi("f") + mrow(mi("z")) + mi("d") + mi("z") + mo("=") + mn("2") + mi(PI) + mi("i") + munderover(mo(SUM), mrow(mi("k") + mo("=") + mn("1")), mi("n")) + mi("Res") + mrow(mi("f") + mo(",") + msub(mi("z"), mi("k"))))},
            {"Fourier transform", math(mi("F") + mrow(mi(OMEGA_L)) + mo("=") + msubsup(mo(INT), mrow(mo("-") + mo(INF)), mo(INF)) + mi("f") + mrow(mi("t")) + msup(mi("e"), mrow(mo("-") + mi("i") + mi(OMEGA_L) + mi("t"))) + mi("d") + mi("t"))},
            {"Inverse Fourier", math(mi("f") + mrow(mi("t")) + mo("=") + mfrac(mn("1"), mrow(mn("2") + mi(PI))) + msubsup(mo(INT), mrow(mo("-") + mo(INF)), mo(INF)) + mi("F") + mrow(mi(OMEGA_L)) + msup(mi("e"), mrow(mi("i") + mi(OMEGA_L) + mi("t"))) + mi("d") + mi(OMEGA_L))},
            {"Laplace transform", math(mi("F") + mrow(mi("s")) + mo("=") + msubsup(mo(INT), mn("0"), mo(INF)) + mi("f") + mrow(mi("t")) + msup(mi("e"), mrow(mo("-") + mi("s") + mi("t"))) + mi("d") + mi("t"))},

            // ============================================================
            // 4. 극한/급수 (111-140)
            // ============================================================
            {"Limit sinx/x", math(munder(mi("lim"), mrow(mi("x") + mo(RARR) + mn("0"))) + mfrac(mrow(mi("sin") + mi("x")), mi("x")) + mo("=") + mn("1"))},
            {"e definition", math(mi("e") + mo("=") + munder(mi("lim"), mrow(mi("n") + mo(RARR) + mo(INF))) + msup(mrow(mn("1") + mo("+") + mfrac(mn("1"), mi("n"))), mi("n")))},
            {"e^x limit", math(msup(mi("e"), mi("x")) + mo("=") + munder(mi("lim"), mrow(mi("n") + mo(RARR) + mo(INF))) + msup(mrow(mn("1") + mo("+") + mfrac(mi("x"), mi("n"))), mi("n")))},
            {"Harmonic series", math(munderover(mo(SUM), mrow(mi("n") + mo("=") + mn("1")), mo(INF)) + mfrac(mn("1"), mi("n")) + mo("=") + mo(INF))},
            {"Basel problem", math(munderover(mo(SUM), mrow(mi("n") + mo("=") + mn("1")), mo(INF)) + mfrac(mn("1"), msup(mi("n"), mn("2"))) + mo("=") + mfrac(msup(mi(PI), mn("2")), mn("6")))},
            {"Leibniz formula pi", math(mfrac(mi(PI), mn("4")) + mo("=") + munderover(mo(SUM), mrow(mi("n") + mo("=") + mn("0")), mo(INF)) + mfrac(msup(mrow(mo("-") + mn("1")), mi("n")), mrow(mn("2") + mi("n") + mo("+") + mn("1"))))},
            {"Zeta(3) Apery", math(mi(ZETA_S) + mrow(mn("3")) + mo("=") + munderover(mo(SUM), mrow(mi("n") + mo("=") + mn("1")), mo(INF)) + mfrac(mn("1"), msup(mi("n"), mn("3"))))},
            {"Riemann zeta", math(mi(ZETA_S) + mrow(mi("s")) + mo("=") + munderover(mo(SUM), mrow(mi("n") + mo("=") + mn("1")), mo(INF)) + mfrac(mn("1"), msup(mi("n"), mi("s"))))},
            {"p-series", math(munderover(mo(SUM), mrow(mi("n") + mo("=") + mn("1")), mo(INF)) + mfrac(mn("1"), msup(mi("n"), mi("p"))))},
            {"Alternating series", math(munderover(mo(SUM), mrow(mi("n") + mo("=") + mn("1")), mo(INF)) + mfrac(msup(mrow(mo("-") + mn("1")), mrow(mi("n") + mo("+") + mn("1"))), mi("n")) + mo("=") + mi("ln") + mn("2"))},
            {"Euler product", math(munderover(mo(SUM), mrow(mi("n") + mo("=") + mn("1")), mo(INF)) + mfrac(mn("1"), msup(mi("n"), mi("s"))) + mo("=") + munderover(mo(PROD), mi("p"), mrow()) + mfrac(mn("1"), mrow(mn("1") + mo("-") + msup(mi("p"), mrow(mo("-") + mi("s"))))))},
            {"Wallis product", math(mfrac(mi(PI), mn("2")) + mo("=") + munderover(mo(PROD), mrow(mi("n") + mo("=") + mn("1")), mo(INF)) + mfrac(mrow(mn("4") + msup(mi("n"), mn("2"))), mrow(mn("4") + msup(mi("n"), mn("2")) + mo("-") + mn("1"))))},
            {"Stirling approx", math(mi("n") + mo("!") + mo(APPROX) + msqrt(mrow(mn("2") + mi(PI) + mi("n"))) + msup(mrow(mfrac(mi("n"), mi("e"))), mi("n")))},
            {"Catalan number", math(msub(mi("C"), mi("n")) + mo("=") + mfrac(mn("1"), mrow(mi("n") + mo("+") + mn("1"))) + mfrac(mrow(mrow(mn("2") + mi("n")) + mo("!")), mrow(mi("n") + mo("!") + mi("n") + mo("!"))))},
            {"Fibonacci Binet", math(msub(mi("F"), mi("n")) + mo("=") + mfrac(mrow(msup(mi(PHI), mi("n")) + mo("-") + msup(mi(PSI), mi("n"))), msqrt(mn("5"))))},
            {"Bernoulli number", math(mfrac(mi("t"), mrow(msup(mi("e"), mi("t")) + mo("-") + mn("1"))) + mo("=") + munderover(mo(SUM), mrow(mi("n") + mo("=") + mn("0")), mo(INF)) + msub(mi("B"), mi("n")) + mfrac(msup(mi("t"), mi("n")), mrow(mi("n") + mo("!"))))},
            {"Limit (1-cos)/x2", math(munder(mi("lim"), mrow(mi("x") + mo(RARR) + mn("0"))) + mfrac(mrow(mn("1") + mo("-") + mi("cos") + mi("x")), msup(mi("x"), mn("2"))) + mo("=") + mfrac(mn("1"), mn("2")))},
            {"Limit tan/x", math(munder(mi("lim"), mrow(mi("x") + mo(RARR) + mn("0"))) + mfrac(mrow(mi("tan") + mi("x")), mi("x")) + mo("=") + mn("1"))},
            {"Power series radius", math(mi("R") + mo("=") + mfrac(mn("1"), mrow(munder(mi("limsup"), mrow(mi("n") + mo(RARR) + mo(INF))) + mroot(mrow(mo("|") + msub(mi("a"), mi("n")) + mo("|")), mi("n")))))},
            {"Dirichlet test", math(munderover(mo(SUM), mrow(mi("n") + mo("=") + mn("1")), mo(INF)) + msub(mi("a"), mi("n")) + msub(mi("b"), mi("n")))},

            // ============================================================
            // 5. 선형대수 (141-165)
            // ============================================================
            {"Dot product", math(mi("a") + mo(CDOT) + mi("b") + mo("=") + munderover(mo(SUM), mrow(mi("i") + mo("=") + mn("1")), mi("n")) + msub(mi("a"), mi("i")) + msub(mi("b"), mi("i")))},
            {"Cross product mag", math(mo("|") + mi("a") + mo(TIMES) + mi("b") + mo("|") + mo("=") + mo("|") + mi("a") + mo("|") + mo("|") + mi("b") + mo("|") + mi("sin") + mi(THETA))},
            {"Vector norm", math(mo("|") + mo("|") + mi("v") + mo("|") + mo("|") + mo("=") + msqrt(mrow(msup(msub(mi("v"), mn("1")), mn("2")) + mo("+") + msup(msub(mi("v"), mn("2")), mn("2")) + mo("+") + msup(msub(mi("v"), mn("3")), mn("2")))))},
            {"Eigenvalue equation", math(mi("A") + mi("v") + mo("=") + mi(LAMBDA) + mi("v"))},
            {"Determinant product", math(mi("det") + mrow(mi("A") + mi("B")) + mo("=") + mi("det") + mrow(mi("A")) + mo(CDOT) + mi("det") + mrow(mi("B")))},
            {"Trace", math(mi("tr") + mrow(mi("A")) + mo("=") + munderover(mo(SUM), mrow(mi("i") + mo("=") + mn("1")), mi("n")) + msub(mi("a"), mrow(mi("i") + mi("i"))))},
            {"Transpose product", math(msup(mrow(mi("A") + mi("B")), mi("T")) + mo("=") + msup(mi("B"), mi("T")) + msup(mi("A"), mi("T")))},
            {"Inverse product", math(msup(mrow(mi("A") + mi("B")), mrow(mo("-") + mn("1"))) + mo("=") + msup(mi("B"), mrow(mo("-") + mn("1"))) + msup(mi("A"), mrow(mo("-") + mn("1"))))},
            {"Characteristic poly", math(mi("det") + mrow(mi("A") + mo("-") + mi(LAMBDA) + mi("I")) + mo("=") + mn("0"))},
            {"Cayley-Hamilton", math(mi("p") + mrow(mi("A")) + mo("=") + mn("0"))},
            {"Rank-nullity", math(mi("dim") + mi("V") + mo("=") + mi("rank") + mrow(mi("T")) + mo("+") + mi("nullity") + mrow(mi("T")))},
            {"Projection", math(msub(mi("proj"), mi("u")) + mi("v") + mo("=") + mfrac(mrow(mi("v") + mo(CDOT) + mi("u")), mrow(mi("u") + mo(CDOT) + mi("u"))) + mi("u"))},
            {"Gram-Schmidt", math(msub(mi("u"), mi("k")) + mo("=") + msub(mi("v"), mi("k")) + mo("-") + munderover(mo(SUM), mrow(mi("j") + mo("=") + mn("1")), mrow(mi("k") + mo("-") + mn("1"))) + msub(mi("proj"), msub(mi("u"), mi("j"))) + msub(mi("v"), mi("k")))},
            {"Orthogonal", math(msup(mi("Q"), mi("T")) + mi("Q") + mo("=") + mi("I"))},
            {"Spectral theorem", math(mi("A") + mo("=") + mi("Q") + mi(ULAMBDA) + msup(mi("Q"), mi("T")))},
            {"Matrix exponential", math(msup(mi("e"), mi("A")) + mo("=") + munderover(mo(SUM), mrow(mi("n") + mo("=") + mn("0")), mo(INF)) + mfrac(msup(mi("A"), mi("n")), mrow(mi("n") + mo("!"))))},
            {"SVD", math(mi("A") + mo("=") + mi("U") + mi(USIGMA) + msup(mi("V"), mi("T")))},
            {"Cramer's rule", math(msub(mi("x"), mi("i")) + mo("=") + mfrac(mrow(mi("det") + mrow(msub(mi("A"), mi("i")))), mrow(mi("det") + mrow(mi("A")))))},
            {"Det transpose", math(mi("det") + mrow(msup(mi("A"), mi("T"))) + mo("=") + mi("det") + mrow(mi("A")))},
            {"Trace sum", math(mi("tr") + mrow(mi("A") + mo("+") + mi("B")) + mo("=") + mi("tr") + mrow(mi("A")) + mo("+") + mi("tr") + mrow(mi("B")))},
            {"Hadamard ineq", math(mi("det") + mrow(mi("A")) + mo(LEQ) + munderover(mo(PROD), mrow(mi("i") + mo("=") + mn("1")), mi("n")) + mo("|") + mo("|") + msub(mi("a"), mi("i")) + mo("|") + mo("|"))},
            {"Frobenius norm", math(msub(mo("|") + mo("|") + mi("A") + mo("|") + mo("|"), mi("F")) + mo("=") + msqrt(mrow(munderover(mo(SUM), mrow(mi("i") + mo(",") + mi("j")), mrow()) + msup(mo("|") + msub(mi("a"), mrow(mi("i") + mi("j"))) + mo("|"), mn("2")))))},
            {"Sherman-Morrison", math(msup(mrow(mi("A") + mo("+") + mi("u") + msup(mi("v"), mi("T"))), mrow(mo("-") + mn("1"))) + mo("=") + msup(mi("A"), mrow(mo("-") + mn("1"))) + mo("-") + mfrac(mrow(msup(mi("A"), mrow(mo("-") + mn("1"))) + mi("u") + msup(mi("v"), mi("T")) + msup(mi("A"), mrow(mo("-") + mn("1")))), mrow(mn("1") + mo("+") + msup(mi("v"), mi("T")) + msup(mi("A"), mrow(mo("-") + mn("1"))) + mi("u"))))},
            {"Woodbury identity", math(msup(mrow(mi("A") + mo("+") + mi("U") + mi("C") + mi("V")), mrow(mo("-") + mn("1"))))},
            {"Det inverse", math(mi("det") + mrow(msup(mi("A"), mrow(mo("-") + mn("1")))) + mo("=") + mfrac(mn("1"), mrow(mi("det") + mrow(mi("A")))))},

            // ============================================================
            // 6. 확률/통계 (166-200)
            // ============================================================
            {"Expectation", math(mi("E") + mrow(mi("X")) + mo("=") + munderover(mo(SUM), mi("i"), mrow()) + msub(mi("x"), mi("i")) + msub(mi("p"), mi("i")))},
            {"Variance", math(mi("Var") + mrow(mi("X")) + mo("=") + mi("E") + mrow(msup(mi("X"), mn("2"))) + mo("-") + msup(mrow(mi("E") + mrow(mi("X"))), mn("2")))},
            {"Std deviation", math(mi(SIGMA_L) + mo("=") + msqrt(mrow(mi("Var") + mrow(mi("X")))))},
            {"Bayes theorem", math(mi("P") + mrow(mi("A") + mo("|") + mi("B")) + mo("=") + mfrac(mrow(mi("P") + mrow(mi("B") + mo("|") + mi("A")) + mi("P") + mrow(mi("A"))), mrow(mi("P") + mrow(mi("B")))))},
            {"Normal dist", math(mi("f") + mrow(mi("x")) + mo("=") + mfrac(mn("1"), mrow(mi(SIGMA_L) + msqrt(mrow(mn("2") + mi(PI))))) + msup(mi("e"), mrow(mo("-") + mfrac(msup(mrow(mi("x") + mo("-") + mi(MU)), mn("2")), mrow(mn("2") + msup(mi(SIGMA_L), mn("2")))))))},
            {"Binomial PMF", math(mi("P") + mrow(mi("X") + mo("=") + mi("k")) + mo("=") + mfrac(mrow(mi("n") + mo("!")), mrow(mi("k") + mo("!") + mrow(mi("n") + mo("-") + mi("k")) + mo("!"))) + msup(mi("p"), mi("k")) + msup(mrow(mn("1") + mo("-") + mi("p")), mrow(mi("n") + mo("-") + mi("k"))))},
            {"Poisson PMF", math(mi("P") + mrow(mi("X") + mo("=") + mi("k")) + mo("=") + mfrac(mrow(msup(mi(LAMBDA), mi("k")) + msup(mi("e"), mrow(mo("-") + mi(LAMBDA)))), mrow(mi("k") + mo("!"))))},
            {"Chebyshev", math(mi("P") + mrow(mo("|") + mi("X") + mo("-") + mi(MU) + mo("|") + mo(GEQ) + mi("k") + mi(SIGMA_L)) + mo(LEQ) + mfrac(mn("1"), msup(mi("k"), mn("2"))))},
            {"Law of total prob", math(mi("P") + mrow(mi("A")) + mo("=") + munderover(mo(SUM), mrow(mi("i") + mo("=") + mn("1")), mi("n")) + mi("P") + mrow(mi("A") + mo("|") + msub(mi("B"), mi("i"))) + mi("P") + mrow(msub(mi("B"), mi("i"))))},
            {"Conditional expect", math(mi("E") + mrow(mi("X") + mo("|") + mi("Y")) + mo("=") + munderover(mo(SUM), mi("x"), mrow()) + mi("x") + mi("P") + mrow(mi("X") + mo("=") + mi("x") + mo("|") + mi("Y")))},
            {"Covariance", math(mi("Cov") + mrow(mi("X") + mo(",") + mi("Y")) + mo("=") + mi("E") + mrow(mi("X") + mi("Y")) + mo("-") + mi("E") + mrow(mi("X")) + mi("E") + mrow(mi("Y")))},
            {"Correlation", math(mi(RHO) + mo("=") + mfrac(mrow(mi("Cov") + mrow(mi("X") + mo(",") + mi("Y"))), mrow(msub(mi(SIGMA_L), mi("X")) + msub(mi(SIGMA_L), mi("Y")))))},
            {"MGF definition", math(msub(mi("M"), mi("X")) + mrow(mi("t")) + mo("=") + mi("E") + mrow(msup(mi("e"), mrow(mi("t") + mi("X")))))},
            {"Chi-squared", math(msup(mi(CHI), mn("2")) + mo("=") + munderover(mo(SUM), mrow(mi("i") + mo("=") + mn("1")), mi("k")) + mfrac(msup(mrow(msub(mi("O"), mi("i")) + mo("-") + msub(mi("E"), mi("i"))), mn("2")), msub(mi("E"), mi("i"))))},
            {"Entropy", math(mi("H") + mrow(mi("X")) + mo("=") + mo("-") + munderover(mo(SUM), mi("i"), mrow()) + msub(mi("p"), mi("i")) + mi("log") + msub(mi("p"), mi("i")))},
            {"KL divergence", math(msub(mi("D"), mrow(mi("K") + mi("L"))) + mo("=") + munderover(mo(SUM), mi("i"), mrow()) + msub(mi("p"), mi("i")) + mi("log") + mfrac(msub(mi("p"), mi("i")), msub(mi("q"), mi("i"))))},
            {"Central limit", math(mfrac(mrow(mover(mi("X"), mo("\u00AF")) + mo("-") + mi(MU)), mrow(mi(SIGMA_L) + mo("/") + msqrt(mi("n")))) + mo(RARR) + mi("N") + mrow(mn("0") + mo(",") + mn("1")))},
            {"Markov inequality", math(mi("P") + mrow(mi("X") + mo(GEQ) + mi("a")) + mo(LEQ) + mfrac(mrow(mi("E") + mrow(mi("X"))), mi("a")))},
            {"Jensen inequality", math(mi(PHI) + mrow(mi("E") + mrow(mi("X"))) + mo(LEQ) + mi("E") + mrow(mi(PHI) + mrow(mi("X"))))},
            {"Exponential PDF", math(mi("f") + mrow(mi("x")) + mo("=") + mi(LAMBDA) + msup(mi("e"), mrow(mo("-") + mi(LAMBDA) + mi("x"))))},
            {"Uniform E[X]", math(mi("E") + mrow(mi("X")) + mo("=") + mfrac(mrow(mi("a") + mo("+") + mi("b")), mn("2")))},
            {"Geometric PMF", math(mi("P") + mrow(mi("X") + mo("=") + mi("k")) + mo("=") + msup(mrow(mn("1") + mo("-") + mi("p")), mrow(mi("k") + mo("-") + mn("1"))) + mi("p"))},
            {"Negative binomial", math(mi("P") + mrow(mi("X") + mo("=") + mi("k")) + mo("=") + mfrac(mrow(mrow(mi("k") + mo("-") + mn("1")) + mo("!")), mrow(mrow(mi("r") + mo("-") + mn("1")) + mo("!") + mrow(mi("k") + mo("-") + mi("r")) + mo("!"))) + msup(mi("p"), mi("r")) + msup(mrow(mn("1") + mo("-") + mi("p")), mrow(mi("k") + mo("-") + mi("r"))))},
            {"Beta function", math(mi("B") + mrow(mi(ALPHA) + mo(",") + mi(BETA)) + mo("=") + msubsup(mo(INT), mn("0"), mn("1")) + msup(mi("t"), mrow(mi(ALPHA) + mo("-") + mn("1"))) + msup(mrow(mn("1") + mo("-") + mi("t")), mrow(mi(BETA) + mo("-") + mn("1"))) + mi("d") + mi("t"))},
            {"Gamma function", math(mi(UGAMMA) + mrow(mi("n")) + mo("=") + mrow(mi("n") + mo("-") + mn("1")) + mo("!"))},
            {"MLE likelihood", math(mi("L") + mrow(mi(THETA)) + mo("=") + munderover(mo(PROD), mrow(mi("i") + mo("=") + mn("1")), mi("n")) + mi("f") + mrow(msub(mi("x"), mi("i")) + mo("|") + mi(THETA)))},
            {"Fisher info", math(mi("I") + mrow(mi(THETA)) + mo("=") + mo("-") + mi("E") + mrow(mfrac(mrow(msup(mo(PARTIAL), mn("2"))), mrow(mo(PARTIAL) + msup(mi(THETA), mn("2")))) + mi("ln") + mi("L")))},
            {"Cramer-Rao", math(mi("Var") + mrow(mi("T")) + mo(GEQ) + mfrac(mn("1"), mrow(mi("I") + mrow(mi(THETA)))))},
            {"BLUE", math(mover(mi(BETA), mo("^")) + mo("=") + msup(mrow(msup(mi("X"), mi("T")) + mi("X")), mrow(mo("-") + mn("1"))) + msup(mi("X"), mi("T")) + mi("y"))},
            {"Sample variance", math(msup(mi("s"), mn("2")) + mo("=") + mfrac(mn("1"), mrow(mi("n") + mo("-") + mn("1"))) + munderover(mo(SUM), mrow(mi("i") + mo("=") + mn("1")), mi("n")) + msup(mrow(msub(mi("x"), mi("i")) + mo("-") + mover(mi("x"), mo("\u00AF"))), mn("2")))},
            {"Confidence interval", math(mover(mi("x"), mo("\u00AF")) + mo(PM) + msub(mi("z"), mrow(mi(ALPHA) + mo("/") + mn("2"))) + mfrac(mi(SIGMA_L), msqrt(mi("n"))))},
            {"Regression", math(mi("y") + mo("=") + mi(BETA) + mi("x") + mo("+") + mi(EPSILON))},
            {"R-squared", math(msup(mi("R"), mn("2")) + mo("=") + mn("1") + mo("-") + mfrac(mrow(mi("S") + mi("S") + msub(mi("r"), mrow(mi("e") + mi("s")))), mrow(mi("S") + mi("S") + msub(mi("t"), mrow(mi("o") + mi("t"))))))},
            {"AIC", math(mi("AIC") + mo("=") + mn("2") + mi("k") + mo("-") + mn("2") + mi("ln") + mi("L"))},

            // ============================================================
            // 7. 물리학 (201-250)
            // ============================================================
            {"E=mc^2", math(mi("E") + mo("=") + mi("m") + msup(mi("c"), mn("2")))},
            {"Newton's 2nd law", math(mi("F") + mo("=") + mi("m") + mi("a"))},
            {"Newton's gravity", math(mi("F") + mo("=") + mi("G") + mfrac(mrow(msub(mi("m"), mn("1")) + msub(mi("m"), mn("2"))), msup(mi("r"), mn("2"))))},
            {"Kinetic energy", math(msub(mi("E"), mi("k")) + mo("=") + mfrac(mn("1"), mn("2")) + mi("m") + msup(mi("v"), mn("2")))},
            {"Potential energy", math(msub(mi("E"), mi("p")) + mo("=") + mi("m") + mi("g") + mi("h"))},
            {"Work-energy thm", math(mi("W") + mo("=") + mi(DELTA) + msub(mi("E"), mi("k")))},
            {"Momentum", math(mi("p") + mo("=") + mi("m") + mi("v"))},
            {"Impulse", math(mi("J") + mo("=") + mi("F") + mi(DELTA) + mi("t") + mo("=") + mi(DELTA) + mi("p"))},
            {"Angular momentum", math(mi("L") + mo("=") + mi("I") + mi(OMEGA_L))},
            {"Torque", math(mi(TAU) + mo("=") + mi("r") + mo(TIMES) + mi("F"))},
            {"Centripetal accel", math(msub(mi("a"), mi("c")) + mo("=") + mfrac(msup(mi("v"), mn("2")), mi("r")))},
            {"Simple harmonic", math(mi("x") + mrow(mi("t")) + mo("=") + mi("A") + mi("cos") + mrow(mi(OMEGA_L) + mi("t") + mo("+") + mi(PHI)))},
            {"Wave equation", math(mfrac(mrow(msup(mo(PARTIAL), mn("2")) + mi("u")), mrow(mo(PARTIAL) + msup(mi("t"), mn("2")))) + mo("=") + msup(mi("c"), mn("2")) + mfrac(mrow(msup(mo(PARTIAL), mn("2")) + mi("u")), mrow(mo(PARTIAL) + msup(mi("x"), mn("2")))))},
            {"Schrodinger eq", math(mi("i") + mo(HBAR) + mfrac(mrow(mo(PARTIAL) + mi(PSI)), mrow(mo(PARTIAL) + mi("t"))) + mo("=") + mi("H") + mi(PSI))},
            {"de Broglie", math(mi(LAMBDA) + mo("=") + mfrac(mi("h"), mrow(mi("m") + mi("v"))))},
            {"Heisenberg", math(mi(DELTA) + mi("x") + mo(CDOT) + mi(DELTA) + mi("p") + mo(GEQ) + mfrac(mo(HBAR), mn("2")))},
            {"Coulomb's law", math(mi("F") + mo("=") + msub(mi("k"), mi("e")) + mfrac(mrow(msub(mi("q"), mn("1")) + msub(mi("q"), mn("2"))), msup(mi("r"), mn("2"))))},
            {"Ohm's law", math(mi("V") + mo("=") + mi("I") + mi("R"))},
            {"Power electrical", math(mi("P") + mo("=") + mi("I") + mi("V") + mo("=") + msup(mi("I"), mn("2")) + mi("R"))},
            {"Gauss's law", math(mo(NABLA) + mo(CDOT) + mi("E") + mo("=") + mfrac(mi(RHO), msub(mi(EPSILON), mn("0"))))},
            {"Faraday's law", math(mo(NABLA) + mo(TIMES) + mi("E") + mo("=") + mo("-") + mfrac(mrow(mo(PARTIAL) + mi("B")), mrow(mo(PARTIAL) + mi("t"))))},
            {"Ampere's law", math(mo(NABLA) + mo(TIMES) + mi("B") + mo("=") + msub(mi(MU), mn("0")) + mi("J") + mo("+") + msub(mi(MU), mn("0")) + msub(mi(EPSILON), mn("0")) + mfrac(mrow(mo(PARTIAL) + mi("E")), mrow(mo(PARTIAL) + mi("t"))))},
            {"Gauss mag", math(mo(NABLA) + mo(CDOT) + mi("B") + mo("=") + mn("0"))},
            {"Lorentz force", math(mi("F") + mo("=") + mi("q") + mrow(mi("E") + mo("+") + mi("v") + mo(TIMES) + mi("B")))},
            {"Boltzmann entropy", math(mi("S") + mo("=") + msub(mi("k"), mi("B")) + mi("ln") + mi(UOMEGA))},
            {"Ideal gas", math(mi("P") + mi("V") + mo("=") + mi("n") + mi("R") + mi("T"))},
            {"Stefan-Boltzmann", math(mi("P") + mo("=") + mi(SIGMA_L) + mi("A") + msup(mi("T"), mn("4")))},
            {"Planck radiation", math(mi("E") + mo("=") + mi("h") + mi(NU))},
            {"Photoelectric", math(msub(mi("E"), mi("k")) + mo("=") + mi("h") + mi(NU) + mo("-") + mi(PHI))},
            {"Time dilation", math(mi(DELTA) + msup(mi("t"), mo(PRIME)) + mo("=") + mfrac(mrow(mi(DELTA) + mi("t")), msqrt(mrow(mn("1") + mo("-") + mfrac(msup(mi("v"), mn("2")), msup(mi("c"), mn("2")))))))},
            {"Length contraction", math(msup(mi("L"), mo(PRIME)) + mo("=") + mi("L") + msqrt(mrow(mn("1") + mo("-") + mfrac(msup(mi("v"), mn("2")), msup(mi("c"), mn("2"))))))},
            {"Relativistic energy", math(mi("E") + mo("=") + mfrac(mrow(msub(mi("m"), mn("0")) + msup(mi("c"), mn("2"))), msqrt(mrow(mn("1") + mo("-") + mfrac(msup(mi("v"), mn("2")), msup(mi("c"), mn("2")))))))},
            {"Einstein field", math(msub(mi("R"), mrow(mi(MU) + mi(NU))) + mo("-") + mfrac(mn("1"), mn("2")) + mi("R") + msub(mi("g"), mrow(mi(MU) + mi(NU))) + mo("=") + mfrac(mrow(mn("8") + mi(PI) + mi("G")), msup(mi("c"), mn("4"))) + msub(mi("T"), mrow(mi(MU) + mi(NU))))},
            {"Schwarzschild", math(msup(mi("r"), mo("*")) + mo("=") + mfrac(mrow(mn("2") + mi("G") + mi("M")), msup(mi("c"), mn("2"))))},
            {"Compton", math(mi(DELTA) + mi(LAMBDA) + mo("=") + mfrac(mi("h"), mrow(msub(mi("m"), mi("e")) + mi("c"))) + mrow(mn("1") + mo("-") + mi("cos") + mi(THETA)))},
            {"Doppler effect", math(msup(mi("f"), mo(PRIME)) + mo("=") + mi("f") + mfrac(mrow(mi("v") + mo(PM) + msub(mi("v"), mi("r"))), mrow(mi("v") + mo(MP) + msub(mi("v"), mi("s")))))},
            {"Snell's law", math(msub(mi("n"), mn("1")) + mi("sin") + msub(mi(THETA), mn("1")) + mo("=") + msub(mi("n"), mn("2")) + mi("sin") + msub(mi(THETA), mn("2")))},
            {"Lens equation", math(mfrac(mn("1"), mi("f")) + mo("=") + mfrac(mn("1"), msub(mi("d"), mi("o"))) + mo("+") + mfrac(mn("1"), msub(mi("d"), mi("i"))))},
            {"Hooke's law", math(mi("F") + mo("=") + mo("-") + mi("k") + mi("x"))},
            {"Period pendulum", math(mi("T") + mo("=") + mn("2") + mi(PI) + msqrt(mfrac(mi("L"), mi("g"))))},
            {"Clausius entropy", math(mi("d") + mi("S") + mo(GEQ) + mfrac(mrow(mi(DELTA) + mi("Q")), mi("T")))},
            {"Carnot efficiency", math(mi(ETA) + mo("=") + mn("1") + mo("-") + mfrac(msub(mi("T"), mi("C")), msub(mi("T"), mi("H"))))},
            {"Rocket equation", math(mi(DELTA) + mi("v") + mo("=") + msub(mi("v"), mi("e")) + mi("ln") + mfrac(msub(mi("m"), mn("0")), msub(mi("m"), mi("f"))))},
            {"Bernoulli eq", math(mi("P") + mo("+") + mfrac(mn("1"), mn("2")) + mi(RHO) + msup(mi("v"), mn("2")) + mo("+") + mi(RHO) + mi("g") + mi("h") + mo("=") + mi("const"))},
            {"Navier-Stokes", math(mi(RHO) + mfrac(mrow(mo(PARTIAL) + mi("v")), mrow(mo(PARTIAL) + mi("t"))) + mo("=") + mo("-") + mo(NABLA) + mi("P") + mo("+") + mi(MU) + msup(mo(NABLA), mn("2")) + mi("v"))},
            {"Diffusion equation", math(mfrac(mrow(mo(PARTIAL) + mi("u")), mrow(mo(PARTIAL) + mi("t"))) + mo("=") + mi("D") + msup(mo(NABLA), mn("2")) + mi("u"))},
            {"Black-body", math(mi("B") + mrow(mi(NU) + mo(",") + mi("T")) + mo("=") + mfrac(mrow(mn("2") + mi("h") + msup(mi(NU), mn("3"))), msup(mi("c"), mn("2"))) + mfrac(mn("1"), mrow(msup(mi("e"), mfrac(mrow(mi("h") + mi(NU)), mrow(msub(mi("k"), mi("B")) + mi("T")))) + mo("-") + mn("1"))))},
            {"Hawking temp", math(mi("T") + mo("=") + mfrac(mrow(mo(HBAR) + msup(mi("c"), mn("3"))), mrow(mn("8") + mi(PI) + mi("G") + mi("M") + msub(mi("k"), mi("B")))))},

            // ============================================================
            // 8. 기하학 (251-275)
            // ============================================================
            {"Circle area", math(mi("A") + mo("=") + mi(PI) + msup(mi("r"), mn("2")))},
            {"Circle circumference", math(mi("C") + mo("=") + mn("2") + mi(PI) + mi("r"))},
            {"Sphere volume", math(mi("V") + mo("=") + mfrac(mn("4"), mn("3")) + mi(PI) + msup(mi("r"), mn("3")))},
            {"Sphere surface", math(mi("A") + mo("=") + mn("4") + mi(PI) + msup(mi("r"), mn("2")))},
            {"Cone volume", math(mi("V") + mo("=") + mfrac(mn("1"), mn("3")) + mi(PI) + msup(mi("r"), mn("2")) + mi("h"))},
            {"Cylinder volume", math(mi("V") + mo("=") + mi(PI) + msup(mi("r"), mn("2")) + mi("h"))},
            {"Triangle area", math(mi("A") + mo("=") + mfrac(mn("1"), mn("2")) + mi("b") + mi("h"))},
            {"Heron's formula", math(mi("A") + mo("=") + msqrt(mrow(mi("s") + mrow(mi("s") + mo("-") + mi("a")) + mrow(mi("s") + mo("-") + mi("b")) + mrow(mi("s") + mo("-") + mi("c")))))},
            {"Distance formula", math(mi("d") + mo("=") + msqrt(mrow(msup(mrow(msub(mi("x"), mn("2")) + mo("-") + msub(mi("x"), mn("1"))), mn("2")) + mo("+") + msup(mrow(msub(mi("y"), mn("2")) + mo("-") + msub(mi("y"), mn("1"))), mn("2")))))},
            {"Midpoint", math(mi("M") + mo("=") + mfenced("(", ")", mrow(mfrac(mrow(msub(mi("x"), mn("1")) + mo("+") + msub(mi("x"), mn("2"))), mn("2")) + mo(",") + mfrac(mrow(msub(mi("y"), mn("1")) + mo("+") + msub(mi("y"), mn("2"))), mn("2")))))},
            {"Slope", math(mi("m") + mo("=") + mfrac(mrow(msub(mi("y"), mn("2")) + mo("-") + msub(mi("y"), mn("1"))), mrow(msub(mi("x"), mn("2")) + mo("-") + msub(mi("x"), mn("1")))))},
            {"Ellipse area", math(mi("A") + mo("=") + mi(PI) + mi("a") + mi("b"))},
            {"Ellipse equation", math(mfrac(msup(mi("x"), mn("2")), msup(mi("a"), mn("2"))) + mo("+") + mfrac(msup(mi("y"), mn("2")), msup(mi("b"), mn("2"))) + mo("=") + mn("1"))},
            {"Parabola", math(mi("y") + mo("=") + mi("a") + msup(mi("x"), mn("2")) + mo("+") + mi("b") + mi("x") + mo("+") + mi("c"))},
            {"Hyperbola", math(mfrac(msup(mi("x"), mn("2")), msup(mi("a"), mn("2"))) + mo("-") + mfrac(msup(mi("y"), mn("2")), msup(mi("b"), mn("2"))) + mo("=") + mn("1"))},
            {"Euler polyhedron", math(mi("V") + mo("-") + mi("E") + mo("+") + mi("F") + mo("=") + mn("2"))},
            {"Arc length", math(mi("s") + mo("=") + mi("r") + mi(THETA))},
            {"Sector area", math(mi("A") + mo("=") + mfrac(mn("1"), mn("2")) + msup(mi("r"), mn("2")) + mi(THETA))},
            {"Polar area", math(mi("A") + mo("=") + mfrac(mn("1"), mn("2")) + msubsup(mo(INT), mi(ALPHA), mi(BETA)) + msup(mrow(mi("r") + mrow(mi(THETA))), mn("2")) + mi("d") + mi(THETA))},
            {"Curvature", math(mi(KAPPA) + mo("=") + mfrac(mrow(mo("|") + msup(mi("y"), mrow(mo(PRIME) + mo(PRIME))) + mo("|")), msup(mrow(mn("1") + mo("+") + msup(msup(mi("y"), mo(PRIME)), mn("2"))), mfrac(mn("3"), mn("2")))))},
            {"Gauss curvature", math(mi("K") + mo("=") + msub(mi(KAPPA), mn("1")) + msub(mi(KAPPA), mn("2")))},
            {"Gauss-Bonnet", math(msub(mo(IINT), mi("M")) + mi("K") + mi("d") + mi("A") + mo("=") + mn("2") + mi(PI) + mi(CHI) + mrow(mi("M")))},
            {"Geodesic equation", math(mfrac(mrow(msup(mi("d"), mn("2")) + msup(mi("x"), mi(MU))), mrow(mi("d") + msup(mi(TAU), mn("2")))) + mo("+") + msubsup(mi(UGAMMA), mrow(mi(ALPHA) + mi(BETA)), mi(MU)) + mfrac(mrow(mi("d") + msup(mi("x"), mi(ALPHA))), mrow(mi("d") + mi(TAU))) + mfrac(mrow(mi("d") + msup(mi("x"), mi(BETA))), mrow(mi("d") + mi(TAU))) + mo("=") + mn("0"))},
            {"3D distance", math(mi("d") + mo("=") + msqrt(mrow(msup(mrow(mi(DELTA) + mi("x")), mn("2")) + mo("+") + msup(mrow(mi(DELTA) + mi("y")), mn("2")) + mo("+") + msup(mrow(mi(DELTA) + mi("z")), mn("2")))))},
            {"Torus volume", math(mi("V") + mo("=") + mn("2") + msup(mi(PI), mn("2")) + mi("R") + msup(mi("r"), mn("2")))},

            // ============================================================
            // 9. 수론/이산수학 (276-300)
            // ============================================================
            {"Euler identity", math(msup(mi("e"), mrow(mi("i") + mi(PI))) + mo("+") + mn("1") + mo("=") + mn("0"))},
            {"Fermat's little", math(msup(mi("a"), mrow(mi("p") + mo("-") + mn("1"))) + mo(EQUIV) + mn("1") + mi("mod") + mi("p"))},
            {"Binomial theorem", math(msup(mrow(mi("x") + mo("+") + mi("y")), mi("n")) + mo("=") + munderover(mo(SUM), mrow(mi("k") + mo("=") + mn("0")), mi("n")) + mfrac(mrow(mi("n") + mo("!")), mrow(mi("k") + mo("!") + mrow(mi("n") + mo("-") + mi("k")) + mo("!"))) + msup(mi("x"), mrow(mi("n") + mo("-") + mi("k"))) + msup(mi("y"), mi("k")))},
            {"Euler totient", math(mi(PHI) + mrow(mi("n")) + mo("=") + mi("n") + munderover(mo(PROD), mrow(mi("p") + mo("|") + mi("n")), mrow()) + mrow(mn("1") + mo("-") + mfrac(mn("1"), mi("p"))))},
            {"Wilson's theorem", math(mrow(mi("p") + mo("-") + mn("1")) + mo("!") + mo(EQUIV) + mo("-") + mn("1") + mi("mod") + mi("p"))},
            {"Chinese remainder", math(mi("x") + mo(EQUIV) + msub(mi("a"), mi("i")) + mi("mod") + msub(mi("n"), mi("i")))},
            {"Mobius function", math(mi(MU) + mrow(mi("n")))},
            {"GCD LCM relation", math(mi("gcd") + mrow(mi("a") + mo(",") + mi("b")) + mo(CDOT) + mi("lcm") + mrow(mi("a") + mo(",") + mi("b")) + mo("=") + mi("a") + mo(CDOT) + mi("b"))},
            {"Bezout identity", math(mi("gcd") + mrow(mi("a") + mo(",") + mi("b")) + mo("=") + mi("a") + mi("x") + mo("+") + mi("b") + mi("y"))},
            {"Euclidean algo", math(mi("gcd") + mrow(mi("a") + mo(",") + mi("b")) + mo("=") + mi("gcd") + mrow(mi("b") + mo(",") + mi("a") + mi("mod") + mi("b")))},
            {"Prime counting", math(mi(PI) + mrow(mi("x")) + mo(SIM) + mfrac(mi("x"), mrow(mi("ln") + mi("x"))))},
            {"Goldbach", math(mn("2") + mi("n") + mo("=") + mi("p") + mo("+") + mi("q"))},
            {"Dirichlet theorem", math(munderover(mo(SUM), mrow(mi("n") + mo("=") + mn("1")), mo(INF)) + mfrac(mn("1"), msup(mi("n"), mi("s"))) + mo("=") + munderover(mo(PROD), mi("p"), mrow()) + mfrac(mn("1"), mrow(mn("1") + mo("-") + msup(mi("p"), mrow(mo("-") + mi("s"))))))},
            {"Partition function", math(munderover(mo(SUM), mrow(mi("n") + mo("=") + mn("0")), mo(INF)) + mi("p") + mrow(mi("n")) + msup(mi("x"), mi("n")) + mo("=") + munderover(mo(PROD), mrow(mi("k") + mo("=") + mn("1")), mo(INF)) + mfrac(mn("1"), mrow(mn("1") + mo("-") + msup(mi("x"), mi("k")))))},
            {"Ramanujan 1729", math(mn("1729") + mo("=") + msup(mn("1"), mn("3")) + mo("+") + msup(mn("12"), mn("3")) + mo("=") + msup(mn("9"), mn("3")) + mo("+") + msup(mn("10"), mn("3")))},
            {"Graph Euler", math(mi("V") + mo("-") + mi("E") + mo("+") + mi("F") + mo("=") + mn("2"))},
            {"Handshake lemma", math(munderover(mo(SUM), mrow(mi("v") + mo(IN) + mi("V")), mrow()) + mi("deg") + mrow(mi("v")) + mo("=") + mn("2") + mo("|") + mi("E") + mo("|"))},
            {"Pigeonhole", math(mi("n") + mo("+") + mn("1") + mo(RARR) + mi("n"))},
            {"Derangement", math(msub(mi("D"), mi("n")) + mo("=") + mi("n") + mo("!") + munderover(mo(SUM), mrow(mi("k") + mo("=") + mn("0")), mi("n")) + mfrac(msup(mrow(mo("-") + mn("1")), mi("k")), mrow(mi("k") + mo("!"))))},
            {"Fibonacci recurrence", math(msub(mi("F"), mrow(mi("n") + mo("+") + mn("2"))) + mo("=") + msub(mi("F"), mrow(mi("n") + mo("+") + mn("1"))) + mo("+") + msub(mi("F"), mi("n")))},
            {"Lucas numbers", math(msub(mi("L"), mi("n")) + mo("=") + msup(mi(PHI), mi("n")) + mo("+") + msup(mi(PSI), mi("n")))},
            {"Inclusion-exclusion", math(mo("|") + mi("A") + mo(CUP) + mi("B") + mo("|") + mo("=") + mo("|") + mi("A") + mo("|") + mo("+") + mo("|") + mi("B") + mo("|") + mo("-") + mo("|") + mi("A") + mo(CAP) + mi("B") + mo("|"))},
            {"Stars and bars", math(mfrac(mrow(mrow(mi("n") + mo("+") + mi("k") + mo("-") + mn("1")) + mo("!")), mrow(mi("k") + mo("!") + mrow(mi("n") + mo("-") + mn("1")) + mo("!"))))},
            {"Multinomial", math(mfrac(mrow(mi("n") + mo("!")), mrow(msub(mi("n"), mn("1")) + mo("!") + msub(mi("n"), mn("2")) + mo("!") + mo(CDOTS) + msub(mi("n"), mi("k")) + mo("!"))))},

            // ============================================================
            // 10. 추가 유명 공식 (277-300)
            // ============================================================
            {"Cauchy integral formula", math(mi("f") + mrow(mi("a")) + mo("=") + mfrac(mn("1"), mrow(mn("2") + mi(PI) + mi("i"))) + msub(mo(OINT), mi(GAMMA)) + mfrac(mrow(mi("f") + mrow(mi("z"))), mrow(mi("z") + mo("-") + mi("a"))) + mi("d") + mi("z"))},
            {"Fourier inverse", math(mi("f") + mrow(mi("x")) + mo("=") + mfrac(mn("1"), mrow(mn("2") + mi(PI))) + msubsup(mo(INT), mrow(mo("-") + mo(INF)), mo(INF)) + mi("F") + mrow(mi(OMEGA_L)) + msup(mi("e"), mrow(mi("i") + mi(OMEGA_L) + mi("x"))) + mi("d") + mi(OMEGA_L))},
            {"Parseval theorem", math(msubsup(mo(INT), mrow(mo("-") + mo(INF)), mo(INF)) + msup(mrow(mo("|") + mi("f") + mrow(mi("x")) + mo("|")), mn("2")) + mi("d") + mi("x") + mo("=") + msubsup(mo(INT), mrow(mo("-") + mo(INF)), mo(INF)) + msup(mrow(mo("|") + mi("F") + mrow(mi(OMEGA_L)) + mo("|")), mn("2")) + mi("d") + mi(OMEGA_L))},
            {"Bernoulli equation", math(mfrac(mi("P"), mrow(mi(RHO) + mi("g"))) + mo("+") + mfrac(msup(mi("v"), mn("2")), mrow(mn("2") + mi("g"))) + mo("+") + mi("z") + mo("=") + mi("const"))},
            {"Boltzmann entropy", math(mi("S") + mo("=") + msub(mi("k"), mi("B")) + mi("ln") + mi(UOMEGA))},
            {"Stefan-Boltzmann", math(mi("P") + mo("=") + mi(SIGMA_L) + mi("A") + msup(mi("T"), mn("4")))},
            {"Planck radiation", math(mi("B") + mrow(mi(NU) + mo(",") + mi("T")) + mo("=") + mfrac(mrow(mn("2") + mi("h") + msup(mi(NU), mn("3"))), msup(mi("c"), mn("2"))) + mfrac(mn("1"), mrow(msup(mi("e"), mfrac(mrow(mi("h") + mi(NU)), mrow(msub(mi("k"), mi("B")) + mi("T")))) + mo("-") + mn("1"))))},
            {"Wien displacement", math(msub(mi(LAMBDA), mi("max")) + mo(CDOT) + mi("T") + mo("=") + mi("b"))},
            {"Compton scattering", math(mi(DELTA) + mi(LAMBDA) + mo("=") + mfrac(mi("h"), mrow(msub(mi("m"), mi("e")) + mi("c"))) + mrow(mn("1") + mo("-") + mi("cos") + mi(THETA)))},
            {"De Broglie wavelength", math(mi(LAMBDA) + mo("=") + mfrac(mi("h"), mi("p")))},
            {"Kepler third law", math(msup(mi("T"), mn("2")) + mo("=") + mfrac(mrow(mn("4") + msup(mi(PI), mn("2"))), mrow(mi("G") + mi("M"))) + msup(mi("a"), mn("3")))},
            {"Hubble law", math(mi("v") + mo("=") + msub(mi("H"), mn("0")) + mi("d"))},
            {"Logistic equation", math(mfrac(mrow(mi("d") + mi("P")), mrow(mi("d") + mi("t"))) + mo("=") + mi("r") + mi("P") + mrow(mn("1") + mo("-") + mfrac(mi("P"), mi("K"))))},
            {"Wave equation", math(mfrac(mrow(msup(mo(PARTIAL), mn("2")) + mi("u")), mrow(mo(PARTIAL) + msup(mi("t"), mn("2")))) + mo("=") + msup(mi("c"), mn("2")) + mfrac(mrow(msup(mo(PARTIAL), mn("2")) + mi("u")), mrow(mo(PARTIAL) + msup(mi("x"), mn("2")))))},
            {"Diffusion equation", math(mfrac(mrow(mo(PARTIAL) + mi("u")), mrow(mo(PARTIAL) + mi("t"))) + mo("=") + mi("D") + msup(mo(NABLA), mn("2")) + mi("u"))},
            {"Rodrigues formula", math(msub(mi("P"), mi("n")) + mrow(mi("x")) + mo("=") + mfrac(mn("1"), mrow(msup(mn("2"), mi("n")) + mi("n") + mo("!"))) + mfrac(mrow(msup(mi("d"), mi("n"))), mrow(mi("d") + msup(mi("x"), mi("n")))) + msup(mrow(msup(mi("x"), mn("2")) + mo("-") + mn("1")), mi("n")))},
            {"Bessel function", math(msub(mi("J"), mi("n")) + mrow(mi("x")) + mo("=") + munderover(mo(SUM), mrow(mi("m") + mo("=") + mn("0")), mo(INF)) + mfrac(msup(mrow(mo("-") + mn("1")), mi("m")), mrow(mi("m") + mo("!") + mi(UGAMMA) + mrow(mi("m") + mo("+") + mi("n") + mo("+") + mn("1")))) + msup(mrow(mfrac(mi("x"), mn("2"))), mrow(mn("2") + mi("m") + mo("+") + mi("n"))))},
            {"Laplace equation", math(msup(mo(NABLA), mn("2")) + mi(PHI) + mo("=") + mn("0"))},
            {"Poisson equation", math(msup(mo(NABLA), mn("2")) + mi(PHI) + mo("=") + mi("f"))},
            {"Kirchhoff current", math(munderover(mo(SUM), mrow(mi("k") + mo("=") + mn("1")), mi("n")) + msub(mi("I"), mi("k")) + mo("=") + mn("0"))},
            {"Ohm's law", math(mi("V") + mo("=") + mi("I") + mi("R"))},
            {"Coulomb law", math(mi("F") + mo("=") + mi("k") + mfrac(mrow(msub(mi("q"), mn("1")) + msub(mi("q"), mn("2"))), msup(mi("r"), mn("2"))))},
            {"Lorentz force", math(mi("F") + mo("=") + mi("q") + mrow(mi("E") + mo("+") + mi("v") + mo(TIMES) + mi("B")))},
            {"Ideal gas law", math(mi("P") + mi("V") + mo("=") + mi("n") + mi("R") + mi("T"))},
        };
    }

    // ζ (zeta) is not in the basic map, use a string constant
    private static final String ZETA_S = "\u03B6";
    private static final String ETA_S = "\u03B7";
    private static final String PHI_S = "\u03C6";
    private static final String PSI_S = "\u03C8";
    private static final String CHI_S = "\u03C7";
    private static final String KAPPA_S = "\u03BA";
}
