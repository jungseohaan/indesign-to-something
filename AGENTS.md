# AGENTS.md

이 저장소에서 IDML -> HWPX 변환 로직을 수정할 때는 페이지별 예외를 만들지 않는다.
문제가 생기면 먼저 source object ownership 정책으로 일반화한다.
IDML/resolved 메타데이터와 `POLICY-source-ownership.md`만 ownership의 진실로 삼는다.
사용자 요청이 페이지/문구/좌표/색/증상 조건을 추가하게 만들면 바로 구현하지 말고,
먼저 source metadata와 ObjectPlan 모델을 확인해 회귀 가능성을 설명한다.

## 최우선 목표

반복되는 그래픽/텍스트 중복, 누락, z-depth 문제를 하나의 ownership pipeline으로 정리한다.

성공 기준:

- ownership 결정은 한 곳에서만 내려진다.
- 이후 단계는 결정된 plan을 실행만 한다.
- 같은 source bundle의 같은 ownership slot이 visible PNG/TF/style/native shape로 중복 실행되지 않는다.
- inline/floating 여부는 후속 단계에서 뒤집지 않는다.
- 정책은 `FramePlacer`, `InlineFrameHandler`, `BackgroundInjector`, `RenderableFramePlacer`에 흩어지지 않는다.
- 페이지 번호, 문구, 특정 좌표 기반 예외는 추가하지 않는다.
- 색상, 픽셀, occlusion, 보이는 증상만으로 ownership을 결정하지 않는다.

## 기준 문서

- `docs/specs/POLICY-source-ownership.md` (Active / Canonical)
- `docs/policy/` canonical modules
- `docs/specs/POLICY-extraction-planning.md`

삭제된 legacy ownership SPEC는 참조하지 않는다. 필요한 규칙은 반드시
`POLICY-source-ownership.md` 또는 `docs/policy/`에 표현한다.

## Stage 구조

기존 legacy Phase는 아래 5개 Stage로 접는다.

| Stage | 이름 | 책임 | 금지 |
|---|---|---|---|
| 0 | Input Prepare | IDML/resolved/style/page/master 인덱스 생성 | 객체 배치 결정 |
| 1 | Ownership Planner | 모든 객체의 text/visual ownership, placement, layer 결정 | AST 생성 |
| 2 | Text Builder | plan에 따라 HWPX 텍스트 생성 | PNG 배치 재판정 |
| 3 | Visual Builder | plan에 따라 PNG/vector/shell/background 배치 | 텍스트 소유권 재판정 |
| 4 | Validate | wrap, single-line, 최종 layer, 중복 invariant 검증 | 새 ownership 생성 |

현재 코드는 아직 legacy Phase를 사용한다. 새 정책은 먼저 `OwnershipPlanner/ObjectPlan`에 표현하고, legacy Phase는 plan 실행 브리지로만 사용한다.

## 핵심 3원칙

### 1. 텍스트는 기본적으로 HWPX가 소유한다

검색/편집 가치가 있는 텍스트는 모두 HWPX 텍스트다.

PNG가 텍스트까지 소유할 수 있는 것은 단순 위치 표식뿐이다.

예:

- `가/나/다/라/마/바`
- `ㄱ/ㄴ/ㄷ/ㄹ/ㅁ/ㅂ/ㅅ/ㅇ/ㅈ/ㅊ/ㅌ`
- `1/2/3`, `01/02/03`
- 체크박스나 선택지 번호처럼 의미 편집 대상이 아닌 표식

`주장`, `이유 1`, `근거 1`, `최근 사회·문화적 맥락`, 제목, 본문, 문항, 출처는 항상 HWPX 텍스트다.

### 2. 시각 객체는 4층만 쓴다

사람이 정책을 판단할 때는 아래 4층만 본다.

`BACKGROUND < DECORATION < CONTENT < TEXT`

- `BACKGROUND`: 페이지 배경, 큰 영역 바탕
- `DECORATION`: 라벨 배경, 말풍선 껍데기, 컨테이너 외곽선, 구분선, 마스크
- `CONTENT`: 사진, 차트, QR, 삽화, 완성형 표식 PNG
- `TEXT`: HWPX가 소유하는 모든 editable 텍스트

구현 내부에서 더 세분화한 layer가 필요해도 반드시 이 4층 중 하나로 귀속된다.

### 3. 한 source bundle은 slot별 owner를 하나만 가진다

중복 금지는 source id 자체가 아니라 source bundle 안의 ownership slot 단위로 판단한다.

기본 slot:

- `TEXT_SLOT`: editable/searchable text
- `SHELL_SLOT`: textless shell, label backdrop, outline
- `TABLE_STYLE_SLOT`: table/cell fill, border, inset 같은 HWPX table 속성
- `CONTENT_VISUAL_SLOT`: 사진, 삽화, 차트, complete marker PNG

