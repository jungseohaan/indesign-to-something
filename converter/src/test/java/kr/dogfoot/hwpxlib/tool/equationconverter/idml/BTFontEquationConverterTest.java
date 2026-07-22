package kr.dogfoot.hwpxlib.tool.equationconverter.idml;

import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLCharacterRun;
import org.junit.Assert;
import org.junit.Test;

import java.util.Collections;

public class BTFontEquationConverterTest {
    @Test
    public void singleLatinVariableMarkedBySourceGrepBecomesEquation() {
        Assert.assertEquals("x", BTFontEquationConverter.convert(
                Collections.singletonList(run("x", true))));
    }

    @Test
    public void singleLatinWithoutSourceGrepStaysText() {
        Assert.assertNull(BTFontEquationConverter.convert(
                Collections.singletonList(run("x", false))));
    }

    @Test
    public void digitMarkedByGrepDoesNotBecomeStandaloneEquation() {
        Assert.assertNull(BTFontEquationConverter.convert(
                Collections.singletonList(run("1", true))));
    }

    private static IDMLCharacterRun run(String text, boolean grepMath) {
        IDMLCharacterRun run = new IDMLCharacterRun();
        run.content(text);
        run.grepMathFont(grepMath);
        return run;
    }
}
