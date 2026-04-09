# SPEC-018: 시멘틱 레이어 통합 (Java 백엔드 + TypeScript 프론트엔드)

> **주의**: 이 SPEC은 기존 시멘틱 레이어(`packages/semantic-layer`)와 SPEC-018 초안(Java 백엔드 기반 신규 추출기)을 **단일 시스템으로 통합**하는 것을 목표로 한다. 통합 후 SemanticLayer JSON 포맷이 캐노니컬 데이터 모델이 되고, Java 백엔드와 TypeScript 데스크탑은 같은 포맷을 읽고 쓴다.

## 배경 — 현재 두 시스템

### 시스템 A: TypeScript 시멘틱 레이어 (이미 구현, 데스크탑 통합 완료)

위치: `packages/semantic-layer/` + `desktop/src/components/Semantic*.tsx`

```
ASTDocument (JSON)
   │
   ├─ ASTJsonAdapter           ← AST JSON → 어댑터
   │
   ├─ extractFeatures()        ← 블록별 feature 벡터 (StructuralFeatures)
   │     - 위치/크기/zOrder/regionTag
   │     - 텍스트/폰트/스타일/패러그래프 수
   │     - 프레임 속성/콘텐츠 구성
   │     - 공간 근접도
   │
   ├─ classifyNodes(rules)     ← 활성 스키마의 룰을 매칭, label + confidence 부여
   │     룰: { field, operator, value, priority, confidence }
   │
   ├─ buildRelations()         ← CAPTION_FOR / ANSWER_FOR 등 관계 생성
   │
   └─ SemanticLayer JSON       ← 평탄한 노드 + 관계 + mergeHistory + deletedNodes
```

**스키마**:
- `common` (9 라벨): PAGE_HEADER/FOOTER, SECTION_TITLE, BODY_TEXT, FIGURE, CAPTION, TABLE, BACKGROUND, DECORATION
- `math-reference` (16 라벨): 대/중/소단원 제목, CONCEPT_BOX, FORMULA, EXAMPLE, PROBLEM/SUB_PROBLEM, CHOICES, SOLUTION, ANSWER, TIP_BOX, SIDEBAR

**UI**:
- Semantic 탭, 노드 트리, 비주얼/텍스트 프리뷰, 노드 상세
- 스키마 편집기, 룰 제안기(suggester), 검증
- JSON 입출력, PPTX 출력
- 머지(`mergeLayer`) — AST 재추출 후 라벨/수동 오버라이드 보존

**제약**:
- TypeScript에서만 동작 (CLI/배치 변환에서 자동 추출 불가)
- 룰이 단일 conditions 배열 (다중 detector + 신뢰도 누적 없음)
- heading 계층 트리 없음 (평탄한 리스트만)

### 시스템 B: SPEC-018 초안에서 제안한 Java 시멘틱 추출기

**미구현**. 다중 신호 detector chain, 6개 pass(BlockClassifier, ParagraphClassifier, HierarchyBuilder, FigureCaptionLinker, QuestionAggregator, ReadingOrderResolver), 출판사별 매핑 YAML, JSON 출력 등을 제안. 트리 구조(section > heading > body...)를 명시적으로 만든다.

## 통합 목표

1. **데이터 모델 단일화** — `SemanticLayer` JSON이 캐노니컬 포맷. Java 백엔드와 TypeScript 데스크탑이 같은 스키마를 따른다.
2. **Java 백엔드 1차 추출** — 변환 파이프라인의 Phase 3.5에서 자동으로 시멘틱 레이어를 만들고 `.semantic.json`으로 저장. CLI/배치/원격에서도 동작.
3. **TypeScript 프론트엔드 2차 편집** — 데스크탑은 백엔드가 만든 SemanticLayer를 로드해서 검증/수정/PPTX 출력. 또한 백엔드가 없는 경우(과거 호환) 그대로 자체 추출도 가능.
4. **룰 엔진 공유** — 같은 ClassificationRule JSON을 Java와 TypeScript가 똑같이 해석. 룰을 한 곳에서 정의하면 양쪽에서 동작.
5. **점진적 기능 보강** — 기존 9+16개 라벨/룰을 그대로 유지하면서, 다중 신호·heading 계층·출판사 매핑 같은 기능을 같은 스키마 포맷 안에서 확장.
6. **하위 호환** — 기존 데스크탑 워크플로우(브라우저에서 추출→편집→저장)는 그대로 동작.

## 비목표

