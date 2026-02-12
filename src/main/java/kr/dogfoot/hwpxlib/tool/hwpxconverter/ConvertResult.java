package kr.dogfoot.hwpxlib.tool.hwpxconverter;

import java.util.ArrayList;
import java.util.List;

/**
 * HWPX → IDML 변환 결과.
 */
public class ConvertResult {
    private int pageCount;
    private List<String> warnings;

    public ConvertResult() {
        this.warnings = new ArrayList<String>();
    }

    public int pageCount() { return pageCount; }
    public void pageCount(int v) { this.pageCount = v; }

    public List<String> warnings() { return warnings; }
    public void addWarning(String warning) { warnings.add(warning); }

    public boolean hasWarnings() { return !warnings.isEmpty(); }

    public String summary() {
        return "Converted " + pageCount + " pages" +
                (hasWarnings() ? " with " + warnings.size() + " warnings" : "");
    }
}
