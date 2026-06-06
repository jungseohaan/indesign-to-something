# SPEC-035: InDesign 렌더 Ownership 기반 중복 방지

> **상태**: In Progress (2026-06-05)
> **관련**: [SPEC-005](SPEC-005-composed-lines-extraction.md), [SPEC-020](SPEC-020-empty-container-png-preserve.md), [SPEC-025](SPEC-025-text-image-rendering-removal.md), [SPEC-028](SPEC-028-inline-anchored-group-duplicate.md), [SPEC-033](SPEC-033-badge-inline-simplification.md)

---

## 문제

현재 변환 파이프라인은 InDesign이 추출한 PNG와 Java가 만든 HWPX 텍스트/도형을 함께 사용한다. 시각 충실도는 InDesign 렌더가 가장 높지만, 텍스트가 포함된 렌더 PNG를 배치하면 HWPX TextFrame과 중복 표시된다.

대표 사례:

- 순수 그래픽 그룹은 InDesign PNG로 배치하는 것이 맞다.
- TextFrame과 결합된 말풍선/타원/배경 도형은 시각 그래픽만 필요하지만, InDesign `exportFile()`은 텍스트까지 함께 캡처하는 경우가 많다.
- TextFrame과 전체 또는 부분적으로 오버랩된 그래픽은 InDesign 렌더를 사용해야 하지만, TextFrame 자체는 검색 가능한 HWPX 글상자로 유지되어야 한다.
- 중복 방지를 Java의 bounds/occlusion/zOrder 휴리스틱으로 처리하면 예외가 계속 늘어난다.

핵심 원칙:

> 렌더 PNG는 "무엇을 보여 주는가"가 아니라 "무엇을 소유하는가"로 배치한다.

---

## 목표

1. Java의 그래픽/도형 재렌더링 책임을 줄이고, 시각 그래픽은 InDesign 추출 결과를 우선 사용한다.
2. 검색 가능해야 하는 텍스트는 HWPX TextFrame으로 유지한다.
3. 텍스트가 포함된 PNG는 기본적으로 배치하지 않는다.
4. 중복 방지는 bounds/occlusion 추론이 아니라 source object id 기반 ownership gating으로 처리한다.
5. HWP 폰트 매핑 차이로 인한 강제 줄바꿈은 장평 조정보다 InDesign `composedLines` 기반 줄 보존으로 완화한다.

---

## Ownership 모델

`renderedFloatingItems[]` 및 렌더 계열 항목은 아래 필드를 가진다.

```json
{
  "id": 5092,
  "file": "rendered_frames/deco_5092.png",
  "itemType": "page_object",
  "visualOwner": "indesign_png",
  "textOwner": "hwpx_tf",
  "containsText": true,
  "containsEditableText": true,
  "editableTextFrameIds": [5114],
  "visualOnlyChildIds": [5117, 5094],
  "sourceObjectIds": [5092, 5117, 5093, 5114, 5094],
  "placementAllowed": false,
  "overlapPolicy": "in_front_of_text",
  "reason": "mixed_group_contains_editable_tf"
}
```

### 필드 의미

| 필드 | 값 | 의미 |
|---|---|---|
| `visualOwner` | `indesign_png` \| `hwpx_shape` \| `none` | 시각 그래픽을 누가 소유하는가 |
| `textOwner` | `hwpx_tf` \| `indesign_png` \| `hidden_semantic` \| `none` | 텍스트를 누가 소유하는가 |
| `containsText` | boolean | PNG에 텍스트 픽셀이 포함될 가능성 |
| `containsEditableText` | boolean | PNG가 HWPX TF로 배치할 텍스트를 포함하는가 |
| `editableTextFrameIds` | number/string[] | HWPX TF로 배치할 TextFrame id 목록 |
| `visualOnlyChildIds` | number[] | 텍스트 없이 시각 그래픽으로만 필요한 자식 객체 |
| `sourceObjectIds` | number[] | 이 렌더 항목에 포함된 원본 DOM 객체 id |
| `placementAllowed` | boolean | Java가 이 PNG를 바로 배치해도 되는가 |
| `overlapPolicy` | enum | HWPX 배치 시 wrap/앞뒤 정책 |
| `reason` | string | 추출기가 내린 소유권 판정 사유 |

