/**
 * FeatureExtractor — ASTAdapter를 통해 구조적 특징을 추출하고 SemanticNode[]를 생성.
 */

import type { ASTAdapter } from '../adapter/ast-adapter.js'
import type { BlockInfo, PageInfo, ParagraphInfo, InlineItemInfo } from '../adapter/types.js'
import type {
  SemanticNode,
  StructuralFeatures,
  SpatialProximityFeatures,
  RegionTag,
  NodeType,
} from '../types.js'

/** FeatureExtractor 메인 함수 */
export function extractFeatures(adapter: ASTAdapter): SemanticNode[] {
  const pages = adapter.getPages()
  const stories = adapter.getStories()
  const storyMap = new Map(stories.map(s => [s.storyId, s]))
  const nodes: SemanticNode[] = []

  // 페이지별 블록 → 노드 변환
  const allBlocksByPage = new Map<number, BlockInfo[]>()

  for (const page of pages) {
    const blocks = adapter.getBlocks(page.pageNumber)
    allBlocksByPage.set(page.pageNumber, blocks)

    for (let i = 0; i < blocks.length; i++) {
      const block = blocks[i]
      const paragraphs = adapter.getParagraphs(block.id)
      const story = block.storyId ? storyMap.get(block.storyId) ?? null : null

      // 스토리 내 프레임 순서
      let frameIndex = 0
      if (story) {
        const idx = story.linkedFrameIds.indexOf(block.id)
        if (idx >= 0) frameIndex = idx
      }

      const features = buildFeatures(
        block, page, paragraphs, story, frameIndex, adapter,
      )

      const nodeType = mapNodeType(block.blockType)
      const node: SemanticNode = {
        id: `sn-${block.id}`,
        astPath: `sections[${pages.indexOf(page)}].blocks[${i}]`,
        nodeType,
        features,
        label: 'UNKNOWN',
        confidence: 0,
        appliedRule: null,
        manualOverride: false,
        children: [],
        storyId: block.storyId,
        metadata: {},
      }

      nodes.push(node)
    }
  }

  // 공간 근접도 계산 (전체 블록 필요)
  for (const node of nodes) {
    const pageBlocks = allBlocksByPage.get(node.features.pageNumber) ?? []
    const page = pages.find(p => p.pageNumber === node.features.pageNumber)
    if (page) {
      node.features.spatial = computeSpatialProximity(node.features, pageBlocks, node.id)
    }
  }

  return nodes
}

// ─── Features 빌드 ────────────────────────────────────────

