# SPEC-004: Semantic Layer Architecture (SLA)

## 개요

AST는 IDML의 **물리적 구조**(프레임, 스타일, 텍스트 런)를 표현하지만 **의미적 구조**(제목, 문제, 보기, 해설 등)는 담지 않는다. SLA는 AST 위에 시멘틱 정보를 자동 추출 + 수동 보정하는 레이어다.

## 목표

1. AST에서 **구조적 특징(Structural Features)** 자동 추출
2. 규칙 기반으로 **시멘틱 레이블** 자동 분류
3. 과목별 **스키마** 로 시멘틱 구조 교체 가능
4. **에디터** 에서 시각적 확인·수정
5. **PPT 내보내기** 파이프라인 연결
6. **프리미엄 기능** 으로 분리 (별도 TypeScript 패키지)

---

## 1. 전체 파이프라인

```
┌─ 기존 Java JAR (변경 없음) ──────────────────────────────────┐
│                                                               │
│  .indd → ExtendScript → .idml + resolved.json                │
│           ↓                                                   │
│  IDMLLoader → Normalizer → AST → ASTSerializer.toJson()      │
│                                        │                      │
│  ASTToHwpxConverter → .hwpx            │ AST JSON             │
│                                        │                      │
└────────────────────────────────────────│──────────────────────┘
                                         ▼
┌─ @its/semantic-layer (TypeScript) ───────────────────────────┐
│                                                               │
│  [1] ASTJsonAdapter ← AST JSON (유일한 파싱 지점)            │
│          ↓ ASTAdapter 인터페이스                              │
│  [2] FeatureExtractor → SemanticNode[] (features만, 레이블X) │
│          ↓                                                    │
│  [3] RuleClassifier + Schema → 레이블 자동 분류               │
│          ↓                                                    │
│  [4] RelationBuilder → 노드 간 관계 생성                      │
│          ↓                                                    │
│  [5] SemanticLayer JSON 출력                                  │
│                                                               │
│  [선택] RuleSuggester → 수동 레이블에서 규칙 역추론           │
│  [선택] Merger → AST 변경 시 증분 업데이트 (수동 보존)        │
│  [선택] SlideBreaker → SlideLayout → PPTRenderer → .pptx     │
│                                                               │
└───────────────────────────────────────────────────────────────┘
                         ↓ import
┌─ Desktop App (Tauri + React) ────────────────────────────────┐
│  Semantic 탭: 트리 / 프리뷰 오버레이 / 속성 편집              │
│  스키마 에디터 / 규칙 제안 UI / PPT 내보내기                  │
└───────────────────────────────────────────────────────────────┘
```

---

## 2. AST Adapter — 인터페이스 경계

### 2.1 설계 원칙

SLA 코어는 **AST JSON 구조를 직접 파싱하지 않는다.** `ASTAdapter` 인터페이스를 통해서만 데이터에 접근한다. AST 구조 변경 시 `ast-json-adapter.ts` 한 파일만 수정.

> **참고**: Java JAR에는 어댑터 패턴이 없다. 모든 소비자(ASTToHwpxConverter 등)가 직접 `.field()` getter + 명시적 캐스팅으로 접근한다. SLA의 ASTAdapter는 새로운 아키텍처 패턴이다.

### 2.2 어댑터 인터페이스

```typescript
// packages/semantic-layer/src/adapter/ast-adapter.ts

interface ASTAdapter {
  // 문서 구조
  getPages(): PageInfo[]
  getBlocks(pageNumber: number): BlockInfo[]
  getParagraphs(blockId: string): ParagraphInfo[]

  // 스토리
  getStories(): StoryInfo[]
  getStory(storyId: string): StoryInfo | null
  getFrameIdsForStory(storyId: string): string[]

  // 스타일
  getParagraphStyles(): StyleInfo[]
  getCharacterStyles(): StyleInfo[]
  getStyleByRef(ref: string): StyleInfo | null

  // 미디어
  getImageInfo(blockId: string): ImageInfo | null
  getTableInfo(blockId: string): TableInfo | null

  // 색상
  getColorHex(colorRef: string): string | null

  // 해시 (변경 감지용)
  getDocumentHash(): string
}
```

### 2.3 어댑터 데이터 타입

```typescript
// packages/semantic-layer/src/adapter/types.ts

interface PageInfo {
  pageNumber: number
  width: number              // HWPUNIT
  height: number
  marginTop: number
  marginBottom: number
  marginLeft: number
  marginRight: number
  columnCount: number
  columnGutter: number
}

interface BlockInfo {
  id: string                 // sourceId
  blockType: "TEXT_FRAME" | "TABLE" | "FIGURE"
  pageNumber: number
  x: number
  y: number
  width: number
  height: number
  zOrder: number
  rotation: number
  // 텍스트 프레임 전용
  storyId: string | null
  columnCount: number
  fillColor: string | null
  strokeColor: string | null
  hasFill: boolean
  hasStroke: boolean
  isBackgroundOnly: boolean
  verticalJustification: string | null
}

interface ParagraphInfo {
  index: number
  alignment: string | null
  paragraphStyleRef: string | null
  firstLineIndent: number | null
  spaceBefore: number | null
  spaceAfter: number | null
  hasInlineTable: boolean
  hasColumnBreakAfter: boolean
  items: InlineItemInfo[]
}

interface InlineItemInfo {
  itemType: "TEXT_RUN" | "INLINE_OBJECT" | "BREAK" | "EQUATION"
  // TEXT_RUN
  text?: string
  fontFamily?: string
  fontStyle?: string
  fontSize?: number          // HWPUNIT
  textColor?: string
  bold?: boolean
  underline?: boolean
  subscript?: boolean
  superscript?: boolean
  // INLINE_OBJECT
  objectKind?: "IMAGE" | "RENDERED_GROUP" | "INLINE_TEXT_FRAME" | "SPACER_RECT"
  objectWidth?: number
  objectHeight?: number
  // EQUATION
  equationScript?: string
  equationSourceType?: string
}

interface StoryInfo {
  storyId: string
  linkedFrameIds: string[]   // 프레임 체인 (순서대로)
  pages: number[]
  paragraphCount: number
  tableCount: number
}

interface StyleInfo {
  styleId: string
  styleName: string
  type: "paragraph" | "character"
  fontFamily?: string
  fontSize?: number
  bold?: boolean
  alignment?: string
  textColor?: string
}

interface ImageInfo {
  format: string             // "PNG", "JPEG"
  bundlePath: string | null
  pixelWidth: number
  pixelHeight: number
}

interface TableInfo {
  id: string
  rowCount: number
  colCount: number
  columnWidths: number[]
  rows: TableRowInfo[]
}

interface TableRowInfo {
  rowIndex: number
  rowHeight: number
  cells: TableCellInfo[]
}

interface TableCellInfo {
  rowIndex: number
  columnIndex: number
  rowSpan: number
  columnSpan: number
  fillColor: string | null
  paragraphs: ParagraphInfo[]
}
```

