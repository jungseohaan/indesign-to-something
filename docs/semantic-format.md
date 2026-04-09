# Semantic Layer 포맷 명세

시멘틱 레이어의 캐노니컬 JSON 포맷. TypeScript 시멘틱 레이어와 Java 백엔드 추출기가 같은 포맷을 읽고 쓴다.

> **Source of truth**: [packages/semantic-layer/src/types.ts](../packages/semantic-layer/src/types.ts)
> **Schemas**: [packages/semantic-schemas/schemas/](../packages/semantic-schemas/schemas/)
> **SPEC**: [SPEC-018](specs/SPEC-018-semantic-extraction.md)

## 개요

```
ASTDocument (IDML 변환 산출물)
   │
   │  + SemanticSchema (라벨 정의 + 룰)
   │
   ▼
SemanticLayer (이 문서의 대상)
```

`SemanticLayer`는 두 가지 입력의 곱이다:
- **AST**: 어떤 블록이 어디에 있는지 (구조)
- **Schema**: 어떤 의미 라벨이 있는지 + 어떤 조건일 때 어떤 라벨을 붙이는지 (분류 규칙)

## 1. SemanticSchema

```jsonc
{
  "schemaId": "common",
  "schemaName": "공통",
  "version": "1.0.0",
  "subject": "",                  // "수학", "과학" 등 (선택)
  "documentType": "",             // "교과서", "참고서" 등 (선택)
  "extends": "common",            // 다른 스키마 상속 (선택)

  "labels": [ LabelDef, ... ],
  "rules": [ ClassificationRule, ... ],
  "relationRules": [ RelationRule, ... ],
  "layoutHints": [ LayoutHint, ... ]
}
```

### LabelDef

```jsonc
{
  "id": "BODY_TEXT",                       // 고유 식별자 (대문자 + 언더스코어 권장)
  "name": "본문",                           // 사람이 읽는 이름
  "description": "일반 본문 텍스트",
  "color": "#333333",                      // UI 색상
  "icon": "",                              // (선택) 아이콘
  "category": "content",                   // "content" | "structure" | "media" | "decoration"
  "allowedChildren": ["CAPTION"]          // 자식으로 허용되는 라벨 ID들
}
```

### ClassificationRule

```jsonc
{
  "id": "rule-section-title",
  "label": "SECTION_TITLE",                // 매칭 시 부여할 라벨
  "priority": 20,                          // 높을수록 먼저 평가됨
  "conditions": [
    { "field": "regionTag", "operator": "eq", "value": "FULL_WIDTH" },
    { "field": "dominantFontSize", "operator": "gte", "value": 1400 },
    { "field": "paragraphCount", "operator": "lte", "value": 3 }
  ],
  "confidence": 0.8                        // 0..1
}
```

모든 conditions는 **AND**로 결합. 한 조건이라도 false면 룰이 매칭되지 않는다.

#### Operators

| operator | 의미 | value 타입 |
|---|---|---|
| `eq` | 같음 | any |
| `ne` | 다름 | any |
| `gt` | 초과 | number |
| `lt` | 미만 | number |
| `gte` | 이상 | number |
| `lte` | 이하 | number |
| `contains` | 부분 문자열 포함 | string |
| `startsWith` | 접두어 일치 | string |
| `matches` | 정규식 매칭 | string (regex) |
| `in` | 배열 멤버 | any[] |
| `notIn` | 배열 비멤버 | any[] |

### RelationRule

```jsonc
{
  "id": "rel-caption-figure",
  "type": "CAPTION_FOR",
  "sourceLabel": "CAPTION",
  "targetLabel": "FIGURE",
  "conditions": []                         // (선택) 추가 조건
}
```

`RelationType` 값:
`PARENT_OF`, `CAPTION_FOR`, `ANSWER_FOR`, `SOLUTION_FOR`, `CONTINUES_FROM`, `REFERENCES`

### LayoutHint

```jsonc
{
  "label": "PAGE_HEADER",
  "expectedRegions": ["TOP", "FULL_WIDTH"]
}
```

`RegionTag` 값: `TOP`, `MIDDLE`, `BOTTOM`, `LEFT`, `RIGHT`, `FULL_WIDTH`

## 2. SemanticLayer (출력)

```jsonc
{
  "version": "1.0.0",
  "schemaId": "common",
  "sourceAstHash": "sha256:...",           // AST 콘텐츠 해시 (재추출 매칭용)
  "previousAstHash": "sha256:...",         // (선택) 이전 추출 해시
  "createdAt": "2026-04-09T13:57:00Z",
  "modifiedAt": "2026-04-09T13:57:00Z",

  "mergeHistory": [ MergeHistoryEntry, ... ],
  "nodes": [ SemanticNode, ... ],
  "relations": [ SemanticRelation, ... ],
  "deletedNodes": [ DeletedNode, ... ]
}
```

### SemanticNode

```jsonc
{
  "id": "sn-uf5",
  "astPath": "sections[0].blocks[3]",      // AST 역추적 경로
  "nodeType": "FRAME",                     // FRAME | PARAGRAPH | TABLE | FIGURE | INLINE_OBJECT | EQUATION
  "features": StructuralFeatures,
  "label": "BODY_TEXT",                    // "UNKNOWN"이면 미분류
  "confidence": 0.85,
  "appliedRule": "rule-body-text",         // 매칭된 룰 ID, 없으면 null
  "manualOverride": false,                 // 사용자가 라벨을 수동으로 지정했는지
  "children": ["sn-...", ...],             // 자식 노드 ID들
  "storyId": "story-123",
  "metadata": {}                           // 자유 형식 (디버깅/확장용)
}
```

### StructuralFeatures (분류 입력 신호)

