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
| `언어의 자의성`처럼 개념/제목을 나타내는 짧은 의미 라벨 | 라벨 배경/장식만 PNG | 제목 텍스트는 HWPX TF |
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

### TF-owned visual shell 정렬 정책

`textOwner=hwpx_tf`인 visual shell PNG는 HWPX TF가 텍스트를 소유하고, InDesign PNG가 배경/말풍선/밑그림 같은 시각 껍데기만 소유하는 구조이다. 이때 텍스트를 숨기고 export한 PNG는 투명 패딩을 포함할 수 있으므로, PNG의 전체 bounds만 믿으면 TF와 배경 도형이 어긋날 수 있다.

정렬 원칙:

- `hwpx_tf` 소유 visual shell은 원본 zOrder가 알려져 있어도 소유 TF 바로 뒤에 배치한다.
- 내용 없는 TF 자체가 stroke/fill 컨테이너인 경우, shell 자신의 빈 TF zOrder가 아니라 shell과 겹치는 실제 의미 텍스트 TF들의 최소 zOrder보다 충분히 뒤에 배치한다.
- 짧은 제목 라벨의 배경 도형은 큰 컨테이너 shell보다 앞, 제목 TF보다 뒤에 배치한다. 즉 레이어 순서는 `큰 shell < 제목 라벨 배경 < 제목 TF`이다.
- 이 순서는 IDML 원본 zOrder보다 ownership 의미를 우선한다. 큰 shell은 컨테이너 배경이고, 라벨 배경은 제목 TF의 직접 시각 배경이기 때문이다.
- shell PNG 내부에 투명 패딩이 있으면 alpha bbox를 기준으로 실제 보이는 영역을 계산한다.
- 짧은 제목/개념 라벨 TF는 visual shell의 실제 alpha bbox와 겹치는 경우에만 TF의 세로 bounds를 shell에 맞추고 `verticalJustification=CENTER_ALIGN`으로 보정한다.
- 이 보정은 텍스트 의미 단위와 시각 shell이 1:1에 가까운 경우에만 적용한다.

적용 조건:

- TF는 검색/편집 가치가 있는 짧은 제목 또는 개념 라벨이다.
- TF의 composed line은 1줄이거나 1줄로 취급 가능한 구조이다.
- shell의 `editableTextFrameIds`에 해당 TF id가 포함되어 있다.
- shell의 alpha bbox와 TF의 x 범위가 충분히 겹친다.
- shell 높이는 TF 높이보다 약간 크지만, 과도하게 크지 않아야 한다.
  - 작은 제목 shell의 예: 살구색 밑그림, 라벨 배경, 짧은 제목 밴드.
  - 높이 비율이 큰 mixed group은 제목 shell이 아니라 컨테이너로 본다.

금지 조건:

- 하나의 shell이 2개 이상의 독립 editable TF를 포함한다.
- shell bounds 또는 alpha bbox가 제목 TF뿐 아니라 아래 본문/답안 박스/라벨까지 포함한다.
- shell 높이가 TF 높이의 허용 배수를 크게 넘는다.
- shell이 활동 영역 전체, 답안 영역 전체, 말풍선 전체처럼 여러 의미 단위를 묶는 컨테이너이다.
- 이 경우 TF 위치/높이를 shell에 맞춰 확장하지 않는다. 원본 TF bounds를 유지하고, shell은 배경 PNG로만 둔다.
- stroke-only 빈 TF shell은 고정 크기 문턱만으로 버리지 않는다. 라운드 코너가 있거나 내부/겹침 영역에 의미 TF가 있으면 레이아웃 컨테이너로 보고 보존한다.
- `fillTint=0`, `stroke=None/strokeWeight=0`처럼 원본에서 비가시 마스크로 쓰인 도형은 vector PNG로 배치하지 않는다. 이런 객체가 흰색 불투명 PNG로 렌더되면 실제 outline을 가리거나 없는 그래픽을 만든다.
- `fillTint`/`strokeTint=-1`은 InDesign 기본 tint 값으로, 비가시가 아니라 100% 표시로 본다. `-1`을 `0` 이하로 단순 판정하면 stroke-only 외곽선과 박스가 누락된다.

### 선/점선 그리드 소유권 정책