### 2.4 구현체

```typescript
// packages/semantic-layer/src/adapter/ast-json-adapter.ts
// ← AST JSON 구조를 아는 유일한 파일

class ASTJsonAdapter implements ASTAdapter {
  private json: any

  constructor(astJson: string | object) {
    this.json = typeof astJson === 'string' ? JSON.parse(astJson) : astJson
  }

  getPages(): PageInfo[] {
    return this.json.sections.map((s: any) => ({
      pageNumber: s.pageNumber,
      width: s.layout.pageWidth,
      height: s.layout.pageHeight,
      marginTop: s.layout.marginTop,
      // ... 필드 매핑
    }))
  }

  getBlocks(pageNumber: number): BlockInfo[] {
    const section = this.json.sections.find((s: any) => s.pageNumber === pageNumber)
    return (section?.blocks || []).map((b: any) => ({
      id: b.sourceId,
      blockType: this.mapBlockType(b.blockType),
      storyId: b.storyId || null,
      // ... 필드 매핑
    }))
  }

  // ... 나머지 메서드 동일 패턴
}
```

### 2.5 변경 격리 보장

| AST 변경 예시 | 수정 범위 |
|--------------|-----------|
| `blockType` 값 변경 | `ast-json-adapter.ts`의 `mapBlockType()` 1줄 |
| 새 필드 추가 (`opacity` 등) | `BlockInfo`에 필드 추가 + 어댑터 매핑 (선택적) |
| 필드명 변경 (`items` → `children`) | `ast-json-adapter.ts`의 해당 매핑 1줄 |
| 섹션 구조 대폭 변경 | `ast-json-adapter.ts`만 재작성, SLA 코어 무변경 |

---

## 3. SemanticNode — 추출 단위

```
SemanticNode
├── id: string                    # 고유 ID (AST sourceId 기반)
├── astPath: string               # AST 내 경로 ("sections[0].blocks[2]")
├── nodeType: NodeType            # FRAME | PARAGRAPH | TABLE | FIGURE | INLINE_OBJECT | EQUATION
├── features: StructuralFeatures  # 자동 추출된 구조적 특징
├── label: string                 # 시멘틱 레이블 (자동/수동)
├── confidence: number            # 자동 분류 신뢰도 (0.0~1.0)
├── appliedRule: string | null    # 적용된 분류 규칙 ID
├── manualOverride: boolean       # 사용자 수동 수정 여부
├── children: SemanticNode[]      # 하위 노드 (프레임→문단, 테이블→셀)
├── storyId: string | null        # 소속 스토리 ID
└── metadata: Record<string, any> # 과목별 커스텀 메타데이터
```

---

## 4. 구조적 특징 (StructuralFeatures)

ASTAdapter를 통해 자동 추출하는 40+ 특징:

### A. 위치 & 레이아웃

| 특징 | 타입 | 설명 |
|------|------|------|
| `pageNumber` | int | 페이지 번호 |
| `x`, `y` | long | 절대 좌표 (HWPUNIT) |
| `width`, `height` | long | 블록 크기 |
| `zOrder` | int | 레이어 순서 |
| `regionTag` | enum | TOP / MIDDLE / BOTTOM / LEFT / RIGHT / FULL_WIDTH |
| `columnIndex` | int | 다단 기준 위치 |
| `relativeYInPage` | double | 페이지 높이 대비 Y 비율 (0.0~1.0) |

### B. 스토리 & 텍스트 흐름

| 특징 | 타입 | 설명 |
|------|------|------|
| `storyId` | string | 스토리 ID |
| `storyFrameCount` | int | 스토리가 걸치는 프레임 수 |
| `storyPageSpan` | int | 스토리가 걸치는 페이지 수 |
| `frameIndexInStory` | int | 스토리 체인 내 순서 |
| `isStoryStart` | bool | 스토리의 첫 프레임 |
| `isStoryEnd` | bool | 스토리의 마지막 프레임 |

### C. 텍스트 속성

| 특징 | 타입 | 설명 |
|------|------|------|
| `textContent` | string | 전체 텍스트 |
| `textLength` | int | 글자 수 |
| `paragraphCount` | int | 문단 수 |
| `dominantFontSize` | int | 최빈 폰트 크기 (HWPUNIT) |
| `maxFontSize` | int | 최대 폰트 크기 |
| `dominantFontFamily` | string | 최빈 폰트 |
| `hasBoldText` | bool | Bold 포함 여부 |
| `dominantAlignment` | string | 주 정렬 방향 |
| `hasNumberPrefix` | bool | 번호 시작 여부 ("1.", "①", "(가)") |
| `numberPrefixPattern` | string | 번호 패턴 유형 |
| `firstLineText` | string | 첫 문단 첫 줄 (헤딩 판별용) |

### D. 스타일 참조

| 특징 | 타입 | 설명 |
|------|------|------|
| `paragraphStyleNames` | string[] | 사용된 문단 스타일명 |
| `characterStyleNames` | string[] | 사용된 문자 스타일명 |
| `dominantParagraphStyle` | string | 최빈 문단 스타일 |

### E. 프레임 속성

| 특징 | 타입 | 설명 |
|------|------|------|
| `hasFill` | bool | 배경색 여부 |
| `fillColor` | string | 배경색 |
| `hasStroke` | bool | 테두리 여부 |
| `isBackgroundOnly` | bool | 장식 프레임 여부 |
| `columnCount` | int | 프레임 내 단 수 |
| `rotationAngle` | double | 회전 각도 |

