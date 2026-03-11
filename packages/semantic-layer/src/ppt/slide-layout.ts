/**
 * SlideLayout — 슬라이드 내 요소 배치.
 * SlideGroup의 노드를 PPT 좌표계로 배치.
 */

import type { SemanticNode } from '../types.js'
import type { SlideGroup, SlideType } from './slide-breaker.js'
import type { StyleCluster, PPTStyleSlot } from './ppt-style-mapper.js'

/** PPT 슬라이드 크기 (인치) */
export interface SlideSize {
  width: number   // 기본 10
  height: number  // 기본 7.5
}

/** 배치된 슬라이드 요소 */
export interface SlideElement {
  nodeId: string
  label: string
  /** PPT 좌표 (인치) */
  x: number
  y: number
  w: number
  h: number
  /** 텍스트 콘텐츠 */
  text: string
  /** 적용할 스타일 슬롯 */
  styleSlot: PPTStyleSlot
  /** 요소 타입 */
  elementType: 'text' | 'image' | 'table' | 'shape'
}

/** 레이아웃된 슬라이드 */
export interface LayoutedSlide {
  index: number
  type: SlideType
  title: string
  elements: SlideElement[]
}

/** 레이블 → 스타일 슬롯 매핑 */
const LABEL_TO_STYLE: Record<string, PPTStyleSlot> = {
  CHAPTER_TITLE: 'TITLE',
  SECTION_TITLE: 'TITLE',
  SUBSECTION_TITLE: 'HEADING1',
  BODY_TEXT: 'BODY',
  PROBLEM: 'BODY',
  SUB_PROBLEM: 'BODY',
  EXAMPLE: 'BODY',
  SOLUTION: 'BODY',
  ANSWER: 'BODY',
  CHOICES: 'LIST_ITEM',
  CAPTION: 'CAPTION',
  FORMULA: 'CODE',
  CONCEPT_BOX: 'EMPHASIS',
  TIP_BOX: 'EMPHASIS',
  SIDEBAR: 'BODY_SMALL',
  TABLE: 'BODY',
  FIGURE: 'BODY',
}

/** 여백 (인치) */
const MARGIN = { top: 0.8, bottom: 0.5, left: 0.7, right: 0.7 }

/**
 * 슬라이드 그룹을 PPT 레이아웃으로 변환.
 */
export function layoutSlides(
  slides: SlideGroup[],
  slideSize: SlideSize = { width: 10, height: 7.5 },
): LayoutedSlide[] {
  return slides.map(slide => ({
    index: slide.index,
    type: slide.type,
    title: slide.title,
    elements: layoutSlide(slide, slideSize),
  }))
}

function layoutSlide(slide: SlideGroup, size: SlideSize): SlideElement[] {
  switch (slide.type) {
    case 'TITLE_SLIDE': return layoutTitleSlide(slide, size)
    case 'PROBLEM_SLIDE': return layoutProblemSlide(slide, size)
    case 'FIGURE_SLIDE': return layoutFigureSlide(slide, size)
    default: return layoutContentSlide(slide, size)
  }
}

/** 타이틀 슬라이드: 중앙 배치 */
function layoutTitleSlide(slide: SlideGroup, size: SlideSize): SlideElement[] {
  const titleNode = slide.nodes[0]
  if (!titleNode) return []

  return [{
    nodeId: titleNode.id,
    label: titleNode.label,
    x: size.width * 0.1,
    y: size.height * 0.3,
    w: size.width * 0.8,
    h: size.height * 0.4,
    text: titleNode.features.textContent,
    styleSlot: 'TITLE',
    elementType: 'text',
  }]
}

/** 문제 슬라이드: 문제 위 + 보기 아래 */
function layoutProblemSlide(slide: SlideGroup, size: SlideSize): SlideElement[] {
  const elements: SlideElement[] = []
  const contentW = size.width - MARGIN.left - MARGIN.right
  let y = MARGIN.top

  const problem = slide.nodes.find(n => n.label === 'PROBLEM')
  const others = slide.nodes.filter(n => n.label !== 'PROBLEM')

  if (problem) {
    const h = estimateHeight(problem, contentW, size.height)
    elements.push({
      nodeId: problem.id,
      label: problem.label,
      x: MARGIN.left,
      y,
      w: contentW,
      h,
      text: problem.features.textContent,
      styleSlot: 'BODY',
      elementType: 'text',
    })
    y += h + 0.2
  }

  for (const node of others) {
    const h = estimateHeight(node, contentW, size.height)
    if (y + h > size.height - MARGIN.bottom) break

    elements.push({
      nodeId: node.id,
      label: node.label,
      x: MARGIN.left + (node.label === 'SUB_PROBLEM' ? 0.3 : 0),
      y,
      w: contentW - (node.label === 'SUB_PROBLEM' ? 0.3 : 0),
      h,
      text: node.features.textContent,
      styleSlot: LABEL_TO_STYLE[node.label] ?? 'BODY',
      elementType: 'text',
    })
    y += h + 0.15
  }

  return elements
}

/** 그림 슬라이드: 그림 위 + 캡션 아래 */
function layoutFigureSlide(slide: SlideGroup, size: SlideSize): SlideElement[] {
  const elements: SlideElement[] = []
  const contentW = size.width - MARGIN.left - MARGIN.right
  const figure = slide.nodes.find(n => n.label === 'FIGURE' || n.nodeType === 'FIGURE')
  const caption = slide.nodes.find(n => n.label === 'CAPTION')

  if (figure) {
    const figH = caption ? size.height * 0.6 : size.height * 0.7
    elements.push({
      nodeId: figure.id,
      label: figure.label,
      x: size.width * 0.15,
      y: MARGIN.top,
      w: size.width * 0.7,
      h: figH,
      text: '',
      styleSlot: 'BODY',
      elementType: 'image',
    })

    if (caption) {
      elements.push({
        nodeId: caption.id,
        label: caption.label,
        x: MARGIN.left,
        y: MARGIN.top + figH + 0.2,
        w: contentW,
        h: 0.5,
        text: caption.features.textContent,
        styleSlot: 'CAPTION',
        elementType: 'text',
      })
    }
  }

  return elements
}

/** 일반 콘텐츠 슬라이드: 위에서 아래로 순차 배치 */
function layoutContentSlide(slide: SlideGroup, size: SlideSize): SlideElement[] {
  const elements: SlideElement[] = []
  const contentW = size.width - MARGIN.left - MARGIN.right
  let y = MARGIN.top

  for (const node of slide.nodes) {
    const h = estimateHeight(node, contentW, size.height)
    if (y + h > size.height - MARGIN.bottom && elements.length > 0) break

    elements.push({
      nodeId: node.id,
      label: node.label,
      x: MARGIN.left,
      y,
      w: contentW,
      h,
      text: node.features.textContent,
      styleSlot: LABEL_TO_STYLE[node.label] ?? 'BODY',
      elementType: node.nodeType === 'TABLE' ? 'table' : 'text',
    })
    y += h + 0.15
  }

  return elements
}

/** 텍스트 길이 기반 높이 추정 (인치) */
function estimateHeight(node: SemanticNode, widthInch: number, maxHeight: number): number {
  const charsPerLine = Math.floor(widthInch * 7) // ~7 chars per inch at 12pt
  const lines = Math.max(1, Math.ceil(node.features.textLength / charsPerLine))
  const lineHeight = 0.25 // 인치
  return Math.min(lines * lineHeight, maxHeight * 0.4)
}
