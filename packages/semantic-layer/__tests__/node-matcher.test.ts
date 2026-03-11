import { describe, it, expect } from 'vitest'
import { matchNodes, computeSymmetryScore, createFingerprint } from '../src/merge/node-matcher.js'
import type { SemanticNode } from '../src/types.js'

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
      textContent: '테스트 텍스트', textLength: 7, paragraphCount: 1,
      dominantFontSize: 1200, maxFontSize: 1200, dominantFontFamily: '바탕',
      hasBoldText: false, dominantAlignment: null,
      hasNumberPrefix: false, numberPrefixPattern: null, firstLineText: '',
      paragraphStyleNames: [], characterStyleNames: [],
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
    storyId: overrides.storyId ?? null,
    metadata: {},
  }
}

describe('matchNodes', () => {
  it('Stage 1: sourceId 매칭', () => {
    const old = [makeNode('sn-u001')]
    const newN = [makeNode('sn-u001')]

    const result = matchNodes(old, newN)
    expect(result.matched).toHaveLength(1)
    expect(result.matched[0]).toEqual(['sn-u001', 'sn-u001'])
    expect(result.unmatchedOld).toHaveLength(0)
    expect(result.unmatchedNew).toHaveLength(0)
  })

  it('Stage 2: storyId + frameIndex 매칭', () => {
    const old = [makeNode('sn-old', { storyId: 's1', frameIndexInStory: 0 })]
    old[0].storyId = 's1'
    const newN = [makeNode('sn-new', { storyId: 's1', frameIndexInStory: 0 })]
    newN[0].storyId = 's1'

    const result = matchNodes(old, newN)
    expect(result.matched).toHaveLength(1)
    expect(result.matched[0]).toEqual(['sn-old', 'sn-new'])
  })

  it('Stage 3: textFingerprint 매칭', () => {
    const text = '1. 다음 이차방정식을 풀어라.'
    const old = [makeNode('sn-old1', { textContent: text })]
    const newN = [makeNode('sn-new1', { textContent: text })]

    const result = matchNodes(old, newN)
    expect(result.matched).toHaveLength(1)
  })

  it('추가/삭제 노드 식별', () => {
    const old = [
      makeNode('sn-u001', { textContent: 'AAA' }),
      makeNode('sn-u002', { textContent: 'BBB' }),
    ]
    const newN = [
      makeNode('sn-u001', { textContent: 'AAA' }),
      makeNode('sn-u003', { textContent: 'CCC' }),
    ]

    const result = matchNodes(old, newN)
    expect(result.matched).toHaveLength(1)
    expect(result.unmatchedOld).toEqual(['sn-u002'])
    expect(result.unmatchedNew).toEqual(['sn-u003'])
  })

  it('Stage 4: Symmetry Match', () => {
    // sourceId 다르고, storyId 없고, 텍스트가 약간 다르지만 유사
    const old = [makeNode('sn-old', {
      textContent: '1. 다음 방정식을 풀어라. (1)',
      dominantFontSize: 1200,
      dominantFontFamily: '바탕',
      dominantParagraphStyle: '본문',
      hasBoldText: false,
      pageNumber: 1,
      regionTag: 'MIDDLE',
      relativeYInPage: 0.5,
    })]
    const newN = [makeNode('sn-new', {
      textContent: '1. 다음 방정식을 풀어라. (2)',
      dominantFontSize: 1200,
      dominantFontFamily: '바탕',
      dominantParagraphStyle: '본문',
      hasBoldText: false,
      pageNumber: 1,
      regionTag: 'MIDDLE',
      relativeYInPage: 0.5,
    })]

    const result = matchNodes(old, newN, 0.5)
    expect(result.matched).toHaveLength(1)
    expect(result.symmetryMatched).toHaveLength(1)
    expect(result.symmetryMatched[0][2]).toBeGreaterThan(0.5)
  })
})

describe('computeSymmetryScore', () => {
  it('동일 노드 = 1.0', () => {
    const node = makeNode('sn-test', {
      textContent: '테스트',
      dominantFontSize: 1200,
      dominantFontFamily: '바탕',
      dominantParagraphStyle: '본문',
      pageNumber: 1,
      regionTag: 'MIDDLE',
      relativeYInPage: 0.5,
    })
    expect(computeSymmetryScore(node, node)).toBeCloseTo(1.0, 1)
  })

  it('완전히 다른 노드 = 낮은 점수', () => {
    const a = makeNode('sn-a', {
      textContent: '가나다라마바사',
      dominantFontSize: 800,
      dominantFontFamily: '고딕',
      dominantParagraphStyle: '제목',
      pageNumber: 1,
      regionTag: 'TOP',
      relativeYInPage: 0.1,
    })
    const b = makeNode('sn-b', {
      textContent: 'ABCDEFGHIJKLMN',
      dominantFontSize: 1600,
      dominantFontFamily: '바탕',
      dominantParagraphStyle: '본문',
      pageNumber: 3,
      regionTag: 'BOTTOM',
      relativeYInPage: 0.9,
    })
    expect(computeSymmetryScore(a, b)).toBeLessThan(0.3)
  })
})

describe('createFingerprint', () => {
  it('fingerprint 구조', () => {
    const node = makeNode('sn-u001', {
      storyId: 's1',
      frameIndexInStory: 2,
      textContent: '테스트 텍스트',
      pageNumber: 3,
    })
    node.storyId = 's1'

    const fp = createFingerprint(node)
    expect(fp.sourceId).toBe('u001')
    expect(fp.storyId).toBe('s1')
    expect(fp.frameIndexInStory).toBe(2)
    expect(fp.pageNumber).toBe(3)
    expect(fp.textFingerprint.length).toBeGreaterThan(0)
  })
})