### F. 콘텐츠 구성

| 특징 | 타입 | 설명 |
|------|------|------|
| `hasTable` | bool | 테이블 포함 |
| `hasImage` | bool | 이미지 포함 |
| `hasEquation` | bool | 수식 포함 |
| `hasInlineFrame` | bool | 인라인 글상자 포함 |
| `inlineObjectCount` | int | 인라인 객체 수 |
| `blockType` | string | TEXT_FRAME / TABLE / FIGURE |

### G. 공간 근접도 (Spatial Proximity)

인디자인에서 문제 번호("1.")가 본문 프레임 밖에 별도 그래픽 개체로 떠 있는 경우가 빈번하다. Z-Order만으로는 이런 시각적 포함 관계를 파악할 수 없으므로, 물리적 근접도 특징을 추가한다.

| 특징 | 타입 | 설명 |
|------|------|------|
| `nearestContentNodeId` | string \| null | 가장 가까운 CONTENT 레이어 노드 ID |
| `nearestContentDistance` | double | 최근접 CONTENT 노드까지 거리 (HWPUNIT) |
| `overlappingNodeIds` | string[] | AABB 겹침이 있는 노드 ID 목록 |
| `isVisuallyContainedBy` | string \| null | 시각적으로 포함하는 부모 노드 ID |
| `visualContainmentRatio` | double | 포함 비율 (0.0~1.0, 면적 기준) |

**Visual Containment 판정 알고리즘:**

```typescript
// feature-extractor.ts 내 proximity 계산

function computeSpatialProximity(
  node: BlockInfo,
  allNodes: BlockInfo[],
  page: PageInfo
): SpatialProximityFeatures {
  const candidates = allNodes.filter(n => n.id !== node.id && n.pageNumber === node.pageNumber)

  // 1. AABB 겹침 검사
  const overlapping = candidates.filter(c => aabbOverlap(node, c))

  // 2. 시각적 포함 판정 (작은 노드가 큰 노드에 80%+ 포함)
  const container = overlapping.find(c => {
    const overlapArea = computeOverlapArea(node, c)
    const nodeArea = node.width * node.height
    return nodeArea > 0 && overlapArea / nodeArea >= 0.8 && c.width * c.height > nodeArea
  })

  // 3. 최근접 CONTENT 노드 (엣지 간 최소 거리)
  const contentNodes = candidates.filter(c => !c.isBackgroundOnly)
  let nearest = null, minDist = Infinity
  for (const c of contentNodes) {
    const dist = edgeToEdgeDistance(node, c)
    if (dist < minDist) { minDist = dist; nearest = c }
  }

  return {
    nearestContentNodeId: nearest?.id ?? null,
    nearestContentDistance: minDist,
    overlappingNodeIds: overlapping.map(n => n.id),
    isVisuallyContainedBy: container?.id ?? null,
    visualContainmentRatio: container ? computeOverlapArea(node, container) / (node.width * node.height) : 0
  }
}
```

**RelationBuilder에서의 활용:**

```typescript
// relation-builder.ts — 공간 근접도 기반 관계 제안

function buildProximityRelations(nodes: SemanticNode[]): SemanticRelation[] {
  const relations: SemanticRelation[] = []

  for (const node of nodes) {
    // 작은 번호 프레임 → 가장 가까운 본문 프레임에 PARENT_OF 제안
    if (node.label === "PROBLEM_NUMBER" && node.features.nearestContentNodeId) {
      const target = nodes.find(n => n.id === `sn-${node.features.nearestContentNodeId}`)
      if (target && ["PROBLEM", "BODY_TEXT"].includes(target.label)) {
        relations.push({
          type: "PARENT_OF",
          sourceId: target.id,      // 본문이 부모
          targetId: node.id,        // 번호가 자식
          confidence: Math.max(0, 1.0 - node.features.nearestContentDistance / 7200) // 1인치 이내 = 높은 신뢰도
        })
      }
    }

    // 시각적 포함: 캡션 프레임이 이미지 위에 겹쳐 있는 경우
    if (node.features.isVisuallyContainedBy) {
      const parent = nodes.find(n => n.id === `sn-${node.features.isVisuallyContainedBy}`)
      if (parent && parent.label === "FIGURE" && node.label === "CAPTION") {
        relations.push({ type: "CAPTION_FOR", sourceId: node.id, targetId: parent.id })
      }
    }
  }

  return relations
}
```

> **배경**: 코드베이스에 `IDMLGeometry.isFrameOnPage()`(AABB 겹침), `TextFrameGridMerger`(그리드 클러스터링) 등 좌표 계산 유틸이 이미 존재하지만, 노드 간 근접도 기반 관계 추론은 없다. 이 로직은 SLA의 RelationBuilder에서 새로 구현한다.

---

## 5. 시멘틱 레이블

### 5.1 공통 레이블

```typescript
type CommonLabel =
  // 구조
  | "PAGE_HEADER" | "PAGE_FOOTER" | "SECTION_TITLE"
  | "SUBSECTION_TITLE" | "HEADING"
  // 본문
  | "BODY_TEXT" | "CAPTION" | "FOOTNOTE" | "SIDEBAR" | "TIP"
  // 미디어
  | "FIGURE" | "TABLE" | "EQUATION" | "DIAGRAM"
  // 장식
  | "DECORATION" | "BACKGROUND"
  // 미분류
  | "UNKNOWN"
```

### 5.2 과목별 확장 레이블 (예시)

```typescript
// 수학 참고서
type MathLabel = CommonLabel
  | "PROBLEM" | "PROBLEM_NUMBER" | "SUB_PROBLEM"
  | "CHOICES" | "SOLUTION" | "ANSWER" | "EXAMPLE"
  | "CONCEPT_BOX" | "FORMULA_BOX" | "DIFFICULTY_MARKER"

// 국어 교과서
type KoreanLabel = CommonLabel
  | "PASSAGE" | "LITERARY_WORK" | "AUTHOR_INFO"
  | "VOCABULARY" | "ACTIVITY" | "DISCUSSION"

// 영어 교과서
type EnglishLabel = CommonLabel
  | "DIALOGUE" | "READING_PASSAGE" | "GRAMMAR_BOX"
  | "VOCABULARY_LIST" | "LISTENING_SCRIPT" | "EXERCISE"
```

