package kr.dogfoot.hwpxlib.tool.equationconverter.hwpscript;

import java.util.HashMap;
import java.util.Map;

public class GreekLetterMap {
    private static final Map<String, String> LATEX_TO_HWP;

    static {
        LATEX_TO_HWP = new HashMap<String, String>();

        // lowercase
        LATEX_TO_HWP.put("alpha", "alpha");
        LATEX_TO_HWP.put("beta", "beta");
        LATEX_TO_HWP.put("gamma", "gamma");
        LATEX_TO_HWP.put("delta", "delta");
        LATEX_TO_HWP.put("epsilon", "epsilon");
        LATEX_TO_HWP.put("varepsilon", "varepsilon");
        LATEX_TO_HWP.put("zeta", "zeta");
        LATEX_TO_HWP.put("eta", "eta");
        LATEX_TO_HWP.put("theta", "theta");
        LATEX_TO_HWP.put("vartheta", "vartheta");
        LATEX_TO_HWP.put("iota", "iota");
        LATEX_TO_HWP.put("kappa", "kappa");
        LATEX_TO_HWP.put("lambda", "lambda");
        LATEX_TO_HWP.put("mu", "mu");
        LATEX_TO_HWP.put("nu", "nu");
        LATEX_TO_HWP.put("xi", "xi");
        LATEX_TO_HWP.put("pi", "pi");
        LATEX_TO_HWP.put("varpi", "varpi");
        LATEX_TO_HWP.put("rho", "rho");
        LATEX_TO_HWP.put("varrho", "varrho");
        LATEX_TO_HWP.put("sigma", "sigma");
        LATEX_TO_HWP.put("varsigma", "varsigma");
        LATEX_TO_HWP.put("tau", "tau");
        LATEX_TO_HWP.put("upsilon", "upsilon");
        LATEX_TO_HWP.put("phi", "phi");
        LATEX_TO_HWP.put("varphi", "varphi");
        LATEX_TO_HWP.put("chi", "chi");
        LATEX_TO_HWP.put("psi", "psi");
        LATEX_TO_HWP.put("omega", "omega");

        // uppercase
        LATEX_TO_HWP.put("Gamma", "GAMMA");
        LATEX_TO_HWP.put("Delta", "DELTA");
        LATEX_TO_HWP.put("Theta", "THETA");
        LATEX_TO_HWP.put("Lambda", "LAMBDA");
        LATEX_TO_HWP.put("Xi", "XI");
        LATEX_TO_HWP.put("Pi", "PI");
        LATEX_TO_HWP.put("Sigma", "SIGMA");
        LATEX_TO_HWP.put("Upsilon", "UPSILON");
        LATEX_TO_HWP.put("Phi", "PHI");
        LATEX_TO_HWP.put("Psi", "PSI");
        LATEX_TO_HWP.put("Omega", "OMEGA");
    }

    public static String toHwp(String latexGreek) {
        return LATEX_TO_HWP.get(latexGreek);
    }

    public static boolean contains(String latexGreek) {
        return LATEX_TO_HWP.containsKey(latexGreek);
    }
}
