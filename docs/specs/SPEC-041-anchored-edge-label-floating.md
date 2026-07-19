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

## 구조 정정 (2026-07-19, p187 단일 페이지 재추출로 확인)

`extraction-plan.json` sourceItemPreview 기준 실제 소스 트리:

```
364019 Group (앵커된 composite 루트)
├─ 364020 Polygon        (모서리 장식)
├─ 364021 Rectangle      (둥근 인용 박스)
│   └─ 364022 TextFrame  (인용문, story 364025)  ← "그러니까 내가…"
└─ 364045 Rectangle      (파란 pill)
    └─ 364046 TextFrame  ("구절\n풀이", story 364049)
```

**"앵커 캐리어 순환 소유" 가설은 오류.** 364022 는 앵커 캐리어가 아니라 그룹의
자식이다 — 셸 플랜(364019)이 자식 TF 둘을 소유하는 것 자체는 composite 계약상
정상이다. 이 가설로 구현한 jsx 정규화는 0건 매칭으로 확인 후 되돌렸다.

문제의 본질: **인라인 그룹 셸 안의 "가장자리 마커 라벨"(pill) 텍스트가 본문
텍스트(인용문)와 같은 채널로 실행**돼 셀 문단에 평문으로 합쳐진다는 것.
(pill 은 sourceInfo.simpleMarkerLabelContents 성격의 소형 라벨, 그룹 bounds
좌측 가장자리에 걸침: pill [51.9,76,60,84] vs 그룹 [51,78,61,203] 페이지 좌표.)

## 해결 방안 (수정)

두 층위 중 하나 또는 병행:

### A안 — jsx 플래너: 마커 라벨 분리 플랜

`pass.inline_objects` composite 에서, 그룹의 직계 분기 중 (1) 소형 + (2)
simpleMarkerLabel 내용 + (3) 그룹 경계 가장자리에 걸친 TF 분기(pill Rect+TF)를
composite 소유에서 분리해 **별도 FLOATING LABEL_BACKDROP 플랜**으로 낸다.
본문 TF(인용문)는 기존 소유 유지.

### B안 — 컨버터 실행부: 다중 TF 셸의 오버레이 경로 보장

`InlineFrameHandler.buildInlineShellObject` 는 visibleShellTextFrameCount>1 이면
`attachInlineShellChildTextOverlays` 로 자식 텍스트를 셸 내 상대좌표 오버레이로
배치하게 돼 있다. p187 출력이 평문 합류가 된 것은 이 경로가 아닌 폴백(셸 null
→ 텍스트만 셀로)으로 빠졌기 때문으로 추정 — buildInlineShellObject 의 null
반환 지점(1389행 가드, shellFile 부재 등)을 추적해 오버레이 경로를 살리면
플랜 변경 없이 원본에 가까운 렌더(박스 + pill 오버레이)가 된다.

**B안 우선 권장** — 플랜 계약을 건드리지 않고, 재추출 없이 기존 추출물로
반복 검증 가능(`make reconvert EXTRACT=...`).

## B안 실행 경로 추적 (2026-07-19, 진행 중)

p187 산출물의 실제 HWPX 구조: 셀 안 **단일 `<hp:rect>` + 단일 `<hp:drawText>`**
에 "구절 풀이"+인용문이 문단으로 연결돼 있고 오버레이 자식 객체는 없다.

확인된 사실:
- 레거시 헬퍼(tryInlineGroupShellWithEditableChild 등)는 `hasStage1ObjectPlans`
  가드로 전부 우회 → 이 rect 는 플랜 기반 경로 산출물
- `buildInlineShellObject` 의 오버레이 분기 조건(`visibleShellTextFrameCount>1`)
  은 childTfs=[364022,364046] 로 성립해야 함 (둘 다 가시 텍스트,
  isOrcCarrierTextFrame 아님 — normalizeInlineShellText 로 확인)
- 오버레이 소비자는 존재: `InlineFrameBuilder`/`HwpxImageBuilder` 가
  `overlayFrames`/`isOverlay()` 처리

남은 미해결: 오버레이가 AST 에서 안 만들어졌는지(분기 미진입/overlay null),
HWPX 빌더에서 유실됐는지, 아니면 rect 를 만든 게 제3의 빌더(TableBuilder
중첩 복원, PageOverlayBuilder, SingleColumnTableConverter)인지.

### 다음 단계 (재추출 불필요 — reconvert 로 반복)

1. `buildInlineShellObject` 오버레이 분기와 `attachInlineShellChildTextOverlays`
   에 임시 디버그 로그 → `make reconvert EXTRACT=output/issues/고등문학지도서/u2/p187-20260719-094654/extract`
2. 분기 미진입이면 childTfs/카운트 값 확인, 진입했으면 InlineFrameBuilder 의
   overlay 렌더 경로 확인 (테이블 셀 안 INLINE_TEXT_FRAME 의 overlay 지원 여부)
3. 오버레이가 살아나면: pill 텍스트가 셸 내 상대좌표(pill 위치)에 배치되는지
   + 원본 스크린샷과 비교 (pill 은 박스 좌상단 모서리 걸침)

### 검증용 아티팩트 (단일 페이지, 반복 사이클용)

- `output/issues/고등문학지도서/u2/p187-20260719-094654/extract/` — p187 만
- 원본 레이아웃: 회색 인용 박스 + 좌상단 모서리에 파란 pill("구절/풀이" 세로
  2행) + 박스 안 첫머리 "114쪽 9행" — 사용자 원본 스크린샷 확보(2026-07-19)

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
