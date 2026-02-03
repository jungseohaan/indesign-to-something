package kr.dogfoot.hwpxlib.tool.idmlconverter.intermediate;

/**
 * 중간 포맷 수식.
 */
public class IntermediateEquation {
    private String hwpScript;
    private String sourceType;  // "NP_FONT", "MATHML", "LATEX"

    public IntermediateEquation() {}

    public IntermediateEquation(String hwpScript, String sourceType) {
        this.hwpScript = hwpScript;
        this.sourceType = sourceType;
    }

    public String hwpScript() { return hwpScript; }
    public void hwpScript(String v) { this.hwpScript = v; }

    public String sourceType() { return sourceType; }
    public void sourceType(String v) { this.sourceType = v; }
}
