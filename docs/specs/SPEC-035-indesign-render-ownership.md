# SPEC-035: IDML Render Ownership

> 상태: In Progress
> 원칙: 예외 금지. 페이지별 패치 금지. source ownership으로만 해결한다.

---

## 문제

IDML -> HWPX 변환은 두 세계를 섞는다.

- InDesign PNG: 시각 충실도가 높다.
- HWPX Text/Table/Shape: 편집성과 검색성이 높다.

이 둘이 같은 source를 동시에 소유하면 다음 문제가 반복된다.

- 텍스트 중복
- 배경/도형 누락
- 사진/차트가 배경에 가림
- inline/floating 뒤집힘
- z-depth 회귀

따라서 변환의 핵심은 "어떻게 보이는가"가 아니라 "누가 무엇을 소유하는가"다.

---

## 목표

1. 모든 source object의 ownership을 한 곳에서 결정한다.
2. 후속 단계는 plan을 실행만 한다.
3. 같은 source object는 visible output을 하나만 가진다.
4. 텍스트는 기본적으로 HWPX가 소유한다.
5. 시각 객체는 4층 layer로만 판단한다.
6. 예외를 추가하지 않는다.

---

## Stage

| Stage | 이름 | 책임 | 금지 |
|---|---|---|---|
| 0 | Input Prepare | IDML/resolved/style/page/master 인덱스 생성 | 객체 배치 결정 |
| 1 | Ownership Planner | 모든 객체의 text/visual ownership, placement, layer 결정 | AST 생성 |
| 2 | Text Builder | plan에 따라 HWPX 텍스트 생성 | PNG 배치 재판정 |
| 3 | Visual Builder | plan에 따라 PNG/vector/shell/background 배치 | 텍스트 소유권 재판정 |
| 4 | Validate | wrap, single-line, 최종 layer, 중복 invariant 검증 | 새 ownership 생성 |

legacy Phase는 이 Stage 구조로 이관한다. 이관 전까지 legacy Phase는 plan 실행 브리지 역할만 한다.

### Phase 6/7 폐기 계획

Phase 6 `BackgroundInjector`와 Phase 7 `RenderableFramePlacer`는 최종 구조에서 존재하지 않는다.
두 클래스의 책임은 Stage 3 `VisualBuilder` 아래의 plan executor로 흡수한다.

폐기 원칙:

- `ResolvedToASTBuilder`는 Phase 6/7 클래스를 직접 호출하지 않는다.
- 모든 visible visual은 `ObjectPlan`의 `visualAction`, `placement`, `visualLayer`, `zOrder`를 실행한 결과여야 한다.
- crop/page-intersection/spread-overflow는 ownership을 재판정하지 않는 순수 배치 함수여야 한다.
- executor-local duplicate key, child coverage set은 최종적으로 `ObjectPlan` 또는 Stage 4 invariant로 대체한다.
- Phase 7의 “Phase 6에서 이미 처리됨” 같은 후행 보정은 제거한다. 같은 source의 중복은 Stage 1 plan에서 금지한다.

이관 순서:

1. Stage 3 `VisualBuilder`를 단일 진입점으로 만들고 Phase 6/7 직접 호출을 상위 파이프라인에서 제거한다.
2. Phase 7의 spread/page intersection 배치를 `VisualBuilder` executor로 옮긴다. (진행 중: spread-overflow는 Stage 3 helper로 분리 완료)
3. Phase 6의 PNG load/crop/layer/zOrder 계산을 `VisualPlacementExecutor`와 `VisualCropper`로 옮긴다.
4. `computeChildOfGroupSuppression`, `computeInlineCoverageSuppression`을 Stage 1 ownership refinement로 옮긴다.
5. Phase 6/7 중복 suppression bridge를 삭제한다. (late floating suppress set은 삭제 완료)
6. `BackgroundInjector` 클래스를 삭제한다. (`RenderableFramePlacer`는 삭제 완료)

현재 이관 상태:

- `ResolvedToASTBuilder`의 visual 배치는 Stage 3 `VisualBuilder` 단일 진입점으로 들어간다.
- `VisualPlacementResolver`가 Phase 6/7의 plan 권위 suppress를 공통 판정한다.
- Stage 3 `VisualBuilder`는 더 이상 `RenderableFramePlacer`(Phase 7)를 호출하지 않는다.
  `RenderableFramePlacer` 클래스도 삭제했다.
- Phase 7에 남아 있던 spread-overflow-only 배치는 Stage 3 `VisualOverflowPlacer`로 분리했다.
  주 페이지와 교차하지 않는 객체라도 스프레드 기준 인접 페이지에 보이는 조각이 있으면
  `SKIP_OUTSIDE_PAGE` 전에 `PLACE_OUTSIDE_PAGE_OVERFLOW`로 배치한다.
  주 페이지와 교차하는 객체의 인접 페이지 조각도 `BackgroundInjector` 내부 재판정 없이
  같은 helper를 통해 crop/place만 수행한다.
- 빈 parent PNG export에서 사라지는 `GraphicLine` 보강은 Stage 3 `VisualSyntheticLinePlacer`로
  분리했다. 이 helper는 ownership을 새로 판단하지 않고, 이미 허용된 visual pass에서 누락된
  자식 선분 픽셀만 복원한다.