### 5.3 시멘틱 관계

```typescript
interface SemanticRelation {
  type: "PARENT_OF"        // 문제 → 소문항
       | "CAPTION_FOR"     // 캡션 → 그림/표
       | "ANSWER_FOR"      // 정답 → 문제
       | "SOLUTION_FOR"    // 풀이 → 문제
       | "CONTINUES_FROM"  // 같은 스토리의 연속 (자동 생성)
       | "REFERENCES"      // 참조 관계
  sourceId: string
  targetId: string
}
```

---

## 6. 스키마 정의

### 6.1 스키마 구조

```typescript
interface SemanticSchema {
  schemaId: string             // "math-reference-v1"
  schemaName: string           // "수학 참고서"
  version: string
  subject: string              // "수학"
  documentType: string         // "참고서" | "교과서" | "시험지"
  extends?: string             // "common" — 상속
  labels: LabelDef[]
  rules: ClassificationRule[]
  relationRules: RelationRule[]
  layoutHints: LayoutHint[]
}

interface LabelDef {
  id: string                   // "PROBLEM"
  name: string                 // "문제"
  description: string
  color: string                // "#FF6B6B"
  icon: string                 // "📝"
  category: string             // "content" | "structure" | "media" | "decoration"
  allowedChildren: string[]    // ["SUB_PROBLEM", "CHOICES", "FIGURE"]
}
```

### 6.2 분류 규칙

```typescript
interface ClassificationRule {
  id: string
  label: string                // 부여할 레이블
  priority: number             // 낮을수록 먼저 평가
  conditions: Condition[]      // AND 조건
  confidence: number           // 0.0~1.0
}

interface Condition {
  field: string                // StructuralFeatures 필드명
  operator: "eq" | "ne" | "gt" | "lt" | "gte" | "lte"
           | "contains" | "startsWith" | "matches"
           | "in" | "notIn"
  value: any
}
```

**예시 — 수학 참고서:**

```json
[
  {
    "id": "rule-page-header",
    "label": "PAGE_HEADER",
    "priority": 10,
    "conditions": [
      { "field": "regionTag", "operator": "eq", "value": "TOP" },
      { "field": "dominantFontSize", "operator": "lte", "value": 1000 },
      { "field": "paragraphCount", "operator": "lte", "value": 2 }
    ],
    "confidence": 0.9
  },
  {
    "id": "rule-problem",
    "label": "PROBLEM",
    "priority": 50,
    "conditions": [
      { "field": "hasNumberPrefix", "operator": "eq", "value": true },
      { "field": "numberPrefixPattern", "operator": "in", "value": ["arabic_dot", "arabic_bare"] },
      { "field": "dominantFontSize", "operator": "gte", "value": 1000 }
    ],
    "confidence": 0.85
  },
  {
    "id": "rule-choices",
    "label": "CHOICES",
    "priority": 60,
    "conditions": [
      { "field": "hasNumberPrefix", "operator": "eq", "value": true },
      { "field": "numberPrefixPattern", "operator": "eq", "value": "circled" },
      { "field": "textContent", "operator": "matches", "value": "^[①②③④⑤]" }
    ],
    "confidence": 0.9
  }
]
```

### 6.3 스키마 파일 구조

```
schemas/
├── common.schema.json           # 공통 (모든 과목에서 상속)
├── math-reference.schema.json   # 수학 참고서
├── math-textbook.schema.json    # 수학 교과서
├── korean-textbook.schema.json  # 국어 교과서
├── english-textbook.schema.json # 영어 교과서
└── exam-paper.schema.json       # 시험지
```

### 6.4 스키마 생성 워크플로우 (RuleSuggester)

새 교과서/과목에 대한 스키마를 만드는 3단계:

```
Step 1: 샘플 레이블링 (수동)
  ─ 2~3페이지에서 10~20개 노드 수동 레이블링

Step 2: 규칙 제안 (자동 역추론)
  ─ RuleSuggester가 같은 레이블 노드들의 공통 features 분석
  ─ 빈도 임계값(75%+) 이상인 features를 조건으로 규칙 생성
  ─ precision/recall 계산 + 오분류 하이라이트

Step 3: 반복 개선
  ─ 전체 적용 → 오분류 수동 수정 → RuleSuggester 재실행
  ─ 목표: Coverage >90%, Precision >85%, Manual Override <10%
  ─ 만족 시 스키마 저장 → 같은 교과서 시리즈에 재사용
```

**RuleSuggester 알고리즘:**

```
1. 같은 레이블 노드 그룹핑
   PROBLEM: [sn-010, sn-025, sn-040, ...]

2. 그룹별 features 교집합 분석
   PROBLEM:
     hasNumberPrefix = true       (5/5 = 100%)
     dominantFontSize >= 1000     (5/5 = 100%)
     regionTag = "MIDDLE"         (3/5 = 60%)  ← threshold 미달, 제외

3. threshold(75%) 이상인 features만 조건으로 채택

4. 전체 노드에 적용 → precision/recall 계산 → 오분류 보고
```

---

## 7. 증분 업데이트 (Re-extract & Merge)

### 7.1 문제

AST가 변경되면 SLA를 재추출해야 하지만, 사용자 수동 수정은 보존해야 한다.

### 7.2 노드 매칭 (4단계 폴백)

```
1차: sourceId 일치        → 동일 IDML 객체 (가장 확실)
2차: storyId + 프레임순서 → 같은 스토리의 같은 위치
3차: textFingerprint      → sha256(첫 200자) (구조 변경 시 폴백)
4차: Symmetry Check       → 삭제+신규 노드 간 유사도 비교 (문단 분할/합침 대응)
```

### 7.3 Symmetry Check (4차 폴백)

원본 인디자인에서 문단을 분할하거나 합치면 sourceId가 바뀌고 textFingerprint도 깨진다. 이 경우 1~3차 매칭 모두 실패하여 기존 노드는 "삭제", 새 노드는 "추가"로 처리된다.

**Symmetry Check**는 삭제 후보와 추가 후보를 교차 비교하여 "수정됨"을 감지한다:

