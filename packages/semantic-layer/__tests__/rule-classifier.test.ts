import { describe, it, expect } from 'vitest'
import { classifyNodes, classifyNode, evaluateConditions } from '../src/core/rule-classifier.js'
import type { SemanticNode, ClassificationRule, StructuralFeatures, Condition } from '../src/types.js'

function makeNode(overrides: Partial<StructuralFeatures> = {}): SemanticNode {
  return {
    id: 'sn-test',
    astPath: 'sections[0].blocks[0]',
    nodeType: 'FRAME',
    features: {
      pageNumber: 1, x: 0, y: 0, width: 50000, height: 5000,
      zOrder: 1, regionTag: 'FULL_WIDTH', columnIndex: 0, relativeYInPage: 0.05,
      storyId: null, storyFrameCount: 0, storyPageSpan: 0,
      frameIndexInStory: 0, isStoryStart: true, isStoryEnd: true,
      textContent: '테스트 텍스트', textLength: 7, paragraphCount: 1,
      dominantFontSize: 1200, maxFontSize: 1200, dominantFontFamily: '바탕',
      hasBoldText: false, dominantAlignment: 'LeftAlign',
      hasNumberPrefix: false, numberPrefixPattern: null, firstLineText: '테스트 텍스트',
      paragraphStyleNames: ['본문'], characterStyleNames: [],
      dominantParagraphStyle: '본문',
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
    label: 'UNKNOWN',
    confidence: 0,
    appliedRule: null,
    manualOverride: false,
    children: [],
    storyId: null,
    metadata: {},
  }
}

const RULES: ClassificationRule[] = [
  {
    id: 'rule-bg',
    label: 'BACKGROUND',
    priority: 5,
    conditions: [{ field: 'isBackgroundOnly', operator: 'eq', value: true }],
    confidence: 0.95,
  },
  {
    id: 'rule-title',
    label: 'SECTION_TITLE',
    priority: 20,
    conditions: [
      { field: 'dominantFontSize', operator: 'gte', value: 1400 },
      { field: 'paragraphCount', operator: 'lte', value: 3 },
    ],
    confidence: 0.8,
  },
  {
    id: 'rule-problem',
    label: 'PROBLEM',
    priority: 40,
    conditions: [
      { field: 'hasNumberPrefix', operator: 'eq', value: true },
      { field: 'numberPrefixPattern', operator: 'eq', value: 'arabic_dot' },
    ],
    confidence: 0.85,
  },
]

describe('evaluateConditions', () => {
  it('모든 조건 AND 평가', () => {
    const features = makeNode({ dominantFontSize: 1600, paragraphCount: 2 }).features
    const conditions: Condition[] = [
      { field: 'dominantFontSize', operator: 'gte', value: 1400 },
      { field: 'paragraphCount', operator: 'lte', value: 3 },
    ]
    expect(evaluateConditions(conditions, features)).toBe(true)
  })

  it('하나라도 실패하면 false', () => {
    const features = makeNode({ dominantFontSize: 1000, paragraphCount: 2 }).features
    const conditions: Condition[] = [
      { field: 'dominantFontSize', operator: 'gte', value: 1400 },
      { field: 'paragraphCount', operator: 'lte', value: 3 },
    ]
    expect(evaluateConditions(conditions, features)).toBe(false)
  })

  it('contains 연산자 — 문자열', () => {
    const features = makeNode({ textContent: '1. 다음 문제를 풀어라' }).features
    expect(evaluateConditions(
      [{ field: 'textContent', operator: 'contains', value: '문제' }],
      features,
    )).toBe(true)
  })

  it('contains 연산자 — 배열', () => {
    const features = makeNode({ paragraphStyleNames: ['본문', '제목'] }).features
    expect(evaluateConditions(
      [{ field: 'paragraphStyleNames', operator: 'contains', value: '제목' }],
      features,
    )).toBe(true)
  })

  it('in 연산자', () => {
    const features = makeNode({ regionTag: 'LEFT' as any }).features
    expect(evaluateConditions(
      [{ field: 'regionTag', operator: 'in', value: ['LEFT', 'RIGHT'] }],
      features,
    )).toBe(true)
  })

  it('matches 연산자 (regex)', () => {
    const features = makeNode({ firstLineText: '예제 3' }).features
    expect(evaluateConditions(
      [{ field: 'firstLineText', operator: 'matches', value: '^예제\\s+\\d' }],
      features,
    )).toBe(true)
  })

  it('dot-notation 필드 접근', () => {
    const node = makeNode()
    node.features.spatial.overlappingNodeIds = ['a', 'b']
    expect(evaluateConditions(
      [{ field: 'spatial.overlappingNodeIds', operator: 'contains', value: 'a' }],
      node.features,
    )).toBe(true)
  })
})

describe('classifyNode', () => {
  it('매칭되는 규칙 중 confidence 높은 것 선택', () => {
    const node = makeNode({ isBackgroundOnly: true })
    const result = classifyNode(node, RULES)
    expect(result).not.toBeNull()
    expect(result!.label).toBe('BACKGROUND')
    expect(result!.confidence).toBe(0.95)
  })

  it('매칭 규칙 없으면 null', () => {
    const node = makeNode()
    const result = classifyNode(node, RULES)
    expect(result).toBeNull()
  })

  it('여러 규칙 중 최적 선택', () => {
    const node = makeNode({
      dominantFontSize: 1600,
      paragraphCount: 1,
      hasNumberPrefix: true,
      numberPrefixPattern: 'arabic_dot',
    })
    const result = classifyNode(node, RULES)
    // PROBLEM confidence 0.85 > SECTION_TITLE 0.8
    expect(result!.label).toBe('PROBLEM')
  })
})

describe('classifyNodes', () => {
  it('manualOverride 노드 건너뜀', () => {
    const node = makeNode({ isBackgroundOnly: true })
    node.label = 'CUSTOM'
    node.manualOverride = true
    const result = classifyNodes([node], RULES)
    expect(result[0].label).toBe('CUSTOM')
  })

  it('배열 전체 분류', () => {
    const nodes = [
      makeNode({ isBackgroundOnly: true }),
      makeNode({ dominantFontSize: 1600, paragraphCount: 1 }),
      makeNode(), // 매칭 없음
    ]
    nodes[0].id = 'sn-bg'
    nodes[1].id = 'sn-title'
    nodes[2].id = 'sn-body'

    const result = classifyNodes(nodes, RULES)
    expect(result[0].label).toBe('BACKGROUND')
    expect(result[1].label).toBe('SECTION_TITLE')
    expect(result[2].label).toBe('UNKNOWN')
  })
})
