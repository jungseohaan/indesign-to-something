package kr.dogfoot.hwpxlib.tool.idmlconverter.ast;

import java.util.ArrayList;
import java.util.List;

/**
 * Story 메타데이터 — IDML Story의 구조 정보.
 */
public class ASTStory {
    private String storyId;
    private String orientation;
    private int paragraphCount;
    private int tableCount;
    private List<String> linkedFrameIds;
    private List<Integer> pages;

    public ASTStory() {
        this.linkedFrameIds = new ArrayList<>();
        this.pages = new ArrayList<>();
    }

    public String storyId() { return storyId; }
    public void storyId(String v) { this.storyId = v; }

    public String orientation() { return orientation; }
    public void orientation(String v) { this.orientation = v; }

    public int paragraphCount() { return paragraphCount; }
    public void paragraphCount(int v) { this.paragraphCount = v; }

    public int tableCount() { return tableCount; }
    public void tableCount(int v) { this.tableCount = v; }

    public List<String> linkedFrameIds() { return linkedFrameIds; }
    public void linkedFrameIds(List<String> v) { this.linkedFrameIds = v; }

    public List<Integer> pages() { return pages; }
    public void pages(List<Integer> v) { this.pages = v; }
}