점선 구분선, 표 내부 guide line, stroke-only 박스 외곽선은 통 PNG 그룹으로 소유하지 않는다. InDesign의 `exportFile(PNG)`는 dotted/dashed line group에서 세로선 또는 얇은 stroke-only rectangle을 빈 PNG로 만들거나 일부 방향의 stroke만 남기는 경우가 있다.

정책:

- `GraphicLine` 또는 얇은 stroke-only `Rectangle/Polygon/Oval`로만 이루어진 dotted/dashed line-grid 그룹은 `pure_decoration_group` 통 PNG로 export하지 않는다.
- line-grid 그룹의 자식 선/외곽선은 `vector_shape` 단계로 넘겨 개별 시각 객체로 추출한다.
- 이때 `strokeTint=-1`은 100%로 간주해 누락시키지 않는다.
- 그룹 PNG가 자식 ID를 소유한 뒤 실패하면 개별 fallback이 막히므로, line-grid 여부는 그룹 렌더링 전에 판정한다.

실패 방지 사례:

- page 129 차별적 표현 표: `GraphicLine` 점선 그룹은 원본 IDML에 존재하지만 그룹 PNG에는 가로 점선만 남고 세로 점선이 사라졌다. line-grid 그룹은 통 PNG가 아니라 자식 선 단위로 보존한다.

### 텍스트 추종 강조 속성 정책

텍스트와 함께 움직여야 하는 밑줄/강조 배경은 독립 PNG보다 HWPX 텍스트 속성으로 흡수한다. 편집성이 중요한 본문 질문, 빈칸, 강조 문장은 글자가 수정되거나 줄바꿈되어도 장식이 같이 따라가야 한다.

정책:

- InDesign `Underline=true` 또는 underline 계열 CharacterStyle/ParagraphStyle은 HWPX `CharPr` 밑줄로 변환한다.
- InDesign `CharacterShading*`/`Shading*`/`TextShading*` 계열의 문자 강조 배경은 HWPX `CharPr.shadeColor`로 변환한다. 별도 PNG나 floating 그래픽으로 배치하지 않는다.
- 문자 강조 배경은 CharacterStyle의 `BasedOn` 상속, GREP 적용 문자 스타일, 런 직접 속성 순서를 모두 보존한다. 공백 런도 같은 `shadeColor`를 가져야 문장 중간이 끊겨 보이지 않는다.
- 글 사이의 빈 밑줄은 인라인 그래픽 이미지로 두지 않고, HWPX 밑줄이 적용된 공백/탭/리더로 변환한다.
- extractor가 `GraphicLine`이 아닌 얇은 인라인 PNG로 빈 밑줄을 넘긴 경우에도, 앞쪽에 본문 텍스트가 이미 있으면 같은 밑줄 공백 런으로 흡수한다.
- 텍스트 한 줄과 거의 같은 폭/높이로 겹치는 얇은 벡터 배경은 독립 PNG 배치를 생략하고 HWPX paragraph shading으로 흡수한다.
- shading 색상은 원본 PNG의 비투명 픽셀을 흰 종이에 합성한 평균색으로 근사한다.
- extractor가 텍스트를 숨겨 만든 `text_hidden_shell` 계열 PNG도, 긴 한 줄 강조 배경이면 같은 방식으로 흡수할 수 있다.
- `visual_label_text_hidden_shell`/`concept_label_shell`처럼 짧은 라벨/버튼의 배경을 소유하는 shell은 강조 배경으로 흡수하지 않는다. 이 계열은 텍스트는 HWPX TF가 소유하고, 캡슐/라운드 버튼/외곽선/그림자는 InDesign PNG가 TF 뒤에서 소유한다.
- 이 정책은 다음 조건을 모두 만족할 때만 적용한다.
  - 시각 객체가 `indesign_png` 소유의 순수 시각 요소이고 editable text를 포함하지 않는다.
  - 객체 높이가 실제 텍스트 composed line 높이에 가까운 얇은 강조 띠이다.
  - 빈 밑줄 PNG 흡수는 문단 선두 장식과 충돌하지 않도록 선행 본문 텍스트가 있는 경우로 제한한다.
  - 객체와 composed line의 overlap이 충분하다.
  - 객체가 말풍선/박스/표/개념도 컨테이너처럼 여러 의미 단위를 감싸지 않는다.
