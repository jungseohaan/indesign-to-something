# SPEC-047: 문장 중 화학식 진입이 앞 한글 본문을 text-range-shell 로 오분리 (추출기 수정)

## 문제

과학 u1 말풍선 "반응물인 수소의 화학식은 H₂, 산소의 화학식은 O₂이고, 생성물인
물의 화학식은 H₂O야." 에서 앞 절 "반응물인 수소의 화학식은" 이 별도 배경 PNG
셸로 분리되어 뒤 텍스트가 잘리고, 말풍선 배경이 페이지 배경에서 누락된다.

같은 페이지의 화학식 없는 말풍선("반응물의 수소 원자가 2 개이고…")은 정상이다.

## 원인 (조사 완료, 2026-07-20)

**추출기(ExtendScript)의 text-range-shell 선두 range 판정이 화학식 폰트 경계를
라벨 경계로 오판**하는 것이 근본이다.

- `source_index.jsx` `_sourceIndexLeadingStyledStoryRunRanges` 는 문단 선두 런과
  다음 런의 스타일이 다르면(`_sourceIndexRunStyleKey`, appliedFont 포함) 선두
  range 를 만든다. 이 range 가 있으면 `object_plans.jsx`
  `_sourceBundleTextRangeShellObjectPlan` 이 셸을 **atomicTextlessVectorContent
  = true 인 별도 inline PNG 렌더 대상**으로 만들고(배경이 페이지 배경에서 분리),
  Java 하류가 그 PNG 에 선두 텍스트를 담아 원본에서 제거 → 본문 앞이 잘린다.
- 문제 말풍선: r0 "반응물인…"(폰트 HU달달한코코아130) → r1 "H"(폰트
  BT수식H-분수N). **폰트만 다른데** 스타일 경계로 잡혀 range 생성.
- 정상 말풍선: r0 "반응물의…" → r1 "2"(BT수식H-분수N, 첨자 없음). 소스 구조는
  동일하나, 실측상 추출기가 이 말풍선 배경(187377)은 atomic 컬렉션에 넣지
  않아 페이지 배경에 남아 정상 표시. 즉 화학식 첨자/폰트가 만든 경계가 문제
  말풍선(186925)만 별도 렌더로 밀어냈다.

디버깅: 두 말풍선 Polygon 소스 필드 비교로 186925 에만
`atomicTextlessVectorContent / childIdsOmittedByAtomicContent / atomicSource
ObjectCount=2` 가 있음을 확인 → text-range-shell plan(`_sourceBundleText
RangeShellObjectPlan`) 이 atomic true 설정원임을 역추적 → 그 상류
`leadingStyledTextRanges` 생성이 근본임을 확정.

## 해결 — 추출기에서 화학식 폰트 진입 경계 제외

`_sourceIndexLeadingStyledStoryRunRanges` 에 가드: 선두 런이 화학식 폰트가
아니고 다음 런이 화학식 폰트면(문장 중 화학식 진입) 선두 range 를 만들지 않는다.
그러면 이 말풍선은 화학식 없는 말풍선과 동일하게 range 미생성 → 셸 미분리 →
배경이 페이지 배경에 남고 텍스트는 원본에 온전.

`_sourceIndexIsFormulaFont`: BT수식·BTM·BT화살표·EH·NP 판정 (Java
BTFontGlyphMap/EHFontGlyphMap/NPFontGlyphMap 과 동일 기준).

## 수정 파일

1. `scripts/indd/source_index.jsx`
   - `_sourceIndexLeadingStyledStoryRunRanges`: 화학식 폰트 진입 경계 range 제외
   - `_sourceIndexIsFormulaFont` (신규 헬퍼)

## 재추출 필요

추출기 변경이므로 InDesign 재추출로만 반영된다. `leadingStyledTextRanges` 는
source-info 캐시에 저장되므로 `--no-reuse-idml`(또는 캐시 무효화) 재추출 필요.

## 검증 (2026-07-21, 재추출 후)

- 과학 u1: 문제 말풍선 TF `leadingStyledTextRanges=None`(정상 말풍선과 동일),
  Polygon 186925 `atomicTextlessVectorContent=None`(atomic 분리 사라짐). 셀 안
  배경 셸 rect 0, 텍스트 문장 온전. (배경 페이지 배경 포함은 한글 육안 확인.)
- 별도 Java 수정 없이 추출 산출물만으로 해결(SPEC-046 은 별개 유지).
