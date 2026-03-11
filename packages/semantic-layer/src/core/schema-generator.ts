/**
 * SchemaGenerator — AST 노드 features를 통계 분석하여 스키마를 자동 생성.
 *
 * 접근법:
 * 1. 노드를 blockType / regionTag / 스타일(paragraphStyle + fontSize) 기준으로 그룹화
 * 2. 각 그룹의 특징을 분석하여 레이블 + 분류 규칙 생성
 * 3. 공간 근접도 기반 관계 규칙 추론
 */

import type {
  SemanticNode,
  SemanticSchema,
  LabelDef,
  ClassificationRule,
  Condition,
  RelationRule,
  LayoutHint,
  RegionTag,
} from '../types.js'

export interface SchemaGeneratorOptions {
  /** 스키마 ID (기본: "auto-generated") */
  schemaId?: string
  /** 스키마 이름 */
  schemaName?: string
  /** 과목 */
  subject?: string
  /** 문서 유형 */
  documentType?: string
  /** 최소 그룹 크기 (이보다 작은 그룹은 무시) */
  minGroupSize?: number
}

/** 내부 그룹 분석 결과 */
interface NodeGroup {
  key: string
  nodes: SemanticNode[]
  label: string
  labelName: string
  description: string
  category: 'content' | 'structure' | 'media' | 'decoration'
  color: string
  conditions: Condition[]
  priority: number
  confidence: number
  regionTags: RegionTag[]
}

// ─── 색상 팔레트 ──────────────────────────────────────────

const COLORS = [
  '#1565C0', '#0D47A1', '#1976D2', '#2196F3',  // 파란 계열 (구조)
  '#333333', '#616161',                          // 회색 계열 (본문)
  '#FF6B6B', '#FF8A80', '#FFAB91',               // 빨강 계열 (문제)
  '#81C784', '#A5D6A7', '#66BB6A', '#43A047',    // 초록 계열 (풀이/그림)
  '#4FC3F7', '#26A69A',                          // 청록 계열 (개념)
  '#7E57C2', '#AB47BC',                          // 보라 계열 (수식)
  '#FF8F00', '#FFB300',                          // 주황 계열 (표)
  '#FFF176', '#BCAAA4',                          // 노랑/갈색 (참고)
  '#9E9E9E', '#E0E0E0', '#BDBDBD',              // 회색 계열 (장식)
]

/**
 * SemanticNode[] → SemanticSchema 자동 생성.
 * extractFeatures()로 생성된 노드 배열을 입력받아 스키마를 만듦.
 */
export function generateSchema(
  nodes: SemanticNode[],
  options: SchemaGeneratorOptions = {},
): SemanticSchema {
  const {
    schemaId = 'auto-generated',
    schemaName = '자동 생성 스키마',
    subject = '',
    documentType = '',
    minGroupSize = 2,
  } = options

  // 1. 노드 그룹화
  const groups = groupNodes(nodes, minGroupSize)

  // 2. 각 그룹에서 레이블 + 규칙 생성
  const labelGroups = assignLabels(groups)

  // 3. 레이블 정의 생성
  const labels: LabelDef[] = labelGroups.map((g, i) => ({
    id: g.label,
    name: g.labelName,
    description: g.description,
    color: COLORS[i % COLORS.length],
    icon: '',
    category: g.category,
    allowedChildren: [],
  }))

  // UNKNOWN 레이블 추가
  labels.push({
    id: 'UNKNOWN',
    name: '미분류',
    description: '자동 분류되지 않은 노드',
    color: '#9E9E9E',
    icon: '',
    category: 'decoration',
    allowedChildren: [],
  })

  // 4. 분류 규칙 생성
  const rules: ClassificationRule[] = labelGroups.map(g => ({
    id: `rule-${g.label.toLowerCase()}`,
    label: g.label,
    priority: g.priority,
    conditions: g.conditions,
    confidence: g.confidence,
  }))

  // 5. 관계 규칙 추론
  const relationRules = inferRelationRules(labelGroups, nodes)

  // 6. 레이아웃 힌트
  const layoutHints: LayoutHint[] = labelGroups
    .filter(g => g.regionTags.length > 0)
    .map(g => ({
      label: g.label,
      expectedRegions: g.regionTags,
    }))

  // 부모-자식 관계 반영
  for (const rel of relationRules) {
    if (rel.type === 'PARENT_OF') {
      const parentLabel = labels.find(l => l.id === rel.sourceLabel)
      if (parentLabel && !parentLabel.allowedChildren.includes(rel.targetLabel)) {
        parentLabel.allowedChildren.push(rel.targetLabel)
      }
    }
  }

  return {
    schemaId,
    schemaName,
    version: '1.0.0',
    subject,
    documentType,
    labels,
    rules,
    relationRules,
    layoutHints,
  }
}

