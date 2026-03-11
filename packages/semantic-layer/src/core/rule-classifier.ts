/**
 * RuleClassifier — ClassificationRule[] 평가 엔진.
 * 노드의 StructuralFeatures에 대해 조건을 평가하고 최적 레이블을 결정.
 */

import type { SemanticNode, ClassificationRule, Condition, StructuralFeatures } from '../types.js'

export interface ClassificationResult {
  label: string
  confidence: number
  ruleId: string
}

/**
 * 노드 배열에 규칙을 적용하여 레이블을 부여.
 * manualOverride된 노드는 건너뜀.
 */
export function classifyNodes(
  nodes: SemanticNode[],
  rules: ClassificationRule[],
): SemanticNode[] {
  return nodes.map(node => {
    if (node.manualOverride) return node
    const result = classifyNode(node, rules)
    if (!result) return node
    return {
      ...node,
      label: result.label,
      confidence: result.confidence,
      appliedRule: result.ruleId,
    }
  })
}

/** 단일 노드에 대해 최적 규칙 선택 */
export function classifyNode(
  node: SemanticNode,
  rules: ClassificationRule[],
): ClassificationResult | null {
  let best: ClassificationResult | null = null

  for (const rule of rules) {
    if (!evaluateConditions(rule.conditions, node.features)) continue

    // 규칙은 이미 priority 정렬되어 있으므로
    // 같은 confidence면 먼저 매칭된 것 우선
    if (!best || rule.confidence > best.confidence) {
      best = {
        label: rule.label,
        confidence: rule.confidence,
        ruleId: rule.id,
      }
    }
  }

  return best
}

/** 모든 조건이 만족되는지 평가 (AND) */
export function evaluateConditions(
  conditions: Condition[],
  features: StructuralFeatures,
): boolean {
  return conditions.every(c => evaluateCondition(c, features))
}

/** 단일 조건 평가 */
function evaluateCondition(condition: Condition, features: StructuralFeatures): boolean {
  const value = getFieldValue(condition.field, features)
  const expected = condition.value

  switch (condition.operator) {
    case 'eq':
      return value === expected
    case 'ne':
      return value !== expected
    case 'gt':
      return typeof value === 'number' && typeof expected === 'number' && value > expected
    case 'lt':
      return typeof value === 'number' && typeof expected === 'number' && value < expected
    case 'gte':
      return typeof value === 'number' && typeof expected === 'number' && value >= expected
    case 'lte':
      return typeof value === 'number' && typeof expected === 'number' && value <= expected
    case 'contains':
      if (typeof value === 'string' && typeof expected === 'string') {
        return value.includes(expected)
      }
      if (Array.isArray(value)) {
        return value.includes(expected)
      }
      return false
    case 'startsWith':
      return typeof value === 'string' && typeof expected === 'string' && value.startsWith(expected)
    case 'matches':
      if (typeof value === 'string' && typeof expected === 'string') {
        try {
          return new RegExp(expected).test(value)
        } catch {
          return false
        }
      }
      return false
    case 'in':
      return Array.isArray(expected) && expected.includes(value)
    case 'notIn':
      return Array.isArray(expected) && !expected.includes(value)
    default:
      return false
  }
}

/**
 * dot-notation 지원하는 필드 접근.
 * 예: "spatial.overlappingNodeIds" → features.spatial.overlappingNodeIds
 */
function getFieldValue(field: string, features: StructuralFeatures): unknown {
  const parts = field.split('.')
  let current: unknown = features
  for (const part of parts) {
    if (current == null || typeof current !== 'object') return undefined
    current = (current as Record<string, unknown>)[part]
  }
  return current
}