- `textOwner=hwpx_tf`인 짧은 라벨/버튼 shell은 흡수하지 않는다. 라벨 shell은 텍스트 배경 이미지로 남아야 하고, 본문 강조 배경만 HWPX 속성으로 흡수한다. 부모 그룹 중복 방지, inline coverage, visual-label editable 스킵보다 개별 라벨 shell 보존이 우선한다.
- 너무 얇은 선은 shading으로 만들지 않는다. 실제 밑줄 또는 빈칸 선으로 보고 underline 경로를 우선한다.

실패 방지 사례:

- page 74 `"내가 속한 언어 공동체인 ___ 의 시선으로"`: 밑줄 구간은 인라인 그래픽이 아니라 HWPX 밑줄 속성으로 유지한다.
- page 74 `"나는 얼마나 자주, 그리고 어떠한 목적으로 글을 쓰는가?"`: 보라색 사선 배경은 텍스트와 분리된 floating PNG보다 HWPX shading 근사로 흡수한다.

### 개념도 클러스터 정책

교과서의 개념 설명 영역은 겉보기에는 표처럼 보이지만, IDML 구조상 실제 `Table`이 아니라 다음 요소들이 섞인 pseudo-table/concept diagram인 경우가 많다.

- 왼쪽 축 제목 TF
- 개념 라벨을 담은 rounded box/말풍선/버튼 shell
- 라벨 그룹 내부의 editable TF
- 라벨 바로 아래 또는 오른쪽에 놓인 독립 설명 TF
- 연결선, brace, dotted guide, 배경 capsule

이 구조를 table-cell 정책으로 흡수하면 셀 병합/간격/inset 보정이 잘못 적용되고, DOM parent만 따라가면 그룹 밖의 설명 TF가 분리된다. 따라서 converter는 이런 영역을 `concept diagram cluster`로 별도 취급한다.

판정 기준:

- 같은 페이지에서 짧은 의미 라벨 TF와 설명 TF가 반복적인 좌표 관계를 가진다.
  - 라벨 TF는 `이어진문장`, `안은문장`, `대등하게 이어진문장`, `명사절`처럼 짧은 개념명이다.
  - 설명 TF는 라벨 아래 또는 오른쪽에 붙어 있고, 라벨과 x/y 축이 맞는다.
- 하나 이상의 visual shell PNG가 있고, `textOwner=hwpx_tf` 또는 `*_text_hidden` 계열 reason으로 텍스트가 숨겨져 있다.
- 영역 안에 dotted line/connector/brace가 있을 수 있지만, 실제 IDML `Table` 객체가 아니거나 table cell 주소/row/column 구조가 없다.
- 독립 TF라도 같은 visual shell 또는 라벨 TF와 공간적으로 강하게 연결되어 있으면 같은 cluster에 포함한다.

배치 원칙:

- visual shell, connector, dotted guide는 InDesign PNG/vector가 소유한다.
- 모든 개념명과 설명문은 HWPX TF가 소유한다.
- cluster 내부 TF는 원본 absolute bounds를 우선 보존한다. table/grid merge, 연결 글상자 merge, wrap split, label-center 보정 대상에서 제외한다.
- 라벨 TF와 설명 TF를 같은 HWP table cell로 합치지 않는다. 이 영역은 편집 가능한 독립 TF들의 집합으로 유지한다.
- `textOwner=hwpx_tf` shell은 cluster 내부 TF들보다 뒤에 둔다. 단, shell 안 라벨 배경은 라벨 TF 바로 뒤에 배치한다.
- 라벨/설명 TF를 감싸는 개별 rounded box, capsule, 버튼형 shell은 extractor 단계에서 개별 PNG로 추출하고, Java 변환기는 그 PNG를 해당 TF의 배경으로 배치한다. 큰 cluster/container PNG를 Java에서 잘라 쓰지 않는다.
- cluster 밖의 일반 본문 TF와 겹치지 않도록 cluster의 좌표 보존을 우선한다. 글꼴 차이로 줄바꿈이 생겨도 TF를 다른 cluster 요소 쪽으로 밀지 않는다.

Extractor atomic shell 원칙:

