package kr.dogfoot.hwpxlib.tool.idmlconverter.formula;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class FormulaClassifierTest {
    @Test
    public void emitsSingleVariableOnlyWithSourceEvidence() {
        assertTrue(FormulaClassifier.shouldEmitConvertedEquation("x", true));
        assertFalse(FormulaClassifier.shouldEmitConvertedEquation("x", false));
    }

    @Test
    public void doesNotEmitPlainNumberWithSourceEvidence() {
        assertFalse(FormulaClassifier.shouldEmitConvertedEquation("1", true));
    }
}
