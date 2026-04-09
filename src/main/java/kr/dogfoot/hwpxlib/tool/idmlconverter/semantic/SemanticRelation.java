package kr.dogfoot.hwpxlib.tool.idmlconverter.semantic;

/**
 * SemanticRelation — 노드 사이의 시멘틱 관계.
 *
 * <p>TypeScript {@code SemanticRelation} 와 1:1.</p>
 */
public class SemanticRelation {
    public SemanticTypes.RelationType type;
    public String sourceId;
    public String targetId;
    public Double confidence;

    public SemanticRelation() {}

    public SemanticRelation(SemanticTypes.RelationType type, String sourceId, String targetId, Double confidence) {
        this.type = type;
        this.sourceId = sourceId;
        this.targetId = targetId;
        this.confidence = confidence;
    }
}
