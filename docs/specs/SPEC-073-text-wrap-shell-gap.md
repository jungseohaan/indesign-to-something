# SPEC-073: 라벨 셸과 본문이 겹침 — text wrap 미실행

> 상태: **조사 완료, 구현 보류**. 2026-07-25.
> 사례: 영어 u1 p12 "Listen & Number" 배지 — 노란 셸과 "대화 속 여학생…" 본문이 겹침.

## 문제

라벨 배지(노란 셸)와 바로 뒤 본문 첫 글자 사이에 있어야 할 여백이 사라져 **겹쳐 렌더된다**.

## 원본 구조

그룹 `19059` 안에 세 요소가 형제로 놓인다.

| id | 종류 | 역할 | x 좌표(mm) |
|---|---|---|---|
| 2152 | Polygon | 노란 셸 (2173 을 자식으로 가짐) | 23.3~52.6 |
| 2173 | TextFrame | "Listen & Number" | 23.2~52.7 |
| 2149 | TextFrame | "대화 속 여학생이…" 본문 | **45.0**~185.0 |

여기에 헤드폰 아이콘(2176~2178)이 왼쪽에 붙는다.

**본문 프레임은 원래부터 셸과 7.6mm 겹치도록 배치돼 있다.** 이는 오류가 아니라 의도된
설계이고, InDesign 에서 겹쳐 보이지 않는 이유는 셸에 텍스트 감싸기가 걸려 있기 때문이다.

```xml
<TextWrapPreference Inverse="false" TextWrapSide="BothSides" TextWrapMode="BoundingBoxTextWrap">
  <Properties>
    <TextWrapOffset Top="0" Left="0" Bottom="8.503937007874017" Right="8.503937007874017" />
  </Properties>
</TextWrapPreference>
```

셸이 오른쪽으로 **8.5pt** 여백을 만들어 본문 첫 글자를 밀어낸다. 그 여백이 유실돼 겹친다.

같은 패턴이 이 페이지에 셋 있다 — 소스 `2152`, `2064`, `1849`.

## 원인 — 두 겹

### 1. plan 생성이 감싸기를 버린다 (수정함)

추출기는 값을 정상 수집한다. 계측 실측:

```
2152 tw=BoundingBoxTextWrap L0 R8.50393700787402 rawPrefs=yes
2064 tw=BoundingBoxTextWrap L0 R8.50393700787402 rawPrefs=yes
```

그런데 감싸기를 후보에 싣는 코드가 `extraction_plan_builder.jsx` 의
**`mixed_source_bundle_placed_visual_branch` 한 경로에만** 있었다. `pass.decoration_groups`
로 만들어진 셸 후보는 값을 통째로 잃어, **plan 1,013개 중 감싸기를 가진 것이 0개**였다.

→ `_applyTextWrapContractToCandidates` 를 후처리 체인에 추가해 후보 종류를 가리지 않고
적용하도록 고쳤다. 결과: plan 0건 → 3건, 최종 HWPX 에 `outMargin right="850"`(8.5pt,
원본과 정확히 일치) 도달.

### 2. Java 실행 경로가 감싸기를 무력화한다 (미해결 — 본질)

**값이 도달해도 화면은 바뀌지 않는다.** `HwpxImageBuilder` 의 floating 이미지 경로가
두 가지를 하드코딩하기 때문이다.

```java
// 275행 — 원본 textWrapMode 를 보지 않고 z-순서로만 결정
TextWrapMethod wrapMethod = isBehindTextVisualLayer(obj.plannedVisualLayer())
        ? TextWrapMethod.BEHIND_TEXT
        : TextWrapMethod.IN_FRONT_OF_TEXT;

// 296행 — 겹침 허용을 무조건 켠다
pic.pos().allowOverlapAnd(true)
```

`BEHIND_TEXT`/`IN_FRONT_OF_TEXT` 는 정의상 텍스트를 밀어내지 않고 앞뒤로 겹친다. 게다가
`allowOverlap=1` 이면 `outMargin` 이 얼마든 여백이 생기지 않는다. 실제 출력 확인:

```xml
<hp:pic textWrap="SQUARE" textFlow="BOTH_SIDES" ...>
  <hp:outMargin left="0" right="850" top="0" bottom="850"/>
  <hp:pos ... allowOverlap="1" .../>
```

### 왜 이 경로를 타는가

감싸기를 제대로 처리하는 코드는 같은 파일에 따로 있다(195~208행: `useWrapping` 이면
`outMargin` 에 원본 오프셋을 넣고 실제 감싸기 모드를 쓴다). 그 진입 조건인
`InlineFlowPolicy.usesNonFlowWrapping` 은 **`isAnchored`(앵커된 인라인 개체)** 를 요구한다.

