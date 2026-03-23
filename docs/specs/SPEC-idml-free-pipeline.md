# SPEC: IDML-Free 변환 파이프라인

## 문제

현재 파이프라인은 IDML + resolved.json 이중 소스를 사용하여:
- 좌표 변환이 5+ 단계에서 발생 → 좌표 버그 빈발
- IDML 좌표(spread 기준)와 resolved 좌표(page 기준) 단위 불일치
- z-order/레이어 순서가 IDML과 InDesign DOM에서 다름
- Java 벡터 래스터화가 느리고 품질 낮음 (84초 → ExtendScript 15초)
- Stage1~4 파이프라인이 복잡하고 디버그 어려움

## 목표

IDML을 제거하고 resolved.json + 렌더 PNG만으로 변환. 좌표 변환 1회.

## 원칙

0. IDML은 변환 파이프라인에서 사용하지 않음
1. 그래픽, 이미지, 벡터, 효과 텍스트, 프레임은 InDesign ExtendScript 추출 우선
2. Java 렌더링 금지 (fallback에서도 안 함, 경고만 출력)
3. 모든 좌표/크기는 InDesign에서 추출 (page-relative, points)
4. AST에는 최종 HWPUNIT 좌표만 저장
5. HWPX 내보내기는 AST의 최종 좌표만 사용

## resolved.json 보강 (ExtendScript)

### 현재 → 보강

| 데이터 | 현재 소스 | 보강 후 소스 |
|--------|-----------|-------------|
| Story 텍스트 | IDML + resolved | resolved only |
| 인라인 객체 위치 | IDML CharacterRun | resolved runs에 `inline_anchor` 타입 |
| TextFrame 체인 | IDML PreviousTextFrame/NextTextFrame | resolved textFrame에 previousFrameId/nextFrameId |
| Z-order | IDML Spread XML 순서 | resolved pageItems/textFrames에 zOrder |
| Group 중첩 | IDML Group XML | resolved pageItems에 childIds + clipContent |
| 테이블 셀 내용 | IDML Story XML | resolved stories.tables.cells[] |
| 좌표 단위 | IDML=points(spread), resolved=mm | resolved=points(page-relative) 통일 |

### 보강 상세

#### 1. 인라인 객체 마커
```json
{
  "type": "inline_anchor",
  "anchoredObjectId": "6023",
  "anchoredObjectType": "TextFrame"
}
```
Story의 runs 배열에 텍스트 런과 함께 인라인 마커 삽입. U+FFFC 위치에서 분할.

#### 2. TextFrame 보강
```json
{
  "id": "5941",
  "storyId": "5900",
  "pageIndex": 0,
  "zOrder": 5,
  "isInline": false,
  "previousFrameId": null,
  "nextFrameId": "6100",
  "geometricBounds": [0, 189.9, 284.3, 530.1],
  "fillColor": "#EAF6FD",
  "strokeColor": null,
  "opacity": 100,
  "cornerRadius": 0,
  "rotationAngle": 0
}
```

#### 3. 테이블 셀 내용
```json
{
  "id": "33094",
  "bounds": [50.2, 67.0, 130.5, 210.3],
  "cells": [{
    "row": 0, "col": 0,
    "paragraphs": [{ "runs": [{ "text": "문화 향유", ... }] }],
    "fillColor": "#FFFFFF",
    "strokeWeights": { "top": 0, "bottom": 0.5, "left": 0, "right": 0 }
  }]
}
```

#### 4. Z-order
`page.allPageItems` 순서 = 시각적 스태킹 순서. 인덱스를 zOrder로 사용.

#### 5. 좌표 통일
모든 좌표를 page-relative points로 출력:
```javascript
bounds[0] -= page.bounds[0];
bounds[1] -= page.bounds[1];
```

## 새 파이프라인

```
.indd → [ExtendScript] → resolved.json + 페이지 PNG
                              ↓
                     ResolvedDataReader (보강)
                              ↓
                    ResolvedToASTBuilder (NEW)
                     ├─ Phase 1: 페이지/섹션 빌드
                     ├─ Phase 2: TextFrame 분류 (인라인/플로팅)
                     ├─ Phase 3: Story→단락→런 변환
                     ├─ Phase 4: 테이블 셀 변환
                     ├─ Phase 5: Figure/Image 배치 (PNG 로드)
                     └─ Phase 6: 페이지 배경 PNG 주입
                              ↓
                    ASTDocument (최종 HWPUNIT 좌표)
                              ↓
                    FlatToHwpxConverter → .hwpx
```

### 좌표 흐름 (1회 변환)
```
ExtendScript: page-relative points
     ↓ (resolved.json에 저장)
ResolvedDataReader: double[] (points)
     ↓ (pointsToHwpunits() 1회)
ResolvedToASTBuilder: long (HWPUNIT)
     ↓ (변환 없음)
FlatToHwpxConverter: 그대로 사용
     ↓
HWPX XML 출력
```

