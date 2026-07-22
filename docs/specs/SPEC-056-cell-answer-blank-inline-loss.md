# SPEC-056: 표 셀 답란(연필+밑줄) 인라인 객체 유실 3중 원인

> 상태: **완료** — 구현·산출물 검증·한글 육안 확인 통과. 2026-07-22.
> 브랜치 `fix-p019-answer-blank-underline`.

## 문제

과학 u1 p19 표 셀 "질소 : 수소 : 암모니아 = ✏️▁ : ▁ : ▁" (빈 답란)에서:
1. 밑줄 답란 2개가 통째로 사라지고 NBSP 공백만 남음
2. 연필+밑줄 그룹이 `□` 문자로 바뀜
3. (1·2 수정 후) PNG 3개가 문단 **끝에 몰려** "= : : ✏️▁▁▁" 순서로 깨짐

대상 객체: Group 130500(연필+밑줄), Rectangle 130494/130497(밑줄, 자식 Polygon).
전부 `anchoredPosition=INLINE_POSITION` 정상 인라인 앵커이고, 추출기는
`PLACE_INLINE_PNG` plan + PNG 렌더까지 정상 산출한 상태였다.

## 원인 (각각 독립)

1. **`BlankAnchorSpacer.isBlankSpacerGraphic` 오판** (SPEC-044 부작용):
   "fill/stroke 없는 납작 rect = 투명 스페이서" 판정이 **자식 도형을 검사하지
   않았다**. 밑줄 답란은 rect 자체엔 페인트가 없고 밑줄이 자식 Polygon이라
   스페이서로 오판 → `IDMLStoryParser` 파싱 단계에서 NBSP 텍스트로 치환되어
   앵커 자체가 소멸.
2. **`ASTStoryConverter` 답란상자 □ 삼킴**: orcOnly 앵커 런이 수식 폰트 이웃을
   만나면 `formulaAnswerBoxRun`(□)으로 치환하고 인라인 그래픽을 소비한다.
   새 파이프라인 `StoryLoader`에는 plan 기반 가드
   (`MathProcessor.isFormulaAnswerPlaceholderRun` — 콘텐츠 인라인 PNG plan이면
   제외)가 있지만, **표 셀 변환이 쓰는 공유 경로 `ASTStoryConverter`에는
   없었다**.
3. **추출기 셀 fallback 앵커 위치 손실**: 앵커 문자(￼)가 DOM 셀 텍스트에
   없는 경우(`Group`이 CharacterStyleRange에 직접 임베드) `text_collectors.jsx`
   fallback이 `cell.characters` 순회로 앵커를 수집해 **마지막 문단 끝에
   몰아넣었다**. Java(`StoryLoader.buildResolvedCellParagraphs`)는 resolved 런
   순서를 그대로 따르므로 순서가 끝으로 밀림.

## 수정

1. `normalizer/BlankAnchorSpacer.java` — `childGraphics` 비어있지 않으면
   스페이서 아님.
2. `normalizer/ASTStoryConverter.java` — □ 분기에 Stage 1 plan 있는 문서 한정
   가드 추가 (`InlineFrameHandler.isFormulaAnswerPlaceholderAnchorRun` 위임
   신설, `ASTRunConverter.inlineBridgeContext` 패키지 공개).
3. `scripts/indd/text_collectors.jsx` — fallback 앵커를 {문단 인덱스, 텍스트
   오프셋}과 함께 수집해 `_insertCellInlineAnchorRunAtTextOffset`으로 런 사이에
   interleave 삽입 (오프셋이 런 중간이면 텍스트 런 분할).

## 검증 (p19 재추출 → 변환 → HWPX)

- HWPX 셀 시퀀스 `암모니아 = [연필+밑줄PNG] : [밑줄PNG] : [밑줄PNG]` — 원본
  순서 일치
- □ 0건, NBSP 0건
- SPEC-048 게이지 회귀 없음 (`COMPLETE_PNG + FLOATING/PAGE`, "100%" 유출 0)
- 한글 육안 확인 완료

## 함정 메모

- 표 셀 인라인 앵커의 변환 경로는 `StoryLoader.buildResolvedCellParagraphs`
  (resolved 셀 런 기반)다. IDML 스토리 기반 경로(ASTStoryConverter /
  StoryLoader 본문 루프)에 계측을 넣어도 셀 문단은 잡히지 않는다 — 경로 확정은
  `InlineFrameHandler.loadPlannedInlineAnchorItems`에 스택트레이스가 확실.
- □(U+25A1)가 텍스트로 나오면 `ASTMathGrouper.formulaAnswerBoxRun` 계열
  (앵커→답란상자 치환)을 의심할 것. PUA(E285류) 치환과는 별개 경로다.
