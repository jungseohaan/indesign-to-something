# SPEC-067: GREP 정상화 + 수식 그룹핑 경계 재설계

> 상태: **분석 완료, 구현 대기**. 2026-07-24.
> 기준선 커밋 완료(`c183b312`, 브랜치 `grep-baseline-and-fix`).
> GREP 변환 수정은 단독으로는 수학 150건 회귀 → 그룹핑 재설계와 함께 가야 함.

## 배경

영어 u3 p56 에서 한글 런의 색이 뒤따르는 영문 런에 번지는 문제를 조사하다가,
색 결정이 resolved DOM 에 과도하게 의존하는 구조적 원인을 발견했다. 사용자 제안:
"단락 스타일과 GREP 으로 문자 스타일을 결정하는 방식으로 바꾸자".

그 전제 조건으로 GREP 처리 상태를 점검한 결과 **대량의 규칙이 조용히 버려지고
있었다**.

## 발견 1 — GREP 변환 결함 (실측)

`IDMLStoryParser.convertIdGrepToJavaPattern` 이 문자열 전체에 무조건 치환:

```java
javaRegex.replace("\\u", "\\p{Lu}")   // 문자 클래스 안/밖 구분 없음
```

- 문자 클래스 안에서 Java 가 거부하는 형태가 되어 컴파일 오류
- 유니코드 속성 표기(Nd 등)까지 파괴
- 실패 시 `catch → return null` → **로그 없이 규칙 소멸**

| 교과서 | GREP 규칙 | 변환 실패 | 경계 의존 |
|--------|-----------|-----------|-----------|
| 영어 u3 | 133 | 20 (15%) | 23 |
| 과학 u1 | 1419 | 275 (19%) | 563 |
| 수학 u5 | 778 | 275 (35%) | 343 |
| 수학 u1 | 850 | 316 (37%) | 420 |

수학에서 버려지던 규칙이 정확히 변수·숫자·연산자 관련이다
(`(?<=\d|\l)\.(?=\d|H|\l)`, `\d+|[\l\u]|[+|\-|÷|...]` 등).

## 발견 2 — resolved 런 속성은 제거 불가 (실측)

resolved 런 속성을 완전히 끄고 4교과서 변환 실험:

