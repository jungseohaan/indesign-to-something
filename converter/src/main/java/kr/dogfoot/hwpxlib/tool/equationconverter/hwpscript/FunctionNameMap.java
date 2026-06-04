package kr.dogfoot.hwpxlib.tool.equationconverter.hwpscript;

import java.util.HashMap;
import java.util.Map;

public class FunctionNameMap {
    private static final Map<String, String> LATEX_TO_HWP;

    static {
        LATEX_TO_HWP = new HashMap<String, String>();

        LATEX_TO_HWP.put("sin", "sin");
        LATEX_TO_HWP.put("cos", "cos");
        LATEX_TO_HWP.put("tan", "tan");
        LATEX_TO_HWP.put("cot", "cot");
        LATEX_TO_HWP.put("sec", "sec");
        LATEX_TO_HWP.put("csc", "csc");
        LATEX_TO_HWP.put("arcsin", "arcsin");
        LATEX_TO_HWP.put("arccos", "arccos");
        LATEX_TO_HWP.put("arctan", "arctan");
        LATEX_TO_HWP.put("sinh", "sinh");
        LATEX_TO_HWP.put("cosh", "cosh");
        LATEX_TO_HWP.put("tanh", "tanh");
        LATEX_TO_HWP.put("coth", "coth");
        LATEX_TO_HWP.put("log", "log");
        LATEX_TO_HWP.put("ln", "ln");
        LATEX_TO_HWP.put("lg", "lg");
        LATEX_TO_HWP.put("exp", "exp");
        LATEX_TO_HWP.put("det", "det");
        LATEX_TO_HWP.put("gcd", "gcd");
        LATEX_TO_HWP.put("max", "max");
        LATEX_TO_HWP.put("min", "min");
        LATEX_TO_HWP.put("sup", "sup");
        LATEX_TO_HWP.put("inf", "inf");
        LATEX_TO_HWP.put("lim", "lim");
        LATEX_TO_HWP.put("limsup", "limsup");
        LATEX_TO_HWP.put("liminf", "liminf");
        LATEX_TO_HWP.put("arg", "arg");
        LATEX_TO_HWP.put("deg", "deg");
        LATEX_TO_HWP.put("dim", "dim");
        LATEX_TO_HWP.put("hom", "hom");
        LATEX_TO_HWP.put("ker", "ker");
        LATEX_TO_HWP.put("Pr", "Pr");
        LATEX_TO_HWP.put("mod", "mod");
    }

    public static String toHwp(String latexFunc) {
        return LATEX_TO_HWP.get(latexFunc);
    }

    public static boolean contains(String latexFunc) {
        return LATEX_TO_HWP.containsKey(latexFunc);
    }
}
