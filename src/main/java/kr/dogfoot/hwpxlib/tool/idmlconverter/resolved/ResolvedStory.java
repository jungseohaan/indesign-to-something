package kr.dogfoot.hwpxlib.tool.idmlconverter.resolved;

import java.util.ArrayList;
import java.util.List;

/**
 * resolved.json의 스토리 단위 데이터.
 */
public class ResolvedStory {
    private String id;
    private final List<ResolvedParagraph> paragraphs = new ArrayList<>();

    public String id() { return id; }
    public void id(String v) { this.id = v; }

    public List<ResolvedParagraph> paragraphs() { return paragraphs; }
    public void addParagraph(ResolvedParagraph p) { paragraphs.add(p); }
}