## ResolvedToASTBuilder 상세

### Phase 1: 페이지/섹션 빌드
- resolved pages[] → ASTSection + ASTPageLayout
- 좌표: page bounds/margins → pointsToHwpunits()

### Phase 2: TextFrame 분류
- `isInline=true` → 부모 Story의 단락에 인라인 객체로 삽입
- `isInline=false` → ASTTextFrameBlock으로 섹션에 추가
- 스레드 체인: previousFrameId/nextFrameId로 같은 Story의 프레임 연결
- paragraphStart/End로 단락 분배 (기존 ResolvedFrameDistributor 로직 유지)

### Phase 3: Story→단락→런 변환
- resolved story.paragraphs → ASTParagraph
- resolved runs → ASTTextRun (텍스트), ASTInlineObject (inline_anchor)
- BT 수식 폰트 변환: BTFontEquationConverter 유지
- 폰트 매핑: FontMapper 유지 (config 기반)

### Phase 4: 테이블 셀 변환
- resolved tables.cells → ASTTable + ASTTableRow + ASTTableCell
- 셀 내 paragraphs → Phase 3과 동일한 런 변환
- 테이블 좌표: resolved table.bounds → pointsToHwpunits()

### Phase 5: Figure/Image 배치
- 렌더 PNG 파일이 있으면 로드 → ASTFigure
- PNG 없으면 **경고만 출력** (Java 렌더링 금지)
- 좌표: resolved pageItem.bounds → pointsToHwpunits()

### Phase 6: 페이지 배경 PNG 주입
- exportPageBackgrounds() 결과 → ASTFigure (BEHIND_TEXT, z=0)

## 삭제 대상

### Java 클래스 삭제
- `idml/` 패키지 전체 (IDMLLoader, IDMLDocument, IDMLSpread, IDMLPage, IDMLStory, ...)
- `normalizer/Stage1_Flatten`, `Stage2_InlineDetect`, `Stage3_CollapseInlines`
- `normalizer/ASTPageProcessor` (ResolvedToASTBuilder로 대체)
- `normalizer/ASTStoryConverter` (Phase 3으로 통합)
- `normalizer/ASTRunConverter` (Phase 3으로 통합)
- `normalizer/ASTInlineObjectBuilder` (Phase 3으로 통합)
- `normalizer/ASTFigureBuilder` (Java 래스터화 제거)
- `normalizer/ASTTableConverter` (Phase 4로 대체)
- `normalizer/IDMLNormalizer` (ResolvedToASTBuilder로 대체)
- `resolved/ResolvedMerger` (불필요)
- `resolved/ResolvedFrameDistributor` (Phase 2에 통합)
- `IDMLToHwpxConverter` → `ResolvedToHwpxConverter`로 리네임

### 유지 대상
- `ast/` 패키지 전체 (AST 구조는 유지)
- `converter/ASTToHwpxConverter` (또는 `FlatToHwpxConverter`)
- `converter/HwpxParagraphBuilder`, `HwpxTextBoxBuilder`, `HwpxTableBuilder`, `HwpxImageBuilder`
- `converter/FontMapper`, `FontRegistry`, `CharPrBuilder`
- `converter/CoordinateConverter.pointsToHwpunits()` (유일한 좌표 변환)
- `ConversionConfig`, `ConvertOptions`
- `BTFontEquationConverter`

## 구현 순서

### Step 1: ExtendScript 보강
1. z-order 수집 (page.allPageItems 순서)
2. 인라인 객체 마커 (inline_anchor)
3. TextFrame thread chain (previous/next)
4. 테이블 셀 내용
5. 좌표 page-relative points 통일
6. IDML 내보내기 제거

### Step 2: ResolvedDataReader 보강
1. 새 필드 파싱 (zOrder, isInline, cells, threadChain)
2. normalizeToPoints() 제거 (이미 points)

### Step 3: ResolvedToASTBuilder 구현
1. Phase 1-6 구현
2. 기존 로직 중 필요한 부분 이식 (BT수식, 폰트매핑)

### Step 4: 통합 테스트
1. 기존 테스트 문서로 비교 변환
2. 좌표 정확성 검증

### Step 5: 기존 코드 제거
1. IDML 관련 클래스 삭제
2. CLI/Desktop 업데이트

## 검증

- [ ] 빌드 성공
- [ ] 중3영어교과서 u1 변환 비교
- [ ] 중3-1국어(박영민) u1 변환 비교
- [ ] 중3-1국어(박현숙) u3-2 변환 비교
- [ ] 좌표 정확성 (테이블 y좌표 포함)
- [ ] 폰트 매핑 유지
- [ ] 공백 장평 축소 유지
- [ ] 배경 이미지 z-order 정상
