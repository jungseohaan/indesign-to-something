/**
 * RuleValidator — 규칙의 정밀도(precision) / 재현율(recall) 계산.
 * 수동 레이블 데이터와 규칙 분류 결과를 비교.
 */

import type { SemanticNode, ClassificationRule } from '../types.js'
import { classifyNode } from './rule-classifier.js'

export interface ValidationResult {
  /** 규칙별 성능 */
  ruleMetrics: RuleMetric[]
  /** 레이블별 성능 */
  labelMetrics: LabelMetric[]
  /** 전체 정확도 */
  accuracy: number
  /** 분류된 노드 수 */
  classifiedCount: number
  /** 전체 노드 수 */
  totalCount: number
}

export interface RuleMetric {
  ruleId: string
  label: string
  /** True Positive */
  tp: number
  /** False Positive */
  fp: number
  /** False Negative (이 레이블인데 다른 규칙에 매칭) */
  fn: number
  precision: number
  recall: number
  f1: number
}

export interface LabelMetric {
  label: string
  tp: number
  fp: number
  fn: number
  precision: number
  recall: number
  f1: number
  support: number
}

/**
 * 수동 레이블 노드와 규칙 분류 결과 비교.
 * @param groundTruth 정답 레이블이 있는 노드 (manualOverride=true)
 * @param rules 검증할 규칙 세트
 */
export function validateRules(
  groundTruth: SemanticNode[],
  rules: ClassificationRule[],
): ValidationResult {
  const predictions = new Map<string, string>() // nodeId → predicted label
  const actuals = new Map<string, string>() // nodeId → actual label

  for (const node of groundTruth) {
    actuals.set(node.id, node.label)

    // manualOverride를 임시로 해제하고 분류
    const testNode = { ...node, manualOverride: false }
    const result = classifyNode(testNode, rules)
    predictions.set(node.id, result?.label ?? 'UNKNOWN')
  }

  // 레이블별 metrics
  const allLabels = new Set<string>()
  for (const l of actuals.values()) allLabels.add(l)
  for (const l of predictions.values()) allLabels.add(l)
  allLabels.delete('UNKNOWN')

  const labelMetrics: LabelMetric[] = []
  let totalCorrect = 0

  for (const label of allLabels) {
    let tp = 0, fp = 0, fn = 0
    for (const nodeId of actuals.keys()) {
      const actual = actuals.get(nodeId)!
      const predicted = predictions.get(nodeId)!
      if (actual === label && predicted === label) tp++
      else if (actual !== label && predicted === label) fp++
      else if (actual === label && predicted !== label) fn++
    }

    const precision = tp + fp > 0 ? tp / (tp + fp) : 0
    const recall = tp + fn > 0 ? tp / (tp + fn) : 0
    const f1 = precision + recall > 0 ? 2 * precision * recall / (precision + recall) : 0

    labelMetrics.push({
      label,
      tp, fp, fn,
      precision: round(precision),
      recall: round(recall),
      f1: round(f1),
      support: tp + fn,
    })

    totalCorrect += tp
  }

  // 규칙별 metrics
  const ruleMetrics: RuleMetric[] = []
  for (const rule of rules) {
    const lm = labelMetrics.find(m => m.label === rule.label)
    ruleMetrics.push({
      ruleId: rule.id,
      label: rule.label,
      tp: lm?.tp ?? 0,
      fp: lm?.fp ?? 0,
      fn: lm?.fn ?? 0,
      precision: lm?.precision ?? 0,
      recall: lm?.recall ?? 0,
      f1: lm?.f1 ?? 0,
    })
  }

  const totalCount = groundTruth.length
  const classifiedCount = [...predictions.values()].filter(l => l !== 'UNKNOWN').length

  return {
    ruleMetrics,
    labelMetrics,
    accuracy: totalCount > 0 ? round(totalCorrect / totalCount) : 0,
    classifiedCount,
    totalCount,
  }
}

function round(n: number): number {
  return Math.round(n * 10000) / 10000
}
