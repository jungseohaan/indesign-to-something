/**
 * SLA 핵심 타입 정의 — SemanticNode, Schema, Rule, Relation.
 */

// ─── 노드 타입 ────────────────────────────────────────────

export type NodeType = 'FRAME' | 'PARAGRAPH' | 'TABLE' | 'FIGURE' | 'INLINE_OBJECT' | 'EQUATION'

// ─── 공간 근접도 ──────────────────────────────────────────

export interface SpatialProximityFeatures {
  nearestContentNodeId: string | null
  nearestContentDistance: number
  overlappingNodeIds: string[]
  isVisuallyContainedBy: string | null
  visualContainmentRatio: number
}

// ─── 구조적 특징 ──────────────────────────────────────────

export interface StructuralFeatures {
  // A. 위치 & 레이아웃
  pageNumber: number
  x: number
  y: number
  width: number
  height: number
  zOrder: number
  regionTag: RegionTag
  columnIndex: number
  relativeYInPage: number

  // B. 스토리 & 텍스트 흐름
  storyId: string | null
  storyFrameCount: number
  storyPageSpan: number
  frameIndexInStory: number
  isStoryStart: boolean
  isStoryEnd: boolean

  // C. 텍스트 속성
  textContent: string
  textLength: number
  paragraphCount: number
  dominantFontSize: number
  maxFontSize: number
  dominantFontFamily: string
  hasBoldText: boolean
  dominantAlignment: string | null
  hasNumberPrefix: boolean
  numberPrefixPattern: string | null
  firstLineText: string

  // D. 스타일 참조
  paragraphStyleNames: string[]
  characterStyleNames: string[]
  dominantParagraphStyle: string | null

  // E. 프레임 속성
  hasFill: boolean
  fillColor: string | null
  hasStroke: boolean
  isBackgroundOnly: boolean
  columnCount: number
  rotationAngle: number

  // F. 콘텐츠 구성
  hasTable: boolean
  hasImage: boolean
  hasEquation: boolean
  hasInlineFrame: boolean
  inlineObjectCount: number
  blockType: string

  // G. 공간 근접도
  spatial: SpatialProximityFeatures
}

export type RegionTag = 'TOP' | 'MIDDLE' | 'BOTTOM' | 'LEFT' | 'RIGHT' | 'FULL_WIDTH'

// ─── 시멘틱 노드 ──────────────────────────────────────────

export interface SemanticNode {
  id: string
  astPath: string
  nodeType: NodeType
  features: StructuralFeatures
  label: string
  confidence: number
  appliedRule: string | null
  manualOverride: boolean
  children: string[]
  storyId: string | null
  metadata: Record<string, unknown>
}

// ─── 시멘틱 관계 ──────────────────────────────────────────

export type RelationType =
  | 'PARENT_OF'
  | 'CAPTION_FOR'
  | 'ANSWER_FOR'
  | 'SOLUTION_FOR'
  | 'CONTINUES_FROM'
  | 'REFERENCES'

export interface SemanticRelation {
  type: RelationType
  sourceId: string
  targetId: string
  confidence?: number
}

// ─── 스키마 ───────────────────────────────────────────────

export interface SemanticSchema {
  schemaId: string
  schemaName: string
  version: string
  subject: string
  documentType: string
  extends?: string
  labels: LabelDef[]
  rules: ClassificationRule[]
  relationRules: RelationRule[]
  layoutHints: LayoutHint[]
}

export interface LabelDef {
  id: string
  name: string
  description: string
  color: string
  icon: string
  category: 'content' | 'structure' | 'media' | 'decoration'
  allowedChildren: string[]
}

export interface ClassificationRule {
  id: string
  label: string
  priority: number
  conditions: Condition[]
  confidence: number
}

export interface Condition {
  field: string
  operator:
    | 'eq' | 'ne' | 'gt' | 'lt' | 'gte' | 'lte'
    | 'contains' | 'startsWith' | 'matches'
    | 'in' | 'notIn'
  value: unknown
}

export interface RelationRule {
  id: string
  type: RelationType
  sourceLabel: string
  targetLabel: string
  conditions?: Condition[]
}

export interface LayoutHint {
  label: string
  expectedRegions: RegionTag[]
}

// ─── SLA 출력 포맷 ────────────────────────────────────────

export interface SemanticLayer {
  version: string
  schemaId: string
  sourceAstHash: string
  previousAstHash?: string
  createdAt: string
  modifiedAt: string
  mergeHistory: MergeHistoryEntry[]
  nodes: SemanticNode[]
  relations: SemanticRelation[]
  deletedNodes: DeletedNode[]
}

export interface MergeHistoryEntry {
  timestamp: string
  previousHash: string
  stats: {
    matched: number
    manualPreserved: number
    reclassified: number
    added: number
    deleted: number
    symmetryMatched: number
  }
}

export interface DeletedNode {
  id: string
  label: string
  manualOverride: boolean
  deletedAt: string
  fingerprint: NodeFingerprint
}

export interface NodeFingerprint {
  sourceId: string
  storyId: string | null
  frameIndexInStory: number
  textFingerprint: string
  pageNumber: number
}
