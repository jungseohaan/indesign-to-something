package kr.dogfoot.hwpxlib.tool.idmlconverter.semantic.adapter;

import java.util.ArrayList;
import java.util.List;

/**
 * 스토리 정보 (TS {@code StoryInfo} 와 1:1).
 */
public class StoryInfo {
    public String storyId = "";
    public String orientation;
    public List<String> linkedFrameIds = new ArrayList<>();
    public List<Integer> pages = new ArrayList<>();
    public int paragraphCount;
    public int tableCount;
}