- Phase 6 crop/image 준비 결과는 Stage 3 `PreparedVisualImage` 값 객체로 묶었다. 다음 단계에서는
  이 객체를 `VisualCropper` 반환 모델로 사용하여 crop/image mutation을 executor 밖으로 이동한다.
- PNG header-only dimension read는 Stage 3 `VisualPngHeader`로 분리했다. image decode 여부는
  executor가 아니라 image preparation 단계의 책임으로 옮긴다.
- Stage 3 `VisualCropper`를 도입하고 PNG decode/encode, paper-like fill knockout,
  white-stroke pixel inversion을 이동했다. 아직 alpha/page crop 좌표 계산은 legacy executor에
  남아 있으며, 다음 단계에서 cropper의 반환 모델로 옮긴다.
- alpha crop의 픽셀 경계 탐색과 이미지 crop/encode는 `VisualCropper.alphaCrop`으로 이동했다.
  executor는 crop result의 픽셀 사각형을 원본 좌표계 bounds 갱신에만 사용한다.
- page/cropSource crop의 픽셀 clamp, subimage, encode는 `VisualCropper.pageCrop`으로 이동했다.
  crop 사각형 산출도 `VisualCropper.pageCropPlan`과 `PageCropPlan`으로 이동했다.
  executor는 plan의 픽셀 사각형과 strip override를 `PreparedVisualImage`에 적용만 한다.
- master/page-edge strip의 alpha/color column run 탐색은 `VisualCropper.edgeAlphaRun`으로 이동했다.
  executor는 strip crop 적용 여부와 source 정책 판단만 유지한다.
- Stage 3 `VisualPlacementExecutor`를 도입했다. `ASTFigure` / `ASTTextFrameBlock` visible output
  생성과 section 삽입은 이 executor가 수행하고, `BackgroundInjector`는 아직 geometry/zOrder/fromGroup
  산출 후 executor를 호출하는 bridge 역할을 한다.
- Stage 3 `VisualPlacementPlan`을 도입했다. executor는 긴 인자 목록 대신 placement plan을
  받아 AST를 물질화한다. 아직 plan 산출은 `BackgroundInjector` bridge에 남아 있으며, 다음 단계에서
  zOrder/fromGroup 산출을 이 plan builder로 옮긴다.
- Phase 7의 배치 전 suppress 조건은 삭제했다. Stage 3 배치 후 중복 보정은 더 이상 실행하지 않는다.
- Phase 6의 초기 suppress 조건 일부는 `VisualPlacementResolver.phase6InitialRejection` /
  `phase6DisposedRejection`으로 이동했다.
- Phase 6의 `shouldKeepVisualLabelTextEditable`, `shouldSkipByOwnership`,
  child policy suppress는 `phase6LegacyOwnershipRejection`에 격리한다.
  `ObjectPlan`이 있는 객체에는 legacy ownership fallback을 적용하지 않는다.
- legacy `phase6PlacedIds` 이름과 late floating suppress API를 제거했다.
  실제 처리 완료 상태는 `ResolvedBuildContext.markRenderedVisualHandled` /
  `renderedItemDispositions`만 소유한다.
- Stage 2.5 refinement에서 `DROP_VISUAL`로 확정된 id는 ObjectPlan에만 반영한다.
- child-of-group refinement는 실제 `RenderedGroup`이 있는 자식만 DROP_VISUAL 후보로 본다.
  source id만 있고 렌더 후보가 없는 항목은 Stage 3 visible 배치와 무관하므로 plan drop 대상에 넣지 않는다.
- child-of-group 보호 shell(`editable label shell`, concept diagram shell 등)은 Stage 2.5에서
  선제 suppress하지 않는다. 보존되어야 하는 visual은 Stage 3 실행기가 실제 배치한 뒤 중복을 막는다.
- inline coverage 보호 shell도 Stage 2.5에서 선제 suppress하지 않는다. `DROP_VISUAL`로 확정된
  실제 중복만 ObjectPlan에 반영하고, 보존 visual은 Stage 3 실행 결과가 권위를 가진다.
- 이미지 source를 포함한 text shell은 callout/outline shell로 보지 않는다. 내부 콘텐츠 이미지의
  polygon/stroke를 컨테이너 외곽선 근거로 세면 parent shell이 `CONTAINER_OUTLINE` 전면층으로 올라가
  콘텐츠 이미지를 가린다. 이런 shell은 `CONTAINER_BACKDROP`으로 내려가야 한다.
- late floating suppress reason 리포트와 `markRenderedVisualPlacedForLateFloating` bridge는 삭제했다.
  `SKIP_NATIVE_FILL_ABSORBED` 같은 true suppress는 Stage 3 decision log로만 남는다.
- 아직 남은 배지/자식/inline coverage 정책은 Stage 1 ownership refinement로 옮긴 뒤,
  Phase 6 실행기에서는 plan 실행만 남겨야 한다.

각 단계 회귀 게이트:

- `mvn -pl converter -am -DskipTests package`
- badge regression: `scripts/badge_regression/check_badges.py`
- visual smoke: 최근 이슈 페이지의 HWPX 내부 이미지/텍스트 조건 확인
  - page 45: `스스로 질문하기` 라벨이 본문과 과머지되지 않음
  - page 49: `생각 열기`, `활동 안내`, `소단원`이 누락되지 않음
  - page 53/56: anchored table/heading flow가 원본 순서를 유지함
  - page 62: top-left `작품 감상` 그래픽의 두 시각 조각이 함께 유지됨
  - page 64: 중앙 콘텐츠 이미지가 text shell/backdrop보다 앞에 유지됨

---

## 핵심 3원칙

### 1. 텍스트는 기본적으로 HWPX가 소유한다

검색/편집 가치가 있는 텍스트는 HWPX 텍스트다.

PNG가 텍스트까지 소유할 수 있는 것은 단순 위치 표식뿐이다.

허용되는 complete PNG 표식:

- `가/나/다/라/마/바`
- `ㄱ/ㄴ/ㄷ/ㄹ/ㅁ/ㅂ/ㅅ/ㅇ/ㅈ/ㅊ/ㅌ`
- `1/2/3`, `01/02/03`
- 체크박스, 선택지 번호처럼 의미 편집 대상이 아닌 표식

HWPX 텍스트로 유지하는 것:

- 제목, 본문, 문항, 출처
- 단원명, 소단원명, 차례/목차성 제목
- 개념어, 활동명, 표/박스 라벨
- `주장`, `이유 1`, `근거 1`
- `최근 사회·문화적 맥락`
- `쟁점 분석`, `사안이 발생한 사회·문화적 맥락`

판단 기준은 문자열 예외가 아니다. "편집/검색 가치가 있는가"가 기준이다.

`visual_label_indesign_png`처럼 텍스트가 박힌 완성형 PNG가 있더라도,
editable/searchable 의미 텍스트라면 PNG가 텍스트를 소유하지 않는다.

- 텍스트: `OWNED_BY_HWPX_TEXT`
- 텍스트가 박힌 complete PNG: `DROP_VISUAL`
- 별도 text-hidden shell이 있으면 shell만 `PLACE_TEXT_SHELL`로 남긴다.

PNG가 텍스트까지 소유할 수 있는 라벨은 atomic marker뿐이다.
atomic marker는 공백/줄바꿈/단어가 없는 단일 위치 표식이어야 한다.
두 글자 이상이거나 단어/구/제목/단원명으로 읽히면 항상 HWPX 텍스트다.

### 1.1 Contained TF merge gate

TextFrame이 다른 TextFrame의 bounds 안에 들어 있어도 포함 관계만으로 머지하지 않는다.
contained TF merge는 빈칸 위에 얹힌 짧은 답안/번호/표식처럼 한 줄의 단순 텍스트일 때만 허용한다.

허용:

- `1`, `01`, `가`, `ㄱ` 같은 atomic marker
- 공백/줄바꿈이 없는 아주 짧은 답안 텍스트

금지:

- 제목, 활동명, 라벨, 본문성 문장
- 공백 또는 줄바꿈이 있는 문자열
- bullet/화살표/장식 기호가 붙은 구문
- parent TF와 시각적으로 같은 영역에 있을 뿐인 독립 안내문/라벨

예: `이 단원에서 알고 싶은 내용을 스스로 질문해 보자.`와
`스스로 질문하기` 라벨은 bounds 관계가 있어도 머지하지 않는다.

### 1.2 Inline semantic label group gate

인라인 Group 안에 editable TextFrame이 여러 개 있어도 곧바로 본문 문단에 머지하지 않는다.

본문 inline으로 분해할 수 있는 것은 단순 위치 표식/선택지 표식뿐이다.

허용:

- `가/나/다/라`, `ㄱ/ㄴ/ㄷ`, `1/2/3`, `01/02/03`처럼 각 TF가 atomic marker인 경우
- 작은 선택지 번호, 체크박스 번호, 낱글자 배지처럼 본문 의미가 아니라 위치 표식인 경우

금지:

- `스스로` + `질문하기`처럼 두 개 이상의 TF가 합쳐져 독립 활동 라벨이 되는 경우
- `활동 안내`, `질문하기`, `생각 열기`처럼 사용자가 읽는 의미 라벨
- 다중 TF를 outer inline text frame 하나로 감싸 본문 첫머리에 붙이는 변환

이 경우 Stage 1/2는 각 TF를 원래 좌표의 editable floating text로 보존하고, Stage 3는 해당 Group을 본문 paragraph에 inline item으로 삽입하지 않는다.

editable TF가 없더라도 여러 visual 조각으로 구성된 compact `inline_graphic_only` Group이
독립 의미 라벨처럼 동작하면 본문 TF에 머지하지 않는다.

- 해당 Group은 `PLACE_FLOATING_PNG`로 원본 page 좌표에 배치한다.
- 같은 source의 page_object 복제본은 `DROP_VISUAL`로 제거한다.
- 작은 bullet/번호/낱글자 atomic marker는 이 규칙에 포함하지 않는다.

### 1.3 Placed PDF의 텍스트 성격

