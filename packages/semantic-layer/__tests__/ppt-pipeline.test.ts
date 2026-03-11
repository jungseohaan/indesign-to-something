import { describe, it, expect } from 'vitest'
import { clusterStyles } from '../src/ppt/ppt-style-mapper.js'
import { breakIntoSlides } from '../src/ppt/slide-breaker.js'
import { layoutSlides } from '../src/ppt/slide-layout.js'
import { matchTemplate, BUILT_IN_TEMPLATES } from '../src/ppt/ppt-template.js'
import type { SemanticNode, SemanticRelation } from '../src/types.js'

function makeNode(
  id: string,
  label: string,
  overrides: Record<string, any> = {},
): SemanticNode {
  return {
    id,
    astPath: '',
    nodeType: overrides.nodeType ?? 'FRAME',
    features: {
      pageNumber: 1, x: 0, y: overrides.y ?? 0, width: 50000, height: 5000,
      zOrder: 1, regionTag: 'MIDDLE', columnIndex: 0, relativeYInPage: 0.5,
      storyId: null, storyFrameCount: 0, storyPageSpan: 0,
      frameIndexInStory: 0, isStoryStart: true, isStoryEnd: true,
      textContent: overrides.textContent ?? `${label} 내용`,
      textLength: overrides.textLength ?? 10,
      paragraphCount: 1,
      dominantFontSize: overrides.dominantFontSize ?? 1200,
      maxFontSize: overrides.maxFontSize ?? 1200,
      dominantFontFamily: overrides.dominantFontFamily ?? '바탕',
      hasBoldText: overrides.hasBoldText ?? false,
      dominantAlignment: overrides.dominantAlignment ?? 'LeftAlign',
      hasNumberPrefix: false, numberPrefixPattern: null,
      firstLineText: overrides.firstLineText ?? `${label} 내용`,
      paragraphStyleNames: [], characterStyleNames: [],
      dominantParagraphStyle: overrides.dominantParagraphStyle ?? '본문',
      hasFill: false, fillColor: null, hasStroke: false,
      isBackgroundOnly: false, columnCount: 1, rotationAngle: 0,
      hasTable: false, hasImage: false,
      hasEquation: overrides.hasEquation ?? false,
      hasInlineFrame: false, inlineObjectCount: 0,
      blockType: overrides.blockType ?? 'TEXT_FRAME',
      spatial: {
        nearestContentNodeId: null, nearestContentDistance: -1,
        overlappingNodeIds: [], isVisuallyContainedBy: null, visualContainmentRatio: 0,
      },
      ...overrides,
    },
    label,
    confidence: 0.9,
    appliedRule: null,
    manualOverride: false,
    children: [],
    storyId: null,
    metadata: {},
  }
}

describe('clusterStyles', () => {
  it('시멘틱 레이블 기반 슬롯 매핑', () => {
    const nodes = [
      makeNode('n1', 'SECTION_TITLE', { dominantFontSize: 2000, hasBoldText: true }),
      makeNode('n2', 'BODY_TEXT', { dominantFontSize: 1200 }),
      makeNode('n3', 'CHOICES', { dominantFontSize: 1100 }),
      makeNode('n4', 'CAPTION', { dominantFontSize: 900 }),
    ]

    const clusters = clusterStyles(nodes)
    const slotNames = clusters.map(c => c.slot)
    expect(slotNames).toContain('TITLE')
    expect(slotNames).toContain('BODY')
    expect(slotNames).toContain('LIST_ITEM')
    expect(slotNames).toContain('CAPTION')
  })

  it('BACKGROUND/DECORATION 무시', () => {
    const nodes = [
      makeNode('n1', 'BACKGROUND', { textLength: 0 }),
      makeNode('n2', 'BODY_TEXT'),
    ]
    const clusters = clusterStyles(nodes)
    // BACKGROUND용 슬롯이 별도로 생기지 않아야 함
    const bodyCluster = clusters.find(c => c.slot === 'BODY')
    expect(bodyCluster).toBeDefined()
  })

  it('기본 TITLE/BODY 슬롯 보장', () => {
    const clusters = clusterStyles([])
    const slotNames = clusters.map(c => c.slot)
    expect(slotNames).toContain('TITLE')
    expect(slotNames).toContain('BODY')
  })

  it('한글 폰트 매핑', () => {
    const nodes = [
      makeNode('n1', 'BODY_TEXT', { dominantFontFamily: '바탕' }),
    ]
    const clusters = clusterStyles(nodes)
    const body = clusters.find(c => c.slot === 'BODY')
    expect(body?.pptFontFamily).toBe('바탕')
  })

  it('수식 노드 → CODE 슬롯', () => {
    const nodes = [
      makeNode('n1', 'FORMULA', { hasEquation: true }),
    ]
    const clusters = clusterStyles(nodes)
    const code = clusters.find(c => c.slot === 'CODE')
    expect(code).toBeDefined()
  })
})

