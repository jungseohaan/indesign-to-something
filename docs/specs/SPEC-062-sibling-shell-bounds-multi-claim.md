# SPEC-062: 형제 셸 bounds 치환의 다중 점유 붕괴 — p16-17 수식 토큰 겹침

> 상태: **완료** — 구현·한글 육안 확인 통과. 2026-07-23.
> 브랜치 `fix-p17-token-overlay`.

## 문제

과학 u1 p16-17 반응식 만들기 단계 박스에서 수식 토큰들이 같은 자리에 겹쳐
렌더됨 (H₂ 위에 O₂, "2H₂"+"O₂" 스택 → "2𝑂𝐻₂₂" 형태). 파랑 생성물
박스(H₂O/2H₂O)는 텍스트가 셸 상단에 붙어 위로 올라감.

최근 변경과 무관한 기존 버그 — 골든(0722 추출) 산출물과 해당 영역 XML 이
ID 정규화 후 완전 동일함을 확인 (골든 육안 확인 때 누락된 지점).
다중 TF 그룹 텍스트가 hwpx 소유로 전환된 PR #107 이후 노출된 것으로 추정.

## 원인 (증거 사슬)

1. `OwnershipPlanner.ensureSiblingTextShellBoundsTextFramePlans` 는 편집 TF 를
   형제 배지 셸(pill/rect)에 맞춰 **셸 bounds 로 배치**하는 plan
   (`text_frame:sibling_text_shell_bounds`)을 만든다 — "1셸 = 1라벨" 배지 전제.
2. 반응식 단계 박스는 **한 셸(rect 186534) 안에 토큰 TF 2개**(H₂ 186535 +
   O₂ 186558). 각 TF 가 같은 셸을 best-match 로 잡아 **둘 다 전체 셸
   bounds(117×51pt)** 를 받음 → `FramePlacer` 가 두 블록을 동일 좌표에 배치.
   TF 자체 bounds 는 정상(18~20pt 폭, 60pt 간격)이었다.
3. 파랑 박스(단독 점유)는 셸 치환 자체는 정당하나, TF 원본
   `verticalJustification=TOP_ALIGN` 이 셸 상단 기준이 되어 텍스트가 위로
   붙음 — TF 는 자기 높이=텍스트 높이로 셸 정중앙에 놓인 라벨이었다
   (실측: TF 중심 = 셸 중심).

## 수정

1. `OwnershipPlanner.ensureSiblingTextShellBoundsTextFramePlans`: 셸별 점유
   TF 수를 선계산, **단독 점유일 때만** 셸 bounds plan 생성. 다중 점유 TF 는
   plan 없이 자기 bounds 로 배치된다 (셸 시각은 페이지 플레인에 이미 있음).
2. `FramePlacer.placeTextFrames`: `sibling_text_shell_bounds` plan bounds 를
   쓴 블록은 `verticalJustification=CENTER_ALIGN` 으로 보정.

## 검증

- 쌍둥이 2쌍(H₂/O₂, 2H₂/O₂) 분리 — 영역 내 동일좌표 스택 0쌍
- 파랑 박스 3개 subList vertAlign=CENTER
- 단독 점유 배지(H₂O 등) 셸-맞춤 유지, 골든 게이트 PASS (구조 무변화 —
  위치·정렬만 변경), 한글 육안 확인 완료

## 디버깅 메모

블록 좌표 이상은 `FramePlacer` 의 `usingObjectPlanBounds` 여부부터 볼 것 —
plan bounds 치환이면 gb(자기 bounds)가 정상이어도 덮인다. plan 출처는
extract 의 `ownership-plan.jsonl` 에서 domId 로 검색 (Java 파생 plan 포함,
object-plans.json 에는 없는 kind `text_frame:*` 계열이 있다).
