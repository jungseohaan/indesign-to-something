# SPEC-040 — EH 수식 폰트 변환기 전면 재작성

> 상태: **구현 완료 (2026-07-18)** · 브랜치 `eh-rewrite`
> 관련: [SPEC-039](SPEC-039-empty-answer-box-inline-rect.md) (빈 답란 박스)

## 문제

EH 폰트(비상교육 중3수학/과학)는 라틴 글리프 위치에 수학기호를 넣은 "폰트
해킹"이다(√·²·÷·π). 기존 `EHTokenizer → EHIRBuilder → EHHwpScriptEmitter`
파이프라인은 근호·radicand 처리에서 케이스별 패치를 반복할수록 새 엣지케이스가
나오는 구조적 한계에 도달했다:

- **글리프 의미 손실**: `EHTokenizer.isFractionBarDecoration`("영숫자 아님 → 전부
  SQRT_MARKER")이 파이프라인 초입에서 근호 갈고리(0x27)·폭선택자·자리구분자(0x8C)를
  단일 토큰으로 뭉갰다.
- **스트림 heuristic**: `EHIRBuilder.processSqrt`가 국소 lookahead로 근호 범위를
  추측 → √10이 √1로 잘림, √(5/2)√10이 √(5/2·10)으로 뭉침, √(2×□)의 × 누락.

## 목표

- 스트림 heuristic → **재귀하강 문법**.
- 뭉개진 토큰 → **글리프 의미별 Lexeme**(코드포인트 단위 의미 보존).
- 센티넬·정규식 재조립 제거, 죽은 구 파이프라인 삭제.

## 해결 방안

3단계 유지, 내부 재정의:

```
convert(runs)
  → EHLexer   : runs → List<EHLexeme>   글리프 의미 보존, 구조판정 안 함
  → EHParser  : List<EHLexeme> → EHNode  재귀하강 근호 문법
  → EHHwpScriptEmitter : EHNode → hwpScript
```

**근호 문법** (핵심):
```
Sqrt     := HOOK WidthSel? Radicand
Radicand := RadAtom Trailing?
RadAtom  := Number | Var | Fraction | ParenGroup | Box
Number   := DIGIT (DIGIT_SEP DIGIT)*     // 0x8C 이어붙임 → √10 잘림 차단
```
- HOOK마다 새 Sqrt → √2·√3 자동 분리("바 연장 추측" 삭제).
- hook 직후 DIGIT_SEP은 폭 마커로 스킵(앞 숫자 없음) → √a 가 sqrt{ }a 로 새는 것 방지.
- 상부자 폰트라도 기본범위(0x20-0x7F) 글리프는 위첨자가 아니라 작은 크기 radicand.
  진짜 지수는 확장범위(0x80+ ²³) 또는 백틱(`) 종료.
- radicand 뒤 지수는 근호 밖((√3)²) → emitter가 `{sqrt{3}}^{2}` 래핑.
- 빈 근호 + 위첨자 → 위첨자가 radicand(√² 의 2) → `sqrt{2}` (빈지수 `{sqrt{ }}^` 차단).
- BOX(ANSWER)가 RadAtom 자리 → √□, √(2×□)를 문법으로 흡수.

## 수정 파일

1. `equationconverter/idml/EHLexeme.java` (신규) — 글리프 의미 렉심 타입.
2. `equationconverter/idml/EHNode.java` (확장) — Box/Overline/RecurDot/Paren 추가.
3. `equationconverter/idml/EHLexer.java` (신규) — 코드포인트 순회 렉싱, 인라인
   Rectangle → BOX(ANSWER/VINCULUM), GREP 분수는 `EHFontGlyphMap.decodeFractionInner` 공유.
4. `equationconverter/idml/EHParser.java` (신규) — 재귀하강 근호 문법.
5. `equationconverter/idml/EHHwpScriptEmitter.java` (확장) — 신 노드 emit,
   `{sqrt}^{n}` 트리 래핑, 끝 매달린 TIMES/div 제거.
6. `equationconverter/idml/EHFontEquationConverter.java` (축소) — convert() 3단계 호출만.
   `restoreBoxBraces`/`applyRecurringDecimalDots` 삭제.
7. `phase3/StoryLoader.java` — `absorbAnswerBoxIntoOpenSqrt` + 헬퍼 삭제
   (정규식 재조립 → 문법 흡수).
8. **삭제**: `EHTokenizer.java` / `EHIRBuilder.java` / `EHToken.java` (dead).

**유지 (재사용)**: `EHFontGlyphMap`(글리프 디코딩), `EHGrepFractionConverter`
(Korean 프로세 속 `;...;` 분수 분리 — `convert()`가 다루지 않는 별개 책임,
decodeFractionInner 공유). BT/NP 폰트 변환, 화학식 처리.

## 검증

**골든 단위테스트** `EHFontEquationConverterTest` (17건, 전부 통과): sqrt_single_digit,
sqrt_split_number_10/144, sqrt_variable, sqrt_variable_after_leading_sep,
sqrt_superscript_font_radicand, sqrt_radicand_then_exponent,
empty_sqrt_superscript_is_radicand, two_sqrts_product, grep_fraction_half,
sqrt_fraction, two_sqrts_fraction_then_number, sqrt_answer_box,
sqrt_number_times_box, superscript_square, recurring_decimal, pi.

**E2E** (구 empty-answer-box vs 신 eh-rewrite, 전 12유닛 무회귀):

| unit | 빈sqrt 구→신 | sqrt총 구→신 | box |
|------|------|------|-----|
| math_u1 | 26→16 | 757→743 | 13→12* |
| math_u2 | 1→0 | 88→79 | — |
| math_u3/u5, sci_u1~8 | 동일 | 동일 | 동일 |

\* box 13→12: 원문이 깨진 근호 나열(`sqrt{2} sqrt{5=}sqrt{p2} TIMES`)에 구 코드가
box{~}를 억지로 끼워 깨진 출력을 가리던 것을 제거. 정상 √(2×□)는 문법이 그대로 처리.

- [x] 빌드 성공 (`mvn clean install -DskipTests`)
- [x] 골든 17/17
- [x] E2E 전 유닛 무회귀 (빈sqrt 감소, 빈지수 0)
