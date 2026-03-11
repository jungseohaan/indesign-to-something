import { describe, it, expect } from 'vitest'
import { validateRules } from '../src/core/rule-validator.js'
import type { SemanticNode, ClassificationRule } from '../src/types.js'

function makeNode(
  id: string,
  label: string,
  overrides: Record<string, any> = {},
): SemanticNode {
  return {
    id,
    astPath: '',
    nodeType: 'FRAME',
    features: {
      pageNumber: 1, x: 0, y: 0, width: 50000, height: 5000,
      zOrder: 1, regionTag: 'MIDDLE', columnIndex: 0, relativeYInPage: 0.5,
      storyId: null, storyFrameCount: 0, storyPageSpan: 0,
      frameIndexInStory: 0, isStoryStart: true, isStoryEnd: true,
      textContent: '', textLength: 0, paragraphCount: 1,
      dominantFontSize: 1200, maxFontSize: 1200, dominantFontFamily: '바탕',
      hasBoldText: false, dominantAlignment: null,
      hasNumberPrefix: false, numberPrefixPattern: null, firstLineText: '',
      paragraphStyleNames: [], characterStyleNames: [],
      dominantParagraphStyle: null,
      hasFill: false, fillColor: null, hasStroke: false,
      isBackgroundOnly: false, columnCount: 1, rotationAngle: 0,
      hasTable: false, hasImage: false, hasEquation: false,
      hasInlineFrame: false, inlineObjectCount: 0, blockType: 'TEXT_FRAME',
      spatial: {
        nearestContentNodeId: null, nearestContentDistance: -1,
        overlappingNodeIds: [], isVisuallyContainedBy: null, visualContainmentRatio: 0,
      },
      ...overrides,
    },
    label,
    confidence: 1,
    appliedRule: null,
    manualOverride: true,
    children: [],
    storyId: null,
    metadata: {},
  }
}

describe('validateRules', () => {
  const rules: ClassificationRule[] = [
    {
      id: 'rule-bg',
      label: 'BACKGROUND',
      priority: 5,
      conditions: [{ field: 'isBackgroundOnly', operator: 'eq', value: true }],
      confidence: 0.95,
    },
    {
      id: 'rule-problem',
      label: 'PROBLEM',
      priority: 40,
      conditions: [{ field: 'hasNumberPrefix', operator: 'eq', value: true }],
      confidence: 0.85,
    },
  ]

  it('정확히 분류되면 precision/recall 1.0', () => {
    const groundTruth = [
      makeNode('n1', 'BACKGROUND', { isBackgroundOnly: true }),
      makeNode('n2', 'BACKGROUND', { isBackgroundOnly: true }),
    ]

    const result = validateRules(groundTruth, rules)
    const bgMetric = result.labelMetrics.find(m => m.label === 'BACKGROUND')
    expect(bgMetric).toBeDefined()
    expect(bgMetric!.precision).toBe(1)
    expect(bgMetric!.recall).toBe(1)
    expect(result.accuracy).toBe(1)
  })

  it('오분류 감지', () => {
    const groundTruth = [
      makeNode('n1', 'BODY_TEXT', { hasNumberPrefix: true }), // 실제 BODY_TEXT인데 규칙은 PROBLEM으로 분류
      makeNode('n2', 'PROBLEM', { hasNumberPrefix: true }),
    ]

    const result = validateRules(groundTruth, rules)
    // PROBLEM: tp=1 (n2), fp=1 (n1 잘못분류), fn=0
    const problemMetric = result.labelMetrics.find(m => m.label === 'PROBLEM')
    expect(problemMetric!.tp).toBe(1)
    expect(problemMetric!.fp).toBe(1)
    expect(problemMetric!.precision).toBe(0.5)
  })

  it('미분류 노드 처리', () => {
    const groundTruth = [
      makeNode('n1', 'BODY_TEXT'), // 규칙 없음
    ]

    const result = validateRules(groundTruth, rules)
    expect(result.classifiedCount).toBe(0)
    expect(result.totalCount).toBe(1)
    expect(result.accuracy).toBe(0)
  })

  it('전체 정확도 계산', () => {
    const groundTruth = [
      makeNode('n1', 'BACKGROUND', { isBackgroundOnly: true }),
      makeNode('n2', 'PROBLEM', { hasNumberPrefix: true }),
      makeNode('n3', 'BODY_TEXT'), // 미분류
    ]

    const result = validateRules(groundTruth, rules)
    // 3개 중 2개 정확 → 2/3
    expect(result.accuracy).toBeCloseTo(0.6667, 3)
  })
})