// ─── 1. 노드 그룹화 ──────────────────────────────────────

function groupNodes(nodes: SemanticNode[], minGroupSize: number): NodeGroup[] {
  // Step 1: 1차 그룹 — blockType
  const byBlockType = new Map<string, SemanticNode[]>()
  for (const n of nodes) {
    const bt = n.features.blockType
    const list = byBlockType.get(bt) ?? []
    list.push(n)
    byBlockType.set(bt, list)
  }

  const groups: NodeGroup[] = []

  // 그림/표는 별도 그룹
  const figures = byBlockType.get('FIGURE') ?? []
  if (figures.length >= minGroupSize) {
    groups.push(makeBlockTypeGroup('FIGURE', figures))
  }
  const tables = byBlockType.get('TABLE') ?? []
  if (tables.length >= minGroupSize) {
    groups.push(makeBlockTypeGroup('TABLE', tables))
  }

  // TEXT_FRAME 노드를 세분화
  const textFrames = byBlockType.get('TEXT_FRAME') ?? []

  // Step 2: 배경 전용 분리
  const bgNodes = textFrames.filter(n => n.features.isBackgroundOnly)
  const contentNodes = textFrames.filter(n => !n.features.isBackgroundOnly)

  if (bgNodes.length >= minGroupSize) {
    groups.push({
      key: 'background',
      nodes: bgNodes,
      label: 'BACKGROUND',
      labelName: '배경',
      description: '배경 전용 프레임 (장식, 색상 블록)',
      category: 'decoration',
      color: '#E0E0E0',
      conditions: [{ field: 'isBackgroundOnly', operator: 'eq', value: true }],
      priority: 5,
      confidence: 0.95,
      regionTags: [],
    })
  }

  // Step 3: 장식/구분선 (텍스트 없는 작은 프레임)
  const decoNodes = contentNodes.filter(n =>
    n.features.textLength === 0 && n.features.width * n.features.height < 5000 * 5000
  )
  const textContentNodes = contentNodes.filter(n =>
    n.features.textLength > 0 || n.features.width * n.features.height >= 5000 * 5000
  )

  if (decoNodes.length >= minGroupSize) {
    groups.push({
      key: 'decoration',
      nodes: decoNodes,
      label: 'DECORATION',
      labelName: '장식',
      description: '구분선, 아이콘 등 장식 요소',
      category: 'decoration',
      color: '#BDBDBD',
      conditions: [
        { field: 'blockType', operator: 'eq', value: 'TEXT_FRAME' },
        { field: 'textLength', operator: 'eq', value: 0 },
      ],
      priority: 3,
      confidence: 0.90,
      regionTags: [],
    })
  }

  // Step 4: 머리글/꼬리글 — regionTag TOP/BOTTOM + 짧은 텍스트 + 페이지 반복
  const { headers, footers, rest: afterHF } = detectHeaderFooter(textContentNodes)
  if (headers.length >= minGroupSize) {
    groups.push({
      key: 'page-header',
      nodes: headers,
      label: 'PAGE_HEADER',
      labelName: '쪽 머리',
      description: '페이지 상단 반복 텍스트',
      category: 'structure',
      color: '#9E9E9E',
      conditions: buildConditionsFromNodes(headers, ['regionTag', 'textLength_lt']),
      priority: 10,
      confidence: 0.85,
      regionTags: ['TOP', 'FULL_WIDTH'],
    })
  }
  if (footers.length >= minGroupSize) {
    groups.push({
      key: 'page-footer',
      nodes: footers,
      label: 'PAGE_FOOTER',
      labelName: '쪽 꼬리',
      description: '페이지 하단 반복 텍스트 (페이지 번호 등)',
      category: 'structure',
      color: '#9E9E9E',
      conditions: buildConditionsFromNodes(footers, ['regionTag', 'textLength_lt']),
      priority: 10,
      confidence: 0.85,
      regionTags: ['BOTTOM', 'FULL_WIDTH'],
    })
  }

  // Step 5: 나머지 텍스트 프레임 — paragraphStyle + fontSize 기반 클러스터링
  const styleGroups = clusterByStyle(afterHF, minGroupSize)
  groups.push(...styleGroups)

  return groups
}

