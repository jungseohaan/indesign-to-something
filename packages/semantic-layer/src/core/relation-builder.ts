/**
 * RelationBuilder — 시멘틱 관계(SemanticRelation) 생성.
 * 스토리 기반 CONTINUES_FROM + 규칙 기반 + 공간 근접도.
 */

import type { SemanticNode, SemanticRelation, RelationRule, RelationType } from '../types.js'
import { evaluateConditions } from './rule-classifier.js'

/**
 * 노드 배열에서 관계를 추출.
 */
export function buildRelations(
  nodes: SemanticNode[],
  relationRules: RelationRule[],
): SemanticRelation[] {
  const relations: SemanticRelation[] = []

  // 1. 스토리 기반 CONTINUES_FROM
  relations.push(...buildStoryRelations(nodes))

  // 2. 규칙 기반 관계
  relations.push(...buildRuleBasedRelations(nodes, relationRules))

  // 중복 제거
  return deduplicateRelations(relations)
}

/** 스토리 프레임 체인에서 CONTINUES_FROM 관계 생성 */
function buildStoryRelations(nodes: SemanticNode[]): SemanticRelation[] {
  const relations: SemanticRelation[] = []
  const storyGroups = new Map<string, SemanticNode[]>()

  for (const node of nodes) {
    if (!node.storyId) continue
    const group = storyGroups.get(node.storyId)
    if (group) {
      group.push(node)
    } else {
      storyGroups.set(node.storyId, [node])
    }
  }

  for (const [, group] of storyGroups) {
    if (group.length < 2) continue
    // frameIndexInStory로 정렬
    group.sort((a, b) => a.features.frameIndexInStory - b.features.frameIndexInStory)
    for (let i = 1; i < group.length; i++) {
      relations.push({
        type: 'CONTINUES_FROM',
        sourceId: group[i].id,
        targetId: group[i - 1].id,
        confidence: 1.0,
      })
    }
  }

  return relations
}

/** RelationRule 기반 관계 생성 */
function buildRuleBasedRelations(
  nodes: SemanticNode[],
  rules: RelationRule[],
): SemanticRelation[] {
  const relations: SemanticRelation[] = []
  const nodesByLabel = new Map<string, SemanticNode[]>()

  for (const node of nodes) {
    if (node.label === 'UNKNOWN') continue
    const list = nodesByLabel.get(node.label)
    if (list) {
      list.push(node)
    } else {
      nodesByLabel.set(node.label, [node])
    }
  }

  for (const rule of rules) {
    const sources = nodesByLabel.get(rule.sourceLabel) ?? []
    const targets = nodesByLabel.get(rule.targetLabel) ?? []

    for (const source of sources) {
      for (const target of targets) {
        if (source.id === target.id) continue

        // 조건이 있으면 source 기준으로 평가
        if (rule.conditions && rule.conditions.length > 0) {
          if (!evaluateConditions(rule.conditions, source.features)) continue
        }

        // 같은 페이지 또는 공간적으로 가까운 경우만
        if (!isRelatable(source, target, rule.type)) continue

        relations.push({
          type: rule.type,
          sourceId: source.id,
          targetId: target.id,
          confidence: 0.8,
        })
      }
    }
  }

  return relations
}

/** 관계 가능 여부 판단 */
function isRelatable(source: SemanticNode, target: SemanticNode, type: RelationType): boolean {
  // CONTINUES_FROM은 스토리 기반으로만 (위에서 처리)
  if (type === 'CONTINUES_FROM') return false

  // 같은 페이지
  if (source.features.pageNumber === target.features.pageNumber) return true

  // 인접 페이지 (REFERENCES 등)
  if (Math.abs(source.features.pageNumber - target.features.pageNumber) <= 1) return true

  return false
}

/** 중복 관계 제거 */
function deduplicateRelations(relations: SemanticRelation[]): SemanticRelation[] {
  const seen = new Set<string>()
  return relations.filter(r => {
    const key = `${r.type}:${r.sourceId}:${r.targetId}`
    if (seen.has(key)) return false
    seen.add(key)
    return true
  })
}
