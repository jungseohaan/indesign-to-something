# 수식/화학식 변환기 재구현 SPEC

> **목적**: InDesign(IDML) 교과서 조판의 "폰트 해킹" 수식·화학식을 한/글(HWP) 수식
> 스크립트(`hp:equation`)로 변환하는 현행 Java 구현을, **다른 언어로 동일 기능을
> 재구현**할 수 있도록 언어 중립적으로 기술한다. 함수명은 참조용이며 계약·알고리즘이
> 규범이다.
>
> 원본 코드: `converter/src/main/java/kr/dogfoot/hwpxlib/tool/equationconverter/`,
> `…/tool/idmlconverter/normalizer/` (ASTMathGrouper·MathProcessor·math/ 패키지 등)

---

## 1. 문제 정의와 전체 구조

### 1.1 입력의 본질: 폰트 해킹

교과서 InDesign 문서의 수식은 수식 개체가 아니라 **일반 텍스트 + 특수 폰트**다.
유니코드 cmap 은 표준이지만 글리프 모양만 수학 기호로 바꿔치기했다. 따라서:

- 파일에 저장된 문자(예: `'`, `Û`, `;2!;`, `H`)와 시각적 의미(√, ², ½, 순환점)가 다르다
- 의미는 **적용된 폰트/문자스타일**에 의존한다 — 같은 문자가 폰트마다 다른 기호
- 하나의 수식이 폰트 경계·GREP 스타일 적용·hair space 때문에 여러 런으로 파편화된다

### 1.2 폰트 체계 (4계열)

| 체계 | 출처 | 인코딩 방식 | 변환 방식 |
|---|---|---|---|
| **EH계열** (EH수식/상부자/하부자/분수대문자/분수소문자/약물/루트/선모음) | 비상 수학 | 라틴-1 확장(0x80–0xFF) 글리프 + GREP `;…;` 분수 | 렉서→파서(AST)→이미터 3단계 |
| **BT계열** (BT수식M, BT화살표) | 수학·과학 | ASCII 마커 문법 (`_x` `^x` `&` `rt…&` `zrt`) | 문자열 다단계 치환 |
| **NP계열** (NP_*) | 수학 | **폰트 variant 이름 자체**가 구조(NP_RUT=근호, NP_BUN=분수…) | 런 순회 상태머신 |
| GREP 수식 (폰트 무관) | 공통 | 문단스타일의 GREP 규칙이 문자패턴에 수식 charStyle 적용 | 스타일 해석 후 위 경로로 합류 |

### 1.3 출력: HWP 수식 스크립트

한/글 `hp:equation`의 `<hp:script>` 문자열. 주요 문법:

```
sqrt{X}          근호        {A} over {B}    분수
^{X} _{X}        위/아래첨자  dot{X}          순환마디점
overline{X}      선분 표기    bar X           overline(BT 경로)
box{~}           답란 빈칸    left( … right)  자동 크기 괄호
TIMES div +- !=  연산자      LEQ GEQ         ≤ ≥
rarrow larrow    화살표      CDOTS           …
rm X             로만체(화학식) ~              수식 내 공백
pi alpha …       그리스어    DEG, DEG C      ° ℃
INF IN SUBSET CUP CAP therefore int sum prod angle bot parallel equiv approx
```

### 1.4 변환 파이프라인 (조감)

```
IDML 스토리 런 (문자 + charStyle/fontFamily + 인라인그래픽)
  │  ① 폰트 역할 판정 (charStyle 이름 우선, fontFamily 폴백)
  │  ② GREP 수식 스타일 해석 (mixed run 분할, BasedOn 상속)
  ▼
런 그룹핑 (문단 단위 순회: EH/BT/NP 그룹 진입·이탈·flush)   …… 3장
  │  ③ 그룹 → 글리프 변환기 (EH: Lex→Parse→Emit / BT / NP)  …… 2장
  │  ④ 화학식 판정·클러스터 수집 → CHEM_FORMULA equation     …… 4장
  ▼
후처리 패스 (재조립·승격·강등: 파편 화학식, 계수 흡수, 단일문자 강등) …… 5장
  │  ⑤ 3단계 수식 파이프라인 (Span→Structure→Validate)      …… 3.5절
  ▼
sanitizeHwpScript (단일 정규화 관문)                        …… 2.7절
  ▼
AST Equation { hwpScript, type(EH_FONT|CHEM_FORMULA|…), baseUnit, color }
  ▼
HWPX emit: <hp:equation><hp:script>…  (화학식은 이 시점에 "rm " 접두)
```

### 1.5 입력 데이터 계약 (런 모델)

변환기가 소비하는 최소 런 모델 (언어 중립):

```
CharacterRun {
  content        : string        // 텍스트. U+FFFC = 인라인 앵커 자리
  charStyle      : string?       // 적용 문자스타일 이름 (역할 판정 1순위)
  fontFamily     : string?       // 폰트명 (2순위 폴백)
  fontStyle      : string?       // Bold/Italic 등
  fontSize       : number?       // pt
  fillColor      : string?       // 색 참조 ("Color/…")
  position       : SUPER|SUB|null  // DOM 보고 첨자 (신뢰도 낮음 — 4.4절)
  baselineShift  : number?
  tracking       : number?
  grepMathFont   : bool          // GREP 규칙으로 수식 폰트가 적용된 런
  inlineGraphics : [ InlineGraphic { type, widthPt, heightPt, vectorShape{fill,stroke} } ]
}
```

---

## 2. 글리프 변환기 계층 (폰트별 디코더)

### 2.0 공통 원칙

- **역할 판정은 문자스타일 이름이 1순위**다. AppliedFont 직접 지정 런은 실측상
  극소수이고 절대다수가 CharacterStyle 이름으로 식별된다. fontFamily 는 폴백.
- 같은 코드포인트가 **폰트 역할별로 다른 의미**를 가진다 (예: 0xDB 는
  상부자 테이블에선 `2`, EH수식 테이블에선 `g`). 테이블을 역할별로 분리할 것.
- **불가시 코드포인트가 의미를 가진다** (재구현 시 절대 정규화로 지우면 안 됨):
  - `0x8C` 자리구분자 (여러 자리 radicand 의 숫자 사이 장식)
  - `U+241C` 근호 종료+항 간격 센티넬 (원본의 투명 스페이서 Rectangle 자리)
  - `U+2028` 행 경계, `U+2003/2009/2005/2002` 조판 간격, `U+00A0` NBSP
  - `U+E000/E001/E002` overline 사설 마커 (내부 처리용)
  - `U+FFFC` 인라인 앵커
- 모든 경로의 산출물은 최종적으로 단일 관문 `sanitizeHwpScript`(2.7)를 통과한다.

### 2.1 EH 역할 판정과 렉싱 분기

`isEHFontStyle(style)`: 스타일명에 `/EH` 포함 OR
`상부자|하부자|분수대문자|분수소문자|선모음|약물|수식|루트` 중 하나 포함.
`extractFontFromStyle`: 포함 키워드로 역할명 정규화 (기본 폴백 `EH수식`).

| 역할 | 렉싱 처리 |
|---|---|
| EH분수대문자 | hook/폭선택자/자리구분자/GREP분수/atom 렉싱 (근호의 본체) |
| EH분수소문자 | 확장범위 디코딩 → ATOM (실측 극소) |
| EH상부자·EH고딕상부자 | 위첨자 렉싱 (기본범위는 ATOM! — 아래 주의) |
| EH하부자·EH고딕하부자 | 아래첨자 렉싱 |
| EH약물 | 화학·기호 렉싱 (π, ⌒, ≡, ±, …, 순환점) |
| EH루트·EH선모음 | SKIP (시각 장식 전용 폰트) |
| EH수식 + 백틱 포함 | 백틱 지수 렉싱 (`2\`` → ^{2}) |
| 그 외 | 본문 렉싱 (GREP 분수·자리구분·일반 텍스트) |

