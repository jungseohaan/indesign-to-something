# SPEC-068: 페이지 배경 평면의 외부 시각 소유자 숨김

> 상태: **머지 완료 (#140)**. 20페이지 회귀 PASS. 2026-07-24.
> 사례: 영어 u1 p10(1단원 도비라) — 상단 보라색 도형 + 하단 흰색 Look Ahead 박스 중복.

## 문제

영어 u1 첫 페이지에서 상단 보라색 영역과 하단 흰색 박스가 **두 번 그려진다**.
한 번은 `page_background_plane_p1.png` 에 구워진 채로, 또 한 번은 그 도형을 소유한
plan 이 뽑은 개별 PNG 로.

증상만 보면 "배경에서 제거가 안 된다" 지만, 실제로는 배경 평면이 그 도형을
**소유하지도, 숨기지도 않은** 상태다. 소유하지 않으니 숨길 이유가 없다고 판단하는데,
페이지를 통째로 export 하는 방식이라 픽셀은 그대로 찍힌다.

## 원인

배경 평면 PNG 는 InDesign 에서 페이지 전체를 export 해 만든다. 그래서 다른 plan 이
자기 PNG 로 그리는 시각물은 export 동안 숨겨야 한다. 그 숨김 목록은
`render_inline_objects.jsx` `collectPagePlaneHiddenVisualSourceItems` 가 만드는데,
**오직 `candidate.hiddenVisualSourceObjectIds` 에 적힌 것만** 숨긴다.

그런데 이 목록을 채우는 경로가 배경 **소유권** 판정과 한 게이트로 묶여 있었다.
`extraction_plan_builder.jsx` 의 `pageBackgroundEligibilityForSourceOnPage` →
`sourceHasPageBackgroundSourceEvidence` 는 소스의 레이어 이름이 배경 계열
(배경/바탕/background/bg/backdrop)인지를 먼저 본다.

p10 의 실제 데이터:

| 소스 | 종류 | 레이어 | 결과 |
|---|---|---|---|
| 19391 + 20873 | Rectangle + Image (배경 일러스트) | `바탕` | 증거 통과 → 평면이 흡수 |
| 1304 + 1306 | Group + Polygon (보라색) | `헤드` | 증거 탈락 → 소유 안 함 → **숨김도 안 함** |
| 1578 + 1579 | Group + Rectangle (흰 박스) | `헤드` | 증거 탈락 → 소유 안 함 → **숨김도 안 함** |

보라색·흰 박스는 `pass.decoration_groups` 의 `PLACE_TEXT_SHELL` plan 이 이미 소유해
`inline_1304_*.png`, `inline_1578_*.png` 를 따로 뽑는다. 그런데 배경 평면 export 때
숨겨지지 않아 평면 PNG 에도 함께 구워진다.

`SPREAD_CROSS`·`PAGE_WIDE` 판정도 같은 증거 관문을 먼저 통과해야 해서 세 경로 모두 막힌다.

### PR #137 과의 관계

이건 #137 이 만든 회귀가 아니라 #137 이 **드러낸** 구멍이다. 이전에는 Java 쪽 기하 기반
분류기(`isPageMaterialVisualPlan`, `isSingleSourcePageWideBackground`,
`isPageSpanningBackdropVisualPlan` 등)가 "페이지를 넓게 덮는 단색 도형이면 배경"으로
사후 승격시켜 이런 케이스를 가려주고 있었다. #137 이 "기하는 소유 증거가 아니다"라는
정책으로 이들을 전부 `return false` 로 껐고, 그 결과 추출기 증거 관문이 유일한 판정자가
되면서 `헤드` 레이어의 배경 도형이 무주공산이 됐다.

## 목표

**소유권 판정과 숨김 판정을 분리한다.**

- 소유권("평면이 이 소스를 가져도 되나") — 레이어/마스터 등 소스 증거 기반. 그대로 둔다.
- 숨김("페이지 export 중 이 소스를 가려야 하나") — 다른 plan 이 자기 export 로 그 픽셀을
  그리면 배경 자격과 무관하게 무조건 숨겨야 한다.

## 해결 방안

기존 숨김 채널(table-style, complete-PNG text owner)과 동일한 형태로
`hiddenForeignVisualOwnerSourceObjectIds` 채널을 하나 추가한다.

채우는 규칙 — 평면에 흡수되지 **않은** plan 중:

- `materialization !== "PAGE_PLANE_PNG"` (평면 자신 제외)
- `placement !== "INLINE"` 이고 `coordinateSpace !== "STORY_FLOW"`
  — 스토리 인라인 소스는 인라인 수집기가 이미 숨긴다. 여기서 또 숨기면 그 소스의
  **자기 인라인 export 에서도 사라진다**
- `_sourceCoveragePlanHasVisibleVisual(plan)` 로 실제 보이는 시각물인 것만

그리고 평면 자신의 coverage id 는 `_sourceIdsMinus` 로 빼서, 평면이 소유한 것을
스스로 숨기는 자기모순을 막는다.

## 수정 파일

1. `scripts/indd/extraction_plan_builder.jsx`
   - `foreignVisualOwnerSourceIdsByPage` 누적기 + `addForeignVisualOwnerIds` /
     `markForeignVisualOwnerSourceIds` 추가
   - 비흡수 plan 루프에서 `markForeignVisualOwnerSourceIds(existing)` 호출
   - plan 에 `hiddenForeignVisualOwnerSourceObjectIds` / `...Count` 노출,
     반환 진단 2곳에 `foreignVisualOwnerSourceIdsByPage` 추가
   - `_buildExtractionPlan` 에서 `ctx.pagePlaneForeignVisualOwnerSourceObjectIdsByPage` 로 전달
2. `scripts/indd/extraction_orchestrator.jsx`
   - 페이지 평면 export 호출 2곳에 `hiddenForeignVisualOwnerSourceObjectIdsByPage` 전달
   - **`_pagePlaneHideSignature` 에 새 숨김 집합 반영** (SPEC-049 계약 — 빠뜨리면
     오염된 캐시가 재사용된다)
3. `scripts/indd/render_inline_objects.jsx`
   - `collectForeignVisualOwnerSourceItemsToHide` 추가, hide/restore 수명주기 연결,
     `hiddenForeignVisualOwnerItemCount` 계측
4. `scripts/indd/object_plans.jsx`
   - 필드 복사 allowlist 에 `hiddenForeignVisualOwnerSourceObjectIds` 추가
     (allowlist 누락 시 조용히 버려지는 알려진 함정)
5. `docs/policy/70-layer-zdepth.md` — 소유권/숨김 분리 계약 명문화

## 검증

- [x] 4개 jsx 구문 검사 통과
- [x] 영어 u1 p10 클린 재추출(캐시 미사용): 평면 PNG 에서 보라색·흰 박스 소멸,
      `hiddenForeignVisualOwnerSourceCount: 39`, 렌더 시 38개 아이템 숨김
- [x] 개별 PNG 는 byte-identical 유지 (`inline_1304_*`, `inline_1578_*` md5 동일)
- [x] HWPX 구조 불변 — `hp:pic` 12개, 텍스트 440자로 수정 전후 동일
- [x] 영어 u1 p010-029 전체 재추출·변환 후 **수정 전 기준선 대비 PASS**
      (구조 시그니처 완전 일치 — 텍스트·표·수식·이미지 지표 전부 무변화).
      20페이지 중 18페이지에서 외부 시각 소유자 총 1,526건 숨김 적용
- [ ] 한글 육안 확인

### 골든 갱신 필요 (별건)

`test-data/golden/영어u1-p010-029.json` 는 **이 수정 이전부터 이미 stale** 이다.
수정 전 기준선(`p010-029-20260724-184451`)으로 게이트를 돌려도 동일하게 431건
FAIL 이 난다 (picCount 100→337, binDataCount 128→365, paragraphs 679→683,
visibleChars 11890→11973). 즉 이 diff 는 본 SPEC 과 무관하며, PR #138 골든 갱신
이후 다른 변화가 누적된 것이다.

따라서 본 SPEC 의 검증은 **골든이 아니라 수정 직전 기준선과의 대조**로 수행했고,
결과는 PASS(무변화)다. 골든 자체의 재갱신은 원인 규명 후 별도 PR 로 처리해야 한다
— 지금 `--write-golden` 으로 덮으면 정체 불명의 431건 변화를 검증 없이 승인하게 된다.

## 주의사항 (재발 방지)

- **소유권 게이트로 숨김을 결정하지 말 것.** 페이지 평면은 페이지 통째 export 라서,
  "내가 소유하지 않음" 이 "내가 그리지 않음" 을 뜻하지 않는다.
- **새 숨김 집합은 반드시 `_pagePlaneHideSignature` 에 넣을 것.** 안 넣으면 같은
  캐시 파일명으로 옛 평면 PNG 가 재사용돼 수정이 무효가 된다 (SPEC-049).
- **인라인 소스를 이 채널로 숨기지 말 것.** 자기 인라인 export 까지 비어버린다.