### 배치 규칙

1. `placementAllowed == false`이면 Java는 PNG를 배치하지 않는다.
2. `containsEditableText == true`이고 `textOwner != "indesign_png"`이면 Java는 PNG를 배치하지 않는다.
3. `editableTextFrameIds`는 HWPX TextFrame으로 배치한다.
4. `visualOnlyChildIds`의 별도 렌더 PNG가 있으면 그것만 배치한다.
5. 별도 visual-only 렌더가 없으면 중복 배치 대신 warning을 남긴다.
6. `textOwner == "hidden_semantic"`은 시각은 PNG, 텍스트는 검색/시멘틱 레이어 목적으로 숨김 배치한다.

---

## 시나리오 정책

| 시나리오 | 시각 처리 | 텍스트 처리 | 비고 |
|---|---|---|---|
| 순수 그래픽/이미지/도형 그룹 | InDesign PNG | 없음 | `placementAllowed=true` |
| TF 자체가 타원/말풍선/도형 | InDesign visual-only PNG | HWPX TF | HWP native shape/textbox graphics 금지 |
| TF와 그래픽이 오버랩 | InDesign PNG | HWPX TF | zOrder는 IDML 기준 |
| 그룹 안에 TF + 도형 혼합 | visual-only 자식만 PNG | HWPX TF | 그룹 전체 PNG 금지 |
| 인라인/앵커 객체 | InDesign PNG 또는 HWPX inline | 문단 흐름 유지 | floating 전환은 명시 메타 필요 |
| vector-only 인라인 조각 묶음 | 단일 이미지 | 없음 | 체크박스 외곽+체크처럼 겹치는 작은 도형 조각 |
| 표 셀 내부 그래픽/TF | 셀 또는 객체 단위 PNG | 셀 텍스트 유지 | 셀 좌표/클리핑 필요 |
| 마스터 페이지 그래픽/TF | 페이지별 렌더 또는 인스턴스 | 반복 TF | pageIndex/synthetic id 필요 |
| 장식/효과 텍스트 | InDesign PNG 허용 | 선택: hidden semantic | 검색 중요도 낮은 경우 |
| text-on-path/복합 효과 | InDesign PNG | hidden semantic 권장 | 편집성보다 시각 우선 |
| 스프레드 걸침 객체 | 페이지별 crop PNG | 해당 없음 | 동일 zOrder 유지 |

### 그룹 렌더 단위 정책

`mixedGroup`의 기본 렌더 단위는 "가장 큰 부모 그룹"이 아니라 "의미 있는 최소 시각 단위"이다.

배경/버튼/라벨/텍스트 프레임이 함께 묶인 큰 부모 그룹을 통 PNG로 렌더하면, 다음 문제가 생긴다.

- 원본에서 편집 가능한 TF 주변의 독립 그래픽까지 하나의 큰 이미지로 묶인다.
- 작은 라벨 하나를 보존하려고 큰 학습 활동 영역 전체가 이미지화된다.
- zOrder/중복 방지는 쉬워지지만, 편집성과 객체 선택성이 크게 떨어진다.
- 부분 수정이 필요한 그래픽의 원인 추적이 어려워진다.

따라서 큰 부모 그룹은 아래 조건을 만족할 때 통 렌더하지 않는다.

1. 그룹 bounds가 페이지의 넓은 영역을 차지한다.
2. 자식에 2개 이상의 독립 TF 또는 독립 박스/패널이 있다.
3. 자식들이 서로 다른 학습 의미 단위로 보인다. 예: 활동 제목, 설명 TF, `가/나/다/라` 카드, 출처 라벨.
4. 자식 TF 일부는 HWPX 편집 텍스트로 유지되어야 한다.

