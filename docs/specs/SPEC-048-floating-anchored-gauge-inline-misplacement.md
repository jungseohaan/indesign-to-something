# SPEC-048: FLOATING_ANCHORED 게이지의 인라인 오배치 (100% 유출 + 위치 어긋남)

> 상태: **원인 규명 완료, 수정 미완결** (추출기 다중 seed 통일 필요). 2026-07-21.

## 문제

과학 u1 p19 "나의 활동 확인하기"의 "과정·기능"·"가치·태도" 문장 오른쪽 끝에
있어야 할 **게이지(눈금 + "100%" + 연필)**가:
1. "100%" 텍스트가 문장 **앞**에 인라인으로 삽입되어 "100%과정·기능 화학 반응을…"
   처럼 중복/오배치됨
2. 게이지 그래픽이 문장과 **수평 정렬되지 않음**

게이지는 문장에 앵커되지만 페이지 좌표로 floating 배치되어야 하는
`storyAnchorPlacement=FLOATING_ANCHORED` 객체다.

## 원인 (조사 완료)

게이지 Group(75901)이 `FLOATING_ANCHORED` 인데 여러 지점에서 **인라인으로
오분류**되어 `pass.inline_objects` 로 seed 되고, 그 안의 "100%" 편집 텍스트가
문장 flow 로 새고 게이지 좌표 배치가 누락된다.

핵심 판정 함수·지점 (실측 확인):
- `source_index.jsx` `_isInlineFlowItemBySourceInfo` — `storyTextInlineSlot===true`
  체크가 `FLOATING_ANCHORED` 배제보다 **먼저** true 를 반환. 게이지는
  storyTextInlineSlot=true + FLOATING_ANCHORED 라 인라인으로 판정됨. (순서를
  바꾸면 게이지가 inline seed 에서 빠지나, **floating 배치 경로가 게이지를 다시
  claim 하지 않아 게이지가 통째로 누락**되는 부작용.)
- `ownership_candidates.jsx` `_appendSourceDeclaredInlineShellCandidates` (~줄421)
  — 게이지를 2소스 candidate(textOwner=indesign_png, 통짜 PNG 가능)로 seed.
- `extraction_plan_builder.jsx` `_appendInlineObjectExtractionCandidates` (~줄956)
  — **같은 게이지를 34소스 candidate(textOwner=hwpx_tf, 텍스트 분리)로도 seed.
  이 34소스 candidate 가 최종적으로 이긴다.** `inlineRequiresTextHidden` 가
  "편집 텍스트 자식 있으면 무조건 hwpx_tf" 라 게이지도 텍스트 분리됨.
- `object_plans.jsx` `_objectPlanBundleIsInlineEditableTextShellComposite` /
  `_inlineEditableTextShellCompositeBundle` — bundle 을 인라인 편집 텍스트 셸
  컴포지트로 정규화(slotRole=inline_editable_text_shell_composite).
- `planner_bundles.jsx` — bundle 객체 필드 매핑에 `completePngTextAllowed`/
  `textOwner` 가 **빠져 있어** seed 의 통짜-PNG 신호가 bundle 로 전파 안 됨.

즉 게이지가 **여러 seed/candidate 로 중복 생성**되고(2소스 통짜PNG vs 34소스
텍스트분리), dedup/우선순위에서 34소스 텍스트분리 쪽이 최종을 좌우한다.

## 게이지 식별 방법 (확립)

게이지 vs 텍스트 라벨(다르게 해 보기 등)의 결정적 차이:
- **게이지**: 자식에 `GraphicLine`(눈금선·화살표)이 **27개** — 복잡한 벡터
- **라벨**: GraphicLine 0개, 배경 도형(Polygon/Rectangle) + 텍스트뿐

→ `_isGaugeLikeVectorGraphic(item)`: `item.allPageItems` 중 GraphicLine ≥ 8
개면 게이지. (실측: FLOATING_ANCHORED + 편집텍스트 자식 28건 중 게이지 다수,
라벨 소수를 정확히 구분.)

## 시도한 수정과 결과 (전부 되돌림)

사용자 제안 = "게이지 특수조건 객체만 통짜 PNG(그래픽+100% 함께) 허용".
방향은 맞으나 다중 seed 때문에 한 곳을 고쳐도 다른 seed 가 최종을 좌우:

1. `_isInlineFlowItemBySourceInfo` 순서 변경 → 게이지 inline 제외되나 **누락**
   (floating 경로가 재-claim 안 함).
2. `_inlineCompositeCompletePngDecisionForOwnership` 에 게이지 조건 추가 →
   seed 는 completeOwns=true 를 내나, 34소스 candidate 가 이겨 무효.
3. `planner_bundles` 에 `completePngTextAllowed`/`textOwner` 전파 추가 →
   컴포지트 오분류는 막으나(textOwner: hwpx_tf→none) 여전히 통짜PNG 아님.
4. `_appendInlineObjectExtractionCandidates` 34소스 seed 에 게이지 통짜-PNG
   가드 추가 → 여전히 textOwner=none (게이지 판정이 이 지점에서 미발동 또는
   후단 정규화가 덮어씀 — 미확정).

## 완결에 필요한 작업 (다음 세션)

**여러 seed 경로를 게이지에 대해 통일**해야 한다:
- 게이지(GraphicLine 다수 + FLOATING_ANCHORED + 편집텍스트 자식)는 **단일
  경로로 통짜 PNG(OWNED_BY_PNG) + floating 좌표 배치**가 되도록, seed 중복을
  억제하거나 우선순위를 게이지 통짜-PNG candidate 로 강제.
- `planner_bundles` 필드 전파(#3)는 유효하므로 유지 후보.
- 검증: 게이지 그래픽 보임 + "100%" 문장 앞 유출 0 + 게이지가 문장 오른쪽에
  수평 정렬. 재추출(`--no-reuse-idml`) 필요.

## 함정 메모

- 추출기 파이프라인은 seed → cluster → bundle → normalize → placement 다단계.
  한 지점 수정이 최종을 안 바꿀 수 있으니 **최종 candidate 가 어느 seed 에서
  오는지 먼저 확정**(candidate 별 textOwner/passId 계측)할 것.
- 모듈은 `extract_indd.jsx` 가 eval 로드 → 전역 함수 공유. 로드 순서
  (`ownership_candidates` < `extraction_plan_builder`) 내에서 헬퍼 재사용 가능.
- `$.stack` 은 인자값(파일 경로·페이지 객체 배열)을 통째로 담아 함수 체인이
  노이즈에 묻힘 — 함수명만 필요하면 `.replace(/\([^)]*\)/g,"()")` 로 인자 제거.