```typescript
// merge/node-matcher.ts

interface SymmetryMatch {
  deletedNodeId: string
  newNodeId: string
  similarity: number        // 0.0~1.0
  matchType: "SPLIT" | "MERGED" | "MODIFIED"
}

function symmetryCheck(
  unmatchedDeleted: SemanticNode[],   // 1~3차 매칭 실패 → 삭제 후보
  unmatchedNew: SemanticNode[]        // 1~3차 매칭 실패 → 추가 후보
): SymmetryMatch[] {
  const matches: SymmetryMatch[] = []

  for (const deleted of unmatchedDeleted) {
    for (const added of unmatchedNew) {
      // 같은 페이지 + 유사 위치 (반경 3600 HWPUNIT = 0.5인치)
      if (deleted.features.pageNumber !== added.features.pageNumber) continue
      const posDist = Math.hypot(
        deleted.features.x - added.features.x,
        deleted.features.y - added.features.y
      )
      if (posDist > 36000) continue  // 5인치 이상 떨어지면 스킵

      // 텍스트 유사도 (Longest Common Subsequence 비율)
      const textSim = lcsRatio(deleted.features.textContent, added.features.textContent)

      // 스타일 유사도
      const styleSim = deleted.features.dominantParagraphStyle === added.features.dominantParagraphStyle ? 1.0 : 0.0

      // 가중 합산
      const similarity = textSim * 0.6 + styleSim * 0.2 + (1.0 - posDist / 36000) * 0.2

      if (similarity >= 0.5) {
        matches.push({
          deletedNodeId: deleted.id,
          newNodeId: added.id,
          similarity,
          matchType: inferMatchType(deleted, added)  // 텍스트 길이 비교로 SPLIT/MERGED/MODIFIED 판별
        })
      }
    }
  }

  // 최적 매칭 (Hungarian algorithm 또는 greedy 최고 유사도 우선)
  return resolveConflicts(matches)
}

function inferMatchType(deleted: SemanticNode, added: SemanticNode): "SPLIT" | "MERGED" | "MODIFIED" {
  const lenRatio = added.features.textLength / Math.max(deleted.features.textLength, 1)
  if (lenRatio < 0.6) return "SPLIT"     // 텍스트가 크게 줄었으면 분할된 것
  if (lenRatio > 1.5) return "MERGED"    // 텍스트가 크게 늘었으면 합쳐진 것
  return "MODIFIED"
}
```

**Symmetry Match 처리:**

| 유사도 | 동작 |
|--------|------|
| >= 0.8 | **자동 계승**: 삭제 노드의 `manualOverride`, 레이블, 메타데이터를 새 노드에 계승 |
| 0.5 ~ 0.8 | **사용자 확인**: "이 노드가 수정된 것 같습니다. 기존 레이블을 유지할까요?" |
| < 0.5 | **별개 처리**: 삭제 + 신규로 각각 처리 |

```
┌─ Symmetry Check 리뷰 ──────────────────────────────────────┐
│                                                              │
│  ⚠ 수정 감지: 3건                                           │
│                                                              │
│  1. sn-022 (삭제) ↔ sn-new-045 (신규)                       │
│     유형: SPLIT (문단 분할)                                   │
│     유사도: 72%                                               │
│     기존: PROBLEM (manualOverride) "1. 다음 이차방정식..."    │
│     신규: "1. 다음 이차방정식을 풀어라."                       │
│     [기존 레이블 계승] [새로 분류] [직접 수정]                │
│                                                              │
│  2. sn-023 (삭제) ↔ sn-new-046 (신규)                       │
│     유형: MERGED (문단 합침)                                  │
│     유사도: 85% → 자동 계승됨 ✓                               │
│                                                              │
└──────────────────────────────────────────────────────────────┘
```

### 7.4 머지 전략

| 상황 | 동작 |
|------|------|
| 매칭 성공 + `manualOverride` | 사용자 레이블·관계·메타데이터 보존, features만 갱신 |
| 매칭 성공 + 자동분류 | features 갱신 + 규칙 재평가로 label 재계산 |
| Symmetry Match (>=0.8) | 삭제 노드의 수동 정보를 새 노드에 자동 계승 |
| Symmetry Match (0.5~0.8) | 사용자에게 계승 여부 확인 |
| 매칭 실패 (새 노드) | 자동분류 적용 |
| 기존 노드 미매칭 (삭제) | `deletedNodes[]`로 이동 (복구 가능) |

### 7.5 에디터 연동

- AST 로드 시 `sourceAstHash` 비교 → 불일치 시 배너 표시
- 변경 내역을 diff처럼 리뷰 (레이블 변경, 신규, 삭제, **Symmetry Match**)
- 노드별 "새 레이블 수용" vs "기존 유지" 선택 가능
- Symmetry Match 노드는 별도 섹션에서 리뷰 (기존↔신규 텍스트 비교 표시)

---

## 8. SLA 출력 포맷

```json
{
  "version": "1.0.0",
  "schemaId": "math-reference-v1",
  "sourceAstHash": "sha256:abc123...",
  "createdAt": "2026-03-11T12:00:00Z",
  "modifiedAt": "2026-03-11T12:30:00Z",
  "mergeHistory": [],

  "nodes": [
    {
      "id": "sn-001",
      "astPath": "sections[0].blocks[0]",
      "nodeType": "FRAME",
      "label": "PAGE_HEADER",
      "confidence": 0.9,
      "appliedRule": "rule-page-header",
      "manualOverride": false,
      "features": {
        "pageNumber": 1,
        "x": 1440, "y": 720,
        "regionTag": "TOP",
        "storyId": "story_1",
        "textContent": "Ⅱ. 방정식과 부등식",
        "dominantFontSize": 900
      },
      "children": ["sn-001-p0"],
      "metadata": {}
    }
  ],

  "relations": [
    { "type": "PARENT_OF", "sourceId": "sn-010", "targetId": "sn-010-sub1" },
    { "type": "ANSWER_FOR", "sourceId": "sn-050", "targetId": "sn-010" },
    { "type": "CONTINUES_FROM", "sourceId": "sn-045", "targetId": "sn-030" }
  ],

  "deletedNodes": []
}
```

---

## 9. PPT 내보내기

