# SPEC-081: EH/GREP 수식 글리프 전수 조사 기반 알고리즘 재설계

> 상태: **구현 완료, 무회귀 검증 완료(수학 1~5단원 전수)**. 2026-07-27.
>
> 배경: "하나 지적 → 하나 수정" 방식이 회귀를 부르고 근본을 못 잡아, 수학 1~5단원의
> GREP+수식(EH/BT/NP + 상부자(이탤릭)) 케이스를 **전수 조사**해 알고리즘을 재설계했다.
> 5단원 전체 3,577개 수식을 census 로 덤프·분류 → 구조적 버그 확정 → 단일 관문 수정.
>
> **결과: 336개 수식 개선, 수식 개수 완전 동일(0 손실/0 추가) — 무회귀.**
>
> | 클래스 | 전(5단원 합) | 후 | 예시 |
> |--------|------|-----|------|
> | A. `'`=근호 미디코딩 | 112 | 1 | `{'3} over {'2}` → `{sqrt{3}} over {sqrt{2}}` |
> | Z. 라틴-1 글리프 누수 | 141+ | 1 | `90ù`→`90°`, `BCÓ`→`BC²`, `-bÑ`→`-b±` |
> | B. `^{V}`=곱셈(×) 오인 | 45 | 0 | `2^{V}3` → `2 TIMES 3` |
> | E. `;4!;`=GREP분수 | 5 | 0 | `;4!;` → `{1} over {4}` (¼) |
> | D. `cm²` 단위 제곱 | 12 | 2 | `cm`+`^{2}` → `rm cm^{2}` |
>
> 남은 2건: `△ABC^{9}△ADE`(∽ 닮음 기호 오인, HWP 키워드 미상 — 별도) + u3 인라인
> 프레임 뒤 `^{2}` 체인. `{B'C'Ó}`→`{B sqrt{C}^{2}}` 4건(원래도 깨져 회귀 아님).

## 조사 방법 (재현 가능)

`core/.../ast/MathCensusDumper.java` 를 `HwpxParagraphBuilder` 단락 루프 초입에 삽입.
환경변수 `MATH_CENSUS=<path>` 있을 때만 최종 AST 단락(ASTEquation 포함 또는
grepMathFont/grepStyleApplied 런 포함)을 소스 텍스트 + 항목별 스크립트/sourceType JSONL 로 덤프.

```bash
MATH_CENSUS=census-u1.jsonl java -jar converter/target/...-cli.jar --convert output.idml out.hwpx --links-directory Links
python3 scratchpad/analyze_census.py census-u1.jsonl <category>
```

1단원 결과: **435 단락 / 835 수식 / 491 고유 스크립트**.

## 발견된 버그 클래스 (1단원, 심각도 순)

### A. `'`(U+0027) = 근호 갈고리(√) 미디코딩 — **141건, 최대 버그**

`EH분수대문자` 폰트의 `'` 글리프는 근호 갈고리다. 그런데 **인라인 분수 프레임**
(`InlineFrameHandler.tryInlineFractionAsEquation` → sourceType `INLINE_FRACTION`)에서
분자/분모 런의 `'` 가 그대로 새어 `{'3} over {'2}` 로 나온다. 올바른 출력은
`{sqrt{3}} over {sqrt{2}}`(=√3/√2). 소스가 직접 증언한다:

```
{'3} over {'2} = sqrt{{3} over {2}}     ← '3/'2 는 √(3/2)
```

원인: `InlineFrameHandler.convertRunsToHwpScript`(178~197)가
`EHFontGlyphMap.isEHFontFamily` 로 EH 폰트가 있을 때만 `EHFontEquationConverter` 를 타고,
아니면 raw 텍스트(`'3`)로 폴백(162~163). 분수 프레임 분자/분모의 `'3` 런이 EH 폰트로
인식되지 않아 `'` 가 리터럴로 남는다. `lexFractionUpper` 의 HOOK→sqrt 경로가 이 문맥에서
작동하지 않음. (58 고유 / 87 in-fraction + 47 단락)

### B. `^{V}` = 곱셈(×)·체크(✓) 글리프 오인 — 16건

`V` 를 상부자(위첨자)로 오해 → `^{V}`. 실제 의미는 두 가지다:
- **곱셈 ×**: `2^{V}3`(=2×3), `2^{V}sqrt{3}`(=2√3), `^{V}{'3} over {'2}`(=×√3/√2).
  EH수식에서 `_`→× 매핑은 있으나 `V`(다른 곱셈 글리프)는 미매핑.
- **체크 ✓**: `옳으면 ◯ 옳지 않으면 〖^{V}〗표` — 정오 체크표. 위첨자가 아니라 ✓ 문자.

`^{V}` 는 어느 쪽이든 위첨자가 아니다. 문맥(피연산자 사이 vs 지시문)으로 ×/✓ 구분 필요.

### C. `Û`(²) 단위 제곱이 base 없는 `^{2}` 로 분리 — sqrt+unit 1건 + bare-caret