// ─── 2. 레이블 할당 ──────────────────────────────────────

function assignLabels(groups: NodeGroup[]): NodeGroup[] {
  // 이미 레이블이 할당된 것은 유지, 나머지는 특성 기반 추론
  let priorityCounter = 15

  for (const g of groups) {
    if (g.label) continue // 이미 할당됨

    g.priority = priorityCounter
    priorityCounter += 5
  }

  return groups
}

// ─── 3. 관계 규칙 추론 ────────────────────────────────────

function inferRelationRules(groups: NodeGroup[], nodes: SemanticNode[]): RelationRule[] {
  const rules: RelationRule[] = []
  const labelSet = new Set(groups.map(g => g.label))

  // CAPTION_FOR: 캡션 → 그림/표
  if (labelSet.has('CAPTION') && labelSet.has('FIGURE')) {
    rules.push({ id: 'rel-caption-figure', type: 'CAPTION_FOR', sourceLabel: 'CAPTION', targetLabel: 'FIGURE' })
  }
  if (labelSet.has('CAPTION') && labelSet.has('TABLE')) {
    rules.push({ id: 'rel-caption-table', type: 'CAPTION_FOR', sourceLabel: 'CAPTION', targetLabel: 'TABLE' })
  }

  // PARENT_OF: 문제 → 소문항
  if (labelSet.has('PROBLEM') && labelSet.has('SUB_PROBLEM')) {
    rules.push({ id: 'rel-sub-problem', type: 'PARENT_OF', sourceLabel: 'PROBLEM', targetLabel: 'SUB_PROBLEM' })
  }
  if (labelSet.has('PROBLEM') && labelSet.has('CHOICES')) {
    rules.push({ id: 'rel-choices-problem', type: 'PARENT_OF', sourceLabel: 'PROBLEM', targetLabel: 'CHOICES' })
  }

  // SOLUTION_FOR: 풀이 → 예제/문제
  if (labelSet.has('SOLUTION') && labelSet.has('EXAMPLE')) {
    rules.push({ id: 'rel-solution-example', type: 'SOLUTION_FOR', sourceLabel: 'SOLUTION', targetLabel: 'EXAMPLE' })
  }
  if (labelSet.has('ANSWER') && labelSet.has('PROBLEM')) {
    rules.push({ id: 'rel-answer-problem', type: 'ANSWER_FOR', sourceLabel: 'ANSWER', targetLabel: 'PROBLEM' })
  }

  return rules
}

// ─── 스타일 기반 클러스터링 ─────────────────────────────────

interface StyleClusterKey {
  dominantParagraphStyle: string | null
  fontSizeBucket: number
  hasFill: boolean
  hasStroke: boolean
  hasNumberPrefix: boolean
  numberPrefixPattern: string | null
}

