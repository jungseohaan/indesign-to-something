# SPEC-064: 답란 밑줄 빈칸(RuleBelow) 3중 유실 — 영어 u1 p22

> 상태: **완료** — 구현·한글 육안 확인, 과학 골든 게이트 PASS. 2026-07-23.
> 브랜치 `fix-eng-p22-blank-underline`.

## 문제

영어 u1 p22 "Ava wrote down … She wondered ¹___." 의 답란 밑줄 빈칸이 HWPX 에서
통째로 사라짐 (밑줄도 공간도 없음). 문서 전체에서 같은 유형 빈칸 22곳 전부 유실.

## 소스 구조

빈칸 = 본문 스토리에 인라인 앵커(￼)로 걸린 **공백 1자짜리 TextFrame**(20×4.3mm,
fill/stroke 없음). 밑줄은 글자 속성이 아니라 문단 스타일 **"#학생용 답+선"의
RuleBelow=true(단락 아래 괘선)** 로 그려진다. 기존 변환 경로(SPEC-024)는 이를
언더스코어 문자열로 근사한다 (`tryInlineTextFrameAsRun` 빈칸 분기).

## 원인 — 독립적 3중 유실 (하나만 고쳐선 안 나옴)

1. **plan 게이트 탈락**: `OwnershipPlanner.ensureImportedInlineTextFramePlan` 이
   `textFrameHasVisibleSemanticText` 로 공백 TF 를 걸러 plan 을 안 만든다 →
   `tryInlineTextFrameAsRun` 의 `ownershipPlanPlacesInlineHwpxText` allow-list 에서
   앵커가 탈락, 빈칸 분기에 도달조차 못 함.
2. **ruleBelowOn 상속 유실**: `StylePropertyResolver` 의 스타일 merge/copy 에
   `ruleBelowOn` 필드가 빠져 있어, IDML 파서가 읽은 RuleBelow=true 가 resolve
   결과에서 null 로 사라짐 (ruleBelowLineWeight/Color 는 있었음 — CharPr 캐시 키
   트랩과 같은 "필드 하나 누락" 계열).
3. **SPEC-024 억제 가드 오탐**: "빈칸을 포함하는 inline_object PNG 가 있으면
   밑줄이 이미 렌더된 것"이라는 가드가 **기하 포함만** 검사 → 본문이 감싸고 있는
   이웃 삽화 PNG(24×24mm 사람 그림)가 빈칸 rect 를 우연히 덮어 밑줄을 억제.

## 수정

1. `OwnershipPlanner`: 공백 TF 라도 resolved 문단 스타일명이 "선"/underline 계열이면
   plan 생성 (`isBlankRuleUnderlineInlineTextFrame` — 하류 styleName 휴리스틱과 동일 기준).
2. `StylePropertyResolver`: merge/copy 에 `ruleBelowOn` 추가.
3. `InlineFrameHandler`: 억제 가드에 소유 조건 추가 —
   `renderedGroupOwnsBlankTextFrame` (editable/atomic 소유 TF 목록 또는 pageItems
   조상 체인에 렌더 그룹 id) 일 때만 억제. 기하 포함만으로는 억제 안 함.

## 검증

- p22 `wondered ¹__________.` 복구, 영어 u1 전체 밑줄 빈칸 22개 생성 (이전 0)
- 과학 u1 골든 게이트 PASS (공용 Java 변경 무회귀)
- 한글 육안 확인 완료

## 메모

- 빈칸 유형 분류: 이 케이스는 "공백 TF + 문단 RuleBelow". 메모리의
  "빈 답란 박스(인라인 Rectangle) 유실"과는 다른 유형 — Rectangle 빈칸은 여전히 별건.
- 억제 가드류(supersede/dedup)는 "대체자가 대상을 실제 소유하는가"를 확인해야
  한다는 SPEC-060 교훈의 재사례.