이 경우 extractor는 부모 그룹이 아니라 내부의 atomic visual cluster를 렌더한다.

| atomic visual cluster | 시각 처리 | 텍스트 처리 |
|---|---|---|
| `가/나/다/라` 버튼 + 해당 카드 사각형 | 단일 PNG 가능 | 버튼 글자는 PNG 소유 가능 |
| 빈 활동 카드/답안 박스 | 카드 단위 PNG | 내부 편집 텍스트 없음 |
| `모둠 활동` 배지처럼 아이콘+짧은 라벨이 하나의 로고처럼 동작하는 그룹 | 단일 PNG 가능 | 라벨 텍스트는 PNG 소유 또는 hidden semantic |
| 제목 옆 살구색 밑그림/장식 그래픽 | 독립 PNG | 제목 TF는 HWPX TF |
| 긴 지시문/본문/문항 TF | 배경 그래픽과 분리 | HWPX TF |
| 출처/캡션처럼 편집 가치가 낮지만 위치 기준이 필요한 짧은 텍스트 | 기본은 HWPX TF, 시각 효과가 강하면 hidden semantic 검토 | 중복 방지 메타 필수 |

판정 원칙:

- "큰 부모 그룹을 한 번에 렌더"는 fallback이다.
- 먼저 자식 그룹/자식 도형 묶음에서 atomic visual cluster를 찾는다.
- atomic cluster가 렌더되면 해당 cluster의 `sourceObjectIds`를 소유권으로 기록하고, 부모 mixedGroup 렌더에서는 제외한다.
- 부모 mixedGroup의 남은 자식이 독립 cluster들뿐이면 부모 PNG를 만들지 않는다.
- 부모 mixedGroup 렌더가 필요하더라도 editable TF는 숨기고 visual-only shell로만 사용한다.
- `visual_label_indesign_png`의 배치 bounds가 실제 PNG 비율보다 과도하게 넓어져 라벨이 납작해지는 경우, bounds의 가로폭은 유지하고 PNG pixel ratio에 맞춰 세로를 중앙 기준으로 확장한다.

### 마스터 페이지 그래픽 정책

마스터 페이지 그래픽은 page side별로 InDesign의 실제 stacking을 보존한 합성 PNG로 추출한다.

이전처럼 "가장 큰 마스터 객체 1개"만 추출하면 큰 배경 사각형만 살아남고, 그 위의 흰 오버레이/장식 그룹/마스터 로고가 누락된다. 따라서 extractor는 적용된 마스터 스프레드의 각 page side에 걸치는 top-level visual item을 수집해 임시 페이지에 override한 뒤 하나의 PNG로 export한다.

원칙:

- page side와 교차하는 top-level master item을 모두 포함한다.
- 중심점 기준 필터는 사용하지 않는다. 스프레드 걸침 객체와 page side를 덮는 오버레이가 누락될 수 있기 때문이다.
- Group/TextFrame/Rectangle/Oval/Polygon/GraphicLine 등 visible master visual item은 합성 대상이 될 수 있다.
- HWPX TF로 별도 인스턴스화되는 editable master TextFrame은 합성 export 전에 content만 숨겨 텍스트 중복을 막는다.
- 장식 로고/아웃라인/그룹 그래픽은 master PNG가 소유한다.
- 결과 PNG에는 합성에 참여한 `sourceObjectIds`를 기록해 이후 중복 배치 판단에 사용한다.

의도:

- page 188의 `단원 마무리` 좌상단 그룹, 초록 배경, 흰 오버레이처럼 하나의 마스터 visual layer로 동작하는 요소를 함께 보존한다.
- 특정 페이지 예외가 아니라 마스터 추출 단위 자체를 "largest item"에서 "composed master page side"로 바꾼다.

이 정책의 의도:

- `가/나/다/라` 버튼과 각 카드 사각형처럼 하나의 시각 단위인 묶음은 이미지로 보존한다.
- `자료 수집하기 및 쟁점 설정하기` 제목 근처의 살구색 그래픽과 제목 TF는 분리한다.
- `모둠 활동` 배지는 통 이미지로 허용하되, 그것을 이유로 page 181 활동 영역 전체를 통 이미지화하지 않는다.
- 큰 학습 활동 영역은 "통 이미지"가 아니라 작은 시각 단위들의 집합 + HWPX TF로 구성한다.

---

## Visual Source 정책

중복 문제가 ownership gating과 text-hidden visual-only PNG로 해결되었으므로, 시각 그래픽의 소유자를 InDesign 렌더 PNG로 단일화한다.

원칙:

1. Java/HWPX는 editable text container만 만든다.
2. HWP native text box/table cell의 선, 채움, 이미지 브러시, 그림자, 둥근 모서리 시각 표현은 생성하지 않는다.
3. Java가 도형 primitive를 직접 그려 만든 합성 PNG는 생성하지 않는다.
4. HWP 폰트 메트릭(ascent/descent)으로 text container의 폭/높이를 보정하지 않는다.
5. TF와 결합된 말풍선/타원/배경/오버랩 그래픽은 InDesign visual-only PNG로 배치하고, 텍스트는 HWPX TF/Table/DrawText 컨테이너로 배치한다.
6. 실제 문서 표의 셀 선/채움은 별도 정책이 확정되기 전까지 텍스트 프레임 컨테이너 정책과 분리해서 다룬다.
7. InDesign에서 이미 렌더된 작은 vector-only inline PNG 조각들이 원본에서 하나의 시각 단위로 겹쳐 있으면 단일 이미지로 합성해 배치한다.
   - Java는 새 도형을 그리지 않고 InDesign 렌더 PNG의 zOrder/좌표만 보존해 합성한다.
   - 합성된 조각의 나머지 앵커는 이미 배치된 것으로 표시해 중복 inline 배치를 막는다.
8. 텍스트와 원본 도형이 1:1로 결합된 작은 인라인 박스 배지는 제한적으로 HWP native line/fill을 허용한다.
   - 예: `ㄴ`, `ㅈ`, `ㅌ`, `ㄷ`처럼 글자 자체는 HWPX 텍스트여야 하고 외곽 박스도 같은 인라인 흐름 안에서 함께 움직여야 하는 경우.
   - 이 허용은 `ASTInlineObject.nativeGraphicsAllowed=true`인 객체에만 적용하며, 일반 TF/말풍선/배경 그래픽에는 적용하지 않는다.
   - 목적은 InDesign 렌더 PNG를 새로 만들지 못한 경우의 fallback이 아니라, 편집 가능한 텍스트와 소유권이 명확한 작은 도형을 한 단위로 보존하는 것이다.

구현 스위치:

- `VisualSourcePolicy.useHwpNativeTextBoxGraphics() == false`
- `VisualSourcePolicy.useJavaSyntheticGraphicPngs() == false`
- `ASTInlineObject.nativeGraphicsAllowed == true`인 작은 인라인 박스 배지는 전역 스위치와 무관하게 해당 객체의 선/채움만 보존
- HWPX text container height is derived from original TF bounds, not HWP font metrics.

---

## 자연스러운 줄바꿈 정책

HWP 폰트 매핑이 InDesign과 정확히 맞지 않으면 HWPX 글상자 내부에서 문장이 강제 줄바꿈되어 가독성이 떨어진다. 장평 조정은 최후 수단으로만 사용한다.

우선순위:

1. `composedLines`를 truth로 사용한다.
   - InDesign이 조판한 실제 줄 단위로 HWPX 문단에 줄바꿈을 보존한다.
   - 본문 전체가 아니라 좁은 말풍선/활동 박스/주석 TF부터 적용한다.
