# SPEC-045: 화학 반응식 계수 첨자 오염 (resolved 첨자 위치 흘림)

## 문제

과학 u1 pg25/26/28 반응식 생성물 박스 "2H₂O"에서 앞 계수 "2"가 아래첨자로
깨진다(₂H₂O 꼴). 파랑 박스(p17)의 주황 2H₂O 는 정상 — 검정 생성물 박스 3건만
발생.

## 원인 (조사 완료, 2026-07-19)

**resolved DOM 데이터 품질 문제 + 매칭 증폭**의 2단 결함.

1. resolved DOM 이 "2H₂O"를 조판하며 첨자 위치를 계수·hair space(U+200A)에까지
   흘렸다. resolved 런 실측:
   - `[2 H]` NORMAL (계수2 + hairspace + H, BT수식H-분수N)
   - `[2]`   SUBSCRIPT (진짜 첨자)
   - `[hairspace]` **SUBSCRIPT, ViMaru OTF** ← 오염
   - `[O]`   NORMAL

   IDML 원본은 전혀 다르다(Story_u30280): 모든 런 **Position=None**, 첨자는
   charStyle(00_수식(첨자-하부자))로만 표기. 계수 2 = 00_영문 + Position=None.

2. IDML `2 H` 병합 런이 `splitChemicalFormulaMixedRuns` 로 `2`+`H` 로 분리된 뒤,
   단독 계수 `2` 가 `findResolvedRun`(텍스트 contains 매칭)에서 텍스트만 같은
   첨자 `2`(SUBSCRIPT rr)에 오매칭됐다. 그 결과 계수 2 가:
   - `applyPositionStyle`: resolved position=SUBSCRIPT 을 물려받음
   - `resolvedCharacterStyleRef`: rr.charStyle=하부자 를 물려받음 → 하류
     `MathProcessor.applyPositionFromCharacterStyle` 가 다시 첨자화

   CLAUDE.md "resolved DOM 의 EH 폰트/위치 과대 보고" 와 같은 계열(첨자 속성이
   인접 문자로 전이)이나, 여기서는 매칭이 그 오염을 계수로 옮겼다.

## 해결 — IDML "비첨자" 증거 우선 (RunBuilder)

resolved 첨자 주장과 IDML 명시적 비첨자 증거가 모순되면 IDML 을 신뢰한다.
계수 2 는 IDML 이 position=None + charStyle=00_영문(비첨자)이므로 이 가드에
걸려 resolved 의 잘못된 SUBSCRIPT 를 버린다. 진짜 첨자 H₂ 의 2 는 IDML
charStyle=하부자라 가드에 안 걸려 첨자를 유지한다.

1. `applyPositionStyle` — resolved position 이 script 인데 IDML position 이
   normal/null 이고 IDML charStyle 이 첨자 계열이 아니면 resolved position 무시
2. `resolvedCharacterStyleRef` — resolved charStyle 이 첨자(하부자/상부자)인데
   IDML charStyle 이 비첨자면 IDML charStyle 사용 (하류 재첨자화 차단)
3. `isScriptCharacterStyle` 공용 판정 헬퍼 (상부자/하부자/super/subscript)

## 수정 파일

1. `normalizer/resolved/phase3/RunBuilder.java`
   - `applyPositionStyle` — IDML 비첨자 우선 가드 + idml/resolved position 분리
   - `resolvedCharacterStyleRef` — 첨자 charStyle 오염 차단
   - `isScriptCharacterStyle` (신규 헬퍼)

## 검증 (2026-07-19)

- 과학 u1: 2H₂O 첫2 오류 3→0. H₂ 첨자 유지 62=62(무회귀, 기존 누락 6 동일).
  텍스트 100% 동일(1808 문단). HEAD 대비 비교.
- 수학 u1: 수식 diff 0 + 텍스트 100% 동일(9170).
