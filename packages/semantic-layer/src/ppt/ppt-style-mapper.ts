/**
 * PPTStyleMapper — AST 스타일 1,000개+ → PPT 표준 슬롯 10개 클러스터링.
 *
 * 알고리즘:
 * 1. 시멘틱 레이블 기반 1차 매핑
 * 2. 폰트 크기 기반 2차 분류
 * 3. 속성 기반 3차 보정 (Bold → EMPHASIS, Monospace → CODE)
 * 4. 빈도 기반 대표값 선정
 */

import type { SemanticNode } from '../types.js'

/** PPT 표준 스타일 슬롯 */
export type PPTStyleSlot =
  | 'TITLE'
  | 'SUBTITLE'
  | 'HEADING1'
  | 'HEADING2'
  | 'BODY'
  | 'BODY_SMALL'
  | 'LIST_ITEM'
  | 'CAPTION'
  | 'CODE'
  | 'EMPHASIS'

export interface StyleCluster {
  slot: PPTStyleSlot
  pptFontSize: number       // pt
  pptFontFamily: string
  pptBold: boolean
  pptItalic: boolean
  pptColor: string           // hex
  pptAlignment: string
  /** 이 슬롯에 매핑된 원본 스타일명 목록 */
  sourceStyles: string[]
}

/** 시멘틱 레이블 → PPT 슬롯 1차 매핑 */
const LABEL_TO_SLOT: Record<string, PPTStyleSlot> = {
  CHAPTER_TITLE: 'TITLE',
  SECTION_TITLE: 'TITLE',
  SUBSECTION_TITLE: 'HEADING1',
  HEADING: 'HEADING2',
  BODY_TEXT: 'BODY',
  PROBLEM: 'BODY',
  SUB_PROBLEM: 'BODY',
  EXAMPLE: 'BODY',
  SOLUTION: 'BODY',
  ANSWER: 'BODY',
  CHOICES: 'LIST_ITEM',
  CAPTION: 'CAPTION',
  FORMULA: 'CODE',
  PAGE_HEADER: 'BODY_SMALL',
  PAGE_FOOTER: 'BODY_SMALL',
  SIDEBAR: 'BODY_SMALL',
  TIP_BOX: 'EMPHASIS',
  CONCEPT_BOX: 'EMPHASIS',
}

/** HWPUNIT → pt 변환 (100 hwpunit = 1pt) */
function hwpunitToPt(hwpunit: number): number {
  return Math.round(hwpunit / 100)
}

interface StyleEntry {
  slot: PPTStyleSlot
  fontSizePt: number
  fontFamily: string
  bold: boolean
  alignment: string
  styleName: string
  count: number
}

/**
 * 노드에서 PPT 스타일 클러스터를 생성.
 */
export function clusterStyles(nodes: SemanticNode[]): StyleCluster[] {
  const entries: StyleEntry[] = []

  for (const node of nodes) {
    if (node.label === 'BACKGROUND' || node.label === 'DECORATION') continue
    if (node.label === 'UNKNOWN' && node.features.textLength === 0) continue

    const slot = resolveSlot(node)
    const fontSizePt = hwpunitToPt(node.features.dominantFontSize)
    const styleName = node.features.dominantParagraphStyle ?? node.label

    entries.push({
      slot,
      fontSizePt: fontSizePt > 0 ? fontSizePt : 12,
      fontFamily: node.features.dominantFontFamily || '맑은 고딕',
      bold: node.features.hasBoldText,
      alignment: node.features.dominantAlignment ?? 'LeftAlign',
      styleName,
      count: 1,
    })
  }

  // 슬롯별 그룹화 → 대표값 선정
  const slotGroups = new Map<PPTStyleSlot, StyleEntry[]>()
  for (const entry of entries) {
    const group = slotGroups.get(entry.slot)
    if (group) group.push(entry)
    else slotGroups.set(entry.slot, [entry])
  }

  const clusters: StyleCluster[] = []

  for (const [slot, group] of slotGroups) {
    // 최빈 폰트 크기
    const fontSizes = group.map(e => e.fontSizePt)
    const repFontSize = mode(fontSizes) ?? 12

    // 최빈 폰트 패밀리
    const families = group.map(e => e.fontFamily)
    const repFamily = mode(families) ?? '맑은 고딕'

    // Bold 여부 (과반수)
    const boldCount = group.filter(e => e.bold).length
    const repBold = boldCount > group.length / 2

    // 정렬
    const aligns = group.map(e => e.alignment)
    const repAlign = mode(aligns) ?? 'LeftAlign'

    // 원본 스타일명 수집 (중복 제거)
    const sourceStyles = [...new Set(group.map(e => e.styleName))]

    clusters.push({
      slot,
      pptFontSize: clampFontSize(slot, repFontSize),
      pptFontFamily: mapToPptFont(repFamily),
      pptBold: repBold || isTitleSlot(slot),
      pptItalic: slot === 'CAPTION',
      pptColor: getSlotColor(slot),
      pptAlignment: mapAlignment(repAlign),
      sourceStyles,
    })
  }

  // 빠진 슬롯에 기본값 추가
  ensureDefaults(clusters)

  return clusters
}

