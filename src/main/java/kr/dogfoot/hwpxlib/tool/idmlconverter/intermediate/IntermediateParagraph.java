package kr.dogfoot.hwpxlib.tool.idmlconverter.intermediate;

import java.util.ArrayList;
import java.util.List;

/**
 * 중간 포맷 단락.
 * 텍스트 런과 인라인 수식이 순서대로 공존할 수 있다.
 */
public class IntermediateParagraph {
    private String paragraphStyleRef;
    private List<ContentItem> contentItems;

    public IntermediateParagraph() {
        this.contentItems = new ArrayList<ContentItem>();
    }

    public String paragraphStyleRef() { return paragraphStyleRef; }
    public void paragraphStyleRef(String v) { this.paragraphStyleRef = v; }

    public List<ContentItem> contentItems() { return contentItems; }

    /**
     * 텍스트 런 추가.
     */
    public void addRun(IntermediateTextRun run) {
        contentItems.add(new ContentItem(run));
    }

    /**
     * 인라인 수식 추가.
     */
    public void addEquation(IntermediateEquation eq) {
        contentItems.add(new ContentItem(eq));
    }

    /**
     * 수식이 포함되어 있는지 확인.
     */
    public boolean hasEquation() {
        for (ContentItem item : contentItems) {
            if (item.isEquation()) return true;
        }
        return false;
    }

    /**
     * 텍스트 런 목록 반환 (하위 호환).
     */
    public List<IntermediateTextRun> runs() {
        List<IntermediateTextRun> result = new ArrayList<IntermediateTextRun>();
        for (ContentItem item : contentItems) {
            if (item.isTextRun()) result.add(item.textRun());
        }
        return result;
    }

    /**
     * 첫 번째 수식 반환 (하위 호환).
     */
    public IntermediateEquation equation() {
        for (ContentItem item : contentItems) {
            if (item.isEquation()) return item.equation();
        }
        return null;
    }

    /**
     * 수식 설정 (하위 호환 setter).
     */
    public void equation(IntermediateEquation v) {
        contentItems.add(new ContentItem(v));
    }

    /**
     * 콘텐츠 항목 (텍스트 런 또는 인라인 수식).
     */
    public static class ContentItem {
        private final IntermediateTextRun textRun;
        private final IntermediateEquation equation;

        public ContentItem(IntermediateTextRun textRun) {
            this.textRun = textRun;
            this.equation = null;
        }

        public ContentItem(IntermediateEquation equation) {
            this.textRun = null;
            this.equation = equation;
        }

        public boolean isTextRun() { return textRun != null; }
        public boolean isEquation() { return equation != null; }
        public IntermediateTextRun textRun() { return textRun; }
        public IntermediateEquation equation() { return equation; }
    }
}