- 기존 9+16개 라벨/룰 자체를 갈아엎지 않는다. 통합 후에도 그대로 동작해야 한다.
- 데스크탑 UI 재설계는 별도. 이 SPEC은 백엔드/포맷 통합에 집중.
- EPUB/HTML 등 새로운 출력기는 별도 SPEC.

## 통합 아키텍처

```
┌─────────────────────────────────────────────────────────────────────┐
│                       packages/semantic-schemas/                     │
│  (SSOT — Java/TypeScript 양쪽이 참조)                                │
│  ├── schemas/common.schema.json                                      │
│  ├── schemas/math-reference.schema.json                              │
│  ├── schemas/<publisher>.schema.json   ← 출판사 매핑 (신규)          │
│  └── docs/semantic-format.md                                         │
└─────────────────────────────────────────────────────────────────────┘
        │                                           │
        │                                           │
        ▼                                           ▼
┌──────────────────────┐                  ┌────────────────────────┐
│  Java backend        │                  │  TypeScript frontend   │
│  semantic/           │                  │  packages/             │
│                      │                  │    semantic-layer/     │
│  - ASTAdapter        │   같은 룰 JSON   │                        │
│  - FeatureExtractor  │ ◀──────────────▶ │  - ASTJsonAdapter      │
│  - RuleEngine        │   동일 결과      │  - extractFeatures     │
│  - RelationBuilder   │                  │  - classifyNodes       │
│  - LayerWriter       │                  │  - buildRelations      │
│                      │                  │  - mergeLayer          │
│  ↓                   │                  │  ↑                     │
│  .semantic.json      │ ◀──────────────▶ │  load/save             │
└──────────────────────┘                  └────────────────────────┘
        │                                           │
        ▼                                           ▼
   CLI / 배치                                Desktop GUI
   (변환과 함께                              (검증/편집/PPTX/머지)
    자동 생성)
```

### 데이터 흐름

#### A. 데스크탑 GUI 흐름 (현재 + 통합 후 모두 지원)

```
1. 사용자가 INDD 열기
2. 백엔드: IDML 추출 → AST → HWPX 변환
3. (통합 후) 백엔드: AST → SemanticLayer JSON 자동 추출
4. 데스크탑: AST + SemanticLayer 로드
5. 사용자: 스키마 변경, 라벨 수동 오버라이드, 머지
6. 데스크탑: SemanticLayer 저장 (수동 오버라이드 포함)
```

#### B. CLI 배치 흐름 (신규)

```
$ java -jar idml-to-hwpx-cli.jar \
    --convert in.idml out.hwpx \
    --extract-semantics \
    --semantic-schema schemas/math-reference.schema.json \
    --semantic-output out.semantic.json
```

## 통합 단계 (마일스톤)

### M1 — 스키마 패키지 분리 (공용 SSOT)

기존 `packages/semantic-layer/schemas/*.schema.json`을 **새 패키지** `packages/semantic-schemas/`로 분리.

- TypeScript 측은 import 경로만 갱신.
- Java 측이 같은 디렉토리를 클래스패스 리소스로 묶어 읽을 수 있게 한다 (Maven `<resources>`).
- 스키마 포맷 명세 문서: `docs/semantic-format.md` (현재 [packages/semantic-layer/src/types.ts](../../packages/semantic-layer/src/types.ts)의 타입을 그대로 명문화).

검증:
- [ ] 기존 데스크탑 추출이 동일하게 동작 (스키마 경로만 변경)
- [ ] Java에서 같은 JSON을 파싱할 수 있는 POJO 정의 완료

### M2 — Java 백엔드 추출기 (시스템 A 포팅)

`src/main/java/kr/dogfoot/hwpxlib/tool/idmlconverter/semantic/`에 다음을 구현:

```
semantic/
├── SemanticExtractor.java        ← 진입점 (extract(astDoc, schema) → SemanticLayer)
├── SemanticLayer.java            ← 캐노니컬 모델 POJO
├── SemanticNode.java
├── StructuralFeatures.java       ← TS의 StructuralFeatures 1:1 매핑
├── SemanticRelation.java
├── adapter/
│   └── ASTAdapter.java           ← Java AST를 추상화 (TS 어댑터와 동일 인터페이스)
├── extract/
│   └── FeatureExtractor.java     ← TS extractFeatures 포팅
├── classify/
│   ├── RuleEngine.java           ← TS classifyNodes 포팅
│   └── ConditionEvaluator.java   ← eq/ne/gt/lt/contains/matches/in/notIn
├── relate/
│   └── RelationBuilder.java      ← TS buildRelations 포팅
├── schema/
│   └── SchemaLoader.java         ← packages/semantic-schemas/ JSON 로더
└── io/
    ├── SemanticLayerWriter.java  ← JSON 직렬화 (TS와 호환)
    └── SemanticLayerReader.java
```