describe('breakIntoSlides', () => {
  it('SECTION_TITLE에서 새 슬라이드 시작', () => {
    const nodes = [
      makeNode('n1', 'SECTION_TITLE', { y: 0 }),
      makeNode('n2', 'BODY_TEXT', { y: 5000 }),
      makeNode('n3', 'SECTION_TITLE', { y: 10000 }),
    ]
    const slides = breakIntoSlides(nodes, [])
    expect(slides.length).toBeGreaterThanOrEqual(2)
    expect(slides[0].type).toBe('TITLE_SLIDE')
  })

  it('PROBLEM + 하위 노드 그루핑', () => {
    const nodes = [
      makeNode('n1', 'PROBLEM', { y: 0, textContent: '1. 다음을 풀어라.' }),
      makeNode('n2', 'CHOICES', { y: 5000, textContent: '① A ② B ③ C' }),
    ]
    const relations: SemanticRelation[] = [{
      type: 'PARENT_OF', sourceId: 'n1', targetId: 'n2',
    }]

    const slides = breakIntoSlides(nodes, relations)
    const problemSlide = slides.find(s => s.type === 'PROBLEM_SLIDE')
    expect(problemSlide).toBeDefined()
    expect(problemSlide!.nodes.length).toBeGreaterThanOrEqual(1)
  })

  it('BACKGROUND/UNKNOWN 제외', () => {
    const nodes = [
      makeNode('n1', 'BACKGROUND', { textLength: 0 }),
      makeNode('n2', 'UNKNOWN', { textLength: 0 }),
      makeNode('n3', 'BODY_TEXT', { y: 0 }),
    ]
    const slides = breakIntoSlides(nodes, [])
    // BACKGROUND와 UNKNOWN은 포함되지 않음
    const allNodeIds = slides.flatMap(s => s.nodes.map(n => n.id))
    expect(allNodeIds).not.toContain('n1')
    expect(allNodeIds).not.toContain('n2')
  })

  it('FIGURE + CAPTION 묶음', () => {
    const nodes = [
      makeNode('n1', 'FIGURE', { y: 0, nodeType: 'FIGURE' }),
      makeNode('n2', 'CAPTION', { y: 5000 }),
    ]
    const relations: SemanticRelation[] = [{
      type: 'CAPTION_FOR', sourceId: 'n2', targetId: 'n1',
    }]

    const slides = breakIntoSlides(nodes, relations)
    const figSlide = slides.find(s => s.type === 'FIGURE_SLIDE')
    expect(figSlide).toBeDefined()
    expect(figSlide!.nodes).toHaveLength(2)
  })
})

describe('layoutSlides', () => {
  it('슬라이드 레이아웃 생성', () => {
    const nodes = [
      makeNode('n1', 'SECTION_TITLE', { y: 0, textContent: 'Ⅱ. 방정식' }),
      makeNode('n2', 'BODY_TEXT', { y: 5000, textContent: '본문 내용' }),
    ]
    const slides = breakIntoSlides(nodes, [])
    const layouted = layoutSlides(slides)

    expect(layouted.length).toBeGreaterThan(0)
    for (const slide of layouted) {
      expect(slide.elements.length).toBeGreaterThan(0)
      for (const el of slide.elements) {
        expect(el.x).toBeGreaterThanOrEqual(0)
        expect(el.w).toBeGreaterThan(0)
      }
    }
  })

  it('타이틀 슬라이드는 중앙 배치', () => {
    const nodes = [makeNode('n1', 'SECTION_TITLE', { y: 0 })]
    const slides = breakIntoSlides(nodes, [])
    const layouted = layoutSlides(slides, { width: 10, height: 7.5 })

    const titleSlide = layouted.find(s => s.type === 'TITLE_SLIDE')
    expect(titleSlide).toBeDefined()
    const el = titleSlide!.elements[0]
    expect(el.x).toBeCloseTo(1, 0) // 10%
    expect(el.styleSlot).toBe('TITLE')
  })
})

describe('matchTemplate', () => {
  it('TITLE_SLIDE 매칭', () => {
    const t = matchTemplate('TITLE_SLIDE', ['SECTION_TITLE'])
    expect(t.templateId).toBe('title-only')
  })

  it('PROBLEM + CHOICES 매칭', () => {
    const t = matchTemplate('PROBLEM_SLIDE', ['PROBLEM', 'CHOICES'])
    expect(t.templateId).toBe('problem-with-choices')
  })

  it('매칭 없으면 content-basic 폴백', () => {
    const t = matchTemplate('CONTENT_SLIDE', ['RANDOM_LABEL'])
    expect(t.templateId).toBe('content-basic')
  })

  it('내장 템플릿 수', () => {
    expect(BUILT_IN_TEMPLATES.length).toBeGreaterThanOrEqual(5)
  })
})