function buildFeatures(
  block: BlockInfo,
  page: PageInfo,
  paragraphs: ParagraphInfo[],
  story: { storyId: string; linkedFrameIds: string[]; pages: number[]; paragraphCount: number } | null,
  frameIndex: number,
  adapter: ASTAdapter,
): StructuralFeatures {
  // 텍스트 수집
  const allItems = paragraphs.flatMap(p => p.items)
  const textRuns = allItems.filter(i => i.itemType === 'TEXT_RUN')
  const fullText = textRuns.map(r => r.text ?? '').join('')
  const firstLineText = getFirstLineText(paragraphs)

  // 폰트 분석
  const fontSizes = textRuns.filter(r => r.fontSize != null).map(r => r.fontSize!)
  const fontFamilies = textRuns.filter(r => r.fontFamily != null).map(r => r.fontFamily!)

  // 스타일 수집
  const paraStyleRefs = paragraphs
    .map(p => p.paragraphStyleRef)
    .filter((r): r is string => r != null)
  const paraStyleNames = paraStyleRefs.map(ref => {
    const style = adapter.getStyleByRef(ref)
    return style?.styleName ?? ref
  })
  const charStyleRefs = textRuns
    .map(r => r.characterStyleRef)
    .filter((r): r is string => r != null)
  const charStyleNames = [...new Set(charStyleRefs.map(ref => {
    const style = adapter.getStyleByRef(ref)
    return style?.styleName ?? ref
  }))]

  // 번호 접두사 판별
  const { hasPrefix, pattern } = detectNumberPrefix(firstLineText)

  // 정렬 분석
  const alignments = paragraphs.map(p => p.alignment).filter((a): a is string => a != null)

  return {
    // A. 위치 & 레이아웃
    pageNumber: page.pageNumber,
    x: block.x,
    y: block.y,
    width: block.width,
    height: block.height,
    zOrder: block.zOrder,
    regionTag: computeRegion(block, page),
    columnIndex: computeColumnIndex(block, page),
    relativeYInPage: page.height > 0 ? block.y / page.height : 0,

    // B. 스토리
    storyId: block.storyId,
    storyFrameCount: story?.linkedFrameIds.length ?? 0,
    storyPageSpan: story?.pages.length ?? 0,
    frameIndexInStory: frameIndex,
    isStoryStart: frameIndex === 0,
    isStoryEnd: story ? frameIndex === story.linkedFrameIds.length - 1 : true,

    // C. 텍스트 속성
    textContent: fullText,
    textLength: fullText.length,
    paragraphCount: paragraphs.length,
    dominantFontSize: mode(fontSizes) ?? 0,
    maxFontSize: fontSizes.length > 0 ? Math.max(...fontSizes) : 0,
    dominantFontFamily: mode(fontFamilies) ?? '',
    hasBoldText: textRuns.some(r => r.bold === true),
    dominantAlignment: mode(alignments) ?? null,
    hasNumberPrefix: hasPrefix,
    numberPrefixPattern: pattern,
    firstLineText,

    // D. 스타일
    paragraphStyleNames: [...new Set(paraStyleNames)],
    characterStyleNames: charStyleNames,
    dominantParagraphStyle: mode(paraStyleNames) ?? null,

    // E. 프레임 속성
    hasFill: block.hasFill,
    fillColor: block.fillColor,
    hasStroke: block.hasStroke,
    isBackgroundOnly: block.isBackgroundOnly,
    columnCount: block.columnCount,
    rotationAngle: block.rotation,

    // F. 콘텐츠 구성
    hasTable: block.blockType === 'TABLE',
    hasImage: block.blockType === 'FIGURE' || allItems.some(i => i.objectKind === 'IMAGE'),
    hasEquation: allItems.some(i => i.itemType === 'EQUATION'),
    hasInlineFrame: allItems.some(i => i.objectKind === 'INLINE_TEXT_FRAME'),
    inlineObjectCount: allItems.filter(i => i.itemType === 'INLINE_OBJECT').length,
    blockType: block.blockType,

    // G. 공간 근접도 (나중에 채움)
    spatial: {
      nearestContentNodeId: null,
      nearestContentDistance: Infinity,
      overlappingNodeIds: [],
      isVisuallyContainedBy: null,
      visualContainmentRatio: 0,
    },
  }
}

// ─── 공간 근접도 ──────────────────────────────────────────

function computeSpatialProximity(
  features: StructuralFeatures,
  allBlocks: BlockInfo[],
  selfNodeId: string,
): SpatialProximityFeatures {
  const selfId = selfNodeId.replace('sn-', '')
  const candidates = allBlocks.filter(b => b.id !== selfId)

  // AABB 겹침
  const overlapping = candidates.filter(c => aabbOverlap(features, c))

  // 시각적 포함
  let containerId: string | null = null
  let containmentRatio = 0
  const selfArea = features.width * features.height
  if (selfArea > 0) {
    for (const c of overlapping) {
      const overlapArea = computeOverlapArea(features, c)
      const ratio = overlapArea / selfArea
      if (ratio >= 0.8 && c.width * c.height > selfArea && ratio > containmentRatio) {
        containerId = c.id
        containmentRatio = ratio
      }
    }
  }

  // 최근접 CONTENT 노드
  const contentNodes = candidates.filter(c => !c.isBackgroundOnly)
  let nearestId: string | null = null
  let minDist = Infinity
  for (const c of contentNodes) {
    const dist = edgeToEdgeDistance(features, c)
    if (dist < minDist) {
      minDist = dist
      nearestId = c.id
    }
  }

  return {
    nearestContentNodeId: nearestId,
    nearestContentDistance: minDist === Infinity ? -1 : minDist,
    overlappingNodeIds: overlapping.map(n => n.id),
    isVisuallyContainedBy: containerId,
    visualContainmentRatio: containmentRatio,
  }
}

// ─── 기하 유틸 ────────────────────────────────────────────

interface Rect { x: number; y: number; width: number; height: number }

function aabbOverlap(a: Rect, b: Rect): boolean {
  return a.x < b.x + b.width
    && a.x + a.width > b.x
    && a.y < b.y + b.height
    && a.y + a.height > b.y
}

function computeOverlapArea(a: Rect, b: Rect): number {
  const overlapX = Math.max(0, Math.min(a.x + a.width, b.x + b.width) - Math.max(a.x, b.x))
  const overlapY = Math.max(0, Math.min(a.y + a.height, b.y + b.height) - Math.max(a.y, b.y))
  return overlapX * overlapY
}

