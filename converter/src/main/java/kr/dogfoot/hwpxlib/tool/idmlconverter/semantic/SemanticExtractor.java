package kr.dogfoot.hwpxlib.tool.idmlconverter.semantic;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTDocument;
import kr.dogfoot.hwpxlib.tool.idmlconverter.semantic.adapter.ASTAdapter;
import kr.dogfoot.hwpxlib.tool.idmlconverter.semantic.adapter.ASTDocumentAdapter;
import kr.dogfoot.hwpxlib.tool.idmlconverter.semantic.classify.RuleEngine;
import kr.dogfoot.hwpxlib.tool.idmlconverter.semantic.extract.FeatureExtractor;
import kr.dogfoot.hwpxlib.tool.idmlconverter.semantic.relate.RelationBuilder;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * SemanticExtractor — 시멘틱 레이어 추출 진입점.
 *
 * <p>AST + Schema → SemanticLayer 한 번에 만든다.</p>
 *
 * <p>TS 측 데스크탑의 {@code useSemanticStore.loadFromAst} 와 동일 동작:</p>
 * <pre>
 *   const adapter = new ASTJsonAdapter(astJson)
 *   const nodes = extractFeatures(adapter)
 *   const classified = classifyNodes(nodes, schema.rules)
 *   const relations = buildRelations(classified, schema.relationRules)
 * </pre>
 */
public final class SemanticExtractor {

    private SemanticExtractor() {}

    /** ASTDocument 와 SemanticSchema 로부터 SemanticLayer 추출. */
    public static SemanticLayer extract(ASTDocument astDoc, SemanticSchema schema) {
        return extract(new ASTDocumentAdapter(astDoc), schema);
    }

    /** ASTAdapter 를 직접 받는 오버로드. */
    public static SemanticLayer extract(ASTAdapter adapter, SemanticSchema schema) {
        // 1. Feature 추출
        List<SemanticNode> nodes = FeatureExtractor.extractFeatures(adapter);

        // 2. 분류
        if (schema != null && schema.rules != null) {
            RuleEngine.classifyNodes(nodes, schema.rules);
        }

        // 3. 관계 생성
        List<SemanticRelation> relations;
        if (schema != null && schema.relationRules != null) {
            relations = RelationBuilder.buildRelations(nodes, schema.relationRules);
        } else {
            relations = RelationBuilder.buildRelations(nodes, new ArrayList<SemanticSchema.RelationRule>());
        }

        // 4. SemanticLayer 조립
        SemanticLayer layer = new SemanticLayer();
        layer.version = "1.0.0";
        layer.schemaId = schema != null ? schema.schemaId : "";
        layer.sourceAstHash = adapter.getDocumentHash();
        String now = Instant.now().toString();
        layer.createdAt = now;
        layer.modifiedAt = now;
        layer.nodes = nodes;
        layer.relations = relations;
        return layer;
    }
}