같은 bundle의 같은 slot은 visible owner를 하나만 가진다.

부모 PNG와 자식 PNG, complete PNG와 HWPX TF, inline과 floating이 같은 slot을 동시에 보여 주면 잘못된 것이다.

단, textless shell visual과 그 위의 editable child TF text는 서로 다른 ownership channel이다.
복합 그래픽 그룹은 하나의 shell로 보존할 수 있고, 하나 이상의 child TF는 별도 HWPX 텍스트로 배치할 수 있다.
table cell fill/stroke는 `TABLE_STYLE_SLOT`이 소유할 수 있으며, shell/source bundle에 포함되어 있어도 shell PNG로 중복 배치하지 않는다.

## ObjectPlan 필수 필드

모든 visible 후보는 Stage 1에서 하나의 `ObjectPlan`을 가져야 한다.

- `sourceObjectIds`
- `visualSourceObjectIds`
- `styleSourceObjectIds` 또는 동등한 table/style source 추적 필드
- `ownedTextFrameIds`
- `materialization`
- `textAction`
- `visualAction`
- `placement`
- `coordinateSpace`
- `visualLayer`
- `zOrder`
- `reason`

허용 action:

- text: `OWNED_BY_HWPX_TEXT`, `OWNED_BY_PNG`, `HIDDEN_SEMANTIC`, `DROP_TEXT`
- visual: `PLACE_INLINE_PNG`, `PLACE_FLOATING_PNG`, `PLACE_TEXT_SHELL`, `ABSORB_TEXT_STYLE`, `PLACE_TABLE_STYLE`, `DROP_VISUAL`

허용 materialization:

- `HWPX_TEXT`
- `HWPX_TABLE_STYLE`
- `NATIVE_SOURCE_SHAPE`
- `EXTRACTED_PNG_VECTOR`
- `COMPLETE_PNG`

## 4층 매핑

구현상 layer는 아래 4층으로 해석한다.

| 구현 layer | 정책 layer |
|---|---|
| `PAGE_BACKGROUND` | `BACKGROUND` |
| `CONTAINER_BACKDROP` | `BACKGROUND` |
| `LABEL_BACKDROP` | `DECORATION` |
| `CONTAINER_OUTLINE` | `DECORATION` |
| `FOREGROUND_MASK` | `DECORATION` |
| `CONTENT_VISUAL` | `CONTENT` |
| HWPX Text | `TEXT` |

HWPX에는 `BEHIND_TEXT`와 `IN_FRONT_OF_TEXT` 평면 차이가 있으므로, 원본 zOrder 숫자만으로 앞뒤를 판단하지 않는다.

기본 평면:

- `BACKGROUND`은 `BEHIND_TEXT`
- `DECORATION`은 구현 layer별로 나뉜다.
  - `LABEL_BACKDROP`은 `BEHIND_TEXT`: 소유 TF를 덮으면 안 된다.
  - `CONTAINER_OUTLINE`, `FOREGROUND_MASK`는 `IN_FRONT_OF_TEXT`: 외곽선/마스크 역할을 보존한다.
- `CONTENT`은 `IN_FRONT_OF_TEXT`
- `TEXT`는 항상 최상위 의미 층

## 금지

- 페이지 번호/문구/좌표별 예외
- bounds/occlusion만으로 중복을 막는 수정
- 후속 Phase에서 PNG 배치 여부 재해석
- inline 객체를 후속 단계에서 floating으로 전환
- 임시 상태 집합 추가
- `textOwner=hwpx_tf`인 PNG를 complete PNG처럼 배치

예외가 필요해 보이면 정책이 틀린 것이다. source ownership과 4층 layer 규칙을 먼저 고친다.

## Invariant

변환 전후로 아래를 검증한다.

- 같은 source bundle의 같은 visible slot 중복 금지
- 같은 TextFrame의 `OWNED_BY_PNG` / `OWNED_BY_HWPX_TEXT` 동시 소유 금지
- 같은 source의 inline/floating 동시 visible 배치 금지
- `PLACE_TEXT_SHELL`은 소유 TF보다 뒤
- `BACKGROUND`은 `CONTENT`과 `TEXT`를 덮지 않음
- `DECORATION`은 원본 역할상 필요한 경우에만 `CONTENT` 위에 올 수 있음

## 작업 방식

- 조사에는 `rg`를 우선 사용한다.
- 독립적인 파일 읽기는 병렬로 수행한다.
- 수동 파일 수정은 `apply_patch`를 사용한다.
- 기존 dirty worktree를 되돌리지 않는다.
- 빌드는 가능한 한 `mvn -pl converter -am -DskipTests package`로 확인한다.
