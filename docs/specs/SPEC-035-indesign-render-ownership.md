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
- 개념어, 활동명, 표/박스 라벨
- `주장`, `이유 1`, `근거 1`
- `최근 사회·문화적 맥락`
- `쟁점 분석`, `사안이 발생한 사회·문화적 맥락`

판단 기준은 문자열 예외가 아니다. "편집/검색 가치가 있는가"가 기준이다.

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

3. 실제 콘텐츠 이미지인가?
   - 사진, 차트, QR, 삽화, 그래프면 `CONTENT`

4. 큰 바탕인가?
   - 페이지/영역 바탕이면 `BACKGROUND`

5. 이미 다른 plan이 소유했는가?
   - 그렇다면 `DROP_TEXT` 또는 `DROP_VISUAL`

이 순서를 우회하는 예외는 만들지 않는다.

---

## Editable Label Shell

검색/편집 가치가 있는 짧은 라벨은 텍스트와 시각을 분리한다.

- 텍스트: `OWNED_BY_HWPX_TEXT`
- 배경/껍데기: 우선 `ABSORB_TEXT_STYLE`, 불가능할 때만 `PLACE_TEXT_SHELL`

원칙:

- 라벨 배경이 단순 fill/stroke/corner로 표현 가능한 Rectangle/Polygon/Oval이고,
  라벨 TF와 같은 그룹 안에서 충분히 겹치며 회전/전단이 거의 없으면 HWPX drawText의
  fill/stroke/corner 속성으로 흡수한다.
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
