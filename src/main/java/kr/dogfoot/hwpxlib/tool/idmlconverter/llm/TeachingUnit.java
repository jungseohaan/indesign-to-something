package kr.dogfoot.hwpxlib.tool.idmlconverter.llm;

import java.util.ArrayList;
import java.util.List;

public class TeachingUnit {
    public int unitIndex;
    public String unitTitle;
    public List<Integer> sourcePages = new ArrayList<Integer>();
    public List<SlideContent> slides = new ArrayList<SlideContent>();
}