- `TextFrame`과 이를 감싸는 `Rectangle/Oval/Polygon`이 같은 부모 그룹 안에서 1:1 또는 반복 라벨 패턴을 이루면 `concept_label_shell`로 추출한다.
- 자식이 독립 `Group`인 경우뿐 아니라, `Rectangle + TextFrame` 형제 구조도 후보로 본다. page 114 `대등하게 이어진문장`, `종속적으로 이어진문장`처럼 라벨 도형과 TF가 같은 큰 mixedGroup 안의 형제인 경우가 여기에 해당한다.
- export 대상은 라벨 도형 또는 라벨 도형을 포함한 최소 시각 shell이다. export 전에 연결된 TF 텍스트는 숨기고, 주변 설명문/connector/다른 라벨은 숨기거나 제외한다.
- 추출 메타는 `reason=concept_label_shell`, `textOwner=hwpx_tf`, `visualOwner=indesign_png`, `editableTextFrameIds=[...]`, `placementAllowed=true`를 기록한다.
- 큰 concept container는 계속 억제 가능해야 한다. 개별 shell이 성공한 경우 큰 `mixed_group_text_hidden` 또는 `inline_text_hidden` 부모 PNG가 같은 라벨/설명 텍스트를 덮지 않도록 Java의 child/source suppression 정책이 우선한다.

충돌 방지:

- table fill-color 흡수 정책은 실제 table 또는 strong grid-cell 후보에만 적용한다. concept diagram cluster 안의 라벨 shell/설명 capsule/연결 그래픽은 table cell로 보지 않는다.
- `짧은 라벨 텍스트 소유권 정책`은 라벨 내부 텍스트의 owner만 결정한다. 라벨과 설명 TF의 공간 관계는 concept diagram cluster 정책이 결정한다.
- `TF-owned visual shell 정렬 정책`은 1:1 라벨 shell에만 적용한다. 하나의 shell이 라벨, 설명, 연결선까지 포함하면 cluster container로 보고 TF 위치를 shell alpha bbox에 맞춰 확장하지 않는다.
- 그룹 중복 방지 정책은 `concept_label_shell` 개별 PNG를 자식 그래픽으로 억제하지 않는다. 단, 여러 라벨/설명/연결선을 포함하는 큰 통 PNG는 텍스트를 덮을 수 있으므로 억제한다.
- 그룹 중복 방지 정책은 `visual_label_text_hidden_shell` 개별 PNG도 자식 그래픽으로 억제하지 않는다. 텍스트까지 포함한 `visual_label_indesign_png`는 중복 방지를 위해 스킵할 수 있지만, 텍스트를 숨긴 shell PNG는 라벨의 배경이므로 반드시 배치 후보로 남긴다.
- `INLINE_BADGE_GROUP`은 문단 흐름 안의 실제 콘텐츠이다. 텍스트 overlay가 있는 경우 HWPX 출력은 `container(pic + rect)`보다 `단일 rect + imgBrush + drawText`를 우선한다. container 내부 자식 drawText는 HWP 렌더러에 따라 page/floating shell과 결합될 때 보이지 않을 수 있기 때문이다.
- Java 단계에서 부모 PNG를 crop해서 자식 shell을 만드는 fallback은 사용하지 않는다. crop은 PNG 투명 패딩, stroke outside, effect expansion, DPI 반올림에 민감해 extractor의 원본 객체 export보다 불안정하다.
- line-grid 정책은 dotted guide의 시각 보존만 담당한다. dotted guide가 있다고 해서 주변 TF를 table로 병합하지 않는다.

적용 사례:

- page 114 `문장의 짜임에 따른 문장의 종류` 영역은 실제 Table이 아니라 개념도 클러스터이다.
  - 왼쪽 축 제목 `문장의 짜임에 따른 문장의 종류`, `겹문장의 짜임`은 cluster 제목 TF이다.
  - `이어진문장`, `안은문장` 라벨은 shell PNG 위의 HWPX TF이다.
  - `둘 이상의 홑문장이 나란히 이어진 문장.`, `다른 홑문장을 문장 성분으로 포함하고 있는 전체 문장.`은 부모 그룹이 없어도 각각 라벨 바로 아래의 설명 TF로 cluster에 포함한다.
  - `대등하게 이어진문장`, `종속적으로 이어진문장`, `명사절/관형절/부사절/서술절/인용절`도 같은 cluster 내부 라벨/설명 구조로 유지한다.

실패 방지 사례:

- page 122 `언어의 본질 이해하기`: shell PNG 내부의 투명 패딩 때문에 살색 도형이 아래로 밀려 보였으므로 alpha bbox 기준으로 TF와 shell을 맞춘다.
- page 127 `(2) (1)을 바탕으로 차별적 표현의 의미를 파악해 보자.`: 제목과 아래 답안 영역이 같은 큰 mixed group에 들어 있어도, 이 그룹은 제목 shell이 아니라 컨테이너이다. 제목 TF를 그룹 alpha 높이로 확장하면 아래 글상자와 겹치므로 보정 대상에서 제외한다.