**주의(상/하부자)**: 기본범위(0x20–0x7F) 글리프는 위첨자가 아니라 "작게 조판된
radicand/본문"이다 → ATOM 으로. 진짜 지수는 확장범위(0x80+) 글리프이거나 백틱으로
닫힌다. `÷ × ± °` 는 첨자 폰트에 있어도 일반 ATOM.

### 2.2 EH 글리프 매핑 테이블

#### (a) 상부자/하부자/분수 공유 확장 테이블 (0x80–0xFF)

| CP→ | CP→ | CP→ | CP→ |
|---|---|---|---|
| 0x81 A | 0x82 C | 0x8C a | 0x8D c |
| 0xA1 8 | 0xA2 4 | 0xA3 3 | 0xA4 6 |
| 0xA5 8 | 0xA6 7 | 0xA7 s | 0xA8 r |
| 0xA9 g | 0xAA 2 | 0xAB E | 0xAC U |
| 0xAF O | 0xB0 5 | 0xB1 + | 0xB2 → |
| 0xB3 → | 0xB4 y | 0xB5 m | 0xB6 d |
| 0xB7 w | 0xB8 P | 0xB9 p | 0xBA b |
| 0xBB 9 | 0xBC 0 | 0xBD z | 0xBE ℃ |
| 0xBF ° | 0xC1 1 | 0xC2 l | 0xC3 v |
| 0xC4 f | 0xC5 x | 0xC6 j | 0xC7 n |
| 0xC8 \| | 0xC9 u | 0xCE Q | 0xCF q |
| 0xD0 −  | 0xD1 e | 0xD3 (overline 마커) | 0xD4 i |
| 0xD6 ÷ | 0xD7 V | 0xDA 1 | **0xDB 2** |
| 0xDC 3 | 0xDD 4 | 0xDE 5 | 0xDF 6 |
| 0xE0 7 | 0xE1 9 | 0xE2 0 | 0xE3 W |
| 0xE4 R | 0xE5 M | 0xE7 Y | 0xEA S |
| 0xEB D | 0xEC F | 0xEE H | 0xEF J |
| 0xF0 K | 0xF1 L | 0xF5 B | 0xF6 I |
| 0xF7 N | 0xF8 → | 0xF9 ° | 0xFA h |
| 0xFB k | 0xFC Z | 0xFD G | 0xFE X, 0xFF T |

미정의 코드포인트(0xD2, 0xD5, 0xF2 등)는 수평선/장식 → 스킵.
0xDB(Û)→`2` 가 핵심 (y=ax² 의 ² 인코딩). 0xC1(Á)→1 (이탤릭에서 l=1 동일 글리프).

#### (b) EH수식 폰트 전용 테이블 (큰 괄호·합성 글리프)

(a)와 **같은 코드포인트에 다른 값** — 반드시 역할로 분기:
0xA0→`}` 0xA4→`]` 0xA5/0xA6→`)` 0xA7→`(` 0xB0→`[` 0xB1→`{` 0xC8→π
0xD6→÷ 0xD3→overline 마커, 나머지는 대문자/소문자 군
(0x81 A, 0x82 C, 0x8C A, 0x8D C, 0x9C A, 0x9D C, 0xA2 y, 0xA3 g, 0xAB E, 0xAC U,
0xAF O, 0xB2 N, 0xB3 K, 0xB7 S, 0xB8 R, 0xB9 G, 0xC3 Y, 0xC4 M, 0xC5 D, 0xC6 W,
0xC9 P, 0xCA B, 0xCC Z, 0xCE Q, 0xCF O, 0xD2 L, 0xD4 F, 0xD7 N, 0xD8 U, 0xD9 f,
**0xDB g**, 0xDC j, 0xDD p, 0xDE q, 0xE2 E, 0xE3 W, 0xE4 R, 0xE5 M, 0xE7 Y,
0xEA S, 0xEB D, 0xEC F, 0xEE H, 0xEF J, 0xF0 K, 0xF1 L, 0xF5 B, 0xF6 I, 0xF7 N,
0xFC Z, 0xFD G, 0xFE X, 0xFF T)

#### (c) 근호 갈고리(hook)와 구조 글리프 (EH분수대문자)

| 글리프 | 의미 |
|---|---|
| `'`(0x27) | 기본 hook — 가로줄이 숫자 하나만 덮음 |
| `"`(0x22) | **키 큰 hook** — radicand 에 위첨자 포함(√3², √(-3)²). 누락 시 `"` 리터럴이 새어 수식 통째 유실 |
| `®` `¿` `¾` | hook 변형 |
| 0x8C | 자리구분자 (√121 의 1·2·1 사이) |
| hook 직후 0x80+ 1글자 | 폭 선택자 — 렉서는 보존만 하고 파서가 "뒤에 radicand 가 오는가"로 폭마커 vs 변수 판정 |

#### (d) EH약물 (기호·화학 폰트)

| 문자 | → | 실측 |
|---|---|---|
| `p` | π | -π, π+1 |
| `H` | 순환마디점(DOT) — 다음 숫자에 `dot{}` | 0.2̇8 류 순환소수 |
| `y` | … (말줄임) | √2=1.414… |
| `µ` | ⌒ (호) | ⌒AB (39회) |
| `ª` | ≡ (합동) | △OAM≡△OBM |
| `Ñ` | ± | ±√a (근의 공식) |
| `` ` `` | 제거 (위첨자 마커) | |

#### (e) 본문 폰트로 샌 글리프 (좁은 문맥 조건부 디코딩)

| 문자 | 조건 → 결과 |
|---|---|
| `ù` | 앞이 숫자 → ° (180ù) |
| `Û`/`Ü` | 앞이 영숫자/닫는 괄호 → ²/³ (홀로면 보존) |
| `Ö` | ÷ |
| `Ó`/`Õ` | 제거 (장식) |
| `µ` | 뒤가 단위(m/s/g/A/l/L/F)면 보존, 아니면 ⌒ |
| `ª` | ≡ |
| `_` | × (GREP ASCII 매핑) |

#### (f) overline (선분 표기)

`Ó`(0xD3) 마커 앞의 연속 **영문자**(숫자 제외 — 숫자는 계수)를 사설 마커
`U+E000…U+E001` 로 감싼다. 감쌀 문자가 없는 단독 Ó 런은 `U+E002` 경계마커
(직전 런 끝 대문자에 적용). 최종 `overline{…}` 방출. 실측: AM·BM 선분 (5단원 33런).

### 2.3 EH GREP 분수 (`;…;`)

문단스타일 GREP 이 `;분자분모;` 패턴에 분수 폰트를 적용한 것. 첫 `;` 과 둘째 `;`
사이 길이 1~10 이면 분수로 판정. inner 각 문자의 분자/분모 분류:

| 입력 문자 | 위치 | 변환 |
|---|---|---|
| 0x80+ | 분자 | 확장 테이블로 디코딩 |
| shift-숫자 `! @ # $ % ^ & * ( )` | 분자 | `1 2 3 4 5 6 7 8 9 0` |
| 대문자 A–Z | 분자 | 소문자로 |
| 숫자 0–9 | 분모 | 그대로 |
| 소문자 a–z | 분모 | 그대로 |
| `+ - =` | 분자(분자만 있을 때)/분모 | 위치 판단 |
| `[` `\` `]` | 분모 | 특수 글리프: x, g, y (실측: y=5/x) |
| `{ } \|` | — | 스킵(장식) |

예: `;2!;` → 분모 2, 분자 1(=`!`) → `{1} over {2}`.
출력: `{num} over {den}` (한쪽 비면 `{ }`). 디코딩 실패 시 원본 패스스루.

### 2.4 EH 렉서 (lexeme 11종)

| Kind | 생성 조건 |
|---|---|
| HOOK | 분수대문자의 `' " ® ¿ ¾` |
| WIDTH_SELECTOR | HOOK 직후 0x80+ 1글자 (별개 런으로도 옴) |
| DIGIT_SEP | 0x8C |
| ATOM_TEXT | 디코딩된 일반 텍스트 (U+FFFC·백틱 제거, `{`→`(` `}`→`)` — 단 box{~} 제외) |
| SUPERSCRIPT | 상부자 확장범위 / ASCII+백틱 / EH수식 백틱 지수 |
| SUBSCRIPT | 하부자 확장범위 |
| FRACTION(num,den) | GREP `;…;` 또는 분수 폰트 쌍 |
| BOX(ANSWER\|VINCULUM) | 인라인 rectangle 그래픽. `w ≥ h×2` → VINCULUM(가로막대), 아니면 ANSWER(답란) |
| OVERLINE | 0xD3 마커 |
| DOT | EH약물 `H` |
| SEP | 탭/개행/thin·em space/원문자(①⑴)/U+2028/U+241C(→EM SPACE) |
| SKIP | EH루트·선모음, 미매핑 확장문자 |