2. TextFrame 폭을 안전하게 미세 확장한다.
   - 주변 객체와 충돌하지 않을 때만 2~5% 확장한다.
   - 확장 여부는 `layoutAdjustment` 메타로 남긴다.
3. 폰트 메트릭 기반 매핑을 개선한다.
   - 스타일별 샘플 문자열 폭을 비교해 HWP 후보 폰트를 선택한다.
   - 장평 보정은 1~2% 이내의 마지막 보정으로 제한한다.
4. 복잡한 효과 텍스트는 visual PNG + hidden semantic text로 처리한다.

---

## 색상/틴트/투명도 정책

- InDesign `FillTint`/`StrokeTint`는 투명도가 아니라 원색과 흰색의 혼합 비율이다.
- `tint=100`은 원래 색, `tint=0`은 흰색으로 해석한다.
- `tint=-1`, 누락, `NaN`은 InDesign 기본값으로 보고 `100`으로 보정한다.
- 도형의 표시 여부는 `None`, 실제 opacity, hidden/nonprinting/layer 상태로 판단한다. `tint=0`만으로는 invisible 또는 누락 대상으로 판단하지 않는다.
- Java 변환 경로는 `ColorResolver.applyTintToHex()`를 공용 진입점으로 사용한다. HWPX로 넘길 때는 가능한 한 RGB에 tint를 반영하고 `fillTint=100`으로 고정해, HWPX의 tint 해석 차이로 색이 흔들리지 않게 한다.

## 렌더 결과 추적 필드

- 렌더 항목은 ownership 판단과 회귀 추적을 위해 `placementRole`과 `zSource`를 기록한다.
- `placementRole` 예: `tf_visual_shell`, `inline_object`, `floating_visual`, `master_graphic`, `atomic_text_visual`, `visual_only_png`.
- `zSource` 예: `sourceObjectIds`, `absoluteZOrderIndex`.
- 이 필드는 배치 정책을 직접 바꾸는 값이 아니라, 누락/중복/z-depth 문제를 추적하기 위한 디버그 메타데이터다.

---

## 구현 계획

### Phase 1: resolved ownership 스키마 추가

- `scripts/extract_indd.jsx`
  - 렌더 항목 생성 시 `sourceObjectIds`, `editableTextFrameIds`, `containsEditableText`, `placementAllowed`, `reason`, `placementRole`, `zSource` 기록
  - 그룹 렌더 전 자식 TextFrame 분류 결과를 참조
  - 텍스트 포함 그룹 PNG는 기본 `placementAllowed=false`
- `docs/resolved-json-spec.md`
  - rendered item ownership 필드 문서화
- 진행:
  - ownership 필드 구현 완료 (2026-06-05)
  - 추적 필드와 tint=0 유지 정책 반영 (2026-06-06)
  - `EXTRACT_SCRIPT_VERSION=15`로 캐시 무효화

### Phase 2: Java 모델/파서 확장

- `RenderedGroup.java`
  - ownership 필드 추가
- `ResolvedDataReader.java`
  - 신규 필드 파싱
- 하위 호환:
  - 필드가 없으면 기존 휴리스틱 fallback 유지
  - 필드가 있으면 ownership gating을 우선 적용
- 진행:
  - 완료 (2026-06-05)

### Phase 3: Phase 6/7 단순화

- `BackgroundInjector.java`
  - `placementAllowed=false` 항목 배치 금지
  - `containsEditableText && textOwner != indesign_png` 항목 배치 금지
  - Java synthetic graphic PNG 생성 비활성화
- `RenderableFramePlacer.java`
  - bounds/childIds 추론보다 ownership 필드 우선
  - `visualOnlyChildIds` 렌더가 있으면 자식 렌더만 배치
- 기존 occlusion 기반 텍스트 누락/중복 방지는 제거 또는 fallback 격하
- 진행:
  - ownership 기반 배치 금지 게이트 완료 (2026-06-05)
  - 텍스트 숨김 visual-only PNG는 editable TF child가 있어도 배치되도록 예외 적용 (2026-06-05)
  - `visualOnlyChildIds` 전용 추출/배치는 Phase 1 extractor 생성 이후 연결
  - HWP native text box/table cell graphics 및 Java synthetic PNG 비활성화 (2026-06-05)