function clusterByStyle(nodes: SemanticNode[], minGroupSize: number): NodeGroup[] {
  // 스타일 키 생성
  const clusters = new Map<string, { key: StyleClusterKey; nodes: SemanticNode[] }>()

  for (const n of nodes) {
    const k: StyleClusterKey = {
      dominantParagraphStyle: n.features.dominantParagraphStyle,
      fontSizeBucket: Math.round(n.features.dominantFontSize / 100) * 100, // 1pt 단위 버킷
      hasFill: n.features.hasFill,
      hasStroke: n.features.hasStroke,
      hasNumberPrefix: n.features.hasNumberPrefix,
      numberPrefixPattern: n.features.numberPrefixPattern,
    }
    const keyStr = JSON.stringify(k)
    const existing = clusters.get(keyStr)
    if (existing) {
      existing.nodes.push(n)
    } else {
      clusters.set(keyStr, { key: k, nodes: [n] })
    }
  }

  const groups: NodeGroup[] = []
  let colorIdx = 0
  let priorityCounter = 20

  // 클러스터를 크기 순으로 정렬 (큰 것부터)
  const sorted = [...clusters.values()].sort((a, b) => b.nodes.length - a.nodes.length)

  for (const cluster of sorted) {
    if (cluster.nodes.length < minGroupSize) continue

    const { label, labelName, description, category } = inferLabelFromCluster(cluster.key, cluster.nodes)
    const conditions = buildClusterConditions(cluster.key, cluster.nodes)

    groups.push({
      key: `style-${JSON.stringify(cluster.key)}`,
      nodes: cluster.nodes,
      label,
      labelName,
      description,
      category,
      color: COLORS[colorIdx++ % COLORS.length],
      conditions,
      priority: priorityCounter,
      confidence: computeClusterConfidence(cluster.nodes),
      regionTags: inferRegionTags(cluster.nodes),
    })

    priorityCounter += 5
  }

  // 같은 레이블이 여러 그룹에 할당될 수 있으므로 suffix 추가
  const labelCounts = new Map<string, number>()
  for (const g of groups) {
    const count = (labelCounts.get(g.label) ?? 0) + 1
    labelCounts.set(g.label, count)
    if (count > 1) {
      g.label = `${g.label}_${count}`
      g.labelName = `${g.labelName} ${count}`
    }
  }

  return groups
}

/** 클러스터 특성에서 레이블 추론 */
function inferLabelFromCluster(
  key: StyleClusterKey,
  nodes: SemanticNode[],
): { label: string; labelName: string; description: string; category: 'content' | 'structure' | 'media' | 'decoration' } {
  const avgFontSize = avg(nodes.map(n => n.features.dominantFontSize))
  const avgTextLen = avg(nodes.map(n => n.features.textLength))
  const avgParaCount = avg(nodes.map(n => n.features.paragraphCount))
  const styleName = key.dominantParagraphStyle ?? ''

  // 제목 판별: 큰 폰트 + 짧은 텍스트 + 적은 문단
  if (avgFontSize >= 1400 && avgParaCount <= 3 && avgTextLen < 200) {
    if (avgFontSize >= 1800) {
      return { label: 'CHAPTER_TITLE', labelName: '대단원 제목', description: `스타일: ${styleName}, 폰트 ${(avgFontSize / 100).toFixed(0)}pt`, category: 'structure' }
    }
    return { label: 'SECTION_TITLE', labelName: '단원 제목', description: `스타일: ${styleName}, 폰트 ${(avgFontSize / 100).toFixed(0)}pt`, category: 'structure' }
  }

  // 소제목: 볼드 + 중간 폰트 + 짧은 텍스트
  if (avgFontSize >= 1100 && avgFontSize < 1400 && avgParaCount <= 3 && avgTextLen < 150) {
    return { label: 'SUBSECTION_TITLE', labelName: '소제목', description: `스타일: ${styleName}, 폰트 ${(avgFontSize / 100).toFixed(0)}pt`, category: 'structure' }
  }

  // 번호 접두사 패턴
  if (key.hasNumberPrefix) {
    switch (key.numberPrefixPattern) {
      case 'circled':
      case 'circled_korean':
        return { label: 'SUB_PROBLEM', labelName: '소문항', description: `${key.numberPrefixPattern} 번호 접두사`, category: 'content' }
      case 'parenthesized_arabic':
      case 'parenthesized_korean':
        return { label: 'CHOICES', labelName: '선택지', description: `${key.numberPrefixPattern} 번호 접두사`, category: 'content' }
      case 'arabic_dot':
        if (avgFontSize >= 1200) {
          return { label: 'SECTION_TITLE', labelName: '단원 제목', description: `숫자+점 접두, 폰트 ${(avgFontSize / 100).toFixed(0)}pt`, category: 'structure' }
        }
        return { label: 'PROBLEM', labelName: '문제', description: `숫자+점 접두, 폰트 ${(avgFontSize / 100).toFixed(0)}pt`, category: 'content' }
      case 'roman':
        return { label: 'CHAPTER_TITLE', labelName: '대단원 제목', description: '로마 숫자 접두', category: 'structure' }
    }
  }

  // 박스형: 배경+테두리
  if (key.hasFill && key.hasStroke) {
    if (avgTextLen > 200) {
      return { label: 'CONCEPT_BOX', labelName: '개념 상자', description: '배경+테두리, 긴 텍스트', category: 'content' }
    }
    return { label: 'INFO_BOX', labelName: '정보 상자', description: '배경+테두리 박스', category: 'content' }
  }

  // 배경만 있는 박스
  if (key.hasFill && !key.hasStroke) {
    if (avgTextLen < 100) {
      return { label: 'TIP_BOX', labelName: '참고 상자', description: '배경색 박스, 짧은 텍스트', category: 'content' }
    }
    return { label: 'HIGHLIGHT_BOX', labelName: '강조 상자', description: '배경색 박스', category: 'content' }
  }

  // 수식 포함
  const equationRatio = nodes.filter(n => n.features.hasEquation).length / nodes.length
  if (equationRatio > 0.5) {
    return { label: 'FORMULA', labelName: '공식', description: '수식 포함 블록', category: 'content' }
  }

  // 캡션 판별: 그림/표 근처 짧은 텍스트
  if (avgTextLen < 80 && avgParaCount <= 2) {
    const nearFigureOrTable = nodes.filter(n => {
      const nearby = n.features.spatial.nearestContentNodeId
      return nearby != null
    }).length
    if (nearFigureOrTable > nodes.length * 0.5) {
      return { label: 'CAPTION', labelName: '캡션', description: '그림/표 설명 텍스트', category: 'content' }
    }
  }

  // 본문 (기본값): 가장 많은 그룹이 보통 본문
  if (avgTextLen > 100 && avgParaCount >= 2) {
    return { label: 'BODY_TEXT', labelName: '본문', description: `스타일: ${styleName}, 폰트 ${(avgFontSize / 100).toFixed(0)}pt`, category: 'content' }
  }

  // 키워드 기반 추론
  const firstLines = nodes.map(n => n.features.firstLineText.trim()).filter(t => t.length > 0)
  const commonKeyword = findCommonKeyword(firstLines)
  if (commonKeyword) {
    const safeId = commonKeyword.replace(/[^a-zA-Z가-힣0-9]/g, '_').toUpperCase()
    return { label: `LABELED_${safeId}`, labelName: commonKeyword, description: `공통 키워드: "${commonKeyword}"`, category: 'content' }
  }

  // 스타일 이름 기반
  if (styleName && styleName !== '[Basic Paragraph]') {
    const safeId = styleName.replace(/[^a-zA-Z0-9가-힣]/g, '_').toUpperCase()
    return { label: `STYLE_${safeId}`, labelName: styleName, description: `문단 스타일: ${styleName}`, category: 'content' }
  }

  return { label: 'BODY_TEXT', labelName: '본문', description: '일반 텍스트', category: 'content' }
}

