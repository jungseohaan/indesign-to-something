import { describe, it, expect } from 'vitest'
import { suggestRules } from '../src/core/rule-suggester.js'
import type { SemanticNode } from '../src/types.js'

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

describe('suggestRules', () => {
  it('공통 boolean 패턴 추출', () => {
    const nodes = [
      makeNode('n1', 'PROBLEM', { hasNumberPrefix: true, numberPrefixPattern: 'arabic_dot' }),
      makeNode('n2', 'PROBLEM', { hasNumberPrefix: true, numberPrefixPattern: 'arabic_dot' }),
      makeNode('n3', 'PROBLEM', { hasNumberPrefix: true, numberPrefixPattern: 'arabic_dot' }),
    ]

    const rules = suggestRules(nodes)
    expect(rules.length).toBeGreaterThan(0)
    expect(rules[0].label).toBe('PROBLEM')

    // hasNumberPrefix=true 조건이 어떤 규칙에든 포함되어야 함
    const hasNumberRule = rules.some(r =>
      r.conditions.some(c => c.field === 'hasNumberPrefix' && c.value === true),
    )
    expect(hasNumberRule).toBe(true)
  })

  it('2개 미만 노드는 건너뜀', () => {
    const nodes = [
      makeNode('n1', 'RARE_LABEL', { hasEquation: true }),
    ]
    const rules = suggestRules(nodes)
    expect(rules).toHaveLength(0)
  })

  it('UNKNOWN 레이블 무시', () => {
    const nodes = [
      makeNode('n1', 'UNKNOWN'),
      makeNode('n2', 'UNKNOWN'),
    ]
    const rules = suggestRules(nodes)
    expect(rules).toHaveLength(0)
  })

  it('minCoverage 이하 규칙 제외', () => {
    const nodes = [
      makeNode('n1', 'PROBLEM', { hasNumberPrefix: true }),
      makeNode('n2', 'PROBLEM', { hasNumberPrefix: true }),
      makeNode('n3', 'PROBLEM', { hasNumberPrefix: false }), // 불일치
    ]

    // 높은 threshold 적용
    const rules = suggestRules(nodes, 1.0)
    // hasNumberPrefix=true는 2/3=66%이므로 100% threshold에서 제외
    const hasNumberRule = rules.find(r =>
      r.conditions.length === 1 &&
      r.conditions[0].field === 'hasNumberPrefix' &&
      r.conditions[0].value === true,
    )
    expect(hasNumberRule).toBeUndefined()
  })

  it('여러 레이블에서 각각 규칙 생성', () => {
    const nodes = [
      makeNode('n1', 'PROBLEM', { hasNumberPrefix: true }),
      makeNode('n2', 'PROBLEM', { hasNumberPrefix: true }),
      makeNode('n3', 'BACKGROUND', { isBackgroundOnly: true }),
      makeNode('n4', 'BACKGROUND', { isBackgroundOnly: true }),
    ]

    const rules = suggestRules(nodes)
    const labels = [...new Set(rules.map(r => r.label))]
    expect(labels).toContain('PROBLEM')
    expect(labels).toContain('BACKGROUND')
  })
})