### Phase 4: composedLines 기반 줄 보존

- `FramePlacer.java` / `StoryConverter.java`
  - 좁은 TF 또는 `lineBreakPolicy=preserve_composed_lines`인 TF에 원본 줄바꿈 적용
- `scripts/extract_indd.jsx`
  - `textFrames[].lineBreakPolicy` 또는 `layoutRisk` 기록

### Phase 5: 검증

- 중3국어 박현숙 1단원 소(2) page 32:
  - `"그건 바로 매체 자료의 특성 때문이야..."` TF 중복 없음
  - 말풍선 시각 요소 보존
  - 텍스트 검색 가능
- 인라인 앵커 배지:
  - 줄높이 팽창 없음
  - 같은 source id 중복 배치 없음
- 표 셀 내부 그래픽:
  - 셀 본문 텍스트 검색 가능
  - 셀 배지/아이콘 중복 없음
- 전체 문서:
  - text coverage 회귀 없음
  - 주요 페이지 시각 회귀 수동 확인

---

## 대상 파일

1. `scripts/extract_indd.jsx` — 렌더 항목 ownership 메타 생성
2. `converter/src/main/java/kr/dogfoot/hwpxlib/tool/idmlconverter/resolved/RenderedGroup.java` — ownership 필드 추가
3. `converter/src/main/java/kr/dogfoot/hwpxlib/tool/idmlconverter/resolved/ResolvedDataReader.java` — ownership 필드 파싱
4. `converter/src/main/java/kr/dogfoot/hwpxlib/tool/idmlconverter/normalizer/resolved/phase6/BackgroundInjector.java` — ownership gating 적용
5. `converter/src/main/java/kr/dogfoot/hwpxlib/tool/idmlconverter/normalizer/resolved/phase7/RenderableFramePlacer.java` — ownership gating 적용
6. `converter/src/main/java/kr/dogfoot/hwpxlib/tool/idmlconverter/normalizer/resolved/phase2/FramePlacer.java` — composedLines 줄 보존 정책 적용
7. `core/src/main/java/kr/dogfoot/hwpxlib/tool/idmlconverter/ast/ASTTextFrameBlock.java` — TF별 자동 줄감기 금지 플래그
8. `converter/src/main/java/kr/dogfoot/hwpxlib/tool/idmlconverter/converter/HwpxTextBoxBuilder.java` — TF lineWrap 출력 정책
9. `docs/resolved-json-spec.md` — 스키마 문서 갱신
10. `scripts/extract_indd.jsx` — editable TF 텍스트만 숨기고 mixedGroup의 anchored visual은 부모 PNG의 `visualOnlyChildIds` 소유로 유지

---

## 검증

- [x] `mvn -q -pl converter -am -DskipTests package`
- [x] `node --check --input-type=commonjs < scripts/extract_indd.jsx`
- [x] page 32 말풍선: TF 텍스트 유지 및 기존 중복 PNG 좌표 후보 없음 (`/private/tmp/page32-ownership-fallback.hwpx`)
- [x] InDesign SDEF 로드 확인: `using terms from application "Adobe InDesign 2026"` 프로브 성공
- [x] page 32 v8 재추출 성공: `/private/tmp/ownership-extract-page32-v8/resolved.json`
- [x] 문제 말풍선 `5092`: `textOwner=hwpx_tf`, `containsText=false`, `textHiddenBeforeExport=true`, `editableTextFrameIds=[5114]`
- [x] v8 HWPX 생성: `/private/tmp/page32-ownership-v8.hwpx`
- [x] 대상 문구 핵심 토큰 중복 없음: `그건` 1회, `정보거든` 1회
- [x] visual-only PNG 배치 확인: `/private/tmp/page32-ownership-v8b.hwpx` 안에 `deco_5092.png` 대응 `497x199` 이미지 포함
- [x] `containsEditableText=true` 렌더 중 `textOwner != indesign_png` 항목 0개
- [x] page 32 변환 로그에서 `ResolvedZOrderNormalizer` IDML zOrder 적용 확인 (`rendered=27`)
- [x] Visual Source 정책 적용 출력: `/private/tmp/page32-visual-policy-v1.hwpx`
  - Java synthetic line PNG 제거 확인: 이미지 21개 → 18개, `100x4` PNG 없음
  - 대상 문구 핵심 토큰 중복 없음: `그건` 1회, `정보거든` 1회
  - text coverage 잔여 누락은 기존 v8b와 동일 패턴 (`no-frame`, `corner=5`)