InDesign에 배치된 PDF는 원본 시각 객체로 그대로 유지한다.

PDF 내부에 selectable text layer가 있더라도, converter가 PDF 내부 텍스트를
별도 HWPX TextFrame으로 임의 추출하지 않는다. PDF는 하나의 placed visual source이며,
PDF 내부 텍스트를 제거한 배경 PNG를 만들거나 PDF 내부 글자를 HWPX TF로 overlay하지 않는다.

다만 PDF 내부에서 선택 가능한 문자열은 "텍스트 성격"을 가진다.
따라서 PDF에서 보이는 제목/단원명/본문성 문구를 complete PNG atomic marker 판단의 근거로
사용하면 안 된다.

- placed PDF 자체: 원본 PDF visual을 유지
- PDF 내부 selectable title/body text: semantic text 성격으로 인식
- converter 동작: PDF 내부 텍스트를 HWPX TF로 재생성하지 않음
- 금지: PDF 내부 제목 문구를 단순 표식 PNG로 일반화하는 정책

즉 `나를 깨우는, 문학`, `문학의 본질과 미적 기능`,
`문학의 인식적·윤리적 기능`처럼 PDF에서 선택되는 문구는 텍스트 성격이지만,
source가 placed PDF뿐인 경우에는 PDF visual이 그대로 소유한다.

### 1.4 Numbered side-head flow table

큰 번호가 왼쪽에 서고, 오른쪽 위에 소제목/라벨, 오른쪽 아래에 본문 문장이 놓이는
활동 머리표는 독립 floating TF들의 우연한 겹침이 아니라 하나의 흐름 단위다.

예:

- 왼쪽: `1`, `2`, `01` 같은 큰 번호 표식
- 오른쪽 위: `작품의 시적 발상` 같은 짧은 head/label
- 오른쪽 아래: 해당 활동을 설명하는 본문 문장

정책:

- Stage 1은 source table/story 구조를 보고 `SideHeadFlowPlan`을 만든다.
- Stage 2/Table Builder는 이 plan을 실행해 borderless 2-column flow table을 만든다.
- 왼쪽 marker cell은 head row와 body row를 `rowSpan=2`로 span한다.
- 오른쪽 첫 행은 head/label, 오른쪽 둘째 행은 body paragraph를 소유한다.
- marker/head/body를 각각 독립 floating object로 남기지 않는다.

허용되는 판단 신호:

- source table이 1열 구조로 추출되었고 앞 두 행이 각각 단일 셀이다.
- 첫 행 첫 단락이 큰 숫자 run으로 시작하고, 그 뒤에 head text 또는 inline head object가 있다.
- 둘째 행에 본문성 텍스트가 있다.
- 같은 story/table source에서 온 구조적 관계다.

금지:

- page 번호, 문구, object id, 절대 좌표 기반으로 side-head를 판정
- Stage 4에서 plan 없이 1열 table을 2열 table로 뒤집기
- marker와 label/body를 PNG/TF 양쪽으로 중복 visible 출력

현재 legacy bridge:

- `SideHeadFlowPlanner`가 Stage 1에서 plan을 만든다.
- `TableBuilder`가 ASTTable 생성 직후 해당 sourceId의 plan을 실행한다.
- `NumberedSideHeadTableNormalizer`는 임시 executor bridge이다.
  plan이 없는 structural fallback을 수행하면 `SIDE_HEAD_FLOW_UNPLANNED_BRIDGE`
  warning을 남겨 다음 migration 대상임을 드러낸다.
- 최종적으로는 Table Builder가 처음부터 plan 기반 2열 rowSpan table을 생성하고,
  Stage 4 bridge는 제거한다.

### 2. 시각 객체는 4층만 쓴다

사람이 판단하는 정책 layer는 아래 4개뿐이다.

`BACKGROUND < DECORATION < CONTENT < TEXT`

| Layer | 소유 대상 |
|---|---|
| `BACKGROUND` | 페이지 배경, 큰 영역 바탕, 큰 컨테이너 바탕 |
| `DECORATION` | 라벨 배경, 말풍선 껍데기, 외곽선, 구분선, 마스크 |
| `CONTENT` | 사진, 차트, QR, 삽화, 완성형 표식 PNG |
| `TEXT` | HWPX가 소유하는 editable/searchable 텍스트 |

구현상 세부 layer가 필요해도 반드시 이 4층 중 하나로 귀속한다.

### 3. 한 source object는 한 번만 visible output이 된다

금지되는 중복:

- 부모 PNG와 자식 PNG 동시 표시
- complete PNG와 HWPX TF 동시 표시
- inline PNG와 floating PNG 동시 표시
- shell PNG와 fallback PNG 동시 표시
- 같은 file/bounds/source의 중복 배치

같은 source가 두 개로 보이면 코드가 아니라 ownership 정책이 틀린 것이다.

---

## ObjectPlan

Stage 1은 모든 visible 후보에 대해 `ObjectPlan`을 만든다.

필수 필드:

- `sourceObjectIds`
- `textAction`
- `visualAction`
- `placement`
- `visualLayer`
- `zOrder`
- `reason`

`textAction`:

| 값 | 의미 |
|---|---|
| `OWNED_BY_HWPX_TEXT` | HWPX 텍스트가 소유 |
| `OWNED_BY_PNG` | PNG가 텍스트 픽셀까지 소유 |
| `HIDDEN_SEMANTIC` | 화면은 PNG, 숨김 텍스트만 생성 |
| `DROP_TEXT` | 텍스트 생성 없음 |

`visualAction`:

| 값 | 의미 |
|---|---|
| `PLACE_INLINE_PNG` | inline PNG 배치 |
| `PLACE_FLOATING_PNG` | floating PNG 배치 |
| `PLACE_TEXT_SHELL` | HWPX 텍스트 뒤의 visual shell 배치 |
| `ABSORB_TEXT_STYLE` | 밑줄/음영/라벨 배경 등 HWPX 텍스트 또는 drawText 속성으로 흡수 |
| `PLACE_TABLE_STYLE` | table/cell 속성으로 흡수 |
| `DROP_VISUAL` | visual 생성 없음 |

---

## Layer Mapping

구현 layer는 4층 정책으로 해석한다.

| 구현 layer | 정책 layer |
|---|---|
| `PAGE_BACKGROUND` | `BACKGROUND` |
| `CONTAINER_BACKDROP` | `BACKGROUND` |
| `TEXT_CARD_BACKDROP` | `DECORATION` |
| `LABEL_BACKDROP` | `DECORATION` |
| `CONTAINER_OUTLINE` | `DECORATION` |
| `FOREGROUND_MASK` | `DECORATION` |
| `CONTENT_VISUAL` | `CONTENT` |
| HWPX Text | `TEXT` |

기본 순서:

`BACKGROUND < DECORATION < CONTENT < TEXT`

HWPX에는 `BEHIND_TEXT`와 `IN_FRONT_OF_TEXT` 평면 차이가 있다.

평면 매핑:

- `BACKGROUND`: `BEHIND_TEXT`
- `DECORATION`: 구현 layer별로 분리
  - `TEXT_CARD_BACKDROP`: `BEHIND_TEXT`
  - `LABEL_BACKDROP`: `BEHIND_TEXT`
  - `CONTAINER_OUTLINE`, `FOREGROUND_MASK`: `IN_FRONT_OF_TEXT`
- `CONTENT`: `IN_FRONT_OF_TEXT`
- `TEXT`: HWPX text

원본 zOrder는 같은 정책 layer와 같은 HWPX 평면 안에서만 세부 순서로 사용한다.

---

## 판정 순서

모든 객체는 아래 순서로만 판단한다.

1. 텍스트인가?
   - 편집/검색 가치가 있으면 `OWNED_BY_HWPX_TEXT`
   - 단순 위치 표식이면 `OWNED_BY_PNG`

2. 텍스트 뒤의 모양인가?
   - 라벨 배경, 말풍선, 외곽선, 구분선이면 `DECORATION`
   - `Paper` fill이라도 editable TF와 강한 bbox 관계가 있으면 페이지 배경이 아니라 텍스트 카드 shell이다.

3. 실제 콘텐츠 이미지인가?
   - 사진, 차트, QR, 삽화, 그래프면 `CONTENT`

4. 큰 바탕인가?
   - 페이지/영역 바탕이면 `BACKGROUND`

5. 이미 다른 plan이 소유했는가?
   - 그렇다면 `DROP_TEXT` 또는 `DROP_VISUAL`

이 순서를 우회하는 예외는 만들지 않는다.

---

## Legacy Phase Bridge 규칙

legacy Phase가 남아 있는 동안에도 ObjectPlan이 최종 판단이다.

- `placement=INLINE`, `textAction=OWNED_BY_HWPX_TEXT`, `visualAction=DROP_VISUAL`인 TextFrame은
  Phase 2에서 floating fallback으로 재배치하지 않는다.
- parentId가 없는 inline TF라도 ObjectPlan이 inline text 소유를 명시하면 Phase 3 inline story 흐름이 소유한다.
- parentless inline fallback은 ObjectPlan이 없거나, ObjectPlan이 명시적으로 floating 배치를 요구하는 경우에만 허용한다.
- anchored table plan이 있는 TextFrame은 Y-gap/wrap split으로 여러 TextFrameBlock으로 쪼개지 않는다.
- anchored table plan이 있는 TextFrame은 page-relative 원본 frame bounds를 보존한다. sibling visual shell이나
  composed-line 기반 보정이 anchor flow의 기준 frame을 다시 확장/축소하면 안 된다.
- Phase 2/3/6/7은 inline/floating 여부를 새로 판정하지 않고 plan을 실행한다.
- outside-parent/custom-anchor 검사도 ObjectPlan보다 우선하지 않는다.
  `placement=INLINE`이고 `visualAction=PLACE_INLINE_PNG` 또는 `PLACE_TEXT_SHELL`이면
  Phase 3 story 흐름 안에서 처리한다.
- duplicate suppression은 HWPX 텍스트를 포함한 inline parent PNG의 drop 결정을 먼저 반영한 뒤 실행한다.
  나중에 drop될 inline parent를 기준으로 같은 source의 `visual_label_text_hidden_shell`을 먼저 drop하면,
  흰색/밝은 editable 라벨 텍스트가 배경 shell 없이 남아 누락처럼 보인다.

