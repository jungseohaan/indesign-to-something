# SPEC-079: 발문 변수 x 가 인접 구두점 병합으로 수식화 유실

> 상태: **해결 (회귀 0)**. 2026-07-26.
> 사례: 수학 u1 p12 "다음 직각삼각형에서 **x**의 값을 구하시오" — x 가 hp:equation 이
> 아니라 이탤릭 텍스트로 남는다.

## 해결 (2026-07-26)

**초기 조사의 "grepMathFont 리셋 ↔ 병합 3단계 얽힘" 진단은 부분적 오진이었다.**
실제로 x 는 순수 `[x]` 런으로 **이미 수식 그룹(BT)에 들어가 hp:equation 으로 변환됐다가**,
Stage 3 후처리 `demoteIsolatedSingleLetterMathEquation`(SPEC-078 audit-A)이 **고립 단일
라틴 문자 수식을 이탤릭 텍스트로 되돌리면서** 유실됐다. 이 강등은 과학자 이니셜
(A/J/L)의 오수식화를 막으려던 것인데, 진짜 수학 변수 x 까지 되돌렸다.

**수정: 강등에서 "이탤릭 소문자"(수학 변수)만 제외.** 3파일 +26줄:
1. `ASTEquation` — `sourceItalic` 필드 추가
2. `ASTMathGrouper.flushMathGroup` — GREP 수식 생성 시 원본 이탤릭 여부 기록. 신호는
   명시 `fontStyle=Italic` 또는 GREP 적용 문자 스타일 이름의 "이탤릭"/"italic"
   (수학 변수 전용 스타일 "상부자(이탤릭)")
3. `MathProcessor.demoteIsolatedSingleLetterMathEquation` — `sourceItalic && 소문자`면
   강등 스킵. **대문자는 이탤릭이어도 강등 유지** — 항목 기호(A. B. C. R.)·이니셜이
   같은 GREP 이탤릭 스타일을 받아, 대문자까지 살리면 보기 번호가 수식이 된다

**폐기한 접근**: 변수+구두점 런("x,")에서 구두점을 떼는 상류 split 은 x 를 살리긴 했으나
"a,b"(수식) 의 쉼표를 공백으로 바꾸는 회귀를 냈다. split 없이 강등 가드만으로 x 가
살아나므로(x 는 이미 순수 [x] 런) split 은 불필요 — 제거했다.

**검증**: 수학 u1 685 → 719 수식(+34: x·a·b·n·r 소문자 변수). **없어진 수식 0 —
회귀 완전 없음**. "a,b"·"A=sqrt.." 온전, 항목 기호 J/B/C/R 텍스트 유지. 영어 u1 무영향
(equations=0, warnings=0).

## 문제

수학 발문의 변수 x(y, a, n 등)가 수식(hp:equation)으로 변환되지 않고 이탤릭 평문
텍스트로 남는다. IDML 원본은 GREP 스타일로 x 에 수식 문자 스타일(`상부자(이탤릭)`,
폰트 EH상부자)을 입혀 수식임을 표시하는데, 변환 파이프라인이 이를 중간에 잃는다.

## 원인 (계측으로 규명, 2026-07-26)

원인 사슬 4단계:

1. **IDML GREP 판정은 정상**: 발문 파라스타일의 GREP 규칙
   `\d+|[\l\u]|[...]` → charStyle `태광10.5:상부자(이탤릭)`(폰트 EH상부자)이 x 에
   매칭. `IDMLStoryParser.resolveGrepForParagraph`(line ~2104)가 `run.grepMathFont(true)`
   설정. **여기까진 x 가 수식 근거를 가진다.**

2. **resolved 매칭에서 x 가 인접 구두점과 병합**: x 다음이 ", " 라 한 런으로 묶여
   content=`"x,"`(길이 2)가 된다. (순수 `"x"` 단독 런은 문서에 19건 있고 정상 유지됨)

