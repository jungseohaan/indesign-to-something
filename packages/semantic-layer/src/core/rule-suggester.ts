/**
 * RuleSuggester — 수동 레이블링된 노드에서 규칙을 역추출.
 * 같은 레이블의 노드들에서 공통 feature 패턴을 찾아 규칙 초안 생성.
 */

import type { SemanticNode, ClassificationRule, Condition } from '../types.js'

export interface SuggestedRule extends ClassificationRule {
  /** 이 규칙이 매칭하는 노드 수 */
  matchCount: number
  /** 전체 해당 레이블 노드 대비 비율 */
  coverage: number
}

/** 분석 대상 feature 필드 */
const ANALYZABLE_FIELDS: Array<{
  field: string
  type: 'boolean' | 'number' | 'string' | 'string_contains'
}> = [
  { field: 'regionTag', type: 'string' },
  { field: 'blockType', type: 'string' },
  { field: 'hasBoldText', type: 'boolean' },
  { field: 'hasFill', type: 'boolean' },
  { field: 'hasStroke', type: 'boolean' },
  { field: 'isBackgroundOnly', type: 'boolean' },
  { field: 'hasEquation', type: 'boolean' },
  { field: 'hasImage', type: 'boolean' },
  { field: 'hasTable', type: 'boolean' },
  { field: 'hasNumberPrefix', type: 'boolean' },
  { field: 'numberPrefixPattern', type: 'string' },
  { field: 'dominantParagraphStyle', type: 'string' },
  { field: 'dominantAlignment', type: 'string' },
  { field: 'isStoryStart', type: 'boolean' },
  { field: 'isStoryEnd', type: 'boolean' },
  { field: 'dominantFontFamily', type: 'string' },
  { field: 'firstLineText', type: 'string_contains' },
]

/**
 * 수동 레이블링된 노드에서 규칙 초안 생성.
 * @param labeledNodes manualOverride가 true인 노드들
 * @param minCoverage 최소 커버리지 (기본 0.6 = 60%)
 */
export function suggestRules(
  labeledNodes: SemanticNode[],
  minCoverage: number = 0.6,
): SuggestedRule[] {
  // 레이블별 그룹화
  const groups = new Map<string, SemanticNode[]>()
  for (const node of labeledNodes) {
    if (node.label === 'UNKNOWN') continue
    const group = groups.get(node.label)
    if (group) {
      group.push(node)
    } else {
      groups.set(node.label, [node])
    }
  }

  const suggestions: SuggestedRule[] = []
  let ruleIdx = 0

  for (const [label, nodes] of groups) {
    if (nodes.length < 2) continue

    // 공통 feature 패턴 찾기
    const commonConditions = findCommonConditions(nodes)

    if (commonConditions.length === 0) continue

    // 조건 조합으로 규칙 생성 (1~3개 조건)
    const combos = generateConditionCombos(commonConditions, 3)

    for (const conditions of combos) {
      const matchCount = nodes.filter(n =>
        conditions.every(c => matchesCondition(c, n)),
      ).length
      const coverage = matchCount / nodes.length

      if (coverage >= minCoverage && matchCount >= 2) {
        suggestions.push({
          id: `suggested-${label.toLowerCase()}-${ruleIdx++}`,
          label,
          priority: 100,
          conditions,
          confidence: Math.round(coverage * 100) / 100,
          matchCount,
          coverage,
        })
      }
    }
  }

  // 커버리지 높은 순, 조건 적은 순 정렬
  suggestions.sort((a, b) => {
    if (b.coverage !== a.coverage) return b.coverage - a.coverage
    return a.conditions.length - b.conditions.length
  })

  // 같은 레이블에서 중복 제거 (조건이 더 단순한 것 우선)
  return deduplicateSuggestions(suggestions)
}

