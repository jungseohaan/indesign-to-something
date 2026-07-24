# SPEC-072: 표 셀 글자속성 복원

> 상태: **머지 완료 (#144)**. 20페이지 회귀 PASS. 2026-07-25.
> 선행 실패: SPEC-071(커밋 `4ac91966`, revert `f1428404`) — 20페이지에서 크기 회귀 112건.

## 문제

표 셀 안 텍스트가 IDML 의 글자속성을 잃는다. 영어 u1 p10 "Look Ahead" 표:

| 대상 | IDML 원본 | 출력 |
|---|---|---|
| `Functions`/`Forms`/`Read` 색 | `FillColor="Color/C=0 M=78 Y=55 K=0"` | `Color/Black` |
| 같은 런의 폰트 | `AppliedFont="Arciform Sans"` (산세리프) | 함초롬바탕 (명조) |

## 원인 (확정)

표 셀 문단 경로(`StoryLoader.buildResolvedCellParagraphs`)는 `ResolvedTextFlowAstConverter`
에 **resolved 런만** 넘긴다. [SPEC-067](SPEC-067-grep-normalization-and-math-grouping.md)
수정 B가 추출기의 DOM 글자속성을 차단한 뒤로 셀 런은 텍스트만 담는다.

```json
{"text": "Functions"}
```

색·폰트의 출처가 사라졌는데 대체 경로가 없다. IDML 문단은 이 함수에 넘어오지만 문단 스타일
지정과 `revertUnbackedResolvedCellColors`(오색 되돌리기)에만 쓰이고, 속성을 **부여**하는
데는 쓰이지 않는다. 본문 문단은 `RunBuilder` 가 SPEC-065 색 우선순위를 태워 살린다.

## SPEC-071 이 실패한 이유 (핵심)

### 1. 전역 경로를 건드렸다

색을 주입하면 `CharPrFactory.hasCharacterOverrides` 가 참이 되어 그 런이 자체 CharPr 을
갖는데, `createOverrideCharPr` 이 크기를 **하드코딩 1000(10pt)** 으로 채워 문단 크기가
사라진다. 이를 고치려고 `createOverrideCharPr(textRun, inheritCharPrId)` 로 크기를
물려받게 했다.

그런데 `createOverrideCharPr` 은 **표 셀뿐 아니라 모든 텍스트 런이 지나가는 공용 경로**다.
표 셀 문제를 고치려다 문서 전체의 크기 결정 규칙을 바꿨고, 20페이지에서 112건이 깨졌다.

### 2. 상속 기준값이 문단 크기가 아니었다

`addTextRun(para, textRun, defaultCharPrId)` 의 `defaultCharPrId` 는 호출부마다 다르다.

| 호출부 | 넘기는 값 |
|---|---|
| `HwpxParagraphBuilder:227` | `paraCharPrId` (문단 스타일 charPr — **유일하게 문단 크기**) |
| `InlineFrameBuilder:559` | `"0"` (문서 기본) |
| `InlineItemDispatcher:258,413` | `"0"` |
| `InlineItemDispatcher:489` | `lastCharPrIDRef(para)` — **직전 런의 charPr** |
| `InlineItemDispatcher:517` | `lastCharPrIDRef(para)` |

`lastCharPrIDRef` 는 문단 크기가 아니라 바로 앞 런의 크기다. 게다가 화학식 경로
(`addChemicalFormulaAsTextRuns`)는 루프마다 이 값을 갱신한다:

```java
String baseCharPrIDRef = lastCharPrIDRef(para);
for (ASTTextRun run : runs) {
    paragraphBuilder.addTextRun(para, run, baseCharPrIDRef);
    baseCharPrIDRef = lastCharPrIDRef(para);   // ← 매 런마다 갱신
}
```

첨자처럼 작은 런이 하나 나오면 **그 뒤 런들이 줄줄이 그 크기로 끌려간다**. 실측된 회귀가
정확히 이 모양이다 — 전부 작아지는 방향이고, 연쇄적이다.

### 실측 회귀 (20페이지, 112건)

```
10pt → 9pt : 44    10pt → 8pt : 24    10pt → 7pt : 12
12pt → 11pt : 6     9pt → 7pt : 4      9pt → 8pt : 4
10pt → 5pt : 2     (STEP2 — 8pt 원본이 5.6pt 로)
```

`STEP2` 는 IDML `PointSize=8` 이고 **표 셀도 아니다**(`is in Cell: False`). 셀 문제를
고치려던 변경이 셀 밖 텍스트까지 망가뜨렸다는 직접 증거다.

연쇄 전파도 실측으로 확인했다. 크기가 바뀐 런 402개 중 **273개(68%)가 직전 런도 함께
바뀐 자리**이고, 3개 이상 연속으로 바뀐 구간이 19곳이다. `lastCharPrIDRef` 를 기준값으로
쓰는 경로에서 한 런이 작아지면 뒤따르는 런들이 줄줄이 끌려간다는 뜻이다.

## 다음 시도의 제약

1. **`createOverrideCharPr` 의 크기 기본값을 건드리지 말 것.** 전역 경로이고, 호출부마다
   `defaultCharPrId` 의 의미가 달라 안전한 상속 기준이 없다.
2. **셀 경로 안에서 닫을 것.** 셀 런에 크기를 명시적으로 실어(`fontSizeHwpunits`) 자체
   CharPr 이 생겨도 올바른 값을 갖게 하는 편이, 상속 규칙을 바꾸는 것보다 범위가 좁다.
   크기 출처는 IDML 문단 스타일의 `PointSize` 다(p10 `#도비라_Look Ahead_영문(AB)` = 11,
   `#도비라_Look Ahead` = 10).
3. **폰트는 별도 선결 과제.** `IDMLCharacterRun.fontFamily` 는 런이 직접 지정한 값과
   파서가 `applyCharacterStyleToRun` 으로 스타일 체인에서 상속시킨 값이 섞여 **구분
   불가**하다. 계측 결과 본문 런에도 `DIN Next LT Pro (TT)` 가 상속돼 실려 있었다.
   파서가 상속 출처를 표시해야 명시 폰트만 골라 옮길 수 있다.

## 검증 자산

- 회귀 재현: `output/issues/중3영어교과서/u1/p010-029-20260724-214759` 추출물 재사용
  (Java 만 바꿔 재변환하면 됨 — 재추출 불필요)
- 비교 기준: 같은 추출물의 `converted/*.hwpx`
- 확인 항목: 런별 (크기, 색, 폰트) 튜플 비교. **p10 만으로는 부족하다** — SPEC-071 은
  p10 에서 3건만 바뀌어 통과했지만 20페이지에서 112건이 깨졌다


## 구현 (2026-07-25)

### 1. 파서에 명시/상속 구분 플래그

`IDMLCharacterRun` 에 `fontFamilyExplicit` / `fontSizeExplicit` / `fillColorExplicit` 를
추가하고, **런 태그가 직접 명시한 값에만** 세운다.

| 경로 | 플래그 |
|---|---|
| `createRunBase` — `FillColor`/`PointSize` 속성 | **true** |
| `<AppliedFont>` 태그 | **true** |
| `applyCharacterStyleToRun` — 문자 스타일 상속 | false |
| 문단 후처리 상속(`resolveStyleProp`) | false |

`shallowCopyWithoutInlines` 도 플래그를 함께 복사한다(누락 시 사본이 출처를 잃는다).

계측으로 판별을 확인했다 — `Functions` 만 font·color 가 explicit=true 이고, 본문
`I'm glad`/`What can I do` 는 `DIN Next LT Pro (TT)` 를 갖지만 explicit=false 다.
이 구분이 없어 상속 폰트까지 옮긴 것이 SPEC-071 회귀의 원인이었다.

### 2. 셀 경로에서 명시 속성만 주입

`withExplicitIdmlCharacterAttributes` 가 AST 런 생성 **전에** 명시 속성만 resolved 런
사본으로 옮긴다. 속성을 실은 런은 자체 CharPr 을 갖게 되고 그 경로가 크기를 하드코딩
10pt 로 채우므로, 크기가 없으면 **문단 스타일의 `PointSize`** 를 함께 실어 원래 크기를
지킨다. 전역 `createOverrideCharPr` 은 건드리지 않는다 — SPEC-072 제약 1 준수.

## 검증 (20페이지)

**전제: 기준선은 같은 빌드로 새로 만들어야 한다.** 커밋된 산출물과 비교했더니 크기 385건·
폰트 708건이 어긋난 것처럼 보였는데, 그 파일이 다른 시점 코드로 만들어진 것이었다. 같은
빌드로 baseline 을 다시 뽑으니 실제 변화는 아래가 전부다(변환 자체는 결정적임을 2회 실행으로 확인).

```
run counts: 5676 → 5676
SIZE changed: 8   COLOR changed: 3   FONT changed: 8
  Functions/Forms/Read : 12pt Color/Black 함초롬바탕 → 12pt #E05B5D Arial
  ⓐ①②③               : 1pt → 10pt (부수 개선)
```

- 목표 3건은 **색·크기·폰트 셋 다** 원본과 일치(12pt 유지, 빨강, 산세리프)
- 원문자 ⓐ①②③ 이 1pt(사실상 비가시) → 10pt 로 정상화. IDML 에서 이들도
  `PointSize=None` + `[No paragraph style]`(12pt) 이라 1pt 가 오히려 버그였다
- 구조 시그니처 `verify_hwpx` **PASS** (완전 일치)