### 짧은 라벨 텍스트 소유권 정책

짧은 라벨이라고 해서 무조건 `textOwner=indesign_png`로 만들지 않는다. 길이와 bounds는 보조 조건일 뿐이며, 최종 판정은 라벨의 의미와 편집 가치가 우선이다.

기본값:

- 모든 라벨/버튼 텍스트는 먼저 editable 후보로 본다.
- 완성형 PNG 허용은 예외이며, 플로팅/인라인 여부와 무관하게 같은 기준을 사용한다.
- editable로 판정되면 PNG에는 도형/배지/외곽선/그림자만 남기고, 텍스트는 HWPX TF 또는 inline TF가 소유한다.

`textOwner=indesign_png` 완성형 PNG 허용:

- `가/나/다/라`처럼 한 글자 한글 식별자만 담은 버튼
- `1/2/3`, `01/02`처럼 1~2자리 숫자 식별자만 담은 버튼
- 해당 라벨이 본문 검색/수정 대상이 아니라 카드/도표/문항 위치를 가리키는 시각 마커로만 쓰이는 경우
- 완성형 PNG 안에 포함된 텍스트를 다시 HWPX TF로 배치하면 중복이나 z-depth 오염이 더 커지는 경우
- 아이콘+텍스트가 하나의 로고처럼 동작하고, 편집 대상이 아닌 짧은 활동 배지
- 글자가 도형 효과의 일부라서 HWPX TF로 분리하면 원본성을 크게 잃는 장식 텍스트

`textOwner=hwpx_tf` 유지:

- 검색/수정 대상 본문, 문항 지시문, 제목, 개념어
- `#표제목...` 계열 스타일처럼 개념명/제목/소제목 역할을 하는 라벨
- `언어의 자의성`, `언어의 사회성`, `언어의 역사성`, `언어의 창조성`처럼 검색/편집 가치가 있는 짧은 개념 라벨
- 제목 옆 장식 그래픽과 시각적으로 붙어 있지만, 텍스트 자체는 독립된 의미 단위인 라벨
- `굴을`, `나는`처럼 보기/문항 안에서 의미 단어 역할을 하는 짧은 박스형 라벨
- `주장`, `이유 1`, `근거 1`처럼 학습 구조의 의미 슬롯을 나타내는 라벨

구현 원칙:

- extractor는 의미 라벨을 `visual_label_indesign_png` atomic parent로 만들지 않는다.
- 의미 라벨 주변 그래픽은 텍스트를 숨긴 전체 shell PNG로 배치한다. 개별 자식 장식만 추출하면 외곽선/그림자/채움 중 일부가 누락될 수 있다.
- Java 변환기는 구 캐시에서 이미 `visual_label_indesign_png + textOwner=indesign_png`로 들어온 항목이라도, editable TF의 paragraph style이 `#표제목...`이면 parent PNG를 배치하지 않고 TF를 HWPX 텍스트로 살린다.
- Java 변환기는 `visual_marker_label_indesign_png`라도 연결된 `editableTextFrameIds`의 실제 텍스트가 한 글자 한글 또는 1~2자리 숫자 식별자가 아니면 `indesign_png` 텍스트 소유권을 인정하지 않는다.
- 같은 DOM id가 inline 렌더와 page_object 렌더 양쪽에 있어도 위 기준을 그대로 적용한다. 배치 형태가 아니라 텍스트의 편집 가치가 owner를 결정한다.
- 인라인 단순 버튼 라벨이 위 완성형 PNG 조건을 만족하면 extractor는 텍스트를 숨긴 `inline_text_hidden`이 아니라 텍스트 포함 `visual_marker_label_indesign_png` 인라인 PNG를 만든다.
- 같은 DOM id의 완성형 인라인 PNG와 완성형 page_object PNG가 동시에 존재하면 HWPX 문단 흐름을 보존하기 위해 인라인 PNG가 우선 소유하고, page_object는 중복 배치하지 않는다.
- 이때 parent PNG의 child suppression도 적용하지 않아, 그래픽 껍데기 렌더가 별도로 존재하면 그것을 배치할 수 있게 한다.
- 이 정책은 특정 DOM id나 페이지 예외가 아니라 “시각 마커 라벨”과 “의미 제목 라벨”의 소유권 분리 규칙이다.