---

## Editable Label Shell

검색/편집 가치가 있는 짧은 라벨은 텍스트와 시각을 분리한다.

- 텍스트: `OWNED_BY_HWPX_TEXT`
- 배경/껍데기: 우선 `ABSORB_TEXT_STYLE`, 불가능할 때만 `PLACE_TEXT_SHELL`

원칙:

- 라벨 배경이 단순 fill/stroke/corner로 표현 가능한 Rectangle/Polygon/Oval이고,
  라벨 TF와 같은 그룹 안에서 충분히 겹치며 회전/전단이 거의 없으면 HWPX drawText의
  fill/stroke/corner 속성으로 흡수한다.
- drawText로 흡수된 라벨/텍스트 셸은 텍스트와 그래픽 속성을 모두 보존해야 한다.
  텍스트는 원본 run의 font family/style/size/color/tracking/scale을 공유 스타일 경로로 받고,
  셸은 원본 shape type, fill, stroke, stroke tint/weight, corner radius를 함께 전달한다.
- 문자 `FillTint`는 글자색의 일부다. resolved 캐시가 tint를 제공하지 못하면 IDML story의
  CharacterStyleRange에서 `FillTint`를 보충하고, TF/drawText/inline shell 모두 같은
  텍스트 스타일 경로에서 tint가 적용된 색을 사용한다.
- drawTextize 실행은 inline/floating 여부와 무관하게 하나의 공통 composer를 통한다.
  placement/wrap/anchor/position만 caller가 정하고, line/fill/image brush, drawText/subList,
  paragraph/table 삽입, rounded rectangle geometry는 공통 경로가 처리한다.
- fill이 있다고 stroke를 생략하지 않는다. InDesign의 채움과 외곽선은 독립 속성이므로
  HWPX drawText 셸에서도 독립적으로 적용한다.
- 라벨 앵커 자체가 inline Rectangle/Polygon/Oval이고 child TF를 품은 경우에도 같은 규칙을 쓴다.
  shell 실행 단계는 parent shape의 fill/stroke/corner를 HWPX inline text frame 속성으로 반드시 전달한다.
- `ABSORB_TEXT_STYLE`이 선택된 visual source는 별도 PNG로 배치하지 않는다.
- `주장`, `이유 1`, `근거 1`처럼 구조 표식은 editable 텍스트로 유지하되,
  라운드 사각형 배경은 drawText 도형 속성으로 흡수하는 것이 기본이다.
- shell PNG에는 텍스트 픽셀이 들어가면 안 된다.
- extractor는 source object 묶음 기준으로 visual-only shell을 export한다.
- Java는 큰 PNG를 crop하지 않는다.
- Java는 여러 PNG 조각을 합성하지 않는다.
- shell과 fallback PNG는 동시에 살아남을 수 없다.

`PLACE_TEXT_SHELL`은 아래 경우에만 쓴다.

- 회전/기울임/복합 경계선처럼 HWPX drawText 도형 속성으로 표현하기 어려운 라벨 배경
- 여러 visual source가 하나의 라벨 껍데기를 만들고, extractor가 텍스트 없는 shell PNG를
  안정적으로 export한 경우

실행 규칙:

- floating `PLACE_TEXT_SHELL`도 `ASTFigure`가 아니라 `ASTTextFrameBlock(imageFill + drawText)`로 실행한다.
- shell source가 소유한 editable child TF는 별도 visible TF로 남기지 않는다.
- shell PNG와 child TF가 동시에 보이면 source ownership 위반이다.

## Text Card Backdrop

`Paper` fill 도형은 자동으로 page/container background가 아니다.

원본에서 흰 도형이 텍스트 카드를 만드는 경우:

- 도형은 `TEXT_CARD_BACKDROP`이다.
- `visualAction=PLACE_TEXT_SHELL`이다.
- 정책 layer는 `DECORATION`이다.
- HWPX 평면은 `BEHIND_TEXT`다.
- 큰 페이지/스프레드 배경보다는 위, editable HWPX text보다는 뒤에 배치한다.

판정 기준:

- 같은 page의 editable/searchable TextFrame과 bbox가 강하게 겹친다.
- 도형이 TF를 감싸거나, TF와 거의 같은 카드 footprint를 가진다.
- 도형은 page 전체/대형 영역 배경이 아니다.
- 도형은 `Paper` fill이어도 원본에서 카드 shell 역할이면 visible output이다.

금지:

- `Paper`라는 색 이름만으로 `BACKGROUND`으로 확정하지 않는다.
- 흰 도형이 흰 페이지 위에서 안 보인다는 이유로 임의 stroke를 추가하지 않는다.
- 특정 페이지/문구/좌표로 카드 여부를 판단하지 않는다.

## Master Page Running Title

마스터 페이지의 러닝 타이틀도 본문과 같은 ownership 원칙을 따른다.