`3cmÛ`(3cm²) 이 `3`(텍스트)+`cm`(텍스트)+`^{2}`(base 없는 수식)로 쪼개진다. 사용자
요청: `3cm²`·`5cm²` 를 통째 수식(`rm 3cm^{2}`)으로. 같은 단락 설명부의
`sqrt{3cm},~sqrt{5}` 는 **올바름**(√3 cm, √5 cm = 넓이 3cm²·5cm² 의 한 변) — 버그 아님.

### F. `Û`/`Ü`(²/³) 글리프가 분수 안에서 미디코딩 — 2건

`{30} over {10Û`}`(=30/10²), `{('3 )Û`} over {('2 )Û`}`(=(√3)²/(√2)²). A 와 같은
INLINE_FRACTION 경로에서 `Û` 도 `^{2}` 로 디코딩 안 됨.

### 기타 (미디코딩 잔여)

- `\x8c`(U+008C, 분수대문자 자리구분자) 21건 — 분수 스크립트에 장식 글리프 누수.
- `à`(U+00E0) 1건, `;`(GREP 분수 원문) 2건, `sqrt{ }`(빈 근호, radicand 유실) 10건 중
  일부는 정상(기호 설명 "기호 √를 사용")이나 `sqrt{ }16`(=√16)처럼 radicand 유실도 혼재.

## 근본 원인 (공통 구조)

**근호 갈고리 `'`·자리구분 `\x8c`·제곱 `Û` 같은 EH분수대문자/상부자 글리프의 디코딩이
경로마다 제각각**이다. `EHFontEquationConverter`(EHLexer/EHParser) 는 이들을 안다. 그러나:
1. `InlineFrameHandler.convertRunsToHwpScript` 는 EH 폰트 판정 실패 시 raw 폴백 (A/F 원인)
2. 상부자 `V`(×/✓)는 `EHFontGlyphMap` 매핑 테이블에 없음 (B 원인)
3. `Û` 단위 제곱은 EH 그룹에 안 들어가고 개별 `^{2}` 수식화 (C 원인)

→ 재설계 방향: **모든 수식 스크립트 생성 경로가 단일 글리프 디코딩 계층
(`EHFontGlyphMap`/`EHLexer`)을 거치도록 통일**하고, 근호 갈고리·자리구분·상부자
연산자 글리프를 그 계층에서 완결 처리한다. 경로별 raw 폴백 금지.

## 구현 (완료)

핵심: **모든 수식 스크립트가 반드시 거치는 단일 관문 `EquationBuilder.sanitizeHwpScript`
에 통일 글리프/분수 정리를 넣는다.** EH/BT/NP/INLINE_FRACTION 어느 경로든 최종 emit
직전 이 관문을 통과하므로, 경로별 raw 폴백이 남아도 여기서 마지막에 정리된다.

1. **[A/F/G] INLINE_FRACTION 경로가 근호 글리프를 디코딩** — `InlineFrameHandler`:
   - `convertRunsToHwpScript`: resolved DOM 이 분수대문자 근호 갈고리(`'`)·자리구분자
     (0x8C)·제곱(Û) 런의 `fontFamily` 를 null 로 흘린다(실측 확인). 폰트가 null 이어도
     글리프 자체로 분수대문자 문맥임을 판정(`containsFractionUpperGlyph`)해 `EH분수대문자`
     폰트를 되찍어 `lexFractionUpper` 의 HOOK(√) 경로를 타게 한다.
   - raw 폴백 텍스트에도 `EHFontGlyphMap.decodeStrayGlyphText(t, null)` 적용.
2. **[B] `^{V}` → `TIMES`** — `EquationBuilder.normalizeStrayEHGlyphs`: 전수 45건 전부
   피연산자 사이 곱셈(`2^{V}3`·`8^{V}12=96`·`10^{V}sin`·삼각형 넓이 `½^{V}4^{V}6`).
   체크(✓) 문맥은 수식 아닌 텍스트라 여기 안 옴 → 무조건 곱셈으로 통일.
3. **[Z] 라틴-1 EH 글리프 통일 치환** — 같은 메서드: `ù/¿→°`, `Ó/Û→^{2}`, `Ü→^{3}`,
   `Ñ→+-`(±), `Ã`(근호 폭선택자)→제거. `sqrt{Ó}` 오묶임은 `^{2}` 로 되돌림.
4. **[E] 잔여 GREP 분수** — `decodeResidualGrepFraction`: `;inner;` 를
   `decodeFractionInner` 로 `{num} over {denom}` 복원(경사 `y=-;4!;x` 등).
5. **[C/D] 단위 제곱 병합** — `HwpxParagraphBuilder.mergeUnitExponentEquations`:
   `"…cm"`(텍스트) + 밑수 없는 `^{n}`(수식) → 단위를 떼어 `rm cm^{n}` 수식으로 흡수.
   AST 렌더 루프 전에 in-place 정규화. 설명부 `sqrt{N} cm`(√3 cm)는 대상 아님.

## 수정 파일

1. `converter/.../equationconverter/EquationBuilder.java` — sanitize 관문에
   `normalizeStrayEHGlyphs`(^{V}→TIMES, ù→°, Ó→², Ñ→±…) + `decodeResidualGrepFraction`
2. `converter/.../resolved/phase3/InlineFrameHandler.java` — `convertRunsToHwpScript`
   근호 글리프 폰트 복원 + `containsFractionUpperGlyph` + raw 폴백 글리프 sweep
3. `converter/.../converter/HwpxParagraphBuilder.java` — `mergeUnitExponentEquations`
4. `core/.../ast/MathCensusDumper.java` — 전수 조사 덤퍼(MATH_CENSUS gated, 프로덕션 무영향)

## 안전성 / 회귀 경계

- 정상 케이스(수식 719→835 의 GREP 숫자 수식화 SPEC-080)는 유지.
- `sqrt{3cm}`(√3 cm)처럼 **올바른** sqrt+unit 은 C 대상 아님 — 구분 필수.
- 과학 u1 화학식은 상부자(이탤릭)·분수대문자 미사용 → 무영향 (SPEC-080 확인).

## 조사 산출물

- 덤퍼: `core/.../ast/MathCensusDumper.java` (MATH_CENSUS gated, 프로덕션 무영향)
- 분석기: `scratchpad/analyze_census.py`, `scratchpad/classify.py`
- 원시 census: `scratchpad/census-u1.jsonl` (435 단락)

## 후속: 텍스트 런으로 샌 GREP 분수 (F 클래스, 육안 검수 중 발견)

수식 census 는 ASTEquation 만 봐서 놓쳤던 별개 leak: GREP 분수가 **수식 그룹에 안 묶여
본문 텍스트 런으로** 새어 `;2!;`·`;;Á7°;;`·`y=;2!;xÛ` 처럼 raw 노출된다(전수 23건).
p33 ⑷ `√(1/3) √l ;;Á7°;;`(사용자 지적)가 이 케이스 — `;;Á7°;;`가 텍스트로 남아 있었다.

`RunPostProcessor.convertGrepFractionTextRuns`: 텍스트 런의 `;inner;`/`;;inner;;`를
`decodeFractionInner` 로 분자/분모 복원 → `{num} over {denom}` 수식. 분수 앞이 근호
갈고리(`®`·`'`)면 `sqrt{...}`로 감싼다. StoryLoader(본문·셀)·StoryConverter 3경로에
호출. **+19 수식 전환**(`;2!;`→½, `;;Á7°;;`→15/7, `y=;2!;x²`→y=½x²), 수식 유실 0.
남은 3건: 표 셀 깊은 경로의 bare `;2!;` 1건 + `;1̅0;`(반복소수 표기, 분수 아님) 2건.

## 검증

- [x] 수학 1~5단원 전수 재-census: A 112→1, Z 141→1, B 45→0, E 5→0, D 12→2, F 23→3
- [x] p33 ⑷: `;;Á7°;;` 텍스트 → `{15} over {7}` 수식 전환 확인
- [x] p19 육안 검수(사용자 지적) 2건:
  - `sqrt{3cm}` → `sqrt{3} cm` (근호가 단위 cm 까지 삼킨 것 → `ejectUnitFromRadicand`
    가 근호 밖으로. √는 수치에만). 전 단원 통틀어 대상 1건뿐(오적용 없음)
  - `3cm^{2},` 병합 누락 → 병합됨. 첫 지수가 `^{2},`(꼬리 쉼표)라 순수 매칭에 안 걸리던
    것 → `BARE_EXPONENT` 정규식이 꼬리 구두점 허용(보존). bare `^{n}` 5단원 0(단위²)
- [x] 무회귀: baseline(수정 전) vs 수정 후 수식 개수 5단원 전부 동일
      (835/1085/810/495/352, 0 손실/0 추가), **336개 내용만 개선**. before/after
      페어 스팟검수로 전부 개선 확인(`^{V}`→TIMES, `'N`→sqrt{N}, `;4!;`→¼, cm²)
- [x] 안전성: 과학 화학식은 상부자(이탤릭)·분수대문자 미사용(SPEC-080 전수 0건).
      변경은 EH 글리프 sanitize + 인라인 분수 + 단위 제곱만 건드림 → 화학식 무영향
- [ ] 한글 육안 확인 (분수·근호·곱셈)

## 조사/검증 산출물 (scratchpad)

- census 덤퍼: `core/.../ast/MathCensusDumper.java`
- 분석기: `verify_hwpx_eq.py`(최종 HWPX emit 스크립트 분류 — post-sanitize 진짜 상태),
  `census_all.py`(AST 단계 다단원 분류), `analyze_census.py`
- 재현: `MATH_CENSUS=out.jsonl java -jar …-cli.jar --convert output.idml out.hwpx …`
