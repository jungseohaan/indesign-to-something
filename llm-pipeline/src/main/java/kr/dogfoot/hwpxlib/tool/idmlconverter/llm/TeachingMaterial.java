package kr.dogfoot.hwpxlib.tool.idmlconverter.llm;

import java.util.ArrayList;
import java.util.List;

public class TeachingMaterial {
    public String source;
    public String generatedAt;
    public List<String> promptFiles = new ArrayList<String>();
    public String model;
    public List<TeachingUnit> units = new ArrayList<TeachingUnit>();
}