### 9.1 파이프라인

```
SemanticLayer JSON
      ↓
SlideBreaker        시멘틱 노드 → 슬라이드 분할 (SECTION_TITLE 기준)
      ↓
SlideLayout         슬라이드 내 요소 배치 (레이블별 위치 규칙)
      ↓
PPTTemplate         레이블 조합 → 슬라이드 템플릿 매칭
      ↓
PPTStyleMapper      AST 스타일 → PPT 스타일 변환
      ↓
PPTRenderer         pptxgenjs → .pptx 파일 생성
```

### 9.2 슬라이드 분할 규칙

- `SECTION_TITLE` → 새 슬라이드 시작 (표지)
- `PROBLEM` + 하위 (`SUB_PROBLEM`, `CHOICES`) → 1 슬라이드
- `CONCEPT_BOX`, `FORMULA_BOX` → 독립 슬라이드
- `FIGURE` + `CAPTION` → 한 슬라이드에 묶음
- 콘텐츠 초과 시 자동 분할

### 9.3 스타일 단순화 레이어 (Style Clustering)

PPT는 인디자인이나 한글만큼 정교한 스타일 체계가 없다. 인디자인 문서는 1,000개+ 고유 스타일 조합을 가질 수 있지만, PPT는 표준 레이아웃 10여 개로 압축해야 한다.

**PPTStyleMapper에서 스타일 클러스터링:**

```typescript
// ppt/ppt-style-mapper.ts

/** PPT 표준 스타일 슬롯 (10개) */
type PPTStyleSlot =
  | "TITLE"            // 슬라이드 제목 (24~44pt, Bold)
  | "SUBTITLE"         // 부제목 (18~24pt)
  | "HEADING1"         // 대제목 (20~28pt, Bold)
  | "HEADING2"         // 소제목 (16~22pt, Bold)
  | "BODY"             // 본문 (10~14pt)
  | "BODY_SMALL"       // 작은 본문 (8~10pt)
  | "LIST_ITEM"        // 목록 항목 (10~14pt, Bullet/Number)
  | "CAPTION"          // 캡션 (8~10pt, Italic or Light)
  | "CODE"             // 코드/수식 (Monospace, 10~12pt)
  | "EMPHASIS"         // 강조 (Bold, Color accent)

interface StyleCluster {
  slot: PPTStyleSlot
  pptFontSize: number      // PPT 출력 크기 (pt)
  pptFontFamily: string
  pptBold: boolean
  pptItalic: boolean
  pptColor: string          // hex
  pptAlignment: string
  sourceStyles: string[]    // 이 슬롯에 매핑된 원본 스타일명 목록
}

/**
 * AST 스타일 1,000개+ → PPT 표준 슬롯 10개로 클러스터링
 *
 * 알고리즘:
 * 1. 시멘틱 레이블 기반 1차 매핑 (SECTION_TITLE → TITLE, BODY_TEXT → BODY)
 * 2. 폰트 크기 기반 2차 분류 (큰 것 → HEADING, 작은 것 → CAPTION)
 * 3. 속성 기반 3차 보정 (Bold → EMPHASIS, Monospace → CODE)
 * 4. 빈도 기반 대표값 선정 (같은 슬롯 내 가장 많이 쓰인 스타일이 대표)
 */
function clusterStyles(
  nodes: SemanticNode[],
  adapter: ASTAdapter
): StyleCluster[] {

  // 1차: 시멘틱 레이블 → PPT 슬롯 매핑
  const labelToSlot: Record<string, PPTStyleSlot> = {
    "SECTION_TITLE": "TITLE",
    "SUBSECTION_TITLE": "HEADING1",
    "HEADING": "HEADING2",
    "BODY_TEXT": "BODY",
    "PROBLEM": "BODY",
    "CHOICES": "LIST_ITEM",
    "CAPTION": "CAPTION",
    "EQUATION": "CODE",
    "FOOTNOTE": "BODY_SMALL",
    "SIDEBAR": "BODY_SMALL",
    "TIP": "EMPHASIS",
  }

  // 2차: 라벨 미매핑 스타일 → 폰트 크기로 분류
  // fontSize >= 2000 HWPUNIT (20pt) → HEADING1
  // fontSize >= 1400 (14pt) → BODY
  // fontSize < 1000 (10pt) → BODY_SMALL

  // 3차: 속성 보정
  // hasBoldText + 큰 폰트 → EMPHASIS 승격
  // monospace 폰트 → CODE

  // 4차: 클러스터별 대표값 (최빈 폰트 크기/패밀리)
  return clusters
}
```

**스타일 클러스터링 결과 예시:**

| PPT 슬롯 | 매핑된 원본 스타일 (예) | PPT 출력 |
|-----------|------------------------|----------|
| TITLE | "단원제목", "ChapterTitle", "Head-Large" | 36pt, Bold, #333 |
| HEADING1 | "소단원", "SubHead", "중제목" | 24pt, Bold, #444 |
| BODY | "본문", "NormalText", "문제본문" 등 847개 | 12pt, Regular, #000 |
| LIST_ITEM | "보기", "Choices", "답항" | 11pt, Regular, #000 |
| CAPTION | "그림설명", "CaptionSmall" | 9pt, Italic, #666 |
| CODE | "수식", "Equation" | 11pt, Monospace, #000 |

> **핵심**: 1,000개 스타일을 개별 매핑하지 않고, 시멘틱 레이블 + 폰트 크기 + 속성으로 10개 슬롯에 자동 클러스터링. 사용자는 슬롯별 출력 스타일만 커스터마이즈.

### 9.4 템플릿

```json
{
  "templateId": "problem-with-choices",
  "matchLabels": ["PROBLEM", "CHOICES"],
  "layout": {
    "PROBLEM": { "x": "5%", "y": "10%", "w": "90%", "h": "40%" },
    "CHOICES": { "x": "5%", "y": "55%", "w": "90%", "h": "40%" }
  },
  "slideSize": { "width": 10, "height": 7.5 }
}
```

---

## 10. Semantic 에디터 UI

### 10.1 레이아웃