function edgeToEdgeDistance(a: Rect, b: Rect): number {
  const dx = Math.max(0, Math.max(a.x, b.x) - Math.min(a.x + a.width, b.x + b.width))
  const dy = Math.max(0, Math.max(a.y, b.y) - Math.min(a.y + a.height, b.y + b.height))
  return Math.sqrt(dx * dx + dy * dy)
}

// ─── 영역 판별 ────────────────────────────────────────────

function computeRegion(block: BlockInfo, page: PageInfo): RegionTag {
  const contentHeight = page.height - page.marginTop - page.marginBottom
  const relY = (block.y - page.marginTop) / (contentHeight || 1)
  const relWidth = block.width / (page.width || 1)

  if (relWidth > 0.85) return 'FULL_WIDTH'
  if (relY < 0.15) return 'TOP'
  if (relY > 0.85) return 'BOTTOM'

  // 좌우 단 판별
  if (page.columnCount >= 2) {
    const contentWidth = page.width - page.marginLeft - page.marginRight
    const midX = page.marginLeft + contentWidth / 2
    const blockCenterX = block.x + block.width / 2
    if (blockCenterX < midX - contentWidth * 0.1) return 'LEFT'
    if (blockCenterX > midX + contentWidth * 0.1) return 'RIGHT'
  }

  return 'MIDDLE'
}

function computeColumnIndex(block: BlockInfo, page: PageInfo): number {
  if (page.columnCount <= 1) return 0
  const contentWidth = page.width - page.marginLeft - page.marginRight
  const colWidth = (contentWidth - (page.columnCount - 1) * page.columnGutter) / page.columnCount
  if (colWidth <= 0) return 0
  const relX = block.x - page.marginLeft
  return Math.min(Math.floor(relX / (colWidth + page.columnGutter)), page.columnCount - 1)
}

// ─── 텍스트 분석 ──────────────────────────────────────────

function getFirstLineText(paragraphs: ParagraphInfo[]): string {
  if (paragraphs.length === 0) return ''
  const items = paragraphs[0].items
  const texts: string[] = []
  for (const item of items) {
    if (item.itemType === 'TEXT_RUN' && item.text) {
      const newlineIdx = item.text.indexOf('\n')
      if (newlineIdx >= 0) {
        texts.push(item.text.slice(0, newlineIdx))
        break
      }
      texts.push(item.text)
    }
    if (item.itemType === 'BREAK') break
  }
  return texts.join('').slice(0, 200)
}

const NUMBER_PATTERNS: Array<{ pattern: RegExp; name: string }> = [
  { pattern: /^\d+\.\s/, name: 'arabic_dot' },
  { pattern: /^\d+\s/, name: 'arabic_bare' },
  { pattern: /^[①②③④⑤⑥⑦⑧⑨⑩]/, name: 'circled' },
  { pattern: /^\([가나다라마바사아자차카타파하]\)/, name: 'parenthesized_korean' },
  { pattern: /^\(\d+\)/, name: 'parenthesized_arabic' },
  { pattern: /^[ⅰⅱⅲⅳⅴⅵⅶⅷⅸⅹ][\.\)]/i, name: 'roman' },
  { pattern: /^[가나다라마바사]\.\s/, name: 'korean_dot' },
  { pattern: /^[㉠㉡㉢㉣㉤㉥㉦㉧㉨㉩]/, name: 'circled_korean' },
]

function detectNumberPrefix(text: string): { hasPrefix: boolean; pattern: string | null } {
  const trimmed = text.trimStart()
  for (const { pattern, name } of NUMBER_PATTERNS) {
    if (pattern.test(trimmed)) {
      return { hasPrefix: true, pattern: name }
    }
  }
  return { hasPrefix: false, pattern: null }
}

// ─── 유틸 ─────────────────────────────────────────────────

function mapNodeType(blockType: string): NodeType {
  switch (blockType) {
    case 'TEXT_FRAME': return 'FRAME'
    case 'TABLE': return 'TABLE'
    case 'FIGURE': return 'FIGURE'
    default: return 'FRAME'
  }
}

/** 배열에서 최빈값 */
function mode<T>(arr: T[]): T | undefined {
  if (arr.length === 0) return undefined
  const counts = new Map<T, number>()
  let maxCount = 0
  let maxVal: T = arr[0]
  for (const v of arr) {
    const c = (counts.get(v) ?? 0) + 1
    counts.set(v, c)
    if (c > maxCount) {
      maxCount = c
      maxVal = v
    }
  }
  return maxVal
}
