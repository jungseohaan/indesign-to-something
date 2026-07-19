# SPEC-041: 앵커 가장자리 라벨(구절 풀이 배지)의 테이블 셀 흡수 수정

## 문제

고등문학지도서 u2 p187 (로컬 p18) 등에서, 인용 박스 가장자리에 걸친 "구절 풀이"
배지(pill)가 테이블 셀 텍스트로 흡수된다. 원본은 회색 인용 박스의 좌상단 모서리에
파란 pill 배지가 겹쳐 떠 있는 레이아웃인데, 변환 결과는:

- pill 배경 그래픽과 "구절 풀이" 텍스트가 분리됨
- "구절 풀이" 텍스트가 셀 텍스트 맨 앞에 인라인으로 끼어 들어감 (2×1 테이블
  row0 이 "구절 풀이 그러니까 내가…"로 시작)

같은 페이지에 4건, u1·u2 전권에 수십 건 반복되는 패턴.

## 원인 (조사 완료, 2026-07-19)

### 소스 구조

- IDML 테이블 `u46601i46702`(2행×1열)의 셀 0:0 에 중첩 TextFrame `u58df6`
  (= resolved 364022, 인용문 스토리 364025)
- 인용문 스토리 문단 맨 앞에 앵커 객체 2개:
  - 파란 pill Rectangle(+구절풀이 TF 364046) — 앵커 오프셋 (-25.3, -8.4)로
    캐리어 프레임 밖 좌상단에 걸침
  - "114쪽 9행" pill (TF 364071, 앵커 364070) — 박스 안쪽 상단

### 플랜 결함 (extractor jsx 플래너)

`ownership-plan.jsonl` 기준:

```
domId=364019  pass.inline_objects composite
  visualAction=PLACE_TEXT_SHELL  visualLayer=LABEL_BACKDROP  placement=INLINE
  ownedTextFrameIds=[364022, 364046]   ← 문제
  bounds=[51,78,61,203] (pill ∪ 인용프레임 union)
```

**셸 플랜이 자기 앵커가 실려 있는 캐리어 프레임(364022, 인용문)의 텍스트까지
소유하는 순환 구조.** 플래너가 pill 과 인용 프레임을 겹침 기반으로 한 composite
로 클러스터링한 결과다. 변환기는 이 INLINE 플랜을 앵커 위치(셀 텍스트 맨 앞)에
그대로 실행한다.

### 기존 방어선이 작동하지 않는 이유

1. `OwnershipPlanner.isTableCellAnchoredExternalLabelShell` (INLINE→FLOATING
   승격 규칙)이 정확히 이 케이스용으로 존재하지만:
   - **기본 비활성인 legacy bridge**(`runLegacyOwnershipMutationBridge`) 안에
     있어 실행되지 않는다. 활성 패스(`normalizeStage1ContractsBeforeValidation`)
     는 관찰 계약상 경고만 남긴다 ("Stage 1 ObjectPlan facts must be produced
     before execution").
   - 활성화되더라도 (a) 앵커를 resolved 테이블 셀 `inlineAnchorIds` 에서 찾는데
     이 케이스는 앵커가 셀 안 **중첩 TextFrame 스토리**에 있어 탐색 실패
     (resolved 테이블 145개 중 등록 없음), (b) "앵커 문단에 본문 텍스트 있으면
     거부" 가드에 걸린다 (인용문이 같은 문단).
2. `InlineFrameHandler.isAnchoredOutsideParentByTextFrame` 등 앵커-외부-위치
   판정 함수들은 현재 **호출부가 없는 dead code** (오너십 정책 재작업 때 끊김).

## 목표

- pill 배지(배경+텍스트)를 캐리어 프레임/테이블 가장자리 위 FLOATING 으로 배치
- 인용문 텍스트는 셀 텍스트로 유지 (셸 소유에서 제거)
- "114쪽 9행"처럼 박스 안쪽에 있는 인라인 셸은 현행 유지

## 해결 방안

플랜 권위가 extractor 에 있으므로 **jsx 플래너에서 수정**한다.

### 규칙: 앵커 캐리어 순환 소유 금지

`pass.inline_objects` composite 플랜 생성 시(또는 plan normalize 단계):

1. 셸 플랜의 앵커 캐리어 스토리를 찾는다 (앵커 run 의 소속 스토리).
2. `ownedTextFrameIds` 에 캐리어 스토리의 소유 프레임이 포함되면 순환 —
   캐리어 프레임을 소유에서 제거한다.
3. 남은 소유 프레임(라벨 텍스트)이 있고, 셸(또는 라벨 프레임)의 bounds 가
   캐리어 프레임 경계 밖/걸침이면 `placement=FLOATING, coordinateSpace=PAGE`
   로 승격. bounds 는 union 이 아니라 **라벨 구성요소만의 bounds** 로 재계산.
4. 남은 소유 프레임이 없으면 승격하지 않고 현행 유지 (텍스트 유실 방지).

### 후보 구현 위치

- `scripts/indd/object_plans.jsx` — composite ownedTextFrameIds 구성부
- 또는 `scripts/indd/candidate_normalization.jsx` — 플랜 정규화 단계

(정확한 함수는 구현 시 특정. `pass.inline_objects` composite 의
`shell_slot_only` slotRole 생성 경로를 추적할 것.)

## 수정 파일

1. `scripts/indd/object_plans.jsx` (또는 candidate_normalization.jsx) — 순환
   소유 제거 + FLOATING 승격 규칙
2. (선택) `converter` OwnershipPlanner — 같은 위반을 경고하는 관찰 패스 추가
   (`ANCHOR_CARRIER_CIRCULAR_TEXT_SHELL_OWNERSHIP`)

## 검증

- [ ] u2 재추출 → p187 pill 이 FLOATING 배치, 셀 텍스트에서 "구절 풀이" 제거
- [ ] "114쪽 9행" 인라인 셸은 현행 유지
- [ ] u1·u2 전권 재변환 diff — 다른 페이지 회귀 없음
- [ ] 중3수학 1단원 재변환 diff — 교과서 케이스 회귀 없음

## 조사 아티팩트

- 추출: `output/issues/고등문학지도서/u2/p170-227-20260719-023454/extract/`
- 플랜: 같은 디렉토리 `ownership-plan.jsonl` (domId 364019, 364070)
- 페이지 인벤토리: p18 renderDecisions — PLACE_INLINE_TEXT_SHELL 4쌍