/** 클러스터 조건 생성 */
function buildClusterConditions(key: StyleClusterKey, nodes: SemanticNode[]): Condition[] {
  const conditions: Condition[] = []

  if (key.dominantParagraphStyle) {
    conditions.push({ field: 'dominantParagraphStyle', operator: 'eq', value: key.dominantParagraphStyle })
  }

  if (key.fontSizeBucket > 0) {
    const sizes = nodes.map(n => n.features.dominantFontSize).filter(s => s > 0)
    if (sizes.length > 0) {
      const minSize = Math.min(...sizes)
      const maxSize = Math.max(...sizes)
      if (minSize === maxSize) {
        conditions.push({ field: 'dominantFontSize', operator: 'eq', value: minSize })
      } else {
        conditions.push({ field: 'dominantFontSize', operator: 'gte', value: minSize })
        if (maxSize - minSize > 100) {
          conditions.push({ field: 'dominantFontSize', operator: 'lte', value: maxSize })
        }
      }
    }
  }

  if (key.hasFill) {
    conditions.push({ field: 'hasFill', operator: 'eq', value: true })
  }
  if (key.hasStroke) {
    conditions.push({ field: 'hasStroke', operator: 'eq', value: true })
  }
  if (key.hasNumberPrefix) {
    conditions.push({ field: 'hasNumberPrefix', operator: 'eq', value: true })
    if (key.numberPrefixPattern) {
      conditions.push({ field: 'numberPrefixPattern', operator: 'eq', value: key.numberPrefixPattern })
    }
  }

  return conditions
}

// ─── 머리글/꼬리글 감지 ─────────────────────────────────────