인라인 그래픽 렉싱: 답란 사각형 → BOX(ANSWER) → 최종 `box{~}`;
가로막대(분수선/vinculum) → BOX(VINCULUM) → 파서가 제거.
NBSP 는 일반 공백으로 정규화 (radicand 경계 보존).

### 2.5 EH 파서 (재귀하강 문법)

```
Group    := Item*
Item     := Sqrt | Fraction | Overline | Box | Sep | Text
Sqrt     := HOOK WidthSel? Radicand
Radicand := RadAtom Trailing?
RadAtom  := Number | Var | Fraction | ParenGroup | Box
Number   := digits (DIGIT_SEP digits)*        // 0x8C 로 조각난 √121 이어붙임
ParenGroup := '(' Item* ')'
Trailing := SUPERSCRIPT                        // radicand 안 지수
```

핵심 판정 규칙 (정확도의 심장부):

1. **HOOK 마다 새 Sqrt** — √2·√3 나열 자동 분리.
2. **폭 선택자 vs 변수**: 폭선택자 소비 후 ATOM/FRACTION/BOX 가 곧 오면 폭선택자는
   버림(√121 의 경우); ATOM 이 연산·관계문자(`+ = < > , )`)로 시작하거나
   HOOK/SEP/입력 끝이면 그 글리프 자체가 변수(√u, √l).
3. **키 큰 hook(`"`) 인데 radicand 가 안 따라오면** (GREP 이 조각냄): 폭선택자를
   변수로 해석하지 말고 **빈 근호**로 두어 후속 stitch 패스가 잇게 한다.
4. **radicand 경계**: ATOM 내 `, 공백 thin/em = < >` 에서 끊음. `(` 는 ParenGroup.
   **순수 숫자 radicand 뒤 `+`/`-` 도 경계** (기본 hook 의 가로줄은 숫자 하나만
   덮으므로 "7-2" 는 √7−2 다).
5. **radicand 안 곱셈**: `_`(=×) 뒤가 box/분수/영숫자면 근호 안 곱셈(√(2×□));
   뒤가 HOOK 이면 근호 밖(√2×√3 — TIMES 를 형제 노드로).
6. **ParenGroup**: 괄호 균형까지 수집. 닫는 `)` 직후 SUPERSCRIPT 나 raw `Û`/`Ü` 를
   지수로 흡수 (√(-3)² 의 `)Û` 조각).
7. **radicand 직후 SUPERSCRIPT 는 근호 밖 지수** — 소비하지 않고 남겨 이미터가
   `{sqrt{…}}^{n}` 로 래핑.
8. DIGIT_SEP: hook 직후면 폭마커(스킵), 숫자 사이면 이어붙임.
9. OVERLINE 은 직전 노드를 감쌈; DOT 은 다음 ATOM 첫 숫자에 순환점 적용
   (뒤 숫자 없으면 고아 DOT 버림).

### 2.6 EH 이미터

| 노드 | 방출 |
|---|---|
| Text | 유니코드→HWP 키워드 매핑 (±→`+-`, ×/`_`→`TIMES`, ÷→`div`, °→`DEG`, ℃→`DEG C`, √→`sqrt`, ∞→`INF`, …→`CDOTS`, →→`rarrow`, ≠→`!=`, ≤→`LEQ`, ≥→`GEQ`, ⊂→`SUBSET`, ∪→`CUP`, ∩→`CAP`, ∴→`therefore`, ∫→`int`, Σ→`sum`, ∠→`angle`, ⊥→`bot`, ∥→`parallel`, ≡→`equiv`, ≈→`approx`, 그리스어, ₀₋₉→`_{}`, ⁰⁻⁹²³→`^{}`) |
| Superscript / Subscript | `^{…}` / `_{…}` |
| Fraction | `{num} over {den}` |
| Sqrt | `sqrt{…}` (빈 radicand 는 `sqrt{ }`) |
| Box(ANSWER) | `box{~}` |
| Overline | `overline{…}` |
| RecurDot | `dot{digit}` |
| Paren | `(…)` |

특수 규칙:
- 빈 근호 + Superscript → 지수를 radicand 로 접음 (`sqrt{n}` — √² 의 2 는 radicand)
- Sqrt + Superscript → `{sqrt{…}}^{n}` 래핑
- 공백 노드는 `~` — 단 직전이 `~`/`(`/`{` 이거나 다음이 닫는 구분자면 생략
- 탭은 **선택지 구분자**로 보존 (⑶ f(1/2) ⑷ f(2))

후처리: 닫는 괄호 앞 유니코드 공백 제거(ASCII 공백은 빈 근호 위해 보존),
TIMES/CDOTS 경계 공백 보장, **분수·근호를 품은 괄호를 `left(…right)` 로 확대**
(중첩 depth 처리), 빈 `sqrt{}` 제거, 끝에 매달린 TIMES/div 제거, `{` 짝 보정.
무효 판정: 한국어뿐이거나 영숫자·원문자가 없으면 null (수식 아님).

### 2.7 sanitizeHwpScript — 단일 정규화 관문

모든 경로(EH/BT/NP/인라인 분수)의 산출 스크립트가 마지막에 통과. 순서 고정:

1. **XML 1.0 문자 필터**: 0x1A→` rarrow `, XML 비유효 문자 제거
2. **공백 정규화**: 탭/CR/FF/VT→공백, 연속 공백 합침, trim
3. **잔여 EH 글리프 통일 치환**: `^{V}`→` TIMES `(전수조사 45건 전부 곱셈),
   `sqrt{Ó|Û}`→`^{2}`, `sqrt{Ü}`→`^{3}`, `ù|¿`→`°`, `Ó|Û`→`^{2}`, `Ü`→`^{3}`,
   `Ñ`→`+-`, `Ã`→제거
4. **잔여 GREP 분수 복원**: `;inner;`(1~10자) → `{num} over {den}`
5. **근호 속 단위 방출**: `sqrt{3cm}` → `sqrt{3} cm` (cm|mm|km|kg|m|g|L|t)
6. **sqrt 경계 보정**: `asqrt`→`a sqrt`, `_ ^` 뒤 sqrt 분리,
   `sqrt{ }{A} over {B}` → `sqrt{{A} over {B}}` (빈 근호 뒤로 밀린 분수 흡수)

### 2.8 BT 변환기 (마커 문법, 문자열 치환)

역할 판정: 스타일/폰트명에 `BT수식`(URL 인코딩 변형 포함)·`BTM`·`화살표`.
**예외**: `BT수식H` 계열은 이름만 수식이고 본문 조판용 → 배제. 스타일명에
`영문`/`숫자` 포함 시 배제 (원소기호 H/O, 문제 번호의 수식 오변환 방지).

