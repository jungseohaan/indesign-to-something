package kr.dogfoot.hwpxlib.tool.idmlconverter.semantic.adapter;

import java.util.ArrayList;
import java.util.List;

/**
 * 문단 정보 (TS {@code ParagraphInfo} 와 1:1).
 */
public class ParagraphInfo {
    public int index;
    public String alignment;
    public String paragraphStyleRef;
    public Long firstLineIndent;
    public Long spaceBefore;
    public Long spaceAfter;
    public List<InlineItemInfo> items = new ArrayList<>();
}