/** 공통 조건 추출 */
function findCommonConditions(nodes: SemanticNode[]): Condition[] {
  const conditions: Condition[] = []

  for (const { field, type } of ANALYZABLE_FIELDS) {
    const values = nodes.map(n => getField(n, field))

    if (type === 'boolean') {
      const trueCount = values.filter(v => v === true).length
      const falseCount = values.filter(v => v === false).length
      if (trueCount === nodes.length) {
        conditions.push({ field, operator: 'eq', value: true })
      } else if (falseCount === nodes.length) {
        conditions.push({ field, operator: 'eq', value: false })
      }
    } else if (type === 'string') {
      const nonNull = values.filter((v): v is string => typeof v === 'string' && v !== '')
      if (nonNull.length < nodes.length * 0.8) continue
      const freq = mode(nonNull)
      if (freq && nonNull.filter(v => v === freq).length >= nodes.length * 0.8) {
        conditions.push({ field, operator: 'eq', value: freq })
      }
    } else if (type === 'string_contains') {
      // firstLineText에서 공통 접두사 추출
      const nonNull = values.filter((v): v is string => typeof v === 'string' && v.length > 0)
      if (nonNull.length < nodes.length * 0.8) continue
      const common = findCommonSubstring(nonNull)
      if (common && common.length >= 2) {
        conditions.push({ field, operator: 'contains', value: common })
      }
    }
  }

  // 수치 필드: dominantFontSize 범위
  const fontSizes = nodes.map(n => n.features.dominantFontSize).filter(v => v > 0)
  if (fontSizes.length >= nodes.length * 0.8) {
    const min = Math.min(...fontSizes)
    const max = Math.max(...fontSizes)
    if (min === max) {
      conditions.push({ field: 'dominantFontSize', operator: 'eq', value: min })
    } else if (max - min <= min * 0.2) {
      conditions.push({ field: 'dominantFontSize', operator: 'gte', value: min })
      conditions.push({ field: 'dominantFontSize', operator: 'lte', value: max })
    }
  }

  return conditions
}

/** 조건 조합 생성 (최대 maxSize개) */
function generateConditionCombos(conditions: Condition[], maxSize: number): Condition[][] {
  const result: Condition[][] = []

  // 1개짜리
  for (const c of conditions) {
    result.push([c])
  }

  // 2개짜리
  if (maxSize >= 2 && conditions.length >= 2) {
    for (let i = 0; i < conditions.length; i++) {
      for (let j = i + 1; j < conditions.length; j++) {
        // 같은 필드 범위 조건은 합치기
        if (conditions[i].field === conditions[j].field) {
          result.push([conditions[i], conditions[j]])
        } else {
          result.push([conditions[i], conditions[j]])
        }
      }
    }
  }

  // 3개짜리 (조건이 충분한 경우만)
  if (maxSize >= 3 && conditions.length >= 3) {
    for (let i = 0; i < conditions.length; i++) {
      for (let j = i + 1; j < conditions.length; j++) {
        for (let k = j + 1; k < conditions.length; k++) {
          result.push([conditions[i], conditions[j], conditions[k]])
        }
      }
    }
  }

  return result
}

/** 노드의 feature 필드 접근 */
function getField(node: SemanticNode, field: string): unknown {
  const parts = field.split('.')
  let current: unknown = node.features
  for (const part of parts) {
    if (current == null || typeof current !== 'object') return undefined
    current = (current as Record<string, unknown>)[part]
  }
  return current
}

/** 조건 매칭 확인 */
function matchesCondition(condition: Condition, node: SemanticNode): boolean {
  const value = getField(node, condition.field)
  switch (condition.operator) {
    case 'eq': return value === condition.value
    case 'ne': return value !== condition.value
    case 'gt': return typeof value === 'number' && typeof condition.value === 'number' && value > condition.value
    case 'lt': return typeof value === 'number' && typeof condition.value === 'number' && value < condition.value
    case 'gte': return typeof value === 'number' && typeof condition.value === 'number' && value >= condition.value
    case 'lte': return typeof value === 'number' && typeof condition.value === 'number' && value <= condition.value
    case 'contains':
      if (typeof value === 'string' && typeof condition.value === 'string') return value.includes(condition.value)
      if (Array.isArray(value)) return value.includes(condition.value)
      return false
    default: return false
  }
}

/** 문자열 배열에서 공통 부분 문자열 (접두사 기준) */
function findCommonSubstring(strings: string[]): string | null {
  if (strings.length === 0) return null
  const first = strings[0]
  let prefix = ''
  for (let i = 0; i < Math.min(first.length, 20); i++) {
    const char = first[i]
    if (strings.every(s => s[i] === char)) {
      prefix += char
    } else {
      break
    }
  }
  return prefix.trim() || null
}

/** 최빈값 */
function mode<T>(arr: T[]): T | undefined {
  if (arr.length === 0) return undefined
  const counts = new Map<T, number>()
  let maxCount = 0
  let maxVal: T = arr[0]
  for (const v of arr) {
    const c = (counts.get(v) ?? 0) + 1
    counts.set(v, c)
    if (c > maxCount) { maxCount = c; maxVal = v }
  }
  return maxVal
}

/** 같은 레이블 내 중복 제거 */
function deduplicateSuggestions(suggestions: SuggestedRule[]): SuggestedRule[] {
  const seen = new Map<string, SuggestedRule>()
  return suggestions.filter(s => {
    const key = `${s.label}:${s.conditions.map(c => `${c.field}${c.operator}${c.value}`).sort().join(',')}`
    if (seen.has(key)) return false
    seen.set(key, s)
    return true
  })
}
