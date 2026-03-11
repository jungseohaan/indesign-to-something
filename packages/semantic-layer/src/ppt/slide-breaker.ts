/**
 * SlideBreaker — 시멘틱 노드를 슬라이드 단위로 분할.
 *
 * 분할 규칙:
 * - SECTION_TITLE, CHAPTER_TITLE → 새 슬라이드 시작 (표지)
 * - PROBLEM + SUB_PROBLEM + CHOICES → 1 슬라이드로 그루핑
 * - CONCEPT_BOX, FORMULA → 독립 슬라이드
 * - FIGURE + CAPTION → 한 슬라이드에 묶음
 * - 콘텐츠 초과 시 자동 분할
 */

import type { SemanticNode, SemanticRelation } from '../types.js'

export interface SlideGroup {
  /** 슬라이드 인덱스 (0-based) */
  index: number
  /** 슬라이드 타입 */
  type: SlideType
  /** 포함된 노드 */
  nodes: SemanticNode[]
  /** 대표 제목 */
  title: string
}

export type SlideType =
  | 'TITLE_SLIDE'     // 단원/섹션 제목
  | 'CONTENT_SLIDE'   // 본문 콘텐츠
  | 'PROBLEM_SLIDE'   // 문제 + 보기
  | 'FIGURE_SLIDE'    // 그림 + 캡션
  | 'TABLE_SLIDE'     // 표
  | 'CONCEPT_SLIDE'   // 개념 박스

/** 슬라이드 시작을 유발하는 레이블 */
const SLIDE_BREAK_LABELS = new Set([
  'CHAPTER_TITLE',
  'SECTION_TITLE',
  'SUBSECTION_TITLE',
])

/** 독립 슬라이드로 분리하는 레이블 */
const STANDALONE_LABELS = new Set([
  'CONCEPT_BOX',
  'TABLE',
])

/** PROBLEM 그룹에 포함되는 레이블 */
const PROBLEM_GROUP_LABELS = new Set([
  'PROBLEM',
  'SUB_PROBLEM',
  'CHOICES',
  'SOLUTION',
  'ANSWER',
])

/** 최대 슬라이드당 텍스트 길이 (초과 시 분할) */
const MAX_SLIDE_TEXT_LENGTH = 800

/**
 * 시멘틱 노드를 슬라이드 그룹으로 분할.
 */