```
┌─────────────────────────────────────────────────────────┐
│ [Playground] [Extract] [HWPX Converter] [◆ Semantic]    │
├──────────┬──────────────────────┬───────────────────────┤
│          │                      │                       │
│  노드    │    페이지 프리뷰     │   속성 패널           │
│  트리    │    (시멘틱 오버레이)  │                       │
│          │                      │   ┌─ 레이블 선택 ──┐  │
│  ● PAGE  │  ┌──────────────┐   │   │ [PAGE_HEADER ▼] │  │
│    HEADER│  │ PAGE_HEADER  │   │   └─────────────────┘  │
│  ● BODY  │  │ ░░░░░░░░░░░ │   │   Features:           │
│    TEXT  │  ├──────────────┤   │   - fontSize: 2000     │
│  ● PROBL │  │ PROBLEM  1.  │   │   - region: MIDDLE     │
│    EM    │  │ ├ (1)...     │   │   - story: story_5     │
│    ├ SUB │  │ └ ①②③④⑤    │   │                       │
│    └ CHO │  └──────────────┘   │   Relations:          │
│  ● TABLE│                      │   - PARENT_OF: sn-02  │
│          │  < page 1/24 >       │   Metadata:           │
│          │                      │   problemNumber: [1 ]  │
├──────────┴──────────────────────┴───────────────────────┤
│ Schema: [수학 참고서 v1 ▼]  Rules: 12 matched, 3 manual │
│ [Auto-classify] [Export JSON] [Export PPT] [Schema Edit] │
└─────────────────────────────────────────────────────────┘
```

### 10.2 핵심 인터랙션

| 동작 | 설명 |
|------|------|
| 스키마 선택 | 하단 드롭다운 → 규칙 재적용 |
| Auto-classify | 전체 자동 분류 실행 |
| 레이블 변경 | 드롭다운으로 수동 변경 → `manualOverride: true` |
| 관계 편집 | 드래그 or 선택으로 관계 추가/삭제 |
| 메타데이터 편집 | 과목별 커스텀 필드 (문제번호, 난이도 등) |
| 프리뷰 오버레이 | 페이지 위에 레이블별 색상 반투명 박스 (SVG) |
| Re-extract | AST 변경 감지 → 재추출 + 수동 보존 머지 |
| Export PPT | 시멘틱 기반 .pptx 내보내기 |

### 10.3 프리뷰 오버레이 렌더링

```tsx
<div className="relative">
  <img src={pagePreview} />
  <svg className="absolute inset-0">
    {nodes.filter(n => n.features.pageNumber === currentPage).map(node => (
      <rect
        x={toPixel(node.features.x)}
        y={toPixel(node.features.y)}
        width={toPixel(node.features.width)}
        height={toPixel(node.features.height)}
        fill={labelDef.color}
        fillOpacity={0.25}
        stroke={labelDef.color}
        onClick={() => selectNode(node.id)}
      />
    ))}
  </svg>
</div>
```

### 10.4 스키마 에디터 (모달)

레이블 추가/수정, 규칙 편집, 규칙 제안(RuleSuggester) UI를 제공. 기존 스키마 복제 → 커스텀 수정 → 저장.

---

## 11. 기술 스택 & 패키지 구조

### 11.1 분리 원칙

| 결정 | 근거 |
|------|------|
| Java JAR에 포함하지 않음 | JAR 비대화 방지, 프리미엄 기능 분리 |
| AST JSON을 인터페이스로 사용 | Java 의존성 제로, ASTAdapter 경계 |
| TypeScript 단일 스택 | React UI와 타입 공유, Tauri에서 직접 실행 |

### 11.2 패키지 구조

```
indesign-to-something/
├── src/main/java/...              # 기존 IDML→HWPX (변경 없음)
├── desktop/
│   ├── src/                       # React UI (기존 + Semantic 탭)
│   ├── src-tauri/                 # Rust bridge
│   └── package.json              # npm workspace
│
└── packages/
    └── semantic-layer/            # @its/semantic-layer
        ├── package.json
        ├── tsconfig.json
        ├── vitest.config.ts
        ├── src/
        │   ├── types.ts               # 전체 타입 정의
        │   ├── index.ts               # 공개 API
        │   ├── adapter/
        │   │   ├── types.ts            # PageInfo, BlockInfo, ...
        │   │   ├── ast-adapter.ts      # ASTAdapter 인터페이스
        │   │   └── ast-json-adapter.ts # AST JSON 파싱 (유일한 지점)
        │   ├── core/
        │   │   ├── feature-extractor.ts
        │   │   ├── rule-classifier.ts
        │   │   ├── relation-builder.ts
        │   │   ├── schema-loader.ts
        │   │   ├── rule-suggester.ts
        │   │   └── rule-validator.ts
        │   ├── merge/
        │   │   ├── node-matcher.ts
        │   │   └── merger.ts
        │   └── ppt/
        │       ├── slide-breaker.ts
        │       ├── slide-layout.ts
        │       ├── ppt-renderer.ts
        │       ├── ppt-template.ts
        │       └── ppt-style-mapper.ts
        ├── schemas/
        │   ├── common.schema.json
        │   └── math-reference.schema.json
        ├── templates/
        │   ├── default.ppt-template.json
        │   └── math-lecture.ppt-template.json
        └── __tests__/
```

### 11.3 의존성

```jsonc
// packages/semantic-layer/package.json
{
  "name": "@its/semantic-layer",
  "version": "0.1.0",
  "type": "module",
  "main": "dist/index.js",
  "types": "dist/index.d.ts",
  "scripts": {
    "build": "tsup src/index.ts --format esm,cjs --dts",
    "test": "vitest run"
  },
  "dependencies": {
    "pptxgenjs": "^3.12.0"
  },
  "devDependencies": {
    "tsup": "^8.0.0",
    "vitest": "^1.0.0",
    "typescript": "^5.5.0"
  }
}
```

### 11.4 프리미엄 분리

```typescript
// desktop/src/App.tsx — 옵셔널 dynamic import
let semanticModule: typeof import('@its/semantic-layer') | null = null
try {
  semanticModule = await import('@its/semantic-layer')
} catch {
  // 미설치 → Semantic 탭 비활성화
}
```

