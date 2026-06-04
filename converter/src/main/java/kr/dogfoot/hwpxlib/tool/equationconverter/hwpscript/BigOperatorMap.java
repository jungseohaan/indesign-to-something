package kr.dogfoot.hwpxlib.tool.equationconverter.hwpscript;

import java.util.HashMap;
import java.util.Map;

public class BigOperatorMap {
    private static final Map<String, String> LATEX_TO_HWP;

    static {
        LATEX_TO_HWP = new HashMap<String, String>();

        LATEX_TO_HWP.put("sum", "sum");
        LATEX_TO_HWP.put("prod", "prod");
        LATEX_TO_HWP.put("coprod", "coprod");
        LATEX_TO_HWP.put("int", "int");
        LATEX_TO_HWP.put("oint", "oint");
        LATEX_TO_HWP.put("iint", "dint");
        LATEX_TO_HWP.put("iiint", "tint");
        LATEX_TO_HWP.put("bigcup", "union");
        LATEX_TO_HWP.put("bigcap", "inter");
        LATEX_TO_HWP.put("bigoplus", "bigoplus");
        LATEX_TO_HWP.put("bigotimes", "bigotimes");
        LATEX_TO_HWP.put("bigvee", "bigvee");
        LATEX_TO_HWP.put("bigwedge", "bigwedge");
    }

    public static String toHwp(String latexOp) {
        return LATEX_TO_HWP.get(latexOp);
    }

    public static boolean contains(String latexOp) {
        return LATEX_TO_HWP.containsKey(latexOp);
    }
}
