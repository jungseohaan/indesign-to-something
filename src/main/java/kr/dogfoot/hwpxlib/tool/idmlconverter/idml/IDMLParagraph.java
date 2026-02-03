package kr.dogfoot.hwpxlib.tool.idmlconverter.idml;

import java.util.ArrayList;
import java.util.List;

/**
 * IDML ParagraphStyleRange — 하나의 단락.
 */
public class IDMLParagraph {
    private String appliedParagraphStyle;
    private List<IDMLCharacterRun> characterRuns;

    public IDMLParagraph() {
        this.characterRuns = new ArrayList<IDMLCharacterRun>();
    }

    public String appliedParagraphStyle() { return appliedParagraphStyle; }
    public void appliedParagraphStyle(String v) { this.appliedParagraphStyle = v; }

    public List<IDMLCharacterRun> characterRuns() { return characterRuns; }
    public void addCharacterRun(IDMLCharacterRun run) { characterRuns.add(run); }

    /**
     * 단락의 전체 텍스트.
     */
    public String getPlainText() {
        StringBuilder sb = new StringBuilder();
        for (IDMLCharacterRun run : characterRuns) {
            if (run.content() != null) {
                sb.append(run.content());
            }
        }
        return sb.toString();
    }

    /**
     * NP 폰트 수식 내용이 포함되어 있는지 확인.
     */
    public boolean hasEquationContent() {
        for (IDMLCharacterRun run : characterRuns) {
            if (run.isNPFont()) return true;
        }
        return false;
    }
}
