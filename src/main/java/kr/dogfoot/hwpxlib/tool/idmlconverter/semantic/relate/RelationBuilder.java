package kr.dogfoot.hwpxlib.tool.idmlconverter.semantic.relate;

import kr.dogfoot.hwpxlib.tool.idmlconverter.semantic.SemanticNode;
import kr.dogfoot.hwpxlib.tool.idmlconverter.semantic.SemanticRelation;
import kr.dogfoot.hwpxlib.tool.idmlconverter.semantic.SemanticSchema;
import kr.dogfoot.hwpxlib.tool.idmlconverter.semantic.SemanticTypes;
import kr.dogfoot.hwpxlib.tool.idmlconverter.semantic.classify.RuleEngine;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * RelationBuilder — 시멘틱 관계 생성.
 *
 * <p>TS {@code packages/semantic-layer/src/core/relation-builder.ts} 와 1:1 포팅.</p>
 *
 * <p>두 종류의 관계:</p>
 * <ol>
 *   <li>스토리 기반 CONTINUES_FROM (frameIndexInStory 순서)</li>
 *   <li>RelationRule 기반 (sourceLabel → targetLabel, 같은 페이지 또는 인접 페이지)</li>
 * </ol>
 */
public final class RelationBuilder {

    private RelationBuilder() {}

    public static List<SemanticRelation> buildRelations(
            List<SemanticNode> nodes,
            List<SemanticSchema.RelationRule> relationRules) {
        List<SemanticRelation> relations = new ArrayList<>();
        relations.addAll(buildStoryRelations(nodes));
        relations.addAll(buildRuleBasedRelations(nodes, relationRules));
        return deduplicateRelations(relations);
    }

    /** 스토리 프레임 체인에서 CONTINUES_FROM 관계 생성. */
    private static List<SemanticRelation> buildStoryRelations(List<SemanticNode> nodes) {
        List<SemanticRelation> relations = new ArrayList<>();
        Map<String, List<SemanticNode>> storyGroups = new LinkedHashMap<>();
        for (SemanticNode node : nodes) {
            if (node.storyId == null) continue;
            storyGroups.computeIfAbsent(node.storyId, k -> new ArrayList<>()).add(node);
        }

        for (Map.Entry<String, List<SemanticNode>> e : storyGroups.entrySet()) {
            List<SemanticNode> group = e.getValue();
            if (group.size() < 2) continue;
            // frameIndexInStory 오름차순 정렬 (안정 정렬)
            group.sort(Comparator.comparingInt(n -> n.features.frameIndexInStory));
            for (int i = 1; i < group.size(); i++) {
                SemanticRelation r = new SemanticRelation(
                        SemanticTypes.RelationType.CONTINUES_FROM,
                        group.get(i).id,
                        group.get(i - 1).id,
                        1.0);
                relations.add(r);
            }
        }
        return relations;
    }

    /** RelationRule 기반 관계 생성. */
    private static List<SemanticRelation> buildRuleBasedRelations(
            List<SemanticNode> nodes,
            List<SemanticSchema.RelationRule> rules) {
        List<SemanticRelation> relations = new ArrayList<>();
        if (rules == null || rules.isEmpty()) return relations;

        Map<String, List<SemanticNode>> nodesByLabel = new LinkedHashMap<>();
        for (SemanticNode node : nodes) {
            if (SemanticTypes.LABEL_UNKNOWN.equals(node.label)) continue;
            nodesByLabel.computeIfAbsent(node.label, k -> new ArrayList<>()).add(node);
        }

        for (SemanticSchema.RelationRule rule : rules) {
            List<SemanticNode> sources = nodesByLabel.get(rule.sourceLabel);
            List<SemanticNode> targets = nodesByLabel.get(rule.targetLabel);
            if (sources == null || targets == null) continue;

            for (SemanticNode source : sources) {
                for (SemanticNode target : targets) {
                    if (source.id.equals(target.id)) continue;
                    if (rule.conditions != null && !rule.conditions.isEmpty()) {
                        if (!RuleEngine.evaluateConditions(rule.conditions, source.features)) continue;
                    }
                    if (!isRelatable(source, target, rule.type)) continue;
                    relations.add(new SemanticRelation(rule.type, source.id, target.id, 0.8));
                }
            }
        }
        return relations;
    }

    private static boolean isRelatable(SemanticNode source, SemanticNode target, SemanticTypes.RelationType type) {
        // CONTINUES_FROM 은 스토리 기반으로만
        if (type == SemanticTypes.RelationType.CONTINUES_FROM) return false;
        // 같은 페이지 또는 인접 페이지 (±1)
        int diff = Math.abs(source.features.pageNumber - target.features.pageNumber);
        return diff <= 1;
    }

    private static List<SemanticRelation> deduplicateRelations(List<SemanticRelation> relations) {
        List<SemanticRelation> out = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (SemanticRelation r : relations) {
            String key = (r.type != null ? r.type.name() : "null") + ":" + r.sourceId + ":" + r.targetId;
            if (seen.add(key)) out.add(r);
        }
        return out;
    }
}