export function breakIntoSlides(
  nodes: SemanticNode[],
  relations: SemanticRelation[],
): SlideGroup[] {
  const slides: SlideGroup[] = []

  // BACKGROUND, DECORATION, PAGE_HEADER, PAGE_FOOTER 제외
  const contentNodes = nodes.filter(n =>
    !['BACKGROUND', 'DECORATION', 'PAGE_HEADER', 'PAGE_FOOTER', 'UNKNOWN'].includes(n.label)
    && n.features.textLength > 0
  )

  // 페이지 순서로 정렬
  contentNodes.sort((a, b) => {
    if (a.features.pageNumber !== b.features.pageNumber) {
      return a.features.pageNumber - b.features.pageNumber
    }
    return a.features.y - b.features.y
  })

  // 관계에서 PARENT_OF 매핑 빌드
  const childToParent = new Map<string, string>()
  for (const r of relations) {
    if (r.type === 'PARENT_OF') {
      childToParent.set(r.targetId, r.sourceId)
    }
  }

  let currentSlide: SemanticNode[] = []
  let currentType: SlideType = 'CONTENT_SLIDE'
  let currentTextLen = 0
  const processedIds = new Set<string>()

  function flushSlide() {
    if (currentSlide.length === 0) return
    slides.push({
      index: slides.length,
      type: currentType,
      nodes: [...currentSlide],
      title: extractTitle(currentSlide),
    })
    currentSlide = []
    currentType = 'CONTENT_SLIDE'
    currentTextLen = 0
  }

  for (const node of contentNodes) {
    if (processedIds.has(node.id)) continue

    // 이미 부모에 포함된 자식은 건너뜀
    if (childToParent.has(node.id)) continue

    // 타이틀 슬라이드
    if (SLIDE_BREAK_LABELS.has(node.label)) {
      flushSlide()
      currentType = 'TITLE_SLIDE'
      currentSlide.push(node)
      processedIds.add(node.id)
      flushSlide()
      continue
    }

    // 독립 슬라이드
    if (STANDALONE_LABELS.has(node.label)) {
      flushSlide()
      currentType = node.label === 'TABLE' ? 'TABLE_SLIDE' : 'CONCEPT_SLIDE'
      currentSlide.push(node)
      processedIds.add(node.id)
      // CAPTION 연결 확인
      addRelatedCaptions(node, contentNodes, relations, currentSlide, processedIds)
      flushSlide()
      continue
    }

    // FIGURE 슬라이드
    if (node.label === 'FIGURE' || node.nodeType === 'FIGURE') {
      flushSlide()
      currentType = 'FIGURE_SLIDE'
      currentSlide.push(node)
      processedIds.add(node.id)
      addRelatedCaptions(node, contentNodes, relations, currentSlide, processedIds)
      flushSlide()
      continue
    }

    // PROBLEM 그룹
    if (node.label === 'PROBLEM') {
      flushSlide()
      currentType = 'PROBLEM_SLIDE'
      currentSlide.push(node)
      processedIds.add(node.id)
      // 하위 노드 수집 (SUB_PROBLEM, CHOICES, SOLUTION, ANSWER)
      collectProblemGroup(node, contentNodes, relations, currentSlide, processedIds)
      flushSlide()
      continue
    }

    // 일반 콘텐츠 — 텍스트 길이 초과 시 분할
    if (currentTextLen + node.features.textLength > MAX_SLIDE_TEXT_LENGTH && currentSlide.length > 0) {
      flushSlide()
    }

    currentSlide.push(node)
    processedIds.add(node.id)
    currentTextLen += node.features.textLength
  }

  flushSlide()
  return slides
}

/** PROBLEM의 하위 노드 수집 */
function collectProblemGroup(
  problem: SemanticNode,
  allNodes: SemanticNode[],
  relations: SemanticRelation[],
  slide: SemanticNode[],
  processed: Set<string>,
): void {
  // PARENT_OF 관계로 자식 찾기
  const childIds = new Set<string>()
  for (const r of relations) {
    if (r.type === 'PARENT_OF' && r.sourceId === problem.id) {
      childIds.add(r.targetId)
    }
  }

  // 같은 페이지에서 문제 뒤에 나오는 관련 노드
  for (const node of allNodes) {
    if (processed.has(node.id)) continue
    if (!PROBLEM_GROUP_LABELS.has(node.label)) continue
    if (childIds.has(node.id) ||
        (node.features.pageNumber === problem.features.pageNumber &&
         node.features.y > problem.features.y)) {
      slide.push(node)
      processed.add(node.id)
    }
  }
}

/** 관련 CAPTION 추가 */
function addRelatedCaptions(
  target: SemanticNode,
  allNodes: SemanticNode[],
  relations: SemanticRelation[],
  slide: SemanticNode[],
  processed: Set<string>,
): void {
  for (const r of relations) {
    if (r.type === 'CAPTION_FOR' && r.targetId === target.id) {
      const caption = allNodes.find(n => n.id === r.sourceId)
      if (caption && !processed.has(caption.id)) {
        slide.push(caption)
        processed.add(caption.id)
      }
    }
  }
}

/** 슬라이드의 대표 제목 추출 */
function extractTitle(nodes: SemanticNode[]): string {
  const titleNode = nodes.find(n =>
    ['CHAPTER_TITLE', 'SECTION_TITLE', 'SUBSECTION_TITLE'].includes(n.label)
  )
  if (titleNode) return titleNode.features.firstLineText || titleNode.features.textContent.slice(0, 50)

  const first = nodes[0]
  if (!first) return ''
  return first.features.firstLineText || first.label
}