| 마커 | → | 마커 | → |
|---|---|---|---|
| `_x` (또는 `_다중&`) | `_{x}` | `^x` | `^{x}` |
| `&` | 위치 리셋(공백) | `` ` `` | `~` (thin space) |
| `\` | ` TIMES ` | `.c3` | ` CDOTS ` |
| `rt…&` | `sqrt{…}` | `zrt…` | `+- sqrt{…}` |
| `^-XX^-` | `bar XX ` | `not=` | ` != ` |
| `-<` | ` LEQ ` | `/<` | ` SUBSET ` |
| `cup`/`hap`, `cap` | ` CUP `, ` CAP ` | `div` | ` div ` |
| `rNpar` | ①…(N≤20), `(N)` | `@C` `?C` →←↔⇒⇔ | ` rarrow ` 등 |

화살표 정규화: 같은 화살표가 파일에 `C`/`@`/`@C`/`?C` 로 저장됨 → `→` 로 통일.
필터: `Permutation(`, `Combination(`, 3자+ 영단어(인명 Shakespeare 등)는 수식 배제.

### 2.9 NP 변환기 (폰트 이름 = 구조)

폰트 variant 가 카테고리를 결정:

| 카테고리 | 폰트 | 카테고리 | 폰트 |
|---|---|---|---|
| OPERATOR | NP_PE, NP_BE | FRACTION_BAR | NP_BUN(B) |
| VARIABLE | NP_YP, NP_YB | INTEGRAL | NP_INTE(B) |
| SUB/SUP INDEX | NP_ISHS·PSHS·SUSP 계열 | SUMMATION | NP_SIG(B) |
| SPECIAL_SYMBOL | NP_SUN(B) | LIMIT | NP_LIM(B) |
| ROOT | NP_RUT(B) | ITALIC | NP_IE, NP_BIE |

글리프 매핑(발췌): NP_RUT `j`→`sqrt{` `k`→`}`; NP_BUN `[`→`{` `-`→` over ` `]`→`}`;
NP_SIG `S`→`sum `; NP_LIM `l`→`lim `; NP_YP `E`→`inf ` `9`/`0`→`left lbrace `/`right rbrace`
`y`→`cdots ` `/`→`therefore ` `p`→`pi `; NP_PE `@`→`^{2}`; NP_SUN `!`/`@`→` rarrow ` `Z`→`bar `.
텍스트 폴백용으로 같은 글리프의 유니코드 매핑 테이블(→,∞,∫,√,Σ,△,₀-₉)이 별도 존재.

---

## 3. 런 그룹핑과 수식 파이프라인

### 3.0 두 개의 진입 경로

수식 변환은 병렬 두 경로로 진입하며, 핵심 판정기와 후처리 파이프라인을 공유한다:

```
경로 A: IDML 직접 경로 (스토리 변환기)
  IDML 런 → 한글/라틴 혼합 분할 → 화학식 조각 분할 → 원문자 변환 → 화살표 정규화
          → [런 순회 그룹핑 상태기계] → flush(EH/NP/BT/패턴)
          → 3단계 파이프라인 finalize(SOURCE_TEXT 정책)

경로 B: resolved 경로 (폰트 권위가 InDesign DOM 에서 확정된 런)
  텍스트 아이템 → 소스 줄바꿈 분리 → Stage1 span 물질화
          → (기존 equation 있으면 정규화 후 종료)
          → EH+한글 분리 / charStyle 첨자 / 경계 색 plan
          → 반응식 범위 승격(SPEC-058)
          → [그룹핑 루프 + FormulaCluster 수집 + 화살표 좌변 흡수]
          → flush + 스타일 백필(SPEC-042)
          → 후처리 패스 (5장) → 3단계 파이프라인 finalize(CONVERTED_ITEMS 정책)
```

경로 A 는 IDML 원본 폰트를, 경로 B 는 resolved DOM 이 확정한 폰트를 신뢰한다.
**같은 게이트(□ 흡수, GREP 리셋 등)는 양쪽 경로에 모두 있어야 한다** — 한쪽에만
넣으면 표 셀 경로 등에서 회귀한다 (SPEC-056 교훈).

### 3.1 전처리 런 분할

**한글/라틴 혼합 분할**: 런에 한글과 라틴/수학 문자가 모두 있으면 문자 타입별
연속 구간으로 쪼갠다. 분류: KOREAN(한글 음절·자모 + **원문자 ①–⑳/⑴–⒇**),
LATIN/MATH(영숫자 + `_ ^ & \ `` ` + 연산자·괄호), NEUTRAL(공백 — 직전 타입 상속).
U+FFFC 앵커는 등장 순서대로 해당 서브런에 배분.
예: `"&P_r를 구해 보자"` → `["&P_r", "를 구해 보자"]`.

**화학식 조각 분할**: `")(Cu"`, `"O)과"` 같은 혼합 런을 원소기호 구간에서 분리,
분리 조각이 원소열이면 `grepMathFont=true` 부여. **스킵 조건**: 인라인 페이로드
보유, `Ó`(overline 선분 마커) 포함(기하 선분 PA̅ — SPEC-050), charStyle 이름에
`정체`/`정자`(인명·연도 라틴 본문 — Pythagoras, B.C.569?).

**화살표 정규화**: 화살표 글리프(`@C` `?C` `C` `@` — 문서마다 다름)를 파싱 직후
`→` 로 통일. 조각 런마다 붙이면 `→→` 중복 — 조각 중복 검출 필수.
BT화살표 폰트는 화학식 여부와 무관하게 폰트를 벗긴다(한글이 글리프 못 그림).

### 3.2 판정 술어 (수식 판별 규칙)

```
looksLikeMathRun(text):
  '_ ^ & \' 중 하나 포함 → true (BT 마커)
  ".c3" 포함 → true
  (연산자 +-*/=<>| 있음) AND (영숫자 있음) → true

isPlainAlphanumericRun(run):        # GREP 오검출 방지 게이트
  진짜 BT수식 폰트(본문H 아님) → false
  수학기호(+=<>≤≥±×÷√²³^_π∑∫∞)나 그리스 키워드 포함 → false
  글자 없음(숫자+구두점만: "2","1.4","569?") → true (plain)
  trim 길이 ≤ 2 (단일 변수 a,x,n) → false
  그 외 3자+ 단어 → true (plain)

브리지(그룹 사이 비수식 런을 그룹에 포함할지):
  BT: 화학식 경계런 → 아니오; 순수 공백 → 수식 이웃 있으면 예;
      한글/탭 → 아니오; BT 마커 포함 → 예;
      영숫자만+비BT → 아니오("1","n" 오검출 방지);
      뒤에 BT/유효 GREP 런(한글 전 등장) → 예
  EH: 한글/탭/원문자 → 아니오; 뒤에 EH 런 → 예
  NP: 한글/탭 → 아니오; 뒤에 NP 런 → 예
  pre-NP: 비수식폰트 수학텍스트("y=log") + 바로 뒤 NP 런("2") → NP 그룹 선진입
  pre-EH: base 텍스트가 단일 라틴/숫자형이고 다음 가시런이 진짜 EH 첨자런
          ("x"+"Û" → x²)
```

### 3.3 그룹 상태기계

문단 순회 중 4개의 상호 배타 그룹 버퍼(EH/NP/BT/패턴)를 유지. 우선순위
**EH > NP > BT > 패턴**. 다른 계열로 진입하는 순간 열린 그룹을 flush.

```
런별:
  (a) GREP 리셋: grepMathFont 인데 isPlainAlphanumericRun 이고
      단일 라틴 변수도 아니고 폰트에 "BT수식" 없으면 → grepMathFont 해제
  (b) EH 폰트 + 한국어 전용 텍스트 → 폰트·charStyle 전부 제거
      (한국어가 EH 그룹에 빨리면 미매핑 스킵으로 통째 유실 — "a가 √a 보다…")
  (c) EH 리셋(resolved 경로): 문단에 수학 기호가 없고 단일 라틴 변수도 아니거나,
      ±5 런 내 3자+ 영단어(이름/약어)가 있거나, 한국어 전용이면 EH 해제
  (d) orcOnly(U+FFFC 만 남는 런) → □ 답란 흡수 판정 (3.4)

진입 조건:
  EH:  isEHFont OR EH 인코딩 문자(Û` 등) OR GREP 분수(;…;) OR EH 브리지
  NP:  isNPFont OR NP 브리지 OR pre-NP OR
       (문단에 NP 구조런 존재 + 독립 수학텍스트 "x=k")
  BT:  (BT폰트 OR grepMathFont) AND 한글전용 아님 AND (plain 아님 OR grep)
       OR 화학식 텍스트런 OR BT 브리지 OR (문단에 BT런 존재 + looksLikeMath)
       OR 수식 클러스터런
       — 단 화학식 경계런(괄호/쉼표만)은 진입 금지
  어디도 아니면: 전 그룹 flush → 패턴 감지기 문맥이면 패턴 그룹, 아니면 일반 텍스트
```

BT본문폰트 가드: `BT수식H` 계열(이름만 수식, 본문 조판용)은 수학 내용이 아니면
그룹에 넣지 않는다 — "수소 원자(H)" 의 H, 항목번호 "1." 보호.

### 3.4 □ 답란 흡수 (빈 답란 박스 → 수식 빈칸)

```
formulaClusterRun = isFormulaEquationClusterRun(run)     # ±1 인접 수식성
answerBoxCluster  = formulaClusterRun
                    OR (orcOnly AND paragraphHasFormulaEvidence(문단 런들))  # SPEC-083
answerBoxPlaceholder = 소유권 plan 조회 — 앵커가 실체 시각물(콘텐츠 PNG plan,
                    예: 연필+밑줄 답란)을 가지면 false (□ 로 삼키면 PNG 유실)

orcOnly AND answerBoxCluster AND answerBoxPlaceholder 이면:
  열린 그룹 flush → □(U+25A1) 런을 수식 그룹에 추가, 앵커 그래픽 소비
```

□ 런의 크기·색은 **원본 박스 도형에서 유도** (하드코딩 금지):
`fontSize = 박스높이 / 0.7` (U+25A1 사각형 ≈ 0.7em; 폭 기반은 줄 부풀림),
색 = stroke → fill 순(흰 Paper 제외). 폰트 미지정 시 BT수식M.
빈 답란이 근호 radicand 인 경우(√□): EH 그룹 마지막이 분수분자폰트일 때
`box{~}` 를 EH 그룹에 넣어 `sqrt{box{~}}` 가 되게 한다.

### 3.5 3단계 수식 파이프라인 (계획→실행→검증)

판정과 실행을 분리한 최종 단계. **출력기는 판정을 재해석하지 않는다.**

```
Stage 1a — SpanPlanner (span 분류; 불변 plan, AST 미변경):
  plan():  단일 라틴 문자 런 승격 — [A-Za-z] 정확히 1자 + (상부자·이탤릭 charStyle
           OR grepMathFont OR italic fontStyle) → MATH.
           EH/BT/NP 폰트 런은 제외 (그루퍼가 인접 구조를 해석해야 함)
  planConvertedItems(): 고립 단일 라틴 equation 강등 — 스크립트가 단일 라틴자이고
           (이탤릭+소문자 = 진짜 변수 → 유지), 좌우 인접에 equation 없으면
           → TEXT (이탤릭 텍스트런). 과학자 이니셜 A/J/L 강등 (SPEC-078/079)

Stage 1b — StructurePlanner (인접 구조 병합):
  단위+지수: "…cm" 텍스트런 + "^{2}" 단독 equation → "rm cm^{2}" 병합
             (단위 앞이 ASCII 글자면 합성어로 보고 거부)
  삼각형 라벨: "…△" + 대문자 라벨 + 대문자 equation → 병합

Stage 2 — PlanConverter: plan 을 역순으로 물질화 (인덱스 안정성).
  MATH→equation 승격 시 크기/폰트/색 복사 + sourceItalic 표시.

Stage 3 — Validator: 계약 검사만, AST 무수정.
  위반: 빈 스크립트 / 스크립트 내 제어문자(\t \n \r U+2028) / sourceType 누락
```

plan/materialize 2단계 + 역순 실행 패턴은 모든 후처리 패스(5장)에도 공통 —
**먼저 최종 스크립트를 만들어 검증 통과 시에만 AST 를 바꾼다**.

### 3.6 GREP 수식 해석 (IDML 스토리 파서)

InDesign 문단스타일의 GREP 규칙이 문자 패턴에 수식 문자스타일을 자동 적용한다.
파일에는 폰트 정보가 없으므로 파서가 재현해야 한다:

```
1. "수식 charStyle" 식별: charStyle 의 fontFamily 가 BT/EH 수식 폰트인 것
2. 문단스타일별 GREP 패턴 수집 — BasedOn 부모 체인 상속 (자식 우선, 같은
   표현식이면 부모 규칙 스킵; 루트 스타일에서 중단, 순환 방지)
3. InDesign GREP → 표준 정규식 변환:
   ~a → U+FFFC, ~/ → U+2007, ~m → U+2003, \u → \p{Lu}, \l → \p{Ll}
   (변환 불가 문법은 그 규칙 무시)
4. 문단 텍스트에 문자 단위 매칭 → 전체 매칭이면 런에 grepMathFont 플래그,
   부분 매칭이면 경계에서 서브런 분할 (U+FFFC 앵커를 위치별로 배분)
```

grepMathFont 플래그 런은 하류 그룹핑에서 BT 계열로 취급된다. 비수식 GREP
(색 등)은 별도 채널로 적용.

---

## 4. 화학식 판정과 변환

### 4.0 대원칙

- **다층 AND 판정**: 폰트 증거 + 첨자 존재 + 원소기호 + 수학구조 부재. 단일
  조건으로 승격하지 않는다 (오검 확산 방지).
- **단일 화학식 vs 반응식 분기**: 연산자·화살표 없는 순수 화학식(H₂O, CaCO₃)은
  문자 단위 첨자를 가진 **편집 텍스트로 유지**가 원칙. **반응식(화살표/답란/
  연산자+첨자)만 수식 객체**로 물질화. 예외 승격 경로는 별도 게이트(4.6).
- **`rm` 접두는 AST 스크립트에 없다** — HWPX 방출 순간에 붙는다(로만체 직립).
  수학 수식은 반대로 `it`(이탤릭). 계수 흡수 판정 등에서
  `startsWith("rm ")` 를 조건으로 쓰면 안 됨.

### 4.1 원소기호 화이트리스트

30개 고정: `H He Li Be B C N O F Ne Na Mg Al Si P S Cl Ar K Ca Fe Cu Zn Ag I Ba Pt Au Hg Pb`
2글자 기호 우선 매칭("Cl" 을 C+l 로 쪼개지 않음). 임의 원소 허용은 오검을 늘리므로
화이트리스트 고정 유지.

### 4.2 원소열 판정 (스크립트 문자열 기준)

```
isChemicalFormulaElementSequence(script):
  token ← 공백/_/^/{/} 제거; 라틴·숫자 외 문자가 있으면 즉시 false
  선두 숫자열 소비 → hasLeadingCoefficient
  반복: [대문자(+소문자? — 화이트리스트 2글자 우선)] 가 원소기호여야 함
        → elements++; 이어지는 숫자열 소비 → hasDigit
  return elements ≥ 1 AND (hasDigit OR hasLeadingCoefficient OR elements ≥ 2)
```
단일 원소 단독("H")은 거부 — 숫자가 있거나 원소 2개 이상이어야 화학식 후보.

### 4.3 문단 단위 화학식 판정

**resolved 경로** (보수적 AND 4조건):
```
① 수식 폰트 런 존재 (BT수식 계열)
② 그 폰트 런에 SUBSCRIPT position 존재 (H₂O 의 ₂ 가 표지)
③ 연결 텍스트에 원소기호 1개 이상 (화살표 폰트 런은 "→" 로 치환 후 연결)
④ 수학 구조 문자(= < > ^ _ √ ∑ ∫ π ∞ ≤ ≥ × ÷ / \ { } [ ]) 없음
```
**IDML 폴백** (resolved 매칭이 없는 표 셀 등): ②를 charStyle 이름의 첨자 표기
(`하부자/상부자/아래첨자/위첨자/subscript/superscript` — URL 인코딩 디코딩 후)로,
①을 BT폰트 OR grepMathFont OR 화살표 글리프로 대체.

### 4.4 첨자 위치 판정 우선순위 (SPEC-045 — 재구현 필수 규칙)

resolved DOM 은 첨자 position 을 계수·hair space 까지 흘려 보고하는 결함이 있다.
**IDML charStyle 증거가 비첨자면 resolved 첨자를 버린다**:

```
if resolved 가 첨자를 주장 AND IDML position 은 null/normal
   AND IDML charStyle 이 첨자 계열이 아님:
     resolved 첨자 폐기 (단, "버린 사실"을 힌트로 보존 —
     후속 세그먼트 수식화가 계수/첨자 구분에 사용)
position = resolved첨자(생존 시) ?? IDML첨자
```
같은 원리로 charStyle 참조 선택도: GREP charStyle > (resolved 첨자 charStyle 인데
IDML 이 비첨자면 IDML) > resolved charStyle > IDML charStyle.

추가 가드:
- `normal`/`frombaseline` position 은 **명시적 비첨자** 표시로 기록 — 이름 폴백
  첨자화 차단 (상부자 폰트로 조판된 도형 라벨 "원 O", "□ABCD" — SPEC-053)
- 이름만으로 위첨자 판정된 경우 **밑수(숫자/라틴/닫는 괄호) 뒤에서만** 인정
  (SPEC-080 — 본문 숫자가 위첨자로 깨지는 것 방지)
- 첨자 토큰은 숫자·1~2 라틴자만 그럴듯함(plausible) — 한글/3+라틴/기호 제외

### 4.5 클러스터 수집과 최종 물질화 게이트

문단 내 인접 텍스트 런을 스캔하며 신호를 OR 축적:
`hasLetter, hasDigit, hasOperator, hasBox(□), hasArrow, hasPositioned(첨자),
hasChemicalSymbol, hasFormulaFontEvidence`. 비수식 텍스트/경계 텍스트에서 종료.

수집 후 거부 게이트(모두 통과해야 화학식):
```
- 글자(letter) 없음 → 거부
- 원소기호도 □ 도 화살표도 없음 → 거부
- □ 만 있고 다른 증거 전무 → 거부 (빈 답란 단독)
- 폰트 증거 없이 3자+ 라틴 단어 포함 → 거부 (산문)
- 비화학 수식 키워드(angle/DEG/TIMES/div/sqrt/over/LEQ/GEQ/INF/rarrow/equiv…
  단어 경계 매칭) 포함 → 거부 (∠PAO=90° 가 D·E·G 낱글자로 깨지는 것 방지)
- 길이 128자 초과 → 거부
```

최종 물질화 판정:
```
shouldMaterializeChemicalEquation:
  원소기호도 □ 도 화살표도 없음 → false
  답란 □ 낀 영어 산문 (수식 구조 토큰 없음 + 4자+ 비원소 라틴 단어 3개+) → false
  화살표 OR □ 있음 → true
  등호 + 연산자 + 원소기호 → true
  순수 원소열 + 첨자 + 연산자 없음 → false   # 평범한 H₂O 는 편집 텍스트로
  return 연산자 AND 원소기호 AND (첨자 OR 폰트증거)
```

### 4.6 예외 승격 경로 (게이트 필수)

**(a) 수식폰트 박스 반응식 통짜 승격 (SPEC-058)**: 수식 전용 폰트로만 조판됐으나
BT/EH 타입 교차로 그룹이 매 런 flush 되어 텍스트 폴백되는 경우.
게이트: 구간에 화살표 런 존재 **AND 화살표 앞뒤 각각 수식폰트 런 ≥1** AND
구간 경계가 수식 본문 중간이 아님. → 구간 전체를 하나의 CHEM_FORMULA 로.

**(b) 본문폰트 이온 반응식**: BT 본문폰트(수식폰트 아님) 조판이어도
원소기호 + 화살표 + 연산자 + **순수 이온전하 위첨자**(+/-와 숫자만) 4조건 AND 면
클러스터 시작 허용.

**(c) 형제 문맥 승격 (SPEC-077)**: 반응식 다이어그램의 첨자 없는 라벨("2HCl").
같은 부모 그룹(resolved parentId)에 **확정 화학식 형제**가 있는 프레임의 순수
텍스트 문단만 대상. 라벨 후보 조건: ≤15자, 수학구조 없음, **선두 계수만 허용**
(문자 뒤 숫자=첨자가 있으면 다른 경로 소관), 라틴은 전부 원소기호, 비라틴(한글)
있으면 거부. → 자석 "N극/S극" 캡션은 이중으로 걸러짐.

**(d) 평문 라벨 승격**: 문단 전체가 "2H2O" 류 원소열이면 암시적 첨자를 붙여 승격.

### 4.7 화학식 스크립트 생성 (H₂O → `H_{2}O`)

```
런 → 스크립트 조각:
  런 전체가 첨자 속성 + 전체가 영숫자/+- 토큰 → "_{token}" / "^{token}"
  문자별: 공백류 → 단일 공백(압축) | U+FFFC·□ → '□' | '→' → " rarrow "
         | + - = → 그대로(연산자 플래그) | 라틴 → 그대로(원소 검사)
         | 숫자 → 런이 첨자면 _{d}/^{d}, 아니면 그대로(계수)
         | 그 외 문자 → 클러스터 종료

정규화:
  연산자 주변 공백 제거, RIGHT/RARROW/-> → " rarrow "
  순수 원소열이고 첨자 토큰이 없으면 → 암시적 아래첨자 부여:
    선두 계수는 그대로, [원소기호] 뒤 숫자열 → _{n}    ("H2O" → "H_{2}O")

반응식 재조립용 finalize:
  rarrow 기준 분할 → 각 조각의 모든 공백 제거
  ★ 공백 제거는 유니코드 공백 전부 (U+200A hair space 포함) —
    ASCII \s 만 지우면 "rm H_{2} O" 누수 (SPEC-078)
  " ~ rarrow ~ " 로 재결합 (~ 는 HWP 수식의 가시 공백 토큰)
  첨자 재추론: 원소기호 또는 ')' 직후 숫자열 → _{n};
              선두/'+'/화살표/공백 뒤 숫자열 = 계수 → 그대로
```

### 4.8 메타데이터 전달 (크기·색·폰트)

```
AST 단계: equation 에 힌트 부착
  preferredBaseUnit ← 첫 유효 런의 fontSize (재조립 계열은 조각 최댓값)
  textColor         ← 첫 유효 런 색
  preferredFontFamily ← 첫 런 폰트

HWPX 방출 시 해석:
  baseUnit  = eq힌트 > 문단 상속 CharPr 크기 > 템플릿 > 1100(기본)
  fontFamily= ★템플릿 수식 폰트(HYhwpEQ) 우선 — 본문 폰트를 지정하면 한글 수식
              렌더러가 글리프를 겹쳐 그림(실측 p20). 없으면 매핑된 힌트 폰트
  textColor = 소스색(비검정) > 상속색 > 템플릿 > #000000
              (소스가 검정인데 상속색이 있으면 상속색)
방출: <hp:equation baseUnit=… font=… textColor=…><hp:script>rm …
  화학식(CHEM_FORMULA)은 이 시점에 "rm " 접두 부착; 수학 수식은 "it"
```

### 4.9 화학식 배제 가드 요약

| 가드 | 배제 대상 |
|---|---|
| 수학 구조 문자 존재 | `= < > ^ _ √ ∑ ∫ π ∞ ≤ ≥ × ÷ / \ { } [ ]` → 수학 수식 소관 |
| 단일 원소 단독 | "H" (숫자·2원소 없음) |
| 순수 숫자/단위 | `5cm`, `°`, `℃` |
| 비화학 키워드 | angle/DEG/TIMES/div/sqrt/over… (기하·산술) |
| 답란 산문 (SPEC-066) | □ 낀 영어 문장 |
| overline 마커(Ó) | 기하 선분 PA̅ — 원소 분할 스킵 |
| `정체/정자` charStyle | 인명·연도 라틴 (Pythagoras, B.C.569?) |
| 이탤릭 base | 수학 변수/기하 라벨 B₁ |
| 원소 머리 직전이 라틴 | "pH2" 같은 영문 꼬리 |
| 한글 포함 | 화학식 스크립트에 한글 불가 |
| 3자+ 라틴 단어 | 산문 (단 대문자 2개+ 면 NaCl 류 예외) |

### 4.10 알려진 한계 (현행 미지원)

- **수화물 가운뎃점(·, CuSO₄·5H₂O)**: `·` 는 클러스터 허용 문자가 아니라
  경계로 취급됨 — 현재 미지원. 재구현 시 확장 후보.
- 원소기호 30개 화이트리스트 밖의 원소.
- 계수비 답란 표(N₂□□□NH₃ — 답란+표 구조 얽힘)는 전용 설계 필요.

---

## 5. 후처리 패스 (재조립·승격·강등)

문단 flush 가 끝난 뒤, 파편난 수식/화학식을 봉합하는 다단 패스. 모두
**plan(불변 계획 생성, 최종 스크립트 선검증) → materialize(역순 실행)** 패턴.
실행 순서 고정:

```
1. GREP 분할 수식 봉합            (SPEC-067)
2. 텍스트 연속자 분리 수식 봉합    (SPEC-067)
3. 혼합 클러스터 붕괴             (텍스트+equation+답란 → 단일 CHEM_FORMULA)
4. 첨자 화학식 세그먼트           (SPEC-055 Phase B)
5. 파편 화학식 재조립             (SPEC-078)
6. 화학식 stitch                  (SPEC-055)
7. 파편 반응식 재조립             (SPEC-078B)
8. 3단계 파이프라인 finalize      (단일 라틴 승격/강등 + 구조 병합 + 검증)
```

### 5.1 GREP 분할 수식 봉합 (SPEC-067)

GREP 스타일 경계로 쪼개진 수식. **선두 equation 의 괄호/중괄호 불균형(열림>닫힘)이
신호** — 뒤따르는(사이에 공백만) equation 을 짝이 복구될 때까지 이어붙임.
균형 잡힌 완결 수식은 절대 대상 아님(= 로 시작하는 조각 흡수 시 과병합 — 실측
수학 u5 40건 회귀). 변형: **빈 radicand stitch** — `sqrt{ }` 다음의 균형 잡힌
equation 을 중괄호 안으로 주입 (`sqrt{25} TIMES sqrt{ }` + `(-7)^{2}` →
`sqrt{25} TIMES sqrt{(-7)^{2}}`).

### 5.2 텍스트 연속자 분리 수식 봉합

`1^{2}` / `=1,` / `2^{2}` / `=4` 처럼 순수 수식이 텍스트 연속자로 갈라진 경우.
중간 텍스트는 연산자+값(2자+ 라틴 단어 없음)이어야 하고, 양옆 equation 은
화학식/분수프레임/overline/sqrt/rarrow 계열이 아니어야 한다.

### 5.3 첨자 화학식 세그먼트 (SPEC-055 Phase B)

수식 폰트 증거 없이 **아래첨자 문자속성만으로** 조판된 본문 화학식(H₂O, CO₂).
아래첨자 숫자 런(1~2자리)을 앵커로 직전 런 꼬리에서 원소 머리를 분리:
- 머리는 전부 원소기호여야 함, 머리 직전 문자가 라틴이면 제외(pH2 보호)
- base 가 이탤릭이면 제외 (수학 변수 B₁)
- 후속 아래첨자/이온전하 위첨자/원소·숫자 접두 런으로 확장

### 5.4 파편 화학식 재조립 (SPEC-078)

hair space·폰트 크기 경계로 `[EQ"H"][T"2"][EQ"O"]` 로 갈라진 경우.
단독 원소 equation(스크립트가 원소기호 1~3자, rm/첨자/연산자 없음)으로 시작하는
연속 조각을 모아 raw 결합 → finalize(4.7). 과병합 방지: 라틴 전부 원소기호,
원소 ≥1, 한글/비화학 문자에서 종료.

### 5.5 화학식 stitch (SPEC-055)

CHEM_FORMULA equation 을 앵커로 앞뒤 조각 봉합:
- **후행**: 원소/화살표 근거가 나오는 마지막 지점까지 수집(근거 없는 꼬리는
  버림). 원소 확정 후 단독 숫자는 마지막 원소의 아래첨자로 흡수 (N₂+H+4 → N₂H₄)
- **선행**: 스크립트가 숫자/첨자/괄호로 시작하면 선행 원소가 잘린 것 —
  직전 텍스트 런 꼬리의 화학 조각(`[0-9]{0,2}([A-Z][a-z]?)+…`)을 떼어 앞에 붙임
  ("CH" + "4+2O2 rarrow…")
- **계수 흡수**: 화살표 근거가 있고 머리가 대문자 원소로 시작할 때만, 선행
  단독 1~2자리 숫자 런을 계수로 ("2"+"Cu+O"+EQ → "2Cu+O…"). 런 전체가
  숫자일 때만 — "실험 2" 같은 서술 꼬리 배제
- 결합 결과에 비화학 키워드가 있으면 폐기

### 5.6 파편 반응식 재조립 (SPEC-078B)

`[T"2Mg"][T"+"][EQ"O_{2} rarrow 2MgO"]` → 단일 `2Mg+O_{2} rarrow 2MgO`.
**화살표 근거 필수** — 완성된 단일 반응식(조각 1개)은 병합하지 않는다.

### 5.7 화살표 좌변 흡수 (클러스터 수집 중 인라인)

클러스터 스크립트가 rarrow 로 시작하면 좌변이 비어 있다는 뜻 — 대기 중인
수식 폰트 그룹 + 직전 출력 꼬리의 수식 폰트 런들(최대 24개)을 역방향 수집해
좌변 스크립트를 만들어 클러스터 앞에 붙인다. (반응식 보기가 "좌변 텍스트 +
'~ rarrow ~ 우변' 수식"으로 갈라지는 문제의 해법)

### 5.8 flush 후 스타일 백필 (SPEC-042)

flush 폴백 텍스트가 소스 런과 1:1 평문 동일하면 **원본 런을 재사용**(크기·굵기·
색·첨자 보존). 아니면 산출 런에 스타일 백필 + 화학식 equation 에 크기/색 힌트
백필. **주의**: 그룹 어댑터에 직접 스타일을 실으면 그룹핑 하류 경로가 바뀌어
첨자 회귀 (실패 기록 있음) — 산출물에만 백필할 것.

### 5.9 단일 라틴 승격/강등 (3단계 파이프라인 Stage 1)

- **승격**: 이탤릭/GREP 증거가 있는 단일 라틴 텍스트 런 → 수식 ("x의 값"의 x)
- **강등**: 인접에 수식이 없는 고립 단일 라틴 equation → 이탤릭 텍스트
  (GREP `[단일 라틴]→수식` 규칙이 과학자 이니셜 A/J/L/I 까지 수식화하는 것 복원).
  단 이탤릭+소문자는 진짜 변수로 보고 유지 (SPEC-079)

---

## 6. 엣지케이스 카탈로그 (회귀 테스트 근거)

### 6.1 EH 근호·hook

| 사례 | 규칙 |
|---|---|
| √121 — hook 뒤 폭선택 런이 **별개 런** | 폭선택자 lookahead 는 런 경계를 넘어야 함 |
| √3², √(-3)² — 키 큰 hook `"` | 누락 시 `"` 리터럴 → 수식 통째 유실 |
| √(-3)² 의 `)Û` 조각 | 괄호 밖·근호 안 지수 흡수 |
| √15 √0.81 √7−2 3+√16 | U+241C 센티넬·숫자 radicand 뒤 +/- 경계 — 안 지키면 `sqrt{15 -}` `sqrt{7-2 3+}` 뭉침 |
| √(2×□), √2×√3 | `_`(×) 의 근호 안/밖 판정 |
| GREP 조각화된 sqrt{(-7)²} | 빈 근호 + stitch 패스 |
| √2, π | 콤마는 radicand 경계 — `sqrt{2, pi}` 방지 |
| (√5 )² | 닫는 괄호 앞 얇은 공백 제거 |
| a√(4b/a) | `sqrt{ }{A} over {B}` → 흡수 |
| √3 cm | 단위는 radicand 밖으로 |

### 6.2 EH 지수·기호·분수

| 사례 | 규칙 |
|---|---|
| y=xÛ → y=x² | 본문 폰트로 샌 Û 조건부 디코딩 |
| (1/2)² — EH수식 백틱 `2\`` | 백틱 지수 렉싱 |
| 90ù, sin 60ù | ù→° (앞이 숫자) |
| BCÓ, ACÓ | Ó→^{2} (sanitize) vs Ó=overline 마커 (약물/상부자) — 문맥 분기 |
| ^{V} (45건 전수 곱셈) | → TIMES |
| y=5/x 의 `[`→x | GREP 분수 특수 분모 글리프 |
| y=-;4!;x | 잔여 GREP 분수 복원 → −¼x |
| ⑶ f(1/2) [탭] ⑷ f(2) | 탭 보존 (선택지 구분자) |
| ⌒AB(39회), ≡(3회), ±√a | EH약물 매핑 |
| AM·BM 선분 | overline 사설 마커, 단독 Ó 런은 직전 런 소급 |
| 0.2̇8 순환소수 | 약물 `H` → dot{} — **√로 오인 금지** (√ 는 hook 글리프) |

### 6.3 BT·NP

| 사례 | 규칙 |
|---|---|
| 화살표가 `C`/`@`/`@C`/`?C` 로 저장 | 정규화 → rarrow. 중복 `→→` 방지 (조각 런마다 붙이지 말 것) |
| N₂+3H₂→2NH₃ 이 화살표에서 두 수식으로 갈라짐 | 화살표 클러스터·좌변 흡수 (4장) |
| 원소기호 H/O, 문제번호 1. 이 이탤릭 수식화 | BT수식H·영문/숫자 스타일 배제 |
| Shakespeare, 1874~1951 | 3자+ 영단어 수식 배제 |

---

### 6.4 그룹핑·파이프라인

| 사례 | 규칙 |
|---|---|
| "&P_r를 구해 보자" | 한글/라틴 혼합 런 분할 (원문자는 KOREAN 취급) |
| "a가 √a 보다 항상 큰지…" 한국어가 EH 그룹에 빨려 통째 유실 | EH+한국어전용 런은 폰트 제거 / 첫 한국어 문자에서 분리 |
| ±5 런 내 "Pythagoras" | EH 리셋 (이름/약어 근접) |
| "y=log" + NP "2" | pre-NP 브리지 → log_{2} |
| "x" + EH "Û" | pre-EH 브리지 → x² |
| p32 (√2×√3)² 전개 4행 | U+2028 → 줄바꿈 노드 (다행 수식 행 경계 보존) |
| "(1) 3^{2}=9" | 선행 번호 "(1) " 분리 |
| ⑵⑶ 원문자·탭 | 수식 분할 경계 (탭은 선택지 구분자로 보존) |
| 수학 u1 p15 "…제곱근은 □, …제곱근은 [박스]" | □ 흡수는 ±1 인접이 아니라 문단 단위 수식 증거로 (SPEC-083) |
| 과학 u1 p19 "암모니아 = [연필+밑줄]" | 실체 시각물 앵커는 □ 로 삼키지 않음 |
| 1단원 p32 "√□" | 빈 답란이 radicand — box{~} 를 EH 그룹에 |
| "제곱하여 4가 되는 수"의 4·25 | GREP 상부자(이탤릭) 숫자 직접 수식 방출 (SPEC-080) |
| 인접 수식 없는 이니셜 A/J/L | 고립 단일 라틴 강등 (이탤릭 소문자 변수는 유지) |
| "3cm" + "^{2}" | 단위+지수 구조 병합 → rm cm^{2} |

### 6.5 화학식

| 사례 | 규칙 |
|---|---|
| H₂O, CaCO₃ (연산자 없음) | 편집 텍스트 유지 (수식화 안 함이 원칙) |
| 2H₂+O₂ → 2H₂O (p47 박스) | SPEC-058 화살표 양변 폰트증거 통짜 승격 |
| p16-17 "2H2"/"+O2" 개별 프레임 | 화살표 없음 → SPEC-058 자연 배제 |
| resolved 가 계수 2 를 SUBSCRIPT 로 흘림 (pg25/26/28) | SPEC-045: IDML 비첨자 증거가 이기고 resolved 첨자 폐기 |
| u5 p174 "원 O", "□ABCD" | 명시적 normal position → 이름 폴백 첨자화 차단 (SPEC-053) |
| H₂O 가 "H 2 O" 로 벌어짐 | hair space(U+200A) 는 ASCII \s 로 안 잡힘 — 유니코드 공백 전부 제거 |
| N₂+H+4 → N₂H₄ | stitch 꼬리 첨자 흡수 |
| "2"+"Cu+O"+EQ"2 rarrow 2CuO" | 계수 흡수 (화살표 근거 + 원소 머리일 때만) |
| "실험 2" | 계수 흡수 배제 (런 전체가 숫자일 때만 흡수) |
| 2HCl 라벨 (첨자 없음) | 확정 화학식 형제 문맥 승격 (SPEC-077); "N극/S극"은 이중 배제 |
| Cu+O₂→2CuO 불균형 반응식 | 퀴즈 오답 보기(distractor) — 버그 아님, 소스 그대로 |
| ∠PAO=∠PBO=90° | 비화학 키워드(angle/DEG) → 클러스터 전체 거부 (낱글자화 방지) |
| 영어 u3 p62 "□Marketing words…" | 답란 낀 산문 배제 (SPEC-066) |
| pH2 | 원소 머리 직전 라틴 → 배제 |
| CuSO₄·5H₂O | 가운뎃점 미지원 (알려진 한계) |

---

## 부록 A. 무효/방어 규칙 요약

- 한국어만 있는 스크립트 → 수식 아님 (null)
- 영숫자도 원문자도 없는 스크립트 → 수식 아님
- 글리프만으로 수식을 강제하지 말 것 — 폰트/문자스타일 증거 기반 판정이 원칙
- 개별 디코더가 놓친 raw 글리프는 sanitize 관문이 최종 방어

## 부록 B. 용어

| 용어 | 의미 |
|---|---|
| hook | 근호 갈고리 글리프 (√ 의 시작) |
| 폭 선택자 | hook 직후 근호 가로줄 폭을 고르는 장식 글리프 |
| 자리구분자 | 여러 자리 radicand 의 숫자 사이 장식 (0x8C) |
| GREP 수식 | InDesign 문단스타일의 GREP 규칙이 패턴에 수식 문자스타일을 자동 적용한 런 |
| 셸(shell) | 텍스트를 얹는 배경 시각물. 수식 관점에선 편집 텍스트로 승격된 프레임 |
| 통PNG | 그룹 전체를 단일 이미지로 굽는 처리 |