function detectHeaderFooter(nodes: SemanticNode[]): {
  headers: SemanticNode[]
  footers: SemanticNode[]
  rest: SemanticNode[]
} {
  // 페이지별 상단/하단 영역에 반복 등장하는 짧은 텍스트 감지
  const pageCount = new Set(nodes.map(n => n.features.pageNumber)).size

  if (pageCount < 2) {
    return { headers: [], footers: [], rest: nodes }
  }

  const headers: SemanticNode[] = []
  const footers: SemanticNode[] = []
  const rest: SemanticNode[] = []

  // 상단/하단 후보: 짧은 텍스트 + TOP/BOTTOM
  for (const n of nodes) {
    if (n.features.textLength < 50 && n.features.regionTag === 'TOP') {
      headers.push(n)
    } else if (n.features.textLength < 30 && n.features.regionTag === 'BOTTOM') {
      footers.push(n)
    } else {
      rest.push(n)
    }
  }

  // 페이지의 50% 이상에서 등장해야 머리글/꼬리글로 인정
  const headerPages = new Set(headers.map(n => n.features.pageNumber)).size
  const footerPages = new Set(footers.map(n => n.features.pageNumber)).size

  if (headerPages < pageCount * 0.4) {
    rest.push(...headers)
    headers.length = 0
  }
  if (footerPages < pageCount * 0.4) {
    rest.push(...footers)
    footers.length = 0
  }

  return { headers, footers, rest }
}

// ─── 유틸 ─────────────────────────────────────────────────

function makeBlockTypeGroup(blockType: 'FIGURE' | 'TABLE', nodes: SemanticNode[]): NodeGroup {
  const isFigure = blockType === 'FIGURE'
  return {
    key: blockType.toLowerCase(),
    nodes,
    label: blockType,
    labelName: isFigure ? '그림' : '표',
    description: isFigure ? '이미지, 그래프 등 시각 자료' : '표 블록',
    category: isFigure ? 'media' : 'content',
    color: isFigure ? '#43A047' : '#FF8F00',
    conditions: [{ field: 'blockType', operator: 'eq', value: blockType }],
    priority: 15,
    confidence: 0.90,
    regionTags: [],
  }
}

function buildConditionsFromNodes(
  nodes: SemanticNode[],
  fields: string[],
): Condition[] {
  const conditions: Condition[] = []

  for (const field of fields) {
    if (field === 'regionTag') {
      const tags = nodes.map(n => n.features.regionTag)
      const dominant = mode(tags)
      if (dominant) {
        conditions.push({ field: 'regionTag', operator: 'eq', value: dominant })
      }
    } else if (field === 'textLength_lt') {
      const maxLen = Math.max(...nodes.map(n => n.features.textLength))
      conditions.push({ field: 'textLength', operator: 'lt', value: Math.ceil(maxLen * 1.2) })
    }
  }

  return conditions
}

function computeClusterConfidence(nodes: SemanticNode[]): number {
  // 그룹이 클수록, 스타일이 일관될수록 높은 confidence
  const size = nodes.length
  if (size >= 10) return 0.85
  if (size >= 5) return 0.80
  if (size >= 3) return 0.75
  return 0.70
}

function inferRegionTags(nodes: SemanticNode[]): RegionTag[] {
  const tagCounts = new Map<RegionTag, number>()
  for (const n of nodes) {
    const tag = n.features.regionTag
    tagCounts.set(tag, (tagCounts.get(tag) ?? 0) + 1)
  }

  // 70% 이상 비율인 태그만 반환
  const threshold = nodes.length * 0.7
  const result: RegionTag[] = []
  for (const [tag, count] of tagCounts) {
    if (count >= threshold) result.push(tag)
  }
  return result
}

function findCommonKeyword(texts: string[]): string | null {
  if (texts.length < 2) return null

  // 2글자 이상 공통 접두어 찾기
  const first = texts[0]
  for (let len = Math.min(first.length, 10); len >= 2; len--) {
    const prefix = first.slice(0, len).trim()
    if (prefix.length < 2) continue
    if (texts.every(t => t.startsWith(prefix))) {
      return prefix
    }
  }
  return null
}

function avg(nums: number[]): number {
  if (nums.length === 0) return 0
  return nums.reduce((a, b) => a + b, 0) / nums.length
}

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