우리 셸은 `placement=FLOATING` / `coordinateSpace=PAGE` 인 **페이지 고정 개체**라 조건을
만족하지 못하고, 감싸기를 무시하는 floating 경로로 간다.

## 남은 작업

floating 경로에서도 원본 감싸기를 존중하려면 `allowOverlap` 과 감싸기 모드 결정을
조건부로 바꿔야 한다. 다만 **이 경로는 페이지의 모든 플로팅 시각물이 지나가는 공용 코드**다.

[SPEC-072](SPEC-072-cell-char-attr-investigation.md) 에서 공용 경로(`createOverrideCharPr`)를
건드렸다가 20페이지에서 크기 112건이 깨진 전례가 있다. 같은 실수를 반복하지 않으려면
범위를 좁히는 방법을 먼저 찾아야 한다.

검토할 만한 방향:

1. **감싸기 계약이 있는 plan 에만** `allowOverlap=false` + 원본 감싸기 모드 적용.
   현재 20페이지에서 해당 plan 은 3건뿐이라 영향 범위가 작다
2. 셸+본문을 하나의 인라인 흐름으로 묶어 기존 `usesNonFlowWrapping` 경로를 타게 하는 방법.
   구조 변경이 커서 다른 페이지 회귀 위험이 높다

## 검토했으나 보류한 대안 (2026-07-25)

### A. 셸+본문을 한 테이블에 넣고 글자 취급(`treatAsChar`)

**메커니즘은 정확하다.** `treatAsChar=true` + `affectLSpacing=true` + `flowWithText=true` +
**`allowOverlap=false`** 조합이 이미 코드에 있다(`HwpxImageBuilder.addInlineBadgeGroup`,
`INLINE_BADGE_GROUP → hp:container`). 글자로 취급되면 뒤 텍스트가 자동으로 밀린다.
SPEC-073 본질이던 `allowOverlap=true` 가 여기선 false 라 실제로 작동한다.

**그러나 구조 장벽이 있다.** 이 케이스의 셸(2152)·라벨(2173)·본문(2149)은 **모두
`storyAnchorPlacement=PAGE`** 인 페이지 고정 개체다(앵커된 인라인 아님, 두 TF 는 별개 스토리).
`treatAsChar` 가 작동하려면 대상이 어떤 문단의 글자 자리에 들어가야 하는데 그 문단이 없다.
"테이블로 묶어 흐름을 만든다"는 건 원본에 없는 구조를 합성하는 일이고, 본문을 셀에 넣으면
지금 편집 가능한 `TEXT_SLOT` 이 셀 구조에 종속돼 SPEC-070/072 계열 손상 경로에 노출된다.
→ 위 "방향 2"와 같은 리스크. 보류.

### B. 본문 문단에 왼쪽 들여쓰기(left indent)를 줘 밀기

**이 페이지에는 성립한다.** 실측:

| 셸 | 본문 | 셸 right − 본문 left | 같은 줄? | 본문 |
|---|---|---|---|---|
| 2152 | 2149 | 7.6mm | O (top 차 0.1) | 40자·1줄 (프레임 8.9mm) |
| 2064 | 2060 | 11.0mm | O (top 차 0.4) | 45자·1줄 (프레임 8.7mm) |

본문이 **한 줄**이고 셸과 같은 줄에서 시작하므로, 문단 left indent 를 겹침(+감싸기 오프셋
8.5pt≈3mm)만큼 주면 감싸기와 같은 시각 효과가 난다. **Java floating 경로를 안 건드리므로
공용 회귀 위험이 없다** — 이 접근의 가장 큰 장점.

**제약 두 가지.**

1. 밀기 양이 발문마다 다르다(7.6 vs 11.0mm). 고정값이 아니라 `셸 오른쪽 − 본문 왼쪽`
   개별 계산 필요
2. left indent 는 **모든 줄에 적용**된다. 본문이 두 줄 이상이면 둘째 줄부터 셸 아래로
   내려가 왼쪽 끝에서 시작해야 하는데 left indent 로는 그게 안 된다 → 두 줄짜리 발문에서
   어긋남

**미확인**: 20페이지 전체에 이 셸+본문 패턴이 몇 개고, 그중 본문이 두 줄 이상인 게 있는지.
한 줄 비율이 높으면 이 방식이 방향 1(Java 수정)보다 안전한 해법이다. 재개 시 이 조사부터.

## 검증 자산

- 재현: `output/issues/중3영어교과서/u1/p012-20260725-004738`
- 확인 항목: 최종 HWPX 의 `<hp:outMargin>` 값과 `<hp:pos allowOverlap>`,
  그리고 **한글에서 실제 여백이 생기는지** — 값만 보면 이미 맞아서 통과로 오판하기 쉽다
- **p12 만으로 판단하지 말 것**: SPEC-071/072 의 교훈대로 20페이지 회귀 필요.
  기준선은 반드시 같은 빌드로 새로 뽑을 것