**핵심 원칙**: TypeScript 측 함수와 1:1로 동작이 같아야 한다. 같은 AST + 같은 스키마 → 같은 노드/라벨/관계.

검증:
- [ ] TypeScript와 Java가 같은 스키마/AST에 대해 동일한 SemanticLayer 생성 (대조 테스트)
- [ ] 기존 common/math-reference 스키마 재사용

### M3 — 변환 파이프라인 통합

[IDMLToHwpxConverter.java:211](../../src/main/java/kr/dogfoot/hwpxlib/tool/idmlconverter/IDMLToHwpxConverter.java#L211) — `ASTToHwpxConverter.convert()` 직전 또는 직후에 Phase 3.5 추가.

```java
// Phase 3.5: 시멘틱 추출 (옵션)
if (options.extractSemantics()) {
    SemanticSchema schema = SchemaLoader.load(options.semanticSchemaPath());
    SemanticLayer semanticLayer = SemanticExtractor.extract(astDoc, schema);
    SemanticLayerWriter.write(semanticLayer, options.semanticOutputPath());
}
```

CLI 옵션 ([ConverterCLI.java](../../src/main/java/kr/dogfoot/hwpxlib/tool/idmlconverter/ConverterCLI.java)):
- `--extract-semantics`
- `--semantic-schema <path>` — 기본: `common.schema.json`
- `--semantic-output <path>` — 기본: hwpx 옆 `.semantic.json`

데스크탑 통합:
- 기존 IDML → AST 추출 후, **자동으로 백엔드 시멘틱 추출도 호출**.
- 결과 `.semantic.json`을 데스크탑이 로드해서 첫 화면에 표시.
- 사용자가 추가 추출 버튼을 누르면 TypeScript 측 `loadFromAst`로 재추출 가능 (백엔드 결과 덮어씀).

검증:
- [ ] 변환 옵션 켜고 끄기 동작
- [ ] 데스크탑이 백엔드 결과를 자동 로드
- [ ] CLI 배치에서 .semantic.json 생성

### M4 — 기능 보강 (양쪽 동시 또는 스키마 차원에서)

같은 스키마 포맷 안에서 점진적으로 다음을 추가. **TypeScript와 Java가 같은 룰을 같은 방식으로 해석한다는 원칙 유지**.

#### M4.1 다중 신호 룰
현재 `conditions` 배열은 단순 AND. 다음을 추가:
```json
{
  "id": "rule-section-title",
  "label": "SECTION_TITLE",
  "anyOf": [
    { "conditions": [...], "weight": 1.0 },
    { "conditions": [...], "weight": 0.7 }
  ],
  "minWeight": 0.7,
  "confidence": 0.85
}
```

#### M4.2 출판사 스타일 매핑
```json
{
  "schemaId": "publisher.bisang",
  "extends": "math-reference",
  "styleMapping": {
    "01_단원제목":   { "label": "CHAPTER_TITLE", "confidence": 1.0 },
    "04_본문":       { "label": "BODY_TEXT", "confidence": 1.0 }
  }
}
```
스타일 매핑이 있으면 룰보다 우선 적용 (priority 100 같은 식으로).

#### M4.3 heading 계층 트리 (옵션 후처리)
SemanticLayer는 평탄한 리스트로 유지하되, **derived view**로 트리를 계산:
```json
{
  "tree": {
    "type": "document",
    "children": [
      { "type": "section", "title": "...", "page": 166, "nodeId": "sn-1" }
    ]
  }
}
```
SECTION_TITLE / SUBSECTION_TITLE / CHAPTER_TITLE 라벨이 붙은 노드를 기반으로 자동 계산. 데스크탑 트리 패널이 이 view를 사용.

#### M4.4 그림-캡션 결속 강화
`SpatialProximityFeatures`에 캡션 패턴(`"^(그림|사진|자료) ?\d+[-.]\d+"`) 매칭 추가. 현재 `CAPTION_FOR` 관계 룰이 거리만 보지만, 텍스트 패턴 가중치 추가.

### M5 — 검증 도구 + 출판사 스키마 첫 사례

- `--dump-style-names <out.csv>` CLI: AST의 모든 paragraph/character 스타일명을 빈도순 덤프 → 출판사 스키마 작성 시드
- 비상교육 중3과학 매핑 YAML/JSON 작성, 실제 적용
- 분류 결과 검증 (수동 샘플링) + 신뢰도 캘리브레이션

## 수정/신규 파일

### 신규
1. `packages/semantic-schemas/` — 스키마 SSOT 패키지
2. `packages/semantic-schemas/schemas/common.schema.json` (이전)
3. `packages/semantic-schemas/schemas/math-reference.schema.json` (이전)
4. `packages/semantic-schemas/docs/semantic-format.md` — JSON 포맷 명세
5. `src/main/java/kr/dogfoot/hwpxlib/tool/idmlconverter/semantic/` — Java 백엔드 추출기 (M2 구조)
6. `src/test/java/.../semantic/CrossLanguageParityTest.java` — TS↔Java 동작 일치 검증

### 변경
7. `packages/semantic-layer/package.json` — `semantic-schemas` 의존성 추가
8. `packages/semantic-layer/src/core/schema-loader.ts` — 새 패키지에서 import
9. `desktop/src/components/SemanticPage.tsx` — 백엔드 결과 자동 로드 로직 추가
10. `src/main/java/kr/dogfoot/hwpxlib/tool/idmlconverter/IDMLToHwpxConverter.java` — Phase 3.5
11. `src/main/java/kr/dogfoot/hwpxlib/tool/idmlconverter/ConvertOptions.java` — 시멘틱 옵션
12. `src/main/java/kr/dogfoot/hwpxlib/tool/idmlconverter/ConverterCLI.java` — CLI 플래그
13. `pom.xml` — JSON 라이브러리 (이미 Gson 사용 중) 확인
14. `desktop/src-tauri/src/commands/` — 백엔드 시멘틱 추출 트리거 커맨드 (옵션)

## 위험 요소

1. **TS↔Java 동작 불일치** — 같은 룰을 다르게 해석할 위험. 대조 테스트(parity test) 필수. 결정적 함수만 사용 (Math.round, 정렬 안정성 등 주의).
2. **AST 어댑터 표면적 차이** — TypeScript는 ASTJsonAdapter, Java는 직접 AST 객체를 본다. 같은 feature를 뽑으려면 어댑터 인터페이스를 명세화해야 함.
3. **현재 테스트 부재** — TypeScript 시멘틱 레이어에 대한 unit test가 거의 없어서 리팩토링 안전망이 약함. M1 진입 전 최소 골든 테스트 추가 권장.
4. **mergeLayer의 복잡도** — 머지/수동 오버라이드 보존은 TypeScript에만 있고 Java에는 없음. 백엔드는 1차 추출만, 머지는 데스크탑 책임으로 명확히 분리.
5. **스키마 패키지 분리 비용** — 단순 디렉토리 이동이지만 import 경로/빌드 설정이 흩어져 있을 수 있음. 일단 동작 확인 후 진행.

## 검증

- [ ] M1: 데스크탑 추출이 신규 패키지에서 스키마를 로드
- [ ] M2: TypeScript와 Java가 같은 AST + 같은 스키마에 대해 동일한 노드 ID/라벨/관계 생성
- [ ] M3: CLI에서 `--extract-semantics`로 .semantic.json 생성
- [ ] M3: 데스크탑이 백엔드 결과를 자동 로드, 사용자 편집 후 저장 가능
- [ ] M4.1: 다중 신호 룰이 양쪽에서 동작
- [ ] M4.2: 출판사 매핑이 우선 적용됨
- [ ] M5: 비상교육 중3과학에서 분류 정확도 측정 (≥80% 목표, 라벨별 confusion matrix)
- [ ] 기존 데스크탑 워크플로우(스키마 편집기, 룰 제안기, PPTX 출력)가 계속 동작

## 참고

- 현재 TS 타입 정의: [packages/semantic-layer/src/types.ts](../../packages/semantic-layer/src/types.ts)
- 현재 추출 로직: [packages/semantic-layer/src/core/feature-extractor.ts](../../packages/semantic-layer/src/core/feature-extractor.ts), [classifier](../../packages/semantic-layer/src/core/rule-classifier.ts)
- 현재 스키마: [common.schema.json](../../packages/semantic-layer/schemas/common.schema.json), [math-reference.schema.json](../../packages/semantic-layer/schemas/math-reference.schema.json)
- 데스크탑 통합: [SemanticPage.tsx](../../desktop/src/components/SemanticPage.tsx), [useSemanticStore.ts](../../desktop/src/stores/useSemanticStore.ts)
- 분기점 후보: [IDMLToHwpxConverter.java:211](../../src/main/java/kr/dogfoot/hwpxlib/tool/idmlconverter/IDMLToHwpxConverter.java#L211)
- AST 스키마: [docs/ast-schema.md](../ast-schema.md)
