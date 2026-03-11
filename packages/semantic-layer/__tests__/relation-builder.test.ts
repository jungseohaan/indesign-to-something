import { describe, it, expect } from 'vitest'
import { buildRelations } from '../src/core/relation-builder.js'
import type { SemanticNode, RelationRule } from '../src/types.js'

function makeNode(
  id: string,
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
    label: 'UNKNOWN',
    confidence: 0,
    appliedRule: null,
    manualOverride: false,
    children: [],
    storyId: overrides.storyId ?? null,
    metadata: {},
  }
}

describe('buildRelations', () => {
  it('스토리 기반 CONTINUES_FROM', () => {
    const n1 = makeNode('sn-u1', { storyId: 's1', frameIndexInStory: 0 })
    n1.storyId = 's1'
    const n2 = makeNode('sn-u2', { storyId: 's1', frameIndexInStory: 1 })
    n2.storyId = 's1'

    const relations = buildRelations([n1, n2], [])
    expect(relations).toHaveLength(1)
    expect(relations[0].type).toBe('CONTINUES_FROM')
    expect(relations[0].sourceId).toBe('sn-u2')
    expect(relations[0].targetId).toBe('sn-u1')
  })

  it('규칙 기반 관계', () => {
    const caption = makeNode('sn-cap', { pageNumber: 1 })
    caption.label = 'CAPTION'
    const figure = makeNode('sn-fig', { pageNumber: 1 })
    figure.label = 'FIGURE'

    const rules: RelationRule[] = [{
      id: 'rel-caption-figure',
      type: 'CAPTION_FOR',
      sourceLabel: 'CAPTION',
      targetLabel: 'FIGURE',
    }]

    const relations = buildRelations([caption, figure], rules)
    expect(relations.some(r => r.type === 'CAPTION_FOR')).toBe(true)
  })

  it('다른 페이지 노드 간 관계 없음 (2페이지 이상 차이)', () => {
    const n1 = makeNode('sn-a', { pageNumber: 1 })
    n1.label = 'CAPTION'
    const n2 = makeNode('sn-b', { pageNumber: 5 })
    n2.label = 'FIGURE'

    const rules: RelationRule[] = [{
      id: 'rel-test',
      type: 'CAPTION_FOR',
      sourceLabel: 'CAPTION',
      targetLabel: 'FIGURE',
    }]

    const relations = buildRelations([n1, n2], rules)
    expect(relations.filter(r => r.type === 'CAPTION_FOR')).toHaveLength(0)
  })

  it('단일 프레임 스토리는 CONTINUES_FROM 없음', () => {
    const n1 = makeNode('sn-u1', { storyId: 's1', frameIndexInStory: 0 })
    n1.storyId = 's1'

    const relations = buildRelations([n1], [])
    expect(relations.filter(r => r.type === 'CONTINUES_FROM')).toHaveLength(0)
  })
})
