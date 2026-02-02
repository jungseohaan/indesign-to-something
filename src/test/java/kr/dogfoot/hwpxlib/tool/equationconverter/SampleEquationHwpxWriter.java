package kr.dogfoot.hwpxlib.tool.equationconverter;

import kr.dogfoot.hwpxlib.object.HWPXFile;
import kr.dogfoot.hwpxlib.object.content.section_xml.SectionXMLFile;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.Para;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.Run;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.RunItem;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.object.Equation;
import kr.dogfoot.hwpxlib.reader.HWPXReader;
import kr.dogfoot.hwpxlib.tool.blankfilemaker.BlankFileMaker;
import kr.dogfoot.hwpxlib.writer.HWPXWriter;
import org.junit.Assert;
import org.junit.Test;

/**
 * 300가지 LaTeX 수식을 변환하여 샘플 HWPX 파일을 생성하는 테스트.
 * 거의 모든 지원 수식 표현을 커버한다.
 */
public class SampleEquationHwpxWriter {

    private static final String[][] EQUATIONS = {

            // =====================================================================
            // A. 그리스 문자 (40개: #1~40)
            // =====================================================================

            // 소문자 그리스 문자 (29개)
            {"Greek: alpha", "\\alpha"},
            {"Greek: beta", "\\beta"},
            {"Greek: gamma", "\\gamma"},
            {"Greek: delta", "\\delta"},
            {"Greek: epsilon", "\\epsilon"},
            {"Greek: zeta", "\\zeta"},
            {"Greek: eta", "\\eta"},
            {"Greek: theta", "\\theta"},
            {"Greek: iota", "\\iota"},
            {"Greek: kappa", "\\kappa"},
            {"Greek: lambda", "\\lambda"},
            {"Greek: mu", "\\mu"},
            {"Greek: nu", "\\nu"},
            {"Greek: xi", "\\xi"},
            {"Greek: pi", "\\pi"},
            {"Greek: rho", "\\rho"},
            {"Greek: sigma", "\\sigma"},
            {"Greek: tau", "\\tau"},
            {"Greek: upsilon", "\\upsilon"},
            {"Greek: phi", "\\phi"},
            {"Greek: chi", "\\chi"},
            {"Greek: psi", "\\psi"},
            {"Greek: omega", "\\omega"},

            // 대문자 그리스 문자 (11개)
            {"Greek: Gamma", "\\Gamma"},
            {"Greek: Delta", "\\Delta"},
            {"Greek: Theta", "\\Theta"},
            {"Greek: Lambda", "\\Lambda"},
            {"Greek: Xi", "\\Xi"},
            {"Greek: Pi", "\\Pi"},
            {"Greek: Sigma", "\\Sigma"},
            {"Greek: Upsilon", "\\Upsilon"},
            {"Greek: Phi", "\\Phi"},
            {"Greek: Psi", "\\Psi"},
            {"Greek: Omega", "\\Omega"},

            // =====================================================================
            // B. 기호 (79개: #41~119)
            // =====================================================================

            // 이항 연산자 (9개)
            {"Sym: times", "a \\times b"},
            {"Sym: cdot", "a \\cdot b"},
            {"Sym: div", "a \\div b"},
            {"Sym: pm", "a \\pm b"},
            {"Sym: mp", "a \\mp b"},
            {"Sym: circ", "f \\circ g"},
            {"Sym: bullet", "a \\bullet b"},
            {"Sym: ast", "a \\ast b"},
            {"Sym: star", "a \\star b"},

            // 관계 연산자 (15개)
            {"Sym: leq", "a \\leq b"},
            {"Sym: geq", "a \\geq b"},
            {"Sym: neq", "a \\neq b"},
            {"Sym: approx", "a \\approx b"},
            {"Sym: equiv", "a \\equiv b"},
            {"Sym: sim", "a \\sim b"},
            {"Sym: simeq", "a \\simeq b"},
            {"Sym: cong", "a \\cong b"},
            {"Sym: propto", "y \\propto x"},
            {"Sym: ll", "a \\ll b"},
            {"Sym: gg", "a \\gg b"},
            {"Sym: prec", "a \\prec b"},
            {"Sym: succ", "a \\succ b"},
            {"Sym: asymp", "a \\asymp b"},

            // 집합론 (10개)
            {"Sym: in", "x \\in A"},
            {"Sym: ni", "A \\ni x"},
            {"Sym: notin", "x \\notin A"},
            {"Sym: subset", "A \\subset B"},
            {"Sym: supset", "A \\supset B"},
            {"Sym: subseteq", "A \\subseteq B"},
            {"Sym: supseteq", "A \\supseteq B"},
            {"Sym: cap", "A \\cap B"},
            {"Sym: cup", "A \\cup B"},
            {"Sym: emptyset", "\\emptyset"},

            // 논리 (11개)
            {"Sym: forall", "\\forall x"},
            {"Sym: exists", "\\exists x"},
            {"Sym: neg", "\\neg p"},
            {"Sym: vee", "p \\vee q"},
            {"Sym: wedge", "p \\wedge q"},
            {"Sym: therefore", "\\therefore"},
            {"Sym: because", "\\because"},
            {"Sym: vdash", "A \\vdash B"},
            {"Sym: models", "A \\models B"},
            {"Sym: bot", "\\bot"},
            {"Sym: top", "\\top"},

            // 미적분/기타 (16개)
            {"Sym: partial", "\\partial f"},
            {"Sym: nabla", "\\nabla f"},
            {"Sym: infty", "\\infty"},
            {"Sym: prime", "f\\prime"},
            {"Sym: angle", "\\angle ABC"},
            {"Sym: triangle", "\\triangle ABC"},
            {"Sym: diamond", "\\diamond"},
            {"Sym: dagger", "a\\dagger"},
            {"Sym: aleph", "\\aleph_{0}"},
            {"Sym: hbar", "\\hbar"},
            {"Sym: imath", "\\imath"},
            {"Sym: ell", "\\ell"},
            {"Sym: wp", "\\wp"},
            {"Sym: Re", "\\Re(z)"},

            // 화살표 (17개)
            {"Sym: rightarrow", "A \\rightarrow B"},
            {"Sym: leftarrow", "A \\leftarrow B"},
            {"Sym: leftrightarrow", "A \\leftrightarrow B"},
            {"Sym: Rightarrow", "A \\Rightarrow B"},
            {"Sym: Leftarrow", "A \\Leftarrow B"},
            {"Sym: Leftrightarrow", "A \\Leftrightarrow B"},
            {"Sym: uparrow", "\\uparrow"},
            {"Sym: downarrow", "\\downarrow"},
            {"Sym: mapsto", "x \\mapsto f(x)"},

            // 점 (4개) -- 수식 맥락에서 사용
            {"Sym: ldots", "a_{1}, a_{2}, \\ldots, a_{n}"},
            {"Sym: cdots", "a_{1} + a_{2} + \\cdots + a_{n}"},
            {"Sym: vdots", "\\vdots"},
            {"Sym: ddots", "\\ddots"},

            // 원 연산자 (5개)
            {"Sym: oplus", "A \\oplus B"},
            {"Sym: otimes", "A \\otimes B"},
            {"Sym: odot", "A \\odot B"},
            {"Sym: oslash", "A \\oslash B"},

            // =====================================================================
            // C. 대형 연산자 (13개: #120~132)
            // =====================================================================

            {"BigOp: sum", "\\sum_{i=1}^{n} a_{i}"},
            {"BigOp: prod", "\\prod_{i=1}^{n} a_{i}"},
            {"BigOp: coprod", "\\coprod_{i=1}^{n} A_{i}"},
            {"BigOp: int", "\\int_{a}^{b} f(x) dx"},
            {"BigOp: oint", "\\oint_{C} F \\cdot ds"},
            {"BigOp: iint", "\\iint_{D} f(x,y) dA"},
            {"BigOp: iiint", "\\iiint_{V} f dV"},
            {"BigOp: bigcup", "\\bigcup_{i=1}^{n} A_{i}"},
            {"BigOp: bigcap", "\\bigcap_{i=1}^{n} A_{i}"},
            {"BigOp: bigoplus", "\\bigoplus_{i=1}^{n} V_{i}"},
            {"BigOp: bigotimes", "\\bigotimes_{i=1}^{n} V_{i}"},
            {"BigOp: bigvee", "\\bigvee_{i=1}^{n} p_{i}"},
            {"BigOp: bigwedge", "\\bigwedge_{i=1}^{n} p_{i}"},

            // =====================================================================
            // D. 함수 (31개: #133~163)
            // =====================================================================

            // 삼각함수 (6개)
            {"Func: sin", "\\sin x"},
            {"Func: cos", "\\cos x"},
            {"Func: tan", "\\tan x"},
            {"Func: cot", "\\cot x"},
            {"Func: sec", "\\sec x"},
            {"Func: csc", "\\csc x"},

            // 역삼각함수 (3개)
            {"Func: arcsin", "\\arcsin x"},
            {"Func: arccos", "\\arccos x"},
            {"Func: arctan", "\\arctan x"},

            // 쌍곡선함수 (4개)
            {"Func: sinh", "\\sinh x"},
            {"Func: cosh", "\\cosh x"},
            {"Func: tanh", "\\tanh x"},
            {"Func: coth", "\\coth x"},

            // 로그/지수 (4개)
            {"Func: log", "\\log x"},
            {"Func: ln", "\\ln x"},
            {"Func: lg", "\\lg x"},
            {"Func: exp", "\\exp x"},

            // 대수 (2개)
            {"Func: det", "\\det A"},
            {"Func: gcd", "\\gcd(a, b)"},

            // 극한/극값 (6개)
            {"Func: max", "\\max(a, b)"},
            {"Func: min", "\\min(a, b)"},
            {"Func: sup", "\\sup S"},
            {"Func: inf_func", "\\inf S"},
            {"Func: lim", "\\lim_{n \\to \\infty} a_{n}"},
            {"Func: limsup", "\\limsup_{n \\to \\infty} a_{n}"},

            // 특수 함수 (6개)
            {"Func: arg", "\\arg z"},
            {"Func: deg", "\\deg p"},
            {"Func: dim", "\\dim V"},
            {"Func: hom", "\\hom(A, B)"},
            {"Func: ker", "\\ker f"},
            {"Func: mod", "a \\mod n"},

            // =====================================================================
            // E. 분수/이항 (10개: #164~173)
            // =====================================================================

            {"Frac: simple", "\\frac{a}{b}"},
            {"Frac: dfrac", "\\dfrac{x+1}{y-2}"},
            {"Frac: tfrac", "\\tfrac{1}{2}"},
            {"Frac: binom", "\\binom{n}{k}"},
            {"Frac: nested", "\\frac{\\frac{a}{b}}{c}"},
            {"Frac: nested2", "\\frac{a}{\\frac{b}{c}}"},
            {"Frac: complex num", "\\frac{x^{2}+2x+1}{x-1}"},
            {"Frac: complex den", "\\frac{1}{x^{2}+1}"},
            {"Frac: with sqrt", "\\frac{\\sqrt{a}}{b}"},
            {"Frac: continued", "1 + \\frac{1}{1 + \\frac{1}{1 + \\frac{1}{x}}}"},

            // =====================================================================
            // F. 루트 (8개: #174~181)
            // =====================================================================

            {"Root: sqrt simple", "\\sqrt{x}"},
            {"Root: sqrt expr", "\\sqrt{x^{2} + y^{2}}"},
            {"Root: cube root", "\\sqrt[3]{x}"},
            {"Root: 4th root", "\\sqrt[4]{a^{4} + b^{4}}"},
            {"Root: nth root", "\\sqrt[n]{x}"},
            {"Root: nested sqrt", "\\sqrt{\\sqrt{x}}"},
            {"Root: sqrt frac", "\\sqrt{\\frac{a}{b}}"},
            {"Root: complex", "\\sqrt{a^{2} - 4bc}"},

            // =====================================================================
            // G. 첨자 (12개: #182~193)
            // =====================================================================

            {"Script: super", "x^{2}"},
            {"Script: sub", "x_{i}"},
            {"Script: subsup", "x_{i}^{2}"},
            {"Script: nested super", "2^{2^{2}}"},
            {"Script: nested sub", "a_{i_{j}}"},
            {"Script: super on group", "{(a+b)}^{n}"},
            {"Script: sub on func", "\\log_{2} x"},
            {"Script: multiple", "a_{1}^{2} + a_{2}^{2}"},
            {"Script: sum indexed", "\\sum_{i=1}^{n} x_{i}^{2}"},
            {"Script: tensor", "T_{ij}^{kl}"},
            {"Script: chemical", "H_{2}O"},
            {"Script: isotope", "{}^{14}C"},

            // =====================================================================
            // H. 악센트 (16개: #194~209)
            // =====================================================================

            {"Accent: hat", "\\hat{x}"},
            {"Accent: widehat", "\\widehat{AB}"},
            {"Accent: check", "\\check{x}"},
            {"Accent: tilde", "\\tilde{x}"},
            {"Accent: widetilde", "\\widetilde{AB}"},
            {"Accent: acute", "\\acute{e}"},
            {"Accent: grave", "\\grave{a}"},
            {"Accent: dot", "\\dot{x}"},
            {"Accent: ddot", "\\ddot{x}"},
            {"Accent: bar", "\\bar{x}"},
            {"Accent: vec", "\\vec{v}"},
            {"Accent: overline", "\\overline{AB}"},
            {"Accent: underline", "\\underline{x}"},
            {"Accent: overbrace", "\\overbrace{a+b+c}"},
            {"Accent: underbrace", "\\underbrace{x+y+z}"},
            {"Accent: overrightarrow", "\\overrightarrow{AB}"},

            // =====================================================================
            // I. 괄호/구분자 (15개: #210~224)
            // =====================================================================

            {"Delim: paren", "\\left( \\frac{a}{b} \\right)"},
            {"Delim: bracket", "\\left[ \\frac{a}{b} \\right]"},
            {"Delim: pipe", "\\left| x \\right|"},
            {"Delim: brace", "\\left\\{ a, b, c \\right\\}"},
            {"Delim: angle", "\\left< x, y \\right>"},
            {"Delim: floor", "\\left\\lfloor x \\right\\rfloor"},
            {"Delim: ceil", "\\left\\lceil x \\right\\rceil"},
            {"Delim: Vert", "\\left\\Vert x \\right\\Vert"},
            {"Delim: left none", "\\left. x \\right)"},
            {"Delim: right none", "\\left( x \\right."},
            {"Delim: nested", "\\left( \\left[ a + b \\right] \\right)"},
            {"Delim: paren frac", "\\left( \\frac{x^{2}+1}{x-1} \\right)"},
            {"Delim: bracket sum", "\\left[ \\sum_{i=1}^{n} a_{i} \\right]"},
            {"Delim: pipe sqrt", "\\left| \\sqrt{x^{2} + y^{2}} \\right|"},
            {"Delim: mixed", "\\left( a + \\left\\{ b + c \\right\\} \\right)"},

            // =====================================================================
            // J. text (5개: #225~229)
            // =====================================================================

            {"Text: simple", "\\text{hello}"},
            {"Text: with eq", "x = 1 \\text{ if } x > 0"},
            {"Text: sentence", "\\text{where } a \\in \\mathbb{R}"},
            {"Text: for all", "\\forall x \\text{ such that } x > 0"},
            {"Text: definition", "f(x) = \\text{max}(0, x)"},

            // =====================================================================
            // K. 유명 공식 / 실제 수식 (71개: #230~300)
            // =====================================================================

            // --- 기본 대수 (10개) ---
            {"Algebra: quadratic", "x = \\frac{-b \\pm \\sqrt{b^{2}-4ac}}{2a}"},
            {"Algebra: binomial", "\\left( a+b \\right)^{2} = a^{2} + 2ab + b^{2}"},
            {"Algebra: diff squares", "a^{2} - b^{2} = (a+b)(a-b)"},
            {"Algebra: cube sum", "a^{3} + b^{3} = (a+b)(a^{2}-ab+b^{2})"},
            {"Algebra: abs value", "\\left| a \\cdot b \\right| = \\left| a \\right| \\cdot \\left| b \\right|"},
            {"Algebra: power rule", "(a^{m})^{n} = a^{mn}"},
            {"Algebra: log rule", "\\log_{a} xy = \\log_{a} x + \\log_{a} y"},
            {"Algebra: exp sum", "e^{a+b} = e^{a} \\cdot e^{b}"},
            {"Algebra: geometric", "S_{n} = \\frac{a(1-r^{n})}{1-r}"},
            {"Algebra: arithmetic", "S_{n} = \\frac{n(a_{1}+a_{n})}{2}"},

            // --- 삼각법 (8개) ---
            {"Trig: identity", "\\sin^{2}\\theta + \\cos^{2}\\theta = 1"},
            {"Trig: double sin", "\\sin 2\\theta = 2 \\sin\\theta \\cos\\theta"},
            {"Trig: double cos", "\\cos 2\\theta = \\cos^{2}\\theta - \\sin^{2}\\theta"},
            {"Trig: tangent", "\\tan\\theta = \\frac{\\sin\\theta}{\\cos\\theta}"},
            {"Trig: addition", "\\sin(\\alpha + \\beta) = \\sin\\alpha \\cos\\beta + \\cos\\alpha \\sin\\beta"},
            {"Trig: euler", "e^{i\\theta} = \\cos\\theta + i \\sin\\theta"},
            {"Trig: half angle", "\\sin \\frac{\\theta}{2} = \\pm \\sqrt{\\frac{1 - \\cos\\theta}{2}}"},
            {"Trig: law of cosines", "c^{2} = a^{2} + b^{2} - 2ab \\cos C"},

            // --- 미적분 (12개) ---
            {"Calc: derivative", "\\frac{d}{dx} x^{n} = nx^{n-1}"},
            {"Calc: chain rule", "\\frac{dy}{dx} = \\frac{dy}{du} \\cdot \\frac{du}{dx}"},
            {"Calc: product rule", "(fg)\\prime = f\\prime g + fg\\prime"},
            {"Calc: quotient rule", "\\left( \\frac{f}{g} \\right)\\prime = \\frac{f\\prime g - fg\\prime}{g^{2}}"},
            {"Calc: fundamental", "\\int_{a}^{b} f(x) dx = F(b) - F(a)"},
            {"Calc: by parts", "\\int u dv = uv - \\int v du"},
            {"Calc: Taylor", "f(x) = \\sum_{n=0}^{\\infty} \\frac{f^{(n)}(a)}{n!} (x-a)^{n}"},
            {"Calc: Maclaurin exp", "e^{x} = \\sum_{n=0}^{\\infty} \\frac{x^{n}}{n!}"},
            {"Calc: Maclaurin sin", "\\sin x = \\sum_{n=0}^{\\infty} \\frac{(-1)^{n}}{(2n+1)!} x^{2n+1}"},
            {"Calc: partial deriv", "\\frac{\\partial f}{\\partial x} + \\frac{\\partial f}{\\partial y} = 0"},
            {"Calc: gradient", "\\nabla f = \\frac{\\partial f}{\\partial x} \\hat{i} + \\frac{\\partial f}{\\partial y} \\hat{j}"},
            {"Calc: Laplacian", "\\nabla^{2} f = \\frac{\\partial^{2} f}{\\partial x^{2}} + \\frac{\\partial^{2} f}{\\partial y^{2}}"},

            // --- 극한/급수 (8개) ---
            {"Limit: basic", "\\lim_{x \\to 0} \\frac{\\sin x}{x} = 1"},
            {"Limit: e definition", "e = \\lim_{n \\to \\infty} \\left( 1 + \\frac{1}{n} \\right)^{n}"},
            {"Limit: derivative def", "f\\prime(x) = \\lim_{h \\to 0} \\frac{f(x+h) - f(x)}{h}"},
            {"Series: geometric", "\\sum_{n=0}^{\\infty} r^{n} = \\frac{1}{1-r}"},
            {"Series: harmonic", "\\sum_{n=1}^{\\infty} \\frac{1}{n} = \\infty"},
            {"Series: p-series", "\\sum_{n=1}^{\\infty} \\frac{1}{n^{p}}"},
            {"Series: Basel", "\\sum_{n=1}^{\\infty} \\frac{1}{n^{2}} = \\frac{\\pi^{2}}{6}"},
            {"Series: Leibniz", "\\frac{\\pi}{4} = \\sum_{n=0}^{\\infty} \\frac{(-1)^{n}}{2n+1}"},

            // --- 선형대수 (6개) ---
            {"LinAlg: dot product", "\\vec{a} \\cdot \\vec{b} = \\sum_{i=1}^{n} a_{i} b_{i}"},
            {"LinAlg: norm", "\\left\\Vert \\vec{v} \\right\\Vert = \\sqrt{v_{1}^{2} + v_{2}^{2} + v_{3}^{2}}"},
            {"LinAlg: eigenvalue", "A\\vec{v} = \\lambda \\vec{v}"},
            {"LinAlg: det", "\\det(AB) = \\det(A) \\cdot \\det(B)"},
            {"LinAlg: trace", "\\text{tr}(A) = \\sum_{i=1}^{n} a_{ii}"},
            {"LinAlg: transpose", "(AB)^{T} = B^{T} A^{T}"},

            // --- 확률/통계 (8개) ---
            {"Prob: expectation", "E(X) = \\sum_{i} x_{i} p_{i}"},
            {"Prob: variance", "\\text{Var}(X) = E(X^{2}) - (E(X))^{2}"},
            {"Prob: Bayes", "P(A|B) = \\frac{P(B|A) P(A)}{P(B)}"},
            {"Prob: normal", "f(x) = \\frac{1}{\\sigma \\sqrt{2\\pi}} e^{-\\frac{(x-\\mu)^{2}}{2\\sigma^{2}}}"},
            {"Prob: combination", "C(n,r) = \\frac{n!}{r!(n-r)!}"},
            {"Prob: binomial dist", "P(X=k) = \\binom{n}{k} p^{k} (1-p)^{n-k}"},
            {"Prob: Poisson", "P(X=k) = \\frac{\\lambda^{k} e^{-\\lambda}}{k!}"},
            {"Prob: Chebyshev", "P(\\left| X - \\mu \\right| \\geq k\\sigma) \\leq \\frac{1}{k^{2}}"},

            // --- 물리학 (10개) ---
            {"Phys: E=mc2", "E = mc^{2}"},
            {"Phys: Newton 2nd", "F = ma"},
            {"Phys: gravity", "F = G \\frac{m_{1} m_{2}}{r^{2}}"},
            {"Phys: kinetic energy", "E_{k} = \\frac{1}{2} mv^{2}"},
            {"Phys: wave", "\\psi(x,t) = A \\sin(kx - \\omega t)"},
            {"Phys: Schrodinger", "i\\hbar \\frac{\\partial \\psi}{\\partial t} = \\hat{H} \\psi"},
            {"Phys: Coulomb", "F = k_{e} \\frac{q_{1} q_{2}}{r^{2}}"},
            {"Phys: Maxwell Gauss", "\\nabla \\cdot E = \\frac{\\rho}{\\epsilon_{0}}"},
            {"Phys: de Broglie", "\\lambda = \\frac{h}{mv}"},
            {"Phys: entropy", "S = -k_{B} \\sum_{i} p_{i} \\ln p_{i}"},

            // --- 기하학 (5개) ---
            {"Geo: Pythagorean", "a^{2} + b^{2} = c^{2}"},
            {"Geo: circle area", "A = \\pi r^{2}"},
            {"Geo: sphere volume", "V = \\frac{4}{3} \\pi r^{3}"},
            {"Geo: distance", "d = \\sqrt{(x_{2}-x_{1})^{2} + (y_{2}-y_{1})^{2}}"},
            {"Geo: cone volume", "V = \\frac{1}{3} \\pi r^{2} h"},

            // --- 수론 (4개) ---
            {"NumTh: Euler identity", "e^{i\\pi} + 1 = 0"},
            {"NumTh: Fermat little", "a^{p-1} \\equiv 1 \\mod p"},
            {"NumTh: binomial thm", "(x+y)^{n} = \\sum_{k=0}^{n} \\binom{n}{k} x^{n-k} y^{k}"},
            {"NumTh: Euler phi", "\\phi(n) = n \\prod_{p|n} \\left( 1 - \\frac{1}{p} \\right)"},

            // --- 복합 표현 / 고급 (10개) ---
            {"Complex: nested frac+sqrt", "\\frac{1+\\sqrt{5}}{2}"},
            {"Complex: double sum", "\\sum_{i=1}^{m} \\sum_{j=1}^{n} a_{ij}"},
            {"Complex: integral frac", "\\int_{0}^{1} \\frac{x^{2}}{1+x^{2}} dx"},
            {"Complex: prod frac", "\\prod_{k=1}^{n} \\frac{k}{k+1}"},
            {"Complex: nested delim", "\\left( \\sum_{i=1}^{n} \\left[ \\frac{a_{i}}{b_{i}} \\right] \\right)"},
            {"Complex: deep nesting", "\\sqrt{1 + \\sqrt{1 + \\sqrt{1 + \\sqrt{1 + x}}}}"},
            {"Complex: multi accent", "\\hat{\\bar{x}}"},
            {"Complex: function+limit", "\\lim_{n \\to \\infty} \\left( 1 + \\frac{x}{n} \\right)^{n} = e^{x}"},
            {"Complex: Stirling", "n! \\approx \\sqrt{2\\pi n} \\left( \\frac{n}{e} \\right)^{n}"},
            {"Complex: Cauchy integral", "f(a) = \\frac{1}{2\\pi i} \\oint_{C} \\frac{f(z)}{z-a} dz"},
    };

