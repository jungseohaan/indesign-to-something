package kr.dogfoot.hwpxlib.tool.idmlconverter.idml;

import java.util.ArrayList;
import java.util.List;

/**
 * IDML Story — 텍스트 프레임의 내용.
 */
public class IDMLStory {
    private String selfId;
    private List<IDMLParagraph> paragraphs;
    private List<IDMLTable> tables;
    private String storyOrientation = "Horizontal";  // Horizontal 또는 Vertical

    public IDMLStory() {
        this.paragraphs = new ArrayList<IDMLParagraph>();
        this.tables = new ArrayList<IDMLTable>();
    }

    public String selfId() { return selfId; }
    public void selfId(String v) { this.selfId = v; }

    public String storyOrientation() { return storyOrientation; }
    public void storyOrientation(String v) { this.storyOrientation = v; }

    public boolean isVertical() {
        return "Vertical".equalsIgnoreCase(storyOrientation);
    }

    public List<IDMLParagraph> paragraphs() { return paragraphs; }
    public void addParagraph(IDMLParagraph para) { paragraphs.add(para); }

    public List<IDMLTable> tables() { return tables; }
    public void addTable(IDMLTable table) { tables.add(table); }
    public boolean hasTables() { return !tables.isEmpty(); }

    /**
     * Story 내 모든 인라인 그래픽을 수집하여 반환한다.
     */
    public List<IDMLCharacterRun.InlineGraphic> getAllInlineGraphics() {
        List<IDMLCharacterRun.InlineGraphic> result = new ArrayList<IDMLCharacterRun.InlineGraphic>();
        for (IDMLParagraph para : paragraphs) {
            for (IDMLCharacterRun run : para.characterRuns()) {
                if (run.inlineGraphics() != null) {
                    result.addAll(run.inlineGraphics());
                }
            }
        }
        return result;
    }

    /**
     * Story에 의미 있는 콘텐츠가 없는지 확인한다.
     * 테이블이 있거나 의미 있는 텍스트가 있으면 false.
     */
    public boolean isEmpty() {
        // 테이블이 있으면 빈 Story가 아님
        if (!tables.isEmpty()) {
            return false;
        }

        // 단락에 텍스트가 있으면 빈 Story가 아님
        for (IDMLParagraph para : paragraphs) {
            String text = para.getPlainText();
            if (text != null && !text.trim().isEmpty()) {
                return false;
            }
        }
        return true;
    }
}
