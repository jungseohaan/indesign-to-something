# SPEC-080: `상부자(이탤릭)` GREP 가 본문 숫자를 위첨자로 깨뜨림

> 상태: **Phase 1(위첨자 제거) #175 머지. Phase 2(숫자 수식화) 구현 완료, 회귀 0**. 2026-07-26.
>
> Phase 2 요약: `상부자(이탤릭)` GREP 숫자를 StoryLoader 런 루프 초입에서 ASTEquation
> 으로 직접 방출(EH/BT 그룹 게이트 우회). 본문 문맥 가드(`isBodyNumberContext`: 앞뒤
> 이웃이 한글·문장부호·공백·문단경계)로 수식 조각 내부 숫자(72m²·(sqrt{5})²·3<5) 배제.
> charStyle 은 `grepAppliedCharStyle` 에서 읽음(appliedCharacterStyle 은 [No character style]).
> 검증: 수학 u1 719→818 수식(+99, p14 "4·2·-2·25" 수식화), **없어진 수식 0**. 과학 u1
> 화학식 무변경(상부자(이탤릭) 미사용). 영어 u1 무영향. 부수 관찰: 연도 "1525"(제곱근
> 기호 1525년 사용) 1건도 수식화 — GREP 가 수식 서체로 명시한 원본 의도상 오류 아님.
> 사례: 수학 u1 p14 "제곱하여 **4**가 되는 수는 **2**와 **-2**이다" — 4·2·-2·25 가
> 작은 위첨자로 올라가 깨진다.

## 문제

수학 발문의 본문 숫자(제곱 결과 4·25, 답 2·-2)가 지수처럼 **작은 위첨자**로 렌더된다.
수식도 아니고 정상 본문 크기도 아니다.

## 원인 (계측으로 규명)

문단 스타일(`02_함께 탐구` 등)의 GREP 규칙 `\d+|[\l\u]|[...]` → charStyle
`상부자(이탤릭)`(폰트 EH상부자)이 **본문의 모든 숫자를 무차별 매칭**한다. 원래 지수(²)용인데
`\d+` 가 제곱 결과·답·연도까지 잡는다.

위첨자를 실제로 세우는 지점은 **`IDMLCharacterRun.isSuperscript()`** — position 속성이
없어도 charStyle 이름에 "상부자"가 있으면 true 를 반환한다. 이 값을 두 곳이 소비한다:
1. `MathProcessor.applyPositionFromCharacterStyle`(호출부 102·855) — 이름-폴백 위첨자
2. `RunPostProcessor.emitItalicMathRunsAsText` — 이탤릭 수학 런 텍스트 폴백 시 복사

전수 조사: 위첨자 런 207개, 대부분 `앞[]`(밑수 없음) 본문 숫자·연도(1776)·단위(cm)·
라벨(ABCD)이 오위첨자.

## 해결 (위첨자 제거)

위첨자(지수)는 **밑수 뒤에만** 온다. charStyle 이름만으로(position 근거 없이) 온 위첨자는,
바로 앞 런이 밑수(숫자·라틴 변수·닫는 괄호 `)`·`}`·`]`)로 끝날 때만 유지한다. 밑수 없는
본문 숫자·연도·번호는 본문 크기로 되돌린다.

1. `IDMLCharacterRun.isSuperscriptByStyleNameOnly()` 추가 — position 이 아니라 이름만으로
   온 위첨자 판별 (position=superscript 인 진짜 첨자는 가드 대상 아님)
2. `MathProcessor.applyPositionFromCharacterStyle(tr, prevText)` — 이름-폴백 위첨자에 밑수
   가드. 호출부 102·855 에서 앞 텍스트 런 전달. 진입 시점 superscript(position 근거)는 존중
3. `RunPostProcessor.emitItalicMathRunsAsText` — 같은 밑수 가드

## 후속 과제: GREP 숫자 수식화 (Phase 2)

### GREP 조사 결과 — 숫자가 수식으로 명시됨 (2026-07-26)