- 마스터 TextFrame 인스턴스가 검색/편집 가치가 있는 텍스트를 가진 경우 텍스트는 HWPX TF가 소유한다.
- 같은 master source를 포함한 complete master PNG는 텍스트 픽셀을 포함하므로 `DROP_VISUAL`이다.
- extractor가 같은 source 묶음의 text-free `_fallback_` master PNG를 제공하면 그 fallback만 visual을 소유한다.
- complete master PNG와 HWPX 마스터 TF가 동시에 보이면 ownership 오류다.
- complete master PNG와 fallback master PNG가 동시에 보이면 ownership 오류다.

---

## Anchored Table Flow

InDesign Story 안의 table marker가 실제 표를 가리키는 경우, source concept는 독립 page table이 아니라
"이 TextFrame의 본문 흐름 중 이 문단 뒤에 표가 온다"이다.

대표 구조:

- owner TextFrame의 Story 본문에 table marker가 있다.
- marker table 또는 wrapper table의 셀 안에 nested TextFrame/table이 있다.
- nested table의 시각 위치는 owner TextFrame의 본문 흐름 안에서 anchor로 결정된다.

Ownership:

- owner TextFrame의 본문 단락은 `OWNED_BY_HWPX_TEXT`다.
- nested table의 텍스트/셀/선은 HWPX table이 소유한다.
- wrapper table, nested table, anchored child TextFrame은 하나의 anchored table plan으로 묶는다.
- 같은 anchored source를 floating table, inline table, PNG fallback으로 동시에 배치하지 않는다.

배치 원칙:

- owner TextFrame은 하나의 HWPX 글상자다.
- anchored table은 owner 글상자 내부 flow의 inline table로 삽입한다.
- anchored table로 소비된 marker-only paragraph는 별도 빈 HWPX 문단으로 남기지 않는다.
- inline table만 담는 carrier paragraph는 본문/기본 문단 행간을 상속하지 않는다. carrier paragraph는 0 margin + table height fixed line spacing을 사용하고, 같은 높이의 `linesegarray`를 명시해 line box 자체가 table 높이를 소유하게 한다.
- table-only carrier의 table object는 `treatAsChar=true`, `flowWithText=true`, `allowOverlap=false`, `affectLSpacing=true`, `horzRelTo=COLUMN`, `vertRelTo=PARA`, `vertAlign=TOP`로 배치한다. 즉 table을 강제 좌표로 끼우지 않고 carrier line top에 붙여 다음 paragraph가 table bottom 뒤에서 흐르게 한다.
- table 앞/뒤 단락을 독립 floating TextFrame으로 분리하지 않는다.
- `A-TF → B-table → C-TF`처럼 보이는 경우라도 원본 Story가 하나의 TextFrame flow이면
  A/B/C를 하나의 글상자 내부 순서로 유지한다.
- HWPX가 nested table을 별도 `<hp:tbl>`로 표현하더라도 그것은 owner 글상자 내부의 inline table이어야 한다.

금지되는 보정:

- anchored table을 본문 글상자 밖의 floating table로 승격
- table 앞/뒤 paragraph를 개별 TF로 물리 분리
- Y-gap split, wrap split, composed-line split으로 owner TextFrame을 여러 block으로 쪼개기
- table bbox에 맞추기 위해 owner TextFrame bounds를 sibling shell 기준으로 재확장
- table을 좌표 기반으로 끼워 맞추고 Story 순서를 무시하는 배치
- HWPX 렌더러의 nested inline table flow 문제를 피하려고 owner 글상자 내부를 `text rows → table row → text rows` 같은 물리 행으로 강제 분리

좌표 정책:

- owner TextFrame의 page-relative bounds가 anchor flow의 기준이다.
- table-only owner TextFrame이 있는 IDML Table은 그 owner TextFrame이 placement owner다.
  resolved table bounds는 paragraph/story 기반 추정값일 수 있으므로 owner frame보다 우선하지 않는다.
- table-only owner bounds를 적용할 때는 x/y뿐 아니라 outer width/height도 함께 적용하고,
  column/row 크기는 그 외곽 크기에 비례해 정규화한다.
- composedLine bounds는 paragraph diagnostics와 line-level 보조 정보로만 사용한다.
- composedLine bounds가 필요할 때도 owner TextFrame의 page-relative 좌표계로 환산해야 한다.
- coordinate normalization 차이 때문에 `geometricBounds`와 `pageRelativeBounds`를 직접 섞어
  page origin을 추정하지 않는다.

예외가 필요해 보이면 table ownership plan이 틀린 것이다. 페이지/문구/좌표로 table 위치를 보정하지 않는다.

---

## Visual Backdrop Cluster

텍스트가 없는 여러 visual source가 하나의 컨테이너 배경을 만들면 extractor에서 원본 IDML 객체 묶음으로 다시 export한다.

대표 형태:

- 칠판/보드 배경 이미지 + 중앙 흰 Paper 패널 + 상단 리본
- 큰 활동 박스 배경 + 내부 마스크/패널 + 장식 띠

원칙:

- cluster PNG는 `PLACE_FLOATING_PNG`, `CONTAINER_BACKDROP`이다.
- cluster는 텍스트를 포함하지 않는다.
- 사진, 차트, QR, 삽화처럼 의미 있는 콘텐츠 이미지는 cluster에 포함하지 않는다.
- cluster가 소유한 source object의 개별 shape/image PNG는 `DROP_VISUAL`이다.
- Java는 기존 PNG를 crop하거나 합성하지 않는다. extractor가 원본 visual-only 객체 묶음을 export한다.