- [x] HWP 폰트 메트릭 heightScale 제거 출력: `/private/tmp/page32-no-font-height-scale.hwpx`
  - `5460`, `5512`, `5534`, `5582` 높이 원본 TF bounds와 일치 (`hRatio=1.0`)
  - `5436` 잔여 높이 증가는 font metric이 아닌 `ORC+inline` 보정 경로
- [x] `scripts/text_coverage.py resolved.json out.hwpx` 누락 회귀 없음
- [x] `zOrder`는 `ResolvedZOrderNormalizer`의 IDML 기준값 유지
- [x] 문제 말풍선 TF `5114` 원본 3줄이 HWPX 내부 3개 `hp:p`로 유지
- [x] 선택적 lineWrap 정책 적용 출력: `/private/tmp/page32-selective-linewrap.hwpx`
  - 원본 `composedLines`가 모두 서로 다른 문단인 TF만 `SQUEEZE`
  - 한 문단이 폭 때문에 여러 줄로 감긴 TF는 `BREAK`
  - page 32 확인: `불꽃 축제에 관한 기사를 보니` = `SQUEEZE`, `가 같은 사건을 어떤 관점에서 다루었는지 파악해 보자.` = `BREAK`
- [x] 인라인 그래픽/배지 TF ownership 통일
  - 인라인 객체 PNG 렌더 시 짧은 라벨 텍스트도 숨김 처리
  - `table_inline_text_as_png` 전체 텍스트 PNG fallback 제거
  - 인라인 PNG 메타는 `textOwner=hwpx_tf`, `containsText=false`, `containsEditableText=false`, `textHiddenBeforeExport=true`
  - 인라인 HWPX 텍스트 프레임도 `noAutoLineWrap` 플래그와 선택적 `SQUEEZE/BREAK` 정책 사용
  - 기존 v8 resolved 기준 변환 회귀 없음: `/private/tmp/page32-inline-policy-no-exceptions-old-input.hwpx`
- [x] TF 주변 inline visual 소유권 적용
  - mixedGroup decoration PNG export 시 editable TF 텍스트만 숨기고 top-level anchored visual은 부모 PNG에 포함
  - 해당 anchored visual id는 `visualOnlyChildIds`로 기록하여 Java `loadInlineObject()`에서 inline 재배치 억제
  - image/graphic group PNG export의 TF-owned inline visual 숨김 경로는 별도 유지
  - Java `loadInlineObject()`의 leaf decoration 폐기 필터 제거
  - page 35 기존 resolved 기준 구조 확인: `/private/tmp/so2-page35-tf-owned-inline.hwpx`
    - `가`/`나` 행 글상자 내부 PIC 1개 → 3개(점 + 배지 + 빈 밑줄)
    - 새 추출 데이터에서는 부모 PNG가 TF 주변 inline visual을 소유하므로 중복 inline 재배치 차단
- [ ] 데스크톱 앱 경로로 page 32 재추출 후 ownership 필드 실데이터 확인
  - 직접 `osascript` 경로로 실데이터 확인 완료. 데스크톱 UI 경로 검증은 별도 수동 확인 대상

## 상태: 진행 중