수학 u1 Styles.xml 전수 조사: **거의 모든 문단 스타일**이 GREP 규칙
`\d+|[\l\u]|[+\-÷_=<>%±...]` 로 **숫자(`\d+`)·라틴 문자·연산자를 한 규칙에 묶어**
charStyle `상부자(이탤릭)`(= EH상부자 수식 서체)에 매핑한다. 즉 **디자이너가 GREP 로
숫자를 명시적으로 수식 요소(수식 서체)로 표시**했다. 사용자 지적("GREP 에 숫자도
수식이라고 명시")이 데이터로 확인됨.

- 대부분 문단: `\d+...` → `상부자(이탤릭)` (이탤릭 수식 서체)
- 일부: `상부자`(비이탤릭), `상부자(이탤릭)B`(볼드 변종)
- 예외: `03_발문(대)` 의 `\d+` → `[No character style]`(수식화 안 함),
  `되돌아보기`·`준비활동` 의 `\d+` → 문항번호 스타일 (수식 아님)

따라서 **`상부자(이탤릭)`/`상부자` charStyle 을 받은 숫자를 수식화하는 것은 원본
조판 의도에 부합**한다. GREP 는 "숫자=수식 서체"만 명시하고 위첨자 위치는 명시하지
않는다(위첨자는 `상부자` 폰트/이름에서 파생 — SPEC-080 Phase 1 이 밑수 가드로 처리).

### 블로커 확정 — EH폰트 리셋이 charStyle 을 지움 (2026-07-26, 계측)

숫자 이탈 지점을 계측으로 확정했다:
1. StoryLoader GREP 리셋(340)에서 `상부자(이탤릭)` 숫자의 grepMathFont 는 **유지됨**
   (`isGrepSangbujaItalicNumber` 스코프 작동, 7건 확인)
2. 그러나 바로 뒤 **EH폰트 리셋(StoryLoader:377~380)** 이
   `(!paraHasMathSymbols && !isSingleLatinVar) || nearLongWord || koreanOnly` 조건에서
   `run.fontFamily(null)`·`run.fontStyle(null)`·**`run.appliedCharacterStyle(null)`** 로
   `상부자(이탤릭)`(=EH상부자 폰트) 런의 charStyle 을 통째로 지운다
3. 그 결과 이후 enterBT 판정·`isGrepSangbujaItalicNumber`(charStyle 참조)가 실패 →
   숫자가 `mathGroup.add`(592)·`else`(595) 어디에도 안 옴 → `flushMathGroup` 미도달
   (계측: XDBG-flush/add/else 모두 0건)

즉 진짜 블로커는 FormulaClassifier 가 아니라 **EH폰트 리셋의 charStyle 제거**다.
숫자를 수식화하려면 EH폰트 리셋에서도 `상부자(이탤릭)` 숫자를 보존해야 한다.

### 이행 플랜

1. **[완료] 숫자 이탈 지점 확정** — 위 블로커. EH폰트 리셋(377)이 원인
2. **[스코프] `상부자(이탤릭)` 숫자만 grepMathFont + charStyle 유지**: StoryLoader GREP
   리셋(340)과 **EH폰트 리셋(377) 양쪽에** `isGrepSangbujaItalicNumber` 예외를 넣어
   `isGrepSangbujaItalicNumber`(charStyle=상부자(이탤릭) + 숫자 토큰) 추가. 화학식은
   `상부자(이탤릭)` 미사용(전수 0건)이라 무영향
3. **[방출] FormulaClassifier 순수숫자 허용**: `상부자(이탤릭)` 근거(evidence)면
   line 39·43·44 의 순수숫자 거부를 우회. 단 evidence 판정을 grepMathFont 전체가 아니라
   `상부자(이탤릭)` 로 좁혀, 다른 grepMathFont 숫자(수식 조각)에 안 번지게 함
4. **[위첨자 정합]** Phase 1 밑수 가드와 충돌 점검: 수식화된 숫자는 위첨자 대상에서
   빠지므로 Phase 1 가드가 무력화하지 않는지 확인
5. **[회귀]** 수학 u1(숫자 수식 +N, 위첨자 추가 감소), 과학 u1(화학식 수식 무변경),
   영어 u1(무영향) 3중 검증. 골든 게이트

### 안전성

과학 화학식 계수·첨자는 `상부자(이탤릭)` 을 전혀 안 쓴다(수식(첨자-하부자)/
[No character style], 화학식 스토리 전수 0건) — 이 스코프는 화학식과 무관.
Phase 1(위첨자 밑수 가드)은 이미 머지됨(#175). Phase 2 는 그 위에 얹는다.

## 수정 파일

1. `converter/.../idml/IDMLCharacterRun.java` — `isSuperscriptByStyleNameOnly()`
2. `converter/.../normalizer/resolved/phase3/MathProcessor.java` — 밑수 가드 + 호출부 2곳
3. `converter/.../normalizer/resolved/phase3/RunPostProcessor.java` — 밑수 가드

## 검증

- [x] 수학 u1: 위첨자 **207 → 30**. p14 "4·2·-2·25" 위첨자 아닌 본문 크기.
      **수식 719→719 무변경**(진짜 지수 2²·화학식 온전)
- [x] 과학 u1: 수식 56→56 무변경 — **화학식(CaCO₃·CaCl₂·2HCl) 회귀 0**
- [x] 영어 u1: 무영향 (warnings=0)
- [ ] 한글 육안 확인

## 주의사항

- **`상부자(이탤릭)` = EH상부자 폰트 + charStyle 이름**만으로 위첨자가 되는 게 근본. position
  속성과 무관하다. 진짜 첨자(position=superscript)와 이름-폴백을 구분해야 본문 숫자만 되돌린다
- 남은 위첨자 30개는 밑수 뒤(진짜 지수)이거나 쉼표·단위 등 별개 경로 — 대부분 정당