```jsonc
{
  // A. 위치 & 레이아웃
  "pageNumber": 5,
  "x": 720, "y": 1080, "width": 4800, "height": 600,
  "zOrder": 2,
  "regionTag": "TOP",
  "columnIndex": 0,
  "relativeYInPage": 0.05,

  // B. 스토리 & 텍스트 흐름
  "storyId": "story-123",
  "storyFrameCount": 3,
  "storyPageSpan": 2,
  "frameIndexInStory": 0,
  "isStoryStart": true,
  "isStoryEnd": false,

  // C. 텍스트 속성
  "textContent": "1. 우리 몸의 감각 기관",
  "textLength": 13,
  "paragraphCount": 1,
  "dominantFontSize": 1800,                // HWPUNIT (18pt = 1800)
  "maxFontSize": 1800,
  "dominantFontFamily": "함초롬돋움",
  "hasBoldText": true,
  "dominantAlignment": "center",
  "hasNumberPrefix": true,
  "numberPrefixPattern": "^1\\.\\s",
  "firstLineText": "1. 우리 몸의 감각 기관",

  // D. 스타일 참조
  "paragraphStyleNames": ["02_중제목"],
  "characterStyleNames": [],
  "dominantParagraphStyle": "02_중제목",

  // E. 프레임 속성
  "hasFill": false,
  "fillColor": null,
  "hasStroke": false,
  "isBackgroundOnly": false,
  "columnCount": 1,
  "rotationAngle": 0,

  // F. 콘텐츠 구성
  "hasTable": false,
  "hasImage": false,
  "hasEquation": false,
  "hasInlineFrame": false,
  "inlineObjectCount": 0,
  "blockType": "TEXT_FRAME_BLOCK",         // TEXT_FRAME_BLOCK | TABLE | FIGURE

  // G. 공간 근접도
  "spatial": {
    "nearestContentNodeId": "sn-uf6",
    "nearestContentDistance": 240,
    "overlappingNodeIds": [],
    "isVisuallyContainedBy": null,
    "visualContainmentRatio": 0
  }
}
```

### SemanticRelation

```jsonc
{
  "type": "CAPTION_FOR",
  "sourceId": "sn-uf8",
  "targetId": "sn-uf7",
  "confidence": 0.9
}
```

### MergeHistoryEntry, DeletedNode, NodeFingerprint

AST 재추출 시 사용자의 수동 오버라이드와 라벨을 보존하기 위한 머지 메타데이터. 자세한 의미는 [`packages/semantic-layer/src/merge/`](../packages/semantic-layer/src/merge/) 참조.

```jsonc
// MergeHistoryEntry
{
  "timestamp": "2026-04-09T13:57:00Z",
  "previousHash": "sha256:...",
  "stats": {
    "matched": 412,
    "manualPreserved": 18,
    "reclassified": 35,
    "added": 3,
    "deleted": 1,
    "symmetryMatched": 2
  }
}
```

## 3. 룰 적용 우선순위

1. `manualOverride: true` 노드는 절대 변경되지 않음.
2. `priority` 내림차순으로 룰 평가.
3. 같은 priority 내에서는 정의 순서.
4. 첫 매칭 룰이 노드의 `label`과 `confidence`를 결정. 후속 룰은 평가하지 않음.
5. 어떤 룰도 매칭되지 않으면 `label = "UNKNOWN"`.

## 4. TypeScript / Java 동작 일치 (Parity)

같은 AST + 같은 스키마 → 같은 SemanticLayer (mergeHistory/createdAt 등 시간성 필드 제외).

다음 항목은 양 언어 구현에서 **동일하게 결정적**이어야 한다:

- 노드 ID 생성 (`sn-<sourceId>` 패턴)
- conditions 평가 순서 및 결과
- priority 정렬 (안정적 정렬)
- spatial 거리 계산 (Manhattan? Euclidean? 정확한 공식 명시)
- 텍스트 정규화 (공백/줄바꿈 처리)
- 정규식 엔진 (ECMAScript vs Java — `matches` operator는 양쪽 모두 부분 매칭으로 통일)

## 5. 사용 예

### TypeScript

```ts
import { extractFeatures, classifyNodes, buildRelations, SchemaLoader } from "@its/semantic-layer";
import commonSchema from "@its/semantic-schemas/schemas/common.schema.json";

const adapter = new ASTJsonAdapter(astJson);
const loader = new SchemaLoader();
loader.add(commonSchema);
const schema = loader.get("common")!;

const nodes = extractFeatures(adapter);
const classified = classifyNodes(nodes, schema.rules);
const relations = buildRelations(classified, schema.relationRules);

const layer: SemanticLayer = {
  version: "1.0.0",
  schemaId: schema.schemaId,
  sourceAstHash: adapter.getDocumentHash(),
  createdAt: new Date().toISOString(),
  modifiedAt: new Date().toISOString(),
  mergeHistory: [],
  nodes: classified,
  relations,
  deletedNodes: [],
};
```

### Java (M2 이후)

```java
SemanticSchema schema = SchemaLoader.loadResource("semantic-schemas/common.schema.json");
SemanticLayer layer = SemanticExtractor.extract(astDoc, schema);
SemanticLayerWriter.writeJson(layer, Paths.get("out.semantic.json"));
```

## 6. 변경 정책

이 포맷의 변경은 **양쪽 구현에 동시에 반영**되어야 한다. 변경 종류:

- **호환 추가** (필드 추가, 새 operator): minor 버전 업
- **호환 변경** (의미 변경, 필드 삭제, default 변경): major 버전 업, mergeHistory에 변환 단계 명시

`SemanticLayer.version`이 1.0.0인 동안에는 위 명세를 따른다.