3. **StoryLoader 의 "GREP 수식 리셋" 이 유실 지점**:
   `StoryLoader.buildParagraphContent`(line ~340)
   ```java
   boolean isSingleLatinVar = ct != null && ct.trim().length() == 1
           && Character.isLetter(ct.trim().charAt(0));
   if (!isSingleLatinVar) { run.grepMathFont(false); ...; run.fontStyle(null); }
   ```
   `"x,"` 는 trim 길이 2 라 단일 변수로 안 잡혀 **grepMathFont 리셋 + fontStyle 제거**.
   x 가 수식 근거를 완전히 잃는다.

4. **수식화 실행 경로도 못 탐**: 최종 수식화는 (a) `StoryLoader` BT그룹 진입조건
   (line ~475)의 `|| run.grepMathFont()`, 또는 (b) `RunBuilder.splitLatinVarsInMixedText`
   (단, hasKorean 게이트 필요, 순수 라틴 "x," 런은 통과 못 함). 3단계에서 리셋됐으니
   (a)도 막히고, 순수 라틴이라 (b)도 막힌다.

## 실패한 수정 (회귀로 되돌림)

세 변형 모두 **일관되게 회귀** 발생:

- **완화 A**: 리셋의 isSingleLatinVar 를 "라틴 문자 1개 + 나머지 구두점/공백" 으로 완화.
  → 회귀: `a,b`(-8건), `A=sqrt{18}+2 sqrt{50}+sqrt{98}`, `A=sqrt{(x+1)²}-sqrt{(1-x)²}`
  등 수식 8건+ 이 깨짐. **변수가 수식에서 떨어져 나감**(`A=sqrt..`→`sqrt..`).
  grepMathFont 유지가 다운스트림 수식 그룹 병합(MathProcessor)을 교란.

- **완화 B**: A + 문단 한글 포함(`paraHasKoreanProse`) 가드 추가로 순수 수식 문단 제외.
  → 회귀 동일(a,b 든 문단도 한글 포함이라 가드 무력) + **x 는 여전히 텍스트**
  (grepMathFont 유지로도 수식화 트리거가 안 걸림 — 미규명).

baseline 수식 685개. 완화 시 674개(순증 없이 병합만 교란).

## 재설계 방향 (미확정 — 다음 조사 지점)

핵심 딜레마: grepMathFont 리셋은 "a,b" 같은 순수 수식 조각의 **잘못된 폰트 상속**을
막는 정당한 로직인데, 같은 리셋이 "x," 의 진짜 변수 근거도 지운다. 그리고 유지시키면
수식 그룹 병합이 교란된다 — **리셋 ↔ 병합 ↔ 단일변수 분리 3단계가 얽힘.**

조사 우선순위:
1. **왜 grepMathFont 유지 상태에서도 x 가 BT그룹(StoryLoader line 475)에 진입 못 하는지**
   부터 계측. 이게 안 풀리면 리셋을 고쳐도 x 는 수식이 안 된다.
2. 병합 교란의 실제 메커니즘 — "x," 유지가 왜 인접 문단의 "a,b" 조립을 바꾸는지
   (문단 경계? 수식 그룹 flush 타이밍?).
3. 구두점을 x 에서 **떼어내 별도 런으로 분리**한 뒤 순수 "x" 만 수식화하는 방향이
   리셋/병합을 안 건드려 더 안전할 수 있음(splitLatinVars 의 hasKorean 게이트 완화 대신
   구두점 분리를 상류에서).

SPEC-067(GREP 정상화/수식 그룹 경계 재설계) 계열. [[math-var-x-equation-loss-rootcause]]
메모리에 동일 요약.

## 수정 파일 (예정 — 미착수)

- `converter/.../resolved/phase3/StoryLoader.java` — GREP 수식 리셋 판정 (line ~340)
- `converter/.../resolved/phase3/RunBuilder.java` — splitLatinVarsInMixedText hasKorean 게이트 (line ~852)
- (조사 결과에 따라) `converter/.../idml/IDMLStoryParser.java` — 구두점 분리 상류 처리

## 검증 (착수 시)

- [ ] 수학 u1 p12 x 가 hp:equation 으로 변환
- [ ] 수식 수 회귀 없음 (baseline 685, `a,b`·`A=sqrt..` 등 유지)
- [ ] 골든 게이트 (수학 u1 골든은 stale — gitRev 720460fd, 재생성 필요)
- [ ] 한글 육안 확인