    @Test
    public void createSampleHwpx() throws Exception {
        HWPXFile hwpxFile = BlankFileMaker.make();
        SectionXMLFile section = hwpxFile.sectionXMLFileList().get(0);

        int successCount = 0;
        int failCount = 0;

        for (int i = 0; i < EQUATIONS.length; i++) {
            String label = EQUATIONS[i][0];
            String latex = EQUATIONS[i][1];

            // 설명 텍스트 문단
            addTextPara(section, (i + 1) + ". " + label + "  (LaTeX: " + latex + ")");

            // 수식 문단
            try {
                addEquationPara(section, latex);
                successCount++;
            } catch (ConvertException e) {
                // 변환 실패 시 오류 메시지를 텍스트로 추가
                addTextPara(section, "[CONVERT ERROR: " + e.getMessage() + "]");
                failCount++;
                System.err.println("FAIL #" + (i + 1) + " " + label + ": " + e.getMessage());
            }

            // 빈 줄 (간격용)
            addTextPara(section, "");
        }

        String filepath = "testFile/tool/sample_equations.hwpx";
        HWPXWriter.toFilepath(hwpxFile, filepath);

        System.out.println("=== Sample HWPX Generation Result ===");
        System.out.println("Total: " + EQUATIONS.length);
        System.out.println("Success: " + successCount);
        System.out.println("Failed: " + failCount);
        System.out.println("File: " + filepath);

        // Round-trip 검증: 다시 읽어서 파일이 정상적으로 읽히는지 확인
        HWPXFile readBack = HWPXReader.fromFilepath(filepath);
        SectionXMLFile readSection = readBack.sectionXMLFileList().get(0);
        Assert.assertTrue("Should have paragraphs", readSection.countOfPara() > 1);

        // 성공한 수식 중 일부를 샘플로 검증 (첫 번째 성공한 수식)
        // 수식 문단 인덱스: 1(기본) + 0*3 + 1(설명다음) = 2
        if (successCount > 0) {
            Para firstEqPara = readSection.getPara(2);
            Run firstRun = firstEqPara.getRun(0);
            RunItem item = firstRun.getRunItem(0);
            Assert.assertTrue("First equation should be Equation type", item instanceof Equation);
            Equation eq = (Equation) item;
            Assert.assertEquals("Equation Version 60", eq.version());
            Assert.assertNotNull(eq.script());
        }

        // 모든 수식이 성공해야 함
        Assert.assertEquals("All equations should convert successfully", 0, failCount);

        System.out.println("\nAll " + successCount + " equations converted and written successfully!");
    }

    private void addTextPara(SectionXMLFile section, String text) {
        Para para = section.addNewPara();
        para.idAnd(String.valueOf(System.nanoTime()))
                .paraPrIDRefAnd("3")
                .styleIDRefAnd("0")
                .pageBreakAnd(false)
                .columnBreakAnd(false)
                .merged(false);

        Run run = para.addNewRun();
        run.charPrIDRef("0");
        run.addNewT().addText(text);
    }

    private void addEquationPara(SectionXMLFile section, String latex) throws ConvertException {
        Para para = section.addNewPara();
        para.idAnd(String.valueOf(System.nanoTime()))
                .paraPrIDRefAnd("3")
                .styleIDRefAnd("0")
                .pageBreakAnd(false)
                .columnBreakAnd(false)
                .merged(false);

        Run run = para.addNewRun();
        run.charPrIDRef("0");

        Equation equation = EquationBuilder.fromLatex(latex);
        run.addRunItem(equation);
    }
}