---

## Container Face + Shadow

흰 컨테이너 본체와 컬러 채움 박스가 거의 같은 footprint로 겹쳐 그림자 효과를 만드는 경우,
두 객체는 z-depth로 따로 맞추지 않고 하나의 컨테이너 시각 단위로 소유한다.

대표 형태:

- 흰 Paper 패널 위에 같은 크기의 파랑/보라/회색 채움 박스가 살짝 밀려 그림자처럼 보이는 경우
- 본문/기사/자료 박스에서 shadow source가 본문 텍스트를 덮는 경우

조건:

- 두 객체 모두 텍스트가 없는 visual source다.
- 하나는 Paper-like face이고, 다른 하나는 컬러 fill을 가진 container shadow다.
- 둘의 bounds 면적과 중심점이 거의 같고, 서로 대부분 겹친다.
- line/outline-only shape, label shell, foreground mask는 대상이 아니다.

결과:

- face와 shadow는 하나의 combined PNG로 배치한다.
- combined PNG는 `PLACE_FLOATING_PNG`, `CONTAINER_BACKDROP`이다.
- shadow를 먼저 그리고 face를 나중에 그린 visual order를 보존한다.
- combined plan은 face와 shadow의 `sourceObjectIds`를 모두 소유한다.
- 개별 face/shadow PNG 중 shadow child는 `DROP_VISUAL`이다.
- 후속 단계는 이 케이스를 z-depth 재조정으로 다시 풀지 않는다.

이 정책은 텍스트를 포함한 mixed group 통이미지를 허용한다는 뜻이 아니다.
텍스트는 계속 HWPX가 소유하고, face+shadow visual만 하나의 배경 PNG가 소유한다.

---

## Complete Marker PNG

단순 표식은 완성형 PNG로 갈 수 있다.

조건:

- 텍스트 자체가 편집 대상이 아니다.
- 도형과 글자가 하나의 위치 표식으로 동작한다.
- inline/floating 여부와 관계없이 같은 기준을 쓴다.

결과:

- `textAction=OWNED_BY_PNG`
- `visualAction=PLACE_INLINE_PNG` 또는 `PLACE_FLOATING_PNG`
- companion TF나 shell은 `DROP_TEXT` / `DROP_VISUAL`

---

## Content Image

사진, 차트, QR, 삽화, 그래프는 항상 `CONTENT`다.

주의:

- `image_group_text_hidden`이어도 실제 사진/차트/삽화이면 `CONTENT`
- 큰 배경이나 shell로 강등하지 않는다.
- 부모 group이 `DROP_VISUAL`이어도 child content image가 있으면 child는 살아야 한다.

---

## 금지

- 페이지 번호별 예외
- 문구별 예외
- 좌표별 예외
- bounds/occlusion만으로 중복 방지
- 후속 Phase에서 ownership 재해석
- inline/floating 후속 전환
- 임시 상태 필드 추가
- `textOwner=hwpx_tf` PNG를 complete PNG처럼 배치

예외가 필요해 보이면 source ownership 또는 4층 layer 정책을 고친다.

---

## Invariant

Stage 4는 아래를 검증한다.

- 같은 `sourceObjectId`의 visible visual 중복 금지
- 같은 TextFrame의 `OWNED_BY_PNG` / `OWNED_BY_HWPX_TEXT` 동시 소유 금지
- 같은 source의 inline/floating 동시 visible 금지
- 같은 file/page/bounds의 visible PNG 중복 금지
- `PLACE_TEXT_SHELL`은 소유 TF보다 뒤
- `TEXT_CARD_BACKDROP`은 관련 editable TF보다 뒤, page/container background보다 위
- `BACKGROUND`은 `CONTENT`과 `TEXT`를 덮지 않음
- `DECORATION`은 의도된 장식/외곽선/마스크 역할만 수행

검증 실패는 새 예외가 아니라 planner 오류로 본다.

---

## 회귀 검수

ownership 변경 후 최소 확인:

- 단순 표식 PNG 중복/위치
- editable 라벨의 shell + HWPX 텍스트 쌍
- 사진/차트/QR이 배경에 가리지 않는지
- 컨테이너 외곽선/구분선 누락 여부
- 큰 배경이 콘텐츠 위로 올라오지 않는지
- 같은 source가 PNG/TF로 중복되지 않는지

검수는 "보이는가"뿐 아니라 "왜 그 plan으로 배치되었는가"를 decision log로 확인한다.

---

## 구현 순서

1. `ObjectPlan`을 모든 렌더 후보의 단일 truth source로 만든다.
2. legacy Phase의 skip/placement 판단을 plan 실행으로 바꾼다.
3. 중복 방지 invariant를 테스트로 고정한다.
4. Phase 6/7의 visual 배치를 `Visual Builder`로 통합한다.
5. Phase 2/3의 complete marker와 editable shell 판단을 `Ownership Planner`로 이동한다.
6. 임시 상태 필드를 제거한다.
