package kr.dogfoot.hwpxlib.tool.idmlconverter.idml;

import java.util.ArrayList;
import java.util.List;

/**
 * IDML Story — 텍스트 프레임의 내용.
 */
public class IDMLStory {
    private String selfId;
    private List<IDMLParagraph> paragraphs;

    public IDMLStory() {
        this.paragraphs = new ArrayList<IDMLParagraph>();
    }

    public String selfId() { return selfId; }
    public void selfId(String v) { this.selfId = v; }

    public List<IDMLParagraph> paragraphs() { return paragraphs; }
    public void addParagraph(IDMLParagraph para) { paragraphs.add(para); }

    /**
     * Story에 의미 있는 텍스트가 없는지 확인한다.
     * 모든 단락이 빈 문자열이거나 공백만 있으면 true.
     */
    public boolean isEmpty() {
        for (IDMLParagraph para : paragraphs) {
            String text = para.getPlainText();
            if (text != null && !text.trim().isEmpty()) {
                return false;
            }
        }
        return true;
    }
}
