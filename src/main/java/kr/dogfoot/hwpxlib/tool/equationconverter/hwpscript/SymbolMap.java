package kr.dogfoot.hwpxlib.tool.equationconverter.hwpscript;

import java.util.HashMap;
import java.util.Map;

public class SymbolMap {
    private static final Map<String, String> LATEX_TO_HWP;

    static {
        LATEX_TO_HWP = new HashMap<String, String>();

        // operators
        LATEX_TO_HWP.put("times", "times");
        LATEX_TO_HWP.put("cdot", "cdot");
        LATEX_TO_HWP.put("div", "div");
        LATEX_TO_HWP.put("pm", "+-");
        LATEX_TO_HWP.put("mp", "-+");
        LATEX_TO_HWP.put("circ", "circ");
        LATEX_TO_HWP.put("bullet", "bullet");
        LATEX_TO_HWP.put("ast", "ast");
        LATEX_TO_HWP.put("star", "star");

        // relations
        LATEX_TO_HWP.put("leq", "leq");
        LATEX_TO_HWP.put("le", "leq");
        LATEX_TO_HWP.put("geq", "geq");
        LATEX_TO_HWP.put("ge", "geq");
        LATEX_TO_HWP.put("neq", "neq");
        LATEX_TO_HWP.put("ne", "neq");
        LATEX_TO_HWP.put("approx", "approx");
        LATEX_TO_HWP.put("equiv", "equiv");
        LATEX_TO_HWP.put("sim", "sim");
        LATEX_TO_HWP.put("simeq", "simeq");
        LATEX_TO_HWP.put("cong", "cong");
        LATEX_TO_HWP.put("propto", "propto");
        LATEX_TO_HWP.put("ll", "<<");
        LATEX_TO_HWP.put("gg", ">>");
        LATEX_TO_HWP.put("prec", "prec");
        LATEX_TO_HWP.put("succ", "succ");
        LATEX_TO_HWP.put("doteq", "doteq");
        LATEX_TO_HWP.put("asymp", "asymp");

        // set theory
        LATEX_TO_HWP.put("in", "in");
        LATEX_TO_HWP.put("ni", "owns");
        LATEX_TO_HWP.put("notin", "notin");
        LATEX_TO_HWP.put("subset", "subset");
        LATEX_TO_HWP.put("supset", "supset");
        LATEX_TO_HWP.put("subseteq", "subseteq");
        LATEX_TO_HWP.put("supseteq", "supseteq");
        LATEX_TO_HWP.put("cap", "cap");
        LATEX_TO_HWP.put("cup", "cup");
        LATEX_TO_HWP.put("emptyset", "emptyset");
        LATEX_TO_HWP.put("varnothing", "emptyset");

        // logic
        LATEX_TO_HWP.put("forall", "forall");
        LATEX_TO_HWP.put("exists", "exist");
        LATEX_TO_HWP.put("neg", "lnot");
        LATEX_TO_HWP.put("lnot", "lnot");
        LATEX_TO_HWP.put("vee", "vee");
        LATEX_TO_HWP.put("wedge", "wedge");
        LATEX_TO_HWP.put("therefore", "therefore");
        LATEX_TO_HWP.put("because", "because");
        LATEX_TO_HWP.put("vdash", "vdash");
        LATEX_TO_HWP.put("models", "models");
        LATEX_TO_HWP.put("bot", "bot");
        LATEX_TO_HWP.put("top", "top");
        LATEX_TO_HWP.put("perp", "bot");

        // calculus / misc
        LATEX_TO_HWP.put("partial", "partial");
        LATEX_TO_HWP.put("nabla", "LAPLACE");
        LATEX_TO_HWP.put("infty", "inf");
        LATEX_TO_HWP.put("prime", "prime");
        LATEX_TO_HWP.put("angle", "angle");
        LATEX_TO_HWP.put("triangle", "triangle");
        LATEX_TO_HWP.put("diamond", "diamond");
        LATEX_TO_HWP.put("dagger", "dagger");
        LATEX_TO_HWP.put("ddagger", "ddagger");
        LATEX_TO_HWP.put("aleph", "aleph");
        LATEX_TO_HWP.put("hbar", "hbar");
        LATEX_TO_HWP.put("imath", "imath");
        LATEX_TO_HWP.put("jmath", "jmath");
        LATEX_TO_HWP.put("ell", "ell");
        LATEX_TO_HWP.put("wp", "wp");
        LATEX_TO_HWP.put("Re", "imag");
        LATEX_TO_HWP.put("Im", "image");

        // arrows
        LATEX_TO_HWP.put("rightarrow", "->");
        LATEX_TO_HWP.put("to", "->");
        LATEX_TO_HWP.put("leftarrow", "<-");
        LATEX_TO_HWP.put("gets", "<-");
        LATEX_TO_HWP.put("leftrightarrow", "<->");
        LATEX_TO_HWP.put("Rightarrow", "=>");
        LATEX_TO_HWP.put("Leftarrow", "<=");
        LATEX_TO_HWP.put("Leftrightarrow", "<=>");
        LATEX_TO_HWP.put("uparrow", "uparrow");
        LATEX_TO_HWP.put("downarrow", "downarrow");
        LATEX_TO_HWP.put("Uparrow", "UPARROW");
        LATEX_TO_HWP.put("Downarrow", "DOWNARROW");
        LATEX_TO_HWP.put("nearrow", "nearrow");
        LATEX_TO_HWP.put("searrow", "searrow");
        LATEX_TO_HWP.put("nwarrow", "nwarrow");
        LATEX_TO_HWP.put("swarrow", "swarrow");
        LATEX_TO_HWP.put("mapsto", "mapsto");
        LATEX_TO_HWP.put("hookleftarrow", "hookleft");
        LATEX_TO_HWP.put("hookrightarrow", "hookright");

        // dots
        LATEX_TO_HWP.put("ldots", "ldots");
        LATEX_TO_HWP.put("cdots", "cdots");
        LATEX_TO_HWP.put("vdots", "vdots");
        LATEX_TO_HWP.put("ddots", "ddots");
        LATEX_TO_HWP.put("dots", "cdots");

        // circled operators
        LATEX_TO_HWP.put("oplus", "oplus");
        LATEX_TO_HWP.put("ominus", "ominus");
        LATEX_TO_HWP.put("otimes", "otimes");
        LATEX_TO_HWP.put("odot", "odot");
        LATEX_TO_HWP.put("oslash", "oslash");

        // spacing (LaTeX spacing commands -> HWP spacing)
        LATEX_TO_HWP.put(",", "`");
        LATEX_TO_HWP.put(";", "~");
        LATEX_TO_HWP.put("!", "");
        LATEX_TO_HWP.put(" ", "~");
    }

    public static String toHwp(String latexSymbol) {
        return LATEX_TO_HWP.get(latexSymbol);
    }

    public static boolean contains(String latexSymbol) {
        return LATEX_TO_HWP.containsKey(latexSymbol);
    }
}