/** 노드의 PPT 슬롯 결정 */
function resolveSlot(node: SemanticNode): PPTStyleSlot {
  // 1차: 레이블 기반
  const labelSlot = LABEL_TO_SLOT[node.label]
  if (labelSlot) return labelSlot

  // 2차: 폰트 크기 기반
  const fontPt = hwpunitToPt(node.features.dominantFontSize)
  if (fontPt >= 20) return 'HEADING1'
  if (fontPt >= 14) return 'BODY'
  if (fontPt < 10 && fontPt > 0) return 'BODY_SMALL'

  // 3차: 속성 보정
  if (node.features.hasEquation) return 'CODE'
  if (node.features.hasBoldText && fontPt >= 14) return 'EMPHASIS'

  return 'BODY'
}

/** 슬롯별 폰트 크기 범위 보정 */
function clampFontSize(slot: PPTStyleSlot, pt: number): number {
  const ranges: Record<PPTStyleSlot, [number, number]> = {
    TITLE: [24, 44],
    SUBTITLE: [18, 24],
    HEADING1: [20, 28],
    HEADING2: [16, 22],
    BODY: [10, 14],
    BODY_SMALL: [8, 10],
    LIST_ITEM: [10, 14],
    CAPTION: [8, 10],
    CODE: [10, 12],
    EMPHASIS: [10, 14],
  }
  const [min, max] = ranges[slot]
  return Math.max(min, Math.min(max, pt))
}

function isTitleSlot(slot: PPTStyleSlot): boolean {
  return slot === 'TITLE' || slot === 'HEADING1' || slot === 'HEADING2'
}

/** 한글 폰트 → PPT 호환 폰트 */
function mapToPptFont(family: string): string {
  if (!family) return '맑은 고딕'
  const lower = family.toLowerCase()
  if (lower.includes('고딕') || lower.includes('gothic')) return '맑은 고딕'
  if (lower.includes('바탕') || lower.includes('batang')) return '바탕'
  if (lower.includes('돋움') || lower.includes('dotum')) return '돋움'
  if (lower.includes('굴림') || lower.includes('gulim')) return '굴림'
  if (lower.includes('arial')) return 'Arial'
  if (lower.includes('times')) return 'Times New Roman'
  return '맑은 고딕'
}

/** 정렬 매핑 */
function mapAlignment(align: string): string {
  switch (align) {
    case 'CenterAlign':
    case 'CenterJustify': return 'center'
    case 'RightAlign':
    case 'RightJustify': return 'right'
    default: return 'left'
  }
}

/** 슬롯별 기본 색상 */
function getSlotColor(slot: PPTStyleSlot): string {
  switch (slot) {
    case 'TITLE': return '#333333'
    case 'SUBTITLE': return '#555555'
    case 'HEADING1': return '#333333'
    case 'HEADING2': return '#444444'
    case 'CAPTION': return '#666666'
    case 'BODY_SMALL': return '#666666'
    case 'EMPHASIS': return '#1565C0'
    default: return '#000000'
  }
}

/** 기본 슬롯 보장 */
function ensureDefaults(clusters: StyleCluster[]): void {
  const existing = new Set(clusters.map(c => c.slot))
  const defaults: StyleCluster[] = [
    { slot: 'TITLE', pptFontSize: 36, pptFontFamily: '맑은 고딕', pptBold: true, pptItalic: false, pptColor: '#333333', pptAlignment: 'center', sourceStyles: [] },
    { slot: 'BODY', pptFontSize: 12, pptFontFamily: '맑은 고딕', pptBold: false, pptItalic: false, pptColor: '#000000', pptAlignment: 'left', sourceStyles: [] },
  ]
  for (const d of defaults) {
    if (!existing.has(d.slot)) {
      clusters.push(d)
    }
  }
}

/** 배열 최빈값 */
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