### 마스터 페이지 그래픽 정책

마스터 페이지 그래픽은 page side별로 InDesign의 실제 stacking을 보존한 합성 PNG로 추출한다.

이전처럼 "가장 큰 마스터 객체 1개"만 추출하면 큰 배경 사각형만 살아남고, 그 위의 흰 오버레이/장식 그룹/마스터 로고가 누락된다. 따라서 extractor는 적용된 마스터 스프레드의 각 page side에 걸치는 top-level visual item을 수집한다. 단, page side 전체를 무조건 하나의 PNG로 묶지는 않는다. 서로 bounds가 닿거나 겹치는 top-level item만 하나의 visual cluster로 보고, 떨어져 있는 장식/페이지번호 strip/로고는 별도 PNG로 export한다.

원칙:

- page side와 교차하는 top-level master item을 모두 포함한다.
- 중심점 기준 필터는 사용하지 않는다. 스프레드 걸침 객체와 page side를 덮는 오버레이가 누락될 수 있기 때문이다.
- page side의 top-level master item은 bounds 연결성 기준으로 visual cluster를 나눈다.
  - 서로 겹치거나 닿는 배경+오버레이+로고는 하나의 cluster PNG로 export해 stacking을 보존한다.
  - 서로 떨어진 좌상단 장식과 하단 페이지번호 strip은 별도 cluster PNG로 export한다.
- cluster PNG를 export하기 위해 임시 페이지에 override하더라도 배치 bounds는 임시 페이지 origin을 쓰지 않는다.
  - 임시 페이지는 문서 끝에 추가되어 원본 master side와 page origin이 다를 수 있다.
  - 배치 좌표는 원본 master top-level item cluster의 union bounds를 원본 master page bounds 기준으로 계산한다.
  - 임시 override/export target bounds는 PNG 생성에는 사용할 수 있지만, HWPX 배치 좌표의 기준으로 쓰면 모든 master graphic이 임시 페이지 offset만큼 위/왼쪽으로 밀린다.
- Group/TextFrame/Rectangle/Oval/Polygon/GraphicLine 등 visible master visual item은 합성 대상이 될 수 있다.
- HWPX TF로 별도 인스턴스화되는 editable master TextFrame은 합성 export 전에 content만 숨겨 텍스트 중복을 막는다.
- 자동 페이지번호와 텍스트 변수 기반 하시라는 동적 마스터 텍스트로 본다. 마스터 PNG에는 `PB`, `<01 T>` 같은 placeholder가 들어가면 안 되며, 실제 페이지번호/하시라 텍스트는 HWPX TF 복원 단계가 소유한다.
- 장식 로고/아웃라인/그룹 그래픽은 master PNG가 소유한다.
- 결과 PNG에는 합성에 참여한 `sourceObjectIds`를 기록해 이후 중복 배치 판단에 사용한다.
- 마스터 PNG 파일은 여러 실제 페이지가 공유하는 asset일 수 있다. 따라서 `master_graphic`은 파일명 또는 master id 단독으로 dedupe하지 않고, `id + pageIndex + file` 조합을 하나의 배치 인스턴스로 본다.
- 청크 추출/증분 캐시 병합에서도 `renderedFloatingItems`의 `master_graphic` 인스턴스는 pageIndex별로 보존한다. 같은 `master_*.png`가 여러 페이지에 반복되는 것은 중복이 아니라 정상 적용이다.
- Java 배치 단계의 파일 중복 스킵도 같은 페이지 안에서만 적용한다. 다른 pageIndex의 같은 master PNG는 각각 배치해야 한다.

의도:

- page 188의 `단원 마무리` 좌상단 그룹, 초록 배경, 흰 오버레이처럼 하나의 마스터 visual layer로 동작하는 요소를 함께 보존한다.
- 특정 페이지 예외가 아니라 마스터 추출 단위 자체를 "largest item"에서 "composed master page side"로 바꾼다.
- page 130처럼 같은 마스터가 여러 페이지에 반복 적용되는 경우, 청크 병합 과정에서 후속 페이지의 마스터 인스턴스가 사라지지 않게 한다.
- page 123 이후 footer처럼 마스터 PNG가 placeholder 텍스트를 포함하고 Java가 실제 페이지번호/하시라를 다시 배치하는 중복을 방지한다.
- page 122 좌상단 단원 장식처럼 하단 페이지번호 strip과 떨어진 그래픽은 별도 cluster로 export한다. 한 PNG로 묶으면 하단 strip의 spread-wide bounds가 crop 기준을 오염시켜 좌상단 장식이 작게 보이거나 일부 잘릴 수 있다.
- page 122 좌상단 단원 장식, page 120/121 footer의 파랑 배지처럼 master origin이 틀어지면 그래픽이 페이지 밖으로 과도하게 밀려 작아 보이거나 다른 객체에 덮인다. 따라서 원본 master page 기준 좌표를 보존한다.
- page 124/126 좌상단 단원 장식처럼 일부 master item override가 실패하면 원본 cluster union bounds와 실제 PNG export bounds의 종횡비가 달라질 수 있다. 이때 원본 union bounds를 그대로 HWPX 배치 크기로 쓰면 PNG가 길쭉하게 변형된다.
  - 기본 배치 좌표는 원본 master cluster bounds를 사용한다.
  - 단, 원본 cluster aspect와 export target aspect 차이가 큰 경우에는 원본 top/left anchor를 유지하고, 원본의 주 footprint 축을 보존한 채 PNG aspect에 맞춘다.
  - source aspect가 PNG보다 좁고 긴 경우에는 원본 width를 보존하고 height를 줄인다. 이 상황은 실패한 override/bleed 객체가 세로 bounds를 오염시킨 경우가 많다.
  - source aspect가 PNG보다 넓고 낮은 경우에는 원본 height를 보존하고 width를 조정한다.
  - 작은 장식/로고/단원 아이콘 master cluster가 page top/left 밖으로 크게 나가면 HWPX에서 잘릴 수 있다. HWPX에는 InDesign master/bleed canvas가 없으므로, page 대비 작은 cluster에 한해 `top < -6pt` 또는 `left < -6pt`이면 전체 bounds를 페이지 안쪽으로 평행 이동한다.
  - 이 보정은 page 대비 작은 장식 cluster에만 적용한다. 페이지 전체 배경, 하단 페이지번호 strip, spread-wide graphic은 bleed/crop 정책 대상이지 위치 clamp 대상이 아니다.
  - 이는 실패한 override 객체가 배치 bounds를 오염시키는 것을 막기 위한 일반 정책이며, 특정 페이지 예외가 아니다.

### 부분 text-wrap 문단과 leftIndent 정책

일부 문단만 왼쪽 text-wrap을 크게 받는 TF에서는 `leftIndent`를 HWPX 문단 `leftMargin`으로 그대로 옮기지 않는다.

원칙:

- `composedLines`가 있는 TF에서 visible paragraph가 2개 이상이고, 그중 일부 paragraph만 큰 `wrapIndentLeft`를 가진 경우 부분 text-wrap으로 본다.
- 큰 left wrap 기준은 `wrapIndentLeft > max(10pt, frameWidth * 0.18)`이다.
- 해당 paragraph가 양수 `leftIndent`를 가지더라도, neutral hanging indent/leader tab/의미 있는 tab 정렬/선행 inline anchor 문단이 아니면 `suppressParaLeftIndent=true`를 적용한다.
- `firstLineIndent`는 유지한다. 전체 문단을 오른쪽으로 밀어 버리는 left margin만 제거하고, 첫 줄 보정은 보존한다.
- 작은 내부 탭스톱이 `leftIndent`보다 앞에 있는 경우는 레이아웃 부산물로 보고 보호 조건으로 쓰지 않는다.

의도:

- InDesign의 text-wrap 결과와 paragraph `leftIndent`가 HWPX에서 중복 보정되는 것을 막는다.
- 예: 3-1 3단원 소(2) page 130 `"아내와 함께 외출하던 영식은 길에서 손자인 윤호를 마주친다."` 첫 문단은 왼쪽 그래픽 때문에 composed line wrap이 잡히지만, 문단 left margin까지 누적하면 텍스트가 과도하게 오른쪽으로 밀려 보인다.

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
- 도형의 색상 해석은 `tint`를 반영하되, vector PNG 추출의 표시 여부는 별도 정책으로 판단한다. `tint=-1`은 항상 기본값 100으로 보고, `tint=0`인 stroke/fill-only mask 객체는 PNG 배치 대상에서 제외할 수 있다.
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

