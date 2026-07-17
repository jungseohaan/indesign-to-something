package kr.dogfoot.hwpxlib.tool.idmlconverter.formula;

/**
 * Formula classification result shared by IDML run grouping, resolved run
 * processing, and HWPX inline rendering.
 */
public final class FormulaDecision {
    public enum Type {
        TEXT_ONLY,
        INLINE_CHEM_FORMULA,
        EQUATION_FORMULA,
        REACTION_EQUATION,
        REJECTED
    }

    private final Type type;
    private final String sourceText;
    private final String hwpScript;
    private final String reason;

    private FormulaDecision(Type type, String sourceText, String hwpScript, String reason) {
        this.type = type;
        this.sourceText = sourceText;
        this.hwpScript = hwpScript;
        this.reason = reason;
    }

    public static FormulaDecision of(Type type, String sourceText, String hwpScript, String reason) {
        return new FormulaDecision(type, sourceText, hwpScript, reason);
    }

    public Type type() {
        return type;
    }

    public String sourceText() {
        return sourceText;
    }

    public String hwpScript() {
        return hwpScript;
    }

    public String reason() {
        return reason;
    }

    public boolean isFormula() {
        return type == Type.INLINE_CHEM_FORMULA
                || type == Type.EQUATION_FORMULA
                || type == Type.REACTION_EQUATION;
    }

    public boolean materializeAsEquation() {
        return type == Type.EQUATION_FORMULA || type == Type.REACTION_EQUATION;
    }
}