| 교과서 | 구조 골든 | 문자 속성 |
|--------|-----------|-----------|
| 수학 u1 | FAIL 6건 (수식 소실/변형) | DIFF 21건 |
| 수학 u5 | FAIL 57건 (수식 432→419) | DIFF 26건 |
| 과학 u1 | PASS | **DIFF 20건 — 색 대량 붕괴** (#0E2950 287→0 등) |
| 영어 u3 | PASS | DIFF 7건 (bold 3577→3185자) |

resolved 에만 있는 정보: GREP/중첩 스타일 최종 결과, 스타일 상속 실효값,
오버라이드 반영 렌더 상태. **현재는 "GREP 이 새는 만큼 resolved 가 받쳐주는"
균형 상태**다. GREP 정상화가 선행되어야 resolved 의존을 줄일 수 있다.

## 발견 3 — 조각화의 진짜 원인 (핵심)

GREP 변환을 고치면 수학 u1 구조 골든 149건 FAIL. 원인 추적 결과:

```
GREP 이전:  [3cm][Û][`]        ← "3cm" 이 하나의 런
GREP 이후:  [3][cm][Û][`]      ← 숫자/문자 분리

분리된 조각의 GREP 스타일:
  "3"  → 태광10.5:상부자(이탤릭)   ← 이탤릭 (변수/수치)
  "cm" → 태광10.5:상부자           ← 정자 (단위)
```

**이건 버그가 아니라 InDesign 의 의도적 조판이다.** 숫자는 이탤릭, 단위는 정자로
렌더하려고 GREP 을 쓴 것이고, 스타일이 실제로 다르므로 "동일 스타일 병합"으로는
되돌릴 수 없다.

문제는 **런 분리가 곧 수식 그룹 경계**라는 구조다. `3cm²` 는 스타일이 달라도
하나의 수식이어야 한다.

### 시도하고 실패한 접근 (반복 금지)

1. **동일 스타일 인접 런 병합** — 조각들의 스타일이 실제로 달라 미적용
2. **bridge 조건 확장**(2자 → 24자, 수식 문법 문자 허용) — 문제 런이 그 코드
   경로에 도달하지 않음 (frag=true 2건, 둘 다 follow=false)
3. **글자분해형 GREP 규칙 제외** — 449건으로 회귀 악화. 이 규칙들이 실제로
   EH상부자 폰트 정보의 유일한 출처인 경우가 있다 (578→441자 손실)
4. **EH 스타일을 수식 패스로 이관** — 172건 회귀. 수식 패스 자체도 런을 쪼갬

### 계측으로 확인한 사실

- `enterEH` 판정은 **모든 조각에서 true** — 그룹 진입은 정상
- flush 시점 그룹 내용: `Û` 단독(size=1) 그룹이 반복 → 수식이 끊겨 나간 조각
- 그룹 단절 사유 계측: `non-math-run=43`(대부분 한글 본문 — 정상), `boundary-text=7`,
  `non-textrun-item=5`

## 구현 명세 (다음 세션)

수식 그룹 경계를 **스타일 기반 → 문맥 기반**으로 전환:

- 현재: 런 경계 = 그룹 경계 (스타일 전환 시 flush)
- 목표: 수식 문맥 판정(EH 글리프·수식 폰트·연산자 연속성·단위 토큰)으로 경계를
  정하고, 스타일 전환은 그룹 **내부 속성 변화**로만 처리
- 영향 범위: `MathProcessor` 그룹 루프 + `StoryLoader` EH/BT/NP 그룹 경로.
  수학 교과서 전반(수식 653개)

## 회귀 감시 자산 (커밋 완료)

- `scripts/dev/charpr_profile.py` — 색/폰트/이탤릭/첨자 분포 프로파일러.
  **구조 시그니처 골든에는 이 축이 없다** (GREP 은 주로 여기에 작용)
- 골든: 수학 u1/u5, 영어 u1/u3 구조 + 4교과서 charPr 기준선

```bash
python3 scripts/dev/verify_hwpx.py out.hwpx --golden test-data/golden/수학u1-p010-049.json
python3 scripts/dev/charpr_profile.py out.hwpx --compare test-data/golden/수학u1-p010-049-charpr.json
```

## 2차 세션 추가 규명 (2026-07-24)

### 조각화의 정확한 메커니즘

`x²=a` 소실 케이스를 IDML 원본까지 추적:
- IDML: `xÛ`=a` **단일 런**, `[No character style]` (Û=제곱 글리프)
- 문단 스타일 GREP: `\d+|[\l\u]|[+|\-|÷|...]` → "태광11.5:상부자(이탤릭)"
- 매칭: `x`(0-1), `` ` ``(2-3), `=`(3-4), `a`(4-5) — **`Û`(1-2)만 매칭 안 됨**
- 결과: `x`(이탤릭) / `Û`(스타일없음) / `` `=a ``(이탤릭) 로 분리 → 그룹 끊김

즉 **EH 글리프 해킹 문자(Û 등 라틴-1 보충)가 GREP 규칙에 안 걸려** 그 위치만
스타일이 비고, 스타일 경계에서 런이 쪼개진다.

### 시도 5: 갭 메우기 (실패, stash v2)

`charStylePerChar[]` 에서 매칭 사이에 낀 미매칭 수식 글리프를 인접 스타일로
메우는 `fillMathGlyphGrepGaps` 추가. 하지만 이 케이스는 GREP 이 애초에 안 붙는
문단(`x`의 grepCs=null)이라 대상이 아니었다 — 수학 149건 여전. WIP v2 로 stash.

### 남은 근본 해법

이 케이스의 `x`는 GREP 매칭 결과가 최종 런에 반영되지 않았다(grepCs=null).
갭 메우기가 아니라, **GREP 매칭 결과 자체가 왜 이 문단에서 유실되는지**를 먼저
봐야 한다. `resolveGrepGenericForParagraph` 의 런 재구성에서 매칭 정보가 손실되는
지점 추적이 다음 착수점.

## p56 색 문제 — GREP 과 독립된 별건으로 확정 (2026-07-24)

영어 u3 p56 "A That happens…" 녹색 번짐은 **GREP 문제가 아니다**:
- GREP 은 정상 작동: `A\t` → `1_Communication_선지(대화 AB)`(회색) 정상 매칭,
  나머지 본문 → 색 규칙 없음(검정이 정답)
- 그런데 최종 HWPX 는 전부 녹색(#67B755), 회색이어야 할 `A` 조차 녹색

**실제 경로**: 이 대화 문단은 표 셀 안(`tbl>tc>subList`)이라
`StoryLoader.buildResolvedCellParagraphs` → `ResolvedTextFlowAstConverter.convertRunText`
→ `TextRunSegmenter.fromResolvedText` 경로를 탄다. 이 경로는 **RunBuilder 색
우선순위(SPEC-065)를 안 거치고 resolved run 의 fillColor 를 그대로 쓴다.**
resolved DOM 이 앞 한글 문단(녹색)을 흘려 이 영문 런을 녹색으로 오보고 →
검증 없이 그대로 색이 됨.

수정 지점: 셀 문단 경로(`buildResolvedCellParagraphs`)에도 IDML/GREP 색 근거
검증을 넣어, 근거 없는 resolved 유채색을 문단 기본색으로 되돌린다. 영향 범위가
셀 전체(과학 표 색자 포함)라 charPr 기준선으로 신중히 검증할 것.
**GREP/수식 그룹핑과 독립적이므로 별도 브랜치에서 먼저 처리 가능.**

### 수정 완료 (2026-07-24, 브랜치 `fix-eng-p56-cell-color-bleed`)

`StoryLoader.revertUnbackedResolvedCellColors`: 셀 문단 빌드 후, IDML 색 근거
집합(로컬 FillColor + GREP/적용 문자 스타일 색)에 없는 resolved 유채색을 검정으로
되돌린다. **채도 게이트**(`isSaturatedChromatic`)로 선명한 유채색만 대상 —
`#1A1A1A`(K90 근흑 본문색) 같은 회색은 DOM 보고라도 보존(채도<25% 또는 max<64 제외).
- 검증: 3교과서(과학u1/수학u1/u5) **charpr PASS**(근흑 4000여 자 오되돌림 방지 확인).
  영어 추출물이 캐시에 없어 p56 직접 재현 불가 → 채도 게이트 단위테스트
  (`StoryLoaderTest.saturatedChromaticGateTargetsBleedColorsOnly`)로 녹색 #67B755
  대상·근흑 비대상 고정.
- 미해결 잔여: `#757877`(K60 회색 누출, SPEC-065 영어 u1 사례)는 채도 낮아 게이트 밖 —
  검정과 근사해 무해하나, 진짜 근절하려면 회색 누출도 근거 검증 필요.

## 3차 세션: 조각화 지점 특정 + balance-stitch 구현 (2026-07-24)

3교과서(수학u1/u5·과학u1) 캐시 추출물로 재측정. **측정 원칙**: 골든이 다른
추출 버전이라 이미지/표 축은 노이즈다 → `hp:script`(수식) + charpr 만 before/after
비교. `eqcompare2.py`/`eqcompare3.py`(scratchpad)로 정규화(left(/right)·공백·~ 제거)
후 멀티셋 비교하고, 손실을 "조각화(내용 보존)"와 "진짜 소실"로 분리.

### 확정된 사실

1. **GREP 구문 수정 단독은 순손실**. charpr 개선은 미미(수학u1 3건, 나머지 1건) —
   색/폰트는 이미 resolved DOM 이 대주므로. 반면 수식 조각화가 큼:
   - 수학u1: corrupt 52→74(구문수정만), 온전 수식 다수 fragmentation
   - 수학u5 8건, 과학u1 0건
2. **조각화는 IDML 경로에서 이미 여러 ASTEquation 으로 쪼개진 채 도착**한다
   (`MathProcessor.processResolvedMathGrouping` 이 `hasEquation` 이면 early-return —
   그래서 resolved 그룹 루프가 아니라 **상류 IDML 수식 빌더**가 원인).
   실측 스트림: `[EQ:left(√a right)²=a,~(-√a]  [ ]  [EQ:)²]  [EQ:=a]`.
   앞 조각은 **괄호/중괄호 짝 불균형**(열림>닫힘)이 특징.
3. **`sqrt{v}`/`sqrt{x}` 글리프 오디코드**가 더 깊은 회귀. 구문수정 후 수학u1 에
   15건(기준선 1건). `EHFontGlyphMap` 0xC3(Ã)→'v', 즉 sqrt 근호 내용 `(-7)`·`(-a)`
   가 조각나면서 근호 안이 stray 글리프 'v'/'x' 로 디코드됨. **이건 그룹 경계가
   아니라 EH 수식 렉서의 글리프 디코드 문제** (별건, [[eh-yakmul-glyph-mapping-adhoc]]).

### 구현: `stitchGrepSplitFormulaEquations` (balance-only, 안전)

`MathProcessor` 에 조각 재봉합 패스 추가. **선두 ASTEquation 의 괄호/중괄호가
미완결일 때만**, 뒤따르는(공백만 사이) 수식 조각을 짝이 복구될 때까지 이어 붙인다
(이미 빌드된 스크립트라 `normalizeFormulaScript` 재실행 금지 — RIGHT→rarrow 가
`right)` 를 깨뜨림, 공백만 정리). 결과:
- `)²` 고아 조각 계열(수학u1 17건) 정상 재봉합, corrupt 52→**49**(개선)
- 기준선(구문수정 없음) 대비 거의 no-op(수학u5/과학u1 동일, 수학u1 1건 개선)

### 반복 금지 (3차 실패 실측)

- **연산자 시작(`=`,`<`,`+`,`,`,`~`) 조각 흡수**: 과병합. `overline{AB}` + `=overline{CD}`
  같은 도형 등식을 삼킴. 게다가 `=overline{CD}` 는 **기준선에도 별개 조각으로 존재** —
  GREP 회귀가 아니라 기존 조각화라 손익 판정 자체가 모호. 수학u5 lost 9→40. **불균형
  복구 신호만 안전**(조각이 명백히 미완결이라는 확증).
- **`isFormulaEquationScript` 에 `,~` 허용**(collectMixedFormulaEquationCluster 병합
  유도): 그 경로는 box/arrow/chemical 없으면 return null 이라 순수 대수식엔 무효.

## 4차 세션: `sqrt{v}` 글리프 오디코드 수정 (2026-07-24)

### 근본 원인 특정 (실측)

`EHFontEquationConverter.convert(runs)` 계측: `sqrt{v}` 는 runs = `"`(키 큰 hook,
EH분수대문자) + `0xC3`(width-selector) **+ radicand 없음**에서 나온다. GREP 이
근호 내용(`(-7)²` 등)을 다른 수식 조각으로 떼어내, hook 뒤에 width-selector 만
남는다. `EHParser.parseRadicand` 의 WIDTH_SELECTOR 케이스가 `radicandFollows()`
false 면 그 글리프를 **변수로 디코딩**(`0xC3`→v, `0xC5`→x) → stray `sqrt{v}`.

### 수정 1 — `EHParser` (10줄): 키 큰 hook + radicand 소실 → 빈 근호

WIDTH_SELECTOR 케이스에서 `absorbTrailingSuperscript`(키 큰 hook `"` = 지수 포함
radicand 신호)인데 radicand 가 안 따라오면, 폭선택자를 변수로 디코딩하지 않고
빈 근호로 둔다. **일반 hook(`'`)의 √u·√l 진짜 변수는 무영향**(absorbTrailing=false).
결과: 수학u1 `sqrt{v}`/`sqrt{x}` 15건 → 0건. 대신 `sqrt{ }`(빈 근호)로 남음.

### 수정 2 — `MathProcessor.tryStitchEmptyRadicand`: 빈 근호에 radicand 주입

빈 근호 `sqrt{ }` 는 괄호 짝이 맞아 balance-stitch 가 못 잡는다. 선두 수식이
`sqrt{ }` 로 끝나고 바로 뒤(공백만 사이) 조각이 짝 맞는 수식(`(-7)²`)이면 빈
중괄호에 주입 → `sqrt{(-7)²}`. 수학u1 `sqrt{ }` 15→5, `sqrt{(-...)²}` 정상 복원.

### 현재 브랜치 상태(work-0753) — **GREP 구문수정 없이 무해·개선**

적용: `EHParser`(빈 근호) + `MathProcessor`(balance-stitch + 빈근호 주입).
**GREP 구문수정은 여전히 되돌린 상태**. 기준선 대비:
- 3교과서 charpr **PASS**, 수학u5·과학u1 완전 동일, 수학u1 몇 건 **재봉합만**
  (`(84+84√2` 짝 복구, `=sqrt{ }`+`{a}over{b}` → `=sqrt{{a}over{b}}`)
- 즉 구문수정을 안 켜도 stitch·빈근호 수정은 기존 조각화를 고쳐 **순이득**

### 남은 블로커 (GREP 구문수정을 켜려면)

구문수정 ON 시 수학u1 진짜소실 **24건**(4차 전 36→24, 빈근호로 12건 회수).
남은 24건은 **연산자/쉼표 연속 조각**(`1²=1,2²=4` 는 기준선에선 단일 수식인데
GREP 이 `1²`|`=1,`|`2²`|`=4` 로 쪼갬 — 단, `=1,` 는 ASTEquation 이 아니라
**text run**이라 stitch 대상 밖) + 일부 깊은 글리프 깨짐(`{sqrt{13)}}`). 연산자
연속 흡수는 `overline{AB}`+`=overline{CD}`(기준선에도 별개 조각인 도형 등식)를
삼켜 과병합하므로 금지. **다음 착수**: 등식/쉼표로 쪼개진 조각을 안전하게 잇는
법 — 기준선이 단일 수식이던 것만 구별하는 신호 필요(text-run 연속자 + 양옆 수식폰트).

## 회귀 감시 자산 (커밋 완료)

- `scripts/dev/charpr_profile.py` — 색/폰트/이탤릭/첨자 분포 프로파일러.
  **구조 시그니처 골든에는 이 축이 없다** (GREP 은 주로 여기에 작용)
- 골든: 수학 u1/u5, 영어 u1/u3 구조 + 4교과서 charPr 기준선

## **근본 원인·해결 확정: GREP BasedOn 상속 누락 → DOM 색 의존** (2026-07-24)

p56 대화 녹색 + 과학 색자 딜레마의 진짜 뿌리를 실측으로 규명했다.

**초기 개발 트레이드오프의 실체**: 초기엔 GREP 규칙의 BasedOn 상속을 구현하지
못해, 파생 문단스타일이 부모의 GREP 색을 못 받아 색이 비었다. 그래서 임시로
InDesign DOM 렌더값(`character.fillColor`)을 추출기에서 읽어다 썼다. 하지만 DOM 은
이웃 문단 색을 흘려 **비결정적으로 오보고**한다(같은 p56 bigger 가 재추출마다
녹색↔회색). 이게 p56 녹색 누출의 근원이다.

**실측 규명**: 과학 "무엇을 알아볼까"(초록 #00633E)는 문단스타일
`02_탐구,해보기_준비물` 의 GREP `^무엇을 알아볼까|어떻게 할까` → 문자스타일
`2_무엇을알아볼까요(제목)`(FillColor=C=79 M=0 Y=77 K=49)에서 온다. 실제 텍스트의
문단스타일은 파생 `02_탐구,해보기_준비물(내어쓰기)`(BasedOn=준비물)인데, **변환기가
BasedOn GREP 을 상속 안 해** 초록을 놓쳤다. preview.pdf 육안: "무엇을 알아볼까"만
초록, 뒤 문장 검정 — GREP 이 정확히 그 첫 구절만 착색.

**핵심 버그 2곳**: (1) `IDMLStoryParser.resolveGrepGenericStyles`/`resolveGrepMathStyles`
가 raw `paraStyle.grepStyles()` 만 봄(BasedOn 무시). (2) `StylePropertyResolver.mergeStyles`
가 BasedOn 병합 시 grepStyles 를 안 합침.

### 수정 A — GREP BasedOn 상속 (`IDMLStoryParser`)

`collectInheritedGrepStyles(doc, paraStyle)` 추가: 문단스타일의 GREP 을 BasedOn
부모 체인까지 상속 수집(자식이 같은 표현식 재정의 시 자식 우선, 순환 방지).
`resolveGrepGenericStyles`·`resolveGrepMathStyles` 양쪽이 이걸 쓴다. 결과: 과학
"무엇을 알아볼까" 가 IDML 만으로 #00633E 초록 복원. 3교과서에서 상속으로 색 증가
(과학 #00633E 805→833, #2D4069 142→308 등 — 검정이던 게 원래 색 찾음).

### 수정 B — DOM 글자 속성 읽기 차단 (`text_collectors.jsx`)

`_masterDynamicRunFromRange` 가 아니라 `text_collectors.jsx` 의 런 빌더(runData 블록,
`horizontalScale` 세팅 지점으로 소스 확정)가 대화 story 런의 소스다. 여기서 DOM
글자 속성(color/size/font/style/charStyle/baselineShift/position + GREP 색 보정)을
읽지 않는다. tracking/장평/verticalScale/underline/strikeThru 는 유지. 색은 이제
GREP 상속 포함 IDML 이 확정하므로 DOM 불필요. **추출 성능도 개선**(개별 문자 속성
접근이 InDesign 에서 가장 느린 호출).

### 이번 커밋: GREP BasedOn 상속만 (수정 A)

수정 A(GREP BasedOn 상속)만 커밋한다. 과학 "무엇을 알아볼까" 초록이 DOM 없이도
IDML 로 복원됨을 확인. 3교과서 구조 골든 FAIL 은 추출 버전 노이즈(이미지/표 개수)
로 수식과 무관.

### 후속 과제: DOM 글자속성 차단 (수정 B) — 채우기 미비 선결 필요

수정 B(text_collectors.jsx 에서 DOM 글자속성 안 읽기)는 p56 녹색을 제거하지만,
색뿐 아니라 폰트/스타일/크기까지 IDML 로만 가게 해서 **IDML 채우기 미비가 동시에
드러난다**(실측, 영어 u3 전체):
- 볼드 유실: IDML `19 Bk`(Black)·`07 Bd`(Bold) 약자를 `isBoldStyle` 이 볼드로 판정
  못함(웨이트 19<65). → CharPrFactory.isBoldStyle 에 Bk/Bd 인식 추가 필요.
- 로컬색 스와치 오매핑: `Listen` 의 로컬 FillColor `Color/writing tips`(시안 #3899C9)
  가 #3C4C74 로 잘못 나옴. 스와치 이름 해석 문제.
- 경로 불일치: text_collectors(막힘) vs composedLines(안 막힘)로 같은 텍스트가
  색 있음/없음으로 갈림.

DOM 완전 차단 전에 이 채우기 미비들을 다 잡아야 회귀 없음. p56 은 그때까지 미해결.

## 5차 세션: 선결 3건 정산 — 스와치 항목은 **오진**이었다 (2026-07-24)

수정 B 를 켜기 위한 선결 3건을 실측으로 정산했다. 2건은 이미 해결됐고,
**남은 1건(스와치 오매핑)은 실재하지 않는 버그**로 확인됐다.

| 선결 항목 | 결론 |
|---|---|
| 볼드 유실 (`19 Bk`) | 해결 — `FontStyleClassifier.inferStyleWeight` 에 `bk` 토큰 (b32ba4ad, PR #133) |
| 경로 불일치 (composedLines) | 해결 — composedLines 도 DOM 글자속성 차단 (078a0184, PR #132) |
| 로컬색 스와치 오매핑 | **오진 — 버그 없음** (아래) |

### 스와치 "오매핑"은 서로 다른 두 색을 비교한 것

문서가 적었던 전제("`Listen` 의 로컬 FillColor 가 `Color/writing tips`(시안 #3899C9)
인데 #3C4C74 로 잘못 나옴")는 IDML 실측으로 **셋 다 틀렸다**:

1. **`Listen` 런에는 로컬 FillColor 가 없다.** 영어 u3 `Stories/Story_u422c.xml`
   의 해당 CharacterStyleRange 는 `FillColor` 속성 자체가 없고 문자스타일도
   `[No character style]`. 색은 문단스타일에서 온다.
2. **`Color/writing tips` 는 Styles.xml 에서 참조 0 건.** 스와치는 정의만 되어
   있고 이 텍스트와 무관하다. (`grep -c "Color/writing tips" Resources/Styles.xml` → 0)
3. **#3899C9 는 `writing tips` 의 색이 아니다.** `writing tips` 는 CMYK
   70/52/0/35 이고 ICC 변환 결과가 정확히 `#3C4C74` 다. `#3899C9` 는 **별개
   스와치** `C=70 M=20 Y=0 K=0` 의 색(역탐색 거리 0.0 으로 일치 확인).
   즉 "오매핑"으로 보인 것은 변환 오류가 아니라 애초에 다른 두 색이었다.

**`Listen` 의 진짜 색**: 문단스타일 `ParagraphStyle/표빈공간` 의
`FillColor="Color/C=0 M=0 Y=0 K=60"` → ICC 변환 `#757877` (회색).
이는 위 165 행에 "미해결 잔여: `#757877`(K60 회색 누출)" 로 이미 적어둔 값과
**동일** — 두 항목은 사실 같은 사안이었다.

### 검증 방법 (재현용)

- `IDMLResourceParser.convertColorToHex("70 51.99999809265137 0 35","Process","CMYK")` → `#3C4C74`
- `CMYKColorConverter.isIccAvailable()` → `true` (macOS Generic CMYK Profile
  + JAR 번들 `/icc/GenericCMYK.icc` 양쪽 존재). 폴백 단순수식이면 `#3250A6` 로
  달랐을 것이므로, ICC 경로가 실제로 쓰이고 있음도 함께 확인됨.
- `CMYKColorConverter.cmykToHex(0,0,0,0.60)` → `#757877`

### 스와치 이름 조회 경로는 정상 (별건 확인)

`Color` 엘리먼트의 `Self` 가 이미 `Self="Color/writing tips"` 형태(이름 기반)라
`Name` 속성을 따로 등록하지 않아도 조회된다. `IDMLDocument.putColor` 가
bare/`Color/`/`Swatch/`/`#` 4 별칭을 펼치므로 슬래시·공백 포함 이름도 정상.
→ `IDMLResourceParser.parseGraphic` 수정 불필요.

### 수정 B 실측 (영어 u1 p10-29, 2026-07-24)

DOM 글자속성 차단 상태로 영어 u1 을 재추출해 계측했다(text-only, 96dpi,
IDML 캐시 miss 콜드런, 2회 반복해 재현 확인).

**DOM 글자속성 제거: 11,396 → 0 (완전 차단)**

| resolved.json 런 속성 | 23일(DOM) | 24일(차단) |
|---|---:|---:|
| fillColor / fontFamily / fontStyle | 2093 each | 0 |
| fontSize | 2011 | 0 |
| charStyle / baselineShift / position | 1008 each | 0 |
| pointSize | 82 | 0 |
| **합계** | **11396** | **0** |
| (유지) tracking/장평/underline/strikeThru | 1008 each | 993 each |

resolved.json 3.18MB → 2.81MB (-12%).

**텍스트 수집 구간 26.83s → 12.75s (-52%)**

| phase | 23일 | 24일 | delta |
|---|---:|---:|---:|
| `10i_collectStories_done` | 8.76s | 3.67s | -5.10s |
| `10j_collectTextFrames_done` | 6.17s | 2.65s | -3.52s |
| `10m_collectPageItems_done` | 3.45s | 1.16s | -2.29s |
| `10_collectResolved_done` | 5.65s | 3.41s | -2.24s |
| `10k_instanceMasterFrames_done` | 0.98s | 0.42s | -0.55s |
| `11_writeJson_stories` | 1.82s | 1.44s | -0.38s |

**품질: 텍스트 유실 없음, 경계 개선**

visibleChars 11521 → 11721 (+200). 토큰 차분에서 OLD 쪽 "유실"은 전부
`friendsmusicstudyingsportsFriends`·`wonderQ1What`·`NO조언을` 같은 **런 오병합
덩어리**였고, NEW 는 `{friends music} over {studying sports}`·`있나요?조언을` 로
정상 분리됐다. DOM 오보고가 만들던 잘못된 런 경계가 사라진 것.

charPr 색: `#1A1A1A` 35→0, `#757877`(K60 회색 누출) 14→3 으로 감소.
DOM 색 오보고가 실제로 제거됨을 확인.

### ⚠️ 별건 회귀 발견: `03d15_plan_objectPlans` 0.81s → 116.89s

같은 계측에서 **DOM 차단과 무관한 심각한 성능 회귀**를 발견했다. 추출 총시간이
136s → 208s 로 늘었는데, 원인은 텍스트가 아니라 소유권 계획 단계다.

- `03d15_plan_objectPlans`: 0.81s → **116.89s (+116s, 144배)**. 2회 실행 모두
  재현(116.89s / 116.67s)이라 캐시·환경 노이즈가 아니다.
- 범인 커밋: **`1c3ec362` "Remove synthetic page background plane"**.
  `extraction_plan_builder.jsx` 를 395줄 수정하며 `_appendCanonicalPagePlaneObjectPlans`
  계열에 다중 중첩 루프(`for pi < doc.pages.length` 내부의 rangeTargetPageIndexes/
  crossingPages 순회 등)를 추가했다.
- 같은 커밋이 `issue.py` 의 `PAGE_PLANE_CACHE_ROOT` 를 `page_textless_plane`
  → `page_background_plane` 으로 바꿔 **기존 페이지 평면 캐시가 전부 무효화**됐다
  (신규 경로에 캐시가 쌓이지도 않음 — text-only 라 저장 대상이 없음).

DOM 차단이 텍스트에서 벌어들인 -14s 를 이 +116s 가 덮어써서 총시간이 늘어 보인다.
**두 사안은 독립적이며, 이 회귀는 별도 SPEC/PR 로 다뤄야 한다.**

### 결론: 수정 B 의 선결 조건은 모두 해소

선결 3건이 전부 정리됐으므로 **수정 B(DOM 글자속성 차단)를 진행할 수 있다**.
남은 K60 회색 누출(#757877)은 DOM 차단과 독립된 별건(SPEC-065 계열, 채도 낮아
게이트 밖)이며 수정 B 의 블로커가 아니다.
