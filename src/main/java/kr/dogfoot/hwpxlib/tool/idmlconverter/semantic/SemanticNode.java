package kr.dogfoot.hwpxlib.tool.idmlconverter.semantic;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * SemanticNode — 시멘틱 레이어의 한 노드.
 *
 * <p>TypeScript {@code SemanticNode} 와 1:1.</p>
 */
public class SemanticNode {
    public String id;
    public String astPath;
    public SemanticTypes.NodeType nodeType;
    public StructuralFeatures features;

    /** 미분류 시 {@link SemanticTypes#LABEL_UNKNOWN}. */
    public String label = SemanticTypes.LABEL_UNKNOWN;
    public double confidence;
    public String appliedRule;
    public boolean manualOverride;

    public List<String> children = new ArrayList<>();
    public String storyId;
    public Map<String, Object> metadata = new LinkedHashMap<>();
}