## Disposition Namespace 정책

정책 충돌 방지를 위해 처리 완료(disposition) 상태는 대상 종류별로 분리한다.

- `textFrameDispositions`
  - TextFrame 텍스트가 HWPX 글상자/런으로 이미 배치되었는지 기록한다.
  - Phase 3의 inline text 재주입 억제에만 사용한다.
- `renderedItemDispositions`
  - `renderedFloatingItems` / `page_object` PNG가 이미 흡수되었거나 배치되었는지 기록한다.
  - Phase 6/7의 PNG 중복 배치 억제에만 사용한다.
- `inlineObjectDispositions`
  - inline PNG를 story flow에 넣을지, floating으로 넘길지 기록한다.
  - Phase 3의 `loadInlineObject()` 판단에만 사용한다.

금지 사항:

- TextFrame id와 rendered item id가 같을 수 있으므로 `TEXT_BLOCK_PLACED`를 단일 DOM id 맵으로 공유하지 않는다.
- “텍스트가 이미 배치됨”을 근거로 같은 id의 `tf_shell_*.png`나 visual shell PNG를 스킵하지 않는다.
- Phase 6/7의 스킵 판단은 rendered 전용 disposition과 ownership 메타(`visualOwner`, `textOwner`, `placementAllowed`, `reason`)만 사용한다.

진단 로그:

- 변환 시 resolved 출력 폴더에 `render-decisions.jsonl`을 생성한다.
- 각 `page_object` rendered item의 `PLACE` / `SKIP_*` 결정을 pageIndex, id, file, reason, owner와 함께 기록한다.
- 특정 페이지 그래픽 누락은 먼저 이 파일에서 `pageIndex`와 `id`를 조회해 “추출 누락”, “Phase 6 스킵”, “Phase 7 스킵”, “배치 후 z-depth/렌더 문제”를 분리한다.

## Rendered Item 중복 방지 정책

`renderedFloatingItems`는 extractor 여러 단계의 결과가 합쳐지는 최종 렌더 큐이다. 같은 DOM 객체가 inline 추출과 배경/데코 추출 경로를 모두 통과하면 같은 PNG가 두 번 들어오거나, 부모 visual shell과 자식 `tf_shell`이 동시에 살아 outline이 이중으로 그려질 수 있다.

원칙:

- `id + pageIndex + type + file + reason + bounds + sourceObjectIds`가 같은 렌더 항목은 하나만 보존한다.
- 이 중복 제거는 extractor 최종 병합 단계와 Java `ResolvedData` 로딩 단계 양쪽에 둔다.
  - 새 캐시는 extractor에서 깨끗하게 생성한다.
  - 기존 캐시나 외부 resolved 입력은 Java 로딩 단계에서 한 번 더 방어한다.
- `textOwner=hwpx_tf`이고 `reason`이 `*_text_hidden`, `visual_shell`, `image_group` 계열인 부모 PNG가 정상 배치 가능하면, 부모의 `sourceObjectIds`에 포함된 자식 visual shell은 fallback이 아니라 중복으로 본다.
- 단, 짧은 의미 라벨을 HWPX TF로 살리기 위해 보호된 `editableLabelShell`은 이 suppression 대상에서 제외한다.
- IDML Story의 ORC 재귀 텍스트 추출도 렌더 ownership을 따른다.
  - ORC가 가리키는 inline graphic/TextFrame이 `renderedFloatingItems`에 소유권 있는 이미지로 등록되어 있으면, 그 자식 텍스트를 일반 문자열로 복사하지 않는다.
  - 실제 삽입 단계에서는 `tryInlineGroupAsBoxList`/`tryInlineGroupAsSingleBadge` 같은 구조화된 inline object를 일반 `ASTTextRun`보다 먼저 시도한다.
  - 이렇게 해야 `예` 같은 배지가 배지로도 나오고 뒤 문장 안의 일반 글자로도 들어가는 중복을 막을 수 있다.

의도:

- page 102 `예 문장의 짜임을 이해해 볼 거야.`처럼 inline 객체가 중복 등록되어 텍스트/배지가 반복되는 문제를 막는다.
- page 102 스스로 점검 박스처럼 큰 mixed-group PNG가 이미 박스 외곽선을 포함하는데, 개별 `tf_shell_*`이 다시 올라와 outline이 지저분해지는 문제를 막는다.

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