| 배포 방식 | 설명 |
|-----------|------|
| 개발 중 | npm workspace 로컬 링크 |
| 기본 배포 | `@its/semantic-layer` 미포함 → Semantic 탭 숨김 |
| 프리미엄 배포 | 포함 빌드 + 라이선스 키 검증 |

---

## 12. 구현 계획

### Phase 1: 패키지 셋업 + AST Adapter + Feature 추출

| # | 파일 | 내용 |
|---|------|------|
| 1 | `packages/semantic-layer/` | 패키지 초기화 (tsconfig, tsup, vitest) |
| 2 | `src/adapter/types.ts` | 어댑터 데이터 타입 |
| 3 | `src/adapter/ast-adapter.ts` | ASTAdapter 인터페이스 |
| 4 | `src/adapter/ast-json-adapter.ts` | AST JSON → 인터페이스 변환 |
| 5 | `src/types.ts` | SemanticNode, Schema 등 SLA 타입 |
| 6 | `src/core/feature-extractor.ts` | ASTAdapter → StructuralFeatures + Spatial Proximity |
| 7 | `src/core/schema-loader.ts` | 스키마 JSON 로드 + 상속 |
| 8 | `__tests__/` | 어댑터 매핑 + feature 추출 테스트 |
| 9 | `desktop/package.json` | workspace 설정 |

### Phase 2: 규칙 엔진 + 스키마 생성

| # | 파일 | 내용 |
|---|------|------|
| 1 | `src/core/rule-classifier.ts` | 규칙 평가 엔진 |
| 2 | `src/core/relation-builder.ts` | 스토리 기반 + 규칙 기반 + 공간 근접도 기반 관계 |
| 3 | `src/core/rule-suggester.ts` | 수동 레이블 → 규칙 역추론 |
| 4 | `src/core/rule-validator.ts` | precision/recall 계산 |
| 5 | `src/merge/node-matcher.ts` | Re-extract 노드 매칭 (4단계 + Symmetry Check) |
| 6 | `src/merge/merger.ts` | 증분 업데이트 머지 (Symmetry Match 계승 포함) |
| 7 | `schemas/common.schema.json` | 공통 스키마 |
| 8 | `schemas/math-reference.schema.json` | 수학 참고서 스키마 |

### Phase 3: 에디터 UI

| # | 파일 | 내용 |
|---|------|------|
| 1 | `desktop/src/stores/useSemanticStore.ts` | Zustand 상태 |
| 2 | `desktop/src/components/SemanticPage.tsx` | Semantic 탭 메인 |
| 3 | `SemanticTreePanel.tsx` | 노드 트리 |
| 4 | `SemanticPreviewPanel.tsx` | 프리뷰 + SVG 오버레이 |
| 5 | `SemanticDetailPanel.tsx` | 속성/관계/메타데이터 편집 |
| 6 | `SchemaEditorModal.tsx` | 스키마 편집 |
| 7 | `RuleSuggesterPanel.tsx` | 규칙 제안 UI |
| 8 | `ReextractReviewModal.tsx` | Re-extract diff 리뷰 |

### Phase 4: PPT 내보내기

| # | 파일 | 내용 |
|---|------|------|
| 1 | `src/ppt/slide-breaker.ts` | 시멘틱 → 슬라이드 분할 |
| 2 | `src/ppt/slide-layout.ts` | 요소 배치 |
| 3 | `src/ppt/ppt-template.ts` | 템플릿 매칭 |
| 4 | `src/ppt/ppt-style-mapper.ts` | 스타일 클러스터링 (1000+개 → 10 슬롯) |
| 5 | `src/ppt/ppt-renderer.ts` | pptxgenjs → .pptx |
| 6 | `templates/*.json` | 내장 템플릿 |
| 7 | `PPTExportModal.tsx` | 내보내기 UI |

### Phase 5: 통합 & 프리미엄 배포

| # | 내용 |
|---|------|
| 1 | Tauri 커맨드: SLA JSON 저장/로드, .pptx 저장, 스키마 CRUD |
| 2 | 프리미엄 분리: 옵셔널 import + 라이선스 키 검증 |
| 3 | 빌드 스크립트: 기본 빌드(SLA 미포함) / 프리미엄 빌드(SLA 포함) |

---

## 13. 검증

### AST Adapter
- [ ] ASTJsonAdapter: AST JSON 샘플에서 정확히 변환
- [ ] mock adapter로 feature-extractor 동작 확인
- [ ] AST 필드 변경 시 ast-json-adapter.ts만 수정하면 전체 통과

### Core Engine
- [ ] StructuralFeatures 40+ 필드 추출 정확도
- [ ] Spatial Proximity: 별도 프레임의 문제 번호 → 본문 PARENT_OF 관계 제안
- [ ] Visual Containment: AABB 겹침 기반 포함 관계 판정
- [ ] 수학 참고서 스키마 자동분류 precision/recall
- [ ] 스토리 기반 CONTINUES_FROM 관계 자동 생성
- [ ] RuleSuggester: 5개 수동 레이블 → 규칙 역추론

### Re-extract
- [ ] manualOverride 노드 보존
- [ ] 4단계 폴백 매칭 (sourceId → storyId → textFingerprint → Symmetry Check)
- [ ] Symmetry Check: 문단 분할/합침 시 유사도 0.8+ 자동 계승
- [ ] Symmetry Check: 유사도 0.5~0.8 사용자 확인 UI
- [ ] 삭제 노드 복구

### PPT Export
- [ ] SECTION_TITLE 기준 슬라이드 분할
- [ ] PROBLEM + SUB_PROBLEM + CHOICES 그루핑
- [ ] 스타일 클러스터링: 1,000+ AST 스타일 → 10개 PPT 슬롯 정확도
- [ ] 슬롯별 대표 스타일 선정 (최빈값 기반)
- [ ] pptxgenjs 생성 .pptx → PowerPoint 정상 오픈

### 에디터 UI
- [ ] 레이블 수동 변경 → JSON 반영
- [ ] 프리뷰 오버레이 좌표 정확도
- [ ] 스키마 변경 → 재분류 동작
- [ ] Export JSON / Export PPT 동작

### 프리미엄 분리
- [ ] `@its/semantic-layer` 미설치 시 Semantic 탭 숨김
- [ ] 포함 빌드 시 전체 기능 정상 동작
