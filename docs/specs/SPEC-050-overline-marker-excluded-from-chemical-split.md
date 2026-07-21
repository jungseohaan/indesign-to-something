# SPEC-050: overline(선분) 마커 런을 화학식 원소 분리에서 제외

> 작성: 2026-07-21. 상태: **구현 완료, 한글 육안 확인 대기**. 우선순위: P1.
> 관련: [SPEC-046](SPEC-046-*.md)/[SPEC-047](SPEC-047-*.md) (화학식 오분류 트랩 계열).

## 문제

수학 교과서에서 기하 선분 표기 `PA=PB`(PA·PB 위에 각각 윗줄/overline)가
하나의 수식 `overline{PA}=overline{PB}` 로 변환돼야 하는데, HWPX 출력에서
다음처럼 깨진다 (중3수학 u5, p166, 문제 2번):

- 일반 텍스트 런: `P`
- 수식1: `overline{A}`   ← P 가 빠지고 A 에만 overline
- 수식2: `=PB`           ← overline 자체가 손실

IDML 원본 토큰은 단일 런 `"  PAÓ=PBÓ"` (charStyle=`[No character style]`)이며,
`Ó`(U+00D3)가 EH상부자 폰트의 **선분 위 막대(overline) 마커**다.

## 근본 원인

`ASTMathGrouper.splitChemicalFormulaMixedRuns`
([ASTMathGrouper.java:147](../../converter/src/main/java/kr/dogfoot/hwpxlib/tool/idmlconverter/normalizer/ASTMathGrouper.java#L147))
가 단락 콘텐츠 빌드 최상단
([StoryLoader.java:233](../../converter/src/main/java/kr/dogfoot/hwpxlib/tool/idmlconverter/normalizer/resolved/phase3/StoryLoader.java#L233))
에서 **overline 마킹보다 먼저** 실행되며, 단일 런 `"  PAÓ=PBÓ"` 를
화학식 원소기호 경계로 조각낸다:

- `P`(인)·`B`(붕소)는 `isChemicalElement` 에 포함, `A` 는 미포함
  ([ASTMathGrouper.java:1164](../../converter/src/main/java/kr/dogfoot/hwpxlib/tool/idmlconverter/normalizer/ASTMathGrouper.java#L1164))
- 공백·`=` 는 delimiter (`isChemicalFormulaDelimiter`) → `P`, `PB` 가 원소 스팬으로 분리
- 결과: `["  ", "P", "AÓ=", "PB", "Ó"]` 5조각 (각 별도 런)

이후 조각별 overline 마킹
([TextControlNormalizer.java:63](../../converter/src/main/java/kr/dogfoot/hwpxlib/tool/idmlconverter/normalizer/TextControlNormalizer.java#L63))
은 조각 `"AÓ="` 만 보고 `Ó` 앞 연속 대문자 = `A` 만 감싼다. `P` 는 일반
텍스트로 새어나오고, `PB` 뒤 `Ó` 는 5번째 조각으로 분리·소거되어 overline
이 사라진다.

즉 overline 마킹 알고리즘은 정상(연속 대문자 시퀀스 전체를 감쌈)이나,
**상류 화학식 분리가 런을 미리 쪼개** 마킹이 온전한 `"PAÓ"` 를 못 본다.

## 목표

`Ó`(overline 선분 마커)를 담은 런은 정의상 기하 수식이지 화학식이 아니므로,
화학식 원소 분리 대상에서 제외한다. 그러면 런이 온전히 유지돼 overline
마킹이 `PAÓ` 전체를 보고 `overline{PA}` 를 만든다.

## 해결 방안

`splitChemicalFormulaMixedRuns` 의 per-run 가드에 **overline 마커(`Ó`,
U+00D3) 포함 시 분리 스킵**을 추가한다. 기존 "정체/정자" charStyle 예외
([ASTMathGrouper.java:156-166](../../converter/src/main/java/kr/dogfoot/hwpxlib/tool/idmlconverter/normalizer/ASTMathGrouper.java#L156))
와 같은 위치·같은 패턴.

근거: `Ó`(U+00D3)는 EH상부자 폰트의 선분 위 막대 마커로, 기하 선분 표기
(PA̅, OB̅)에만 나타난다. 화학식에는 등장하지 않으므로 이 가드가 정상 화학식
분리를 저해하지 않는다 (실측: u5 의 모든 `Ó` 는 OA/OB/PA/PB 등 선분 문맥).

## 수정 파일

화학식 오분류는 **두 함수**에서 발생하며 둘 다 수정해야 한다:

1. `converter/.../normalizer/ASTMathGrouper.java`
   — `splitChemicalFormulaMixedRuns` 에 `Ó`(U+00D3) 포함 런 스킵 가드 추가
   → **첫 번째 항** `overline{PA}` 복구 (P 가 밖으로 새지 않음).

2. `converter/.../normalizer/resolved/phase3/RunBuilder.java`
   — `splitChemicalFormulasAndLatinVarsInMixedText` 진입부에 **overline PUA 마커
   (``/``) 포함 시 화학식 분리 스킵** 가드 추가.
   → **두 번째 항** `=overline{PB}` 복구.

### 두 번째 버그 (RunBuilder) 상세

SPEC-050 1차(ASTMathGrouper 가드만) 적용 후에도 두 번째 항이 깨졌다:
`overline{PA}` 는 정상이나 `=overline{}` + raw `PB` 로 분리. 원인은
`RunBuilder.splitChemicalFormulasAndLatinVarsInMixedText`
([RunBuilder.java:766](../../converter/src/main/java/kr/dogfoot/hwpxlib/tool/idmlconverter/normalizer/resolved/phase3/RunBuilder.java#L766),
호출 [:1128](../../converter/src/main/java/kr/dogfoot/hwpxlib/tool/idmlconverter/normalizer/resolved/phase3/RunBuilder.java#L1128))
가 **overline 마킹이 끝난 뒤** 실행되며, 이미 마킹된 텍스트
`PA=PB` 안의 `PB`(P=인, B=붕소, 원소 2개)를 화학식
토큰으로 오인 → 런을 쪼개 두 번째 마커쌍 파괴. `tryParseChemicalFormulaToken`
의 경계 검사(`isLetterOrDigit`)가 PUA 마커를 투명하게 넘겨 마커 안을 화학식으로
본 것이 함정. `PA` 는 `A` 가 원소가 아니라 매칭 안 돼 첫 항은 생존 — 이 비대칭이
증상의 서명이었다.

## 검증

- [x] `mvn -pl converter -am -DskipTests package` — exit 0
- [x] u5 재변환 → p166 문제 2번 `PA=PB` = `overline{PA}` + `=overline{PB}`
      (P 밖 텍스트 없음, 두 항 모두 base 온전)
- [x] 문서 전체 overline 수식 104개 전부 정상, 빈 `overline{}` 0개
      (이전엔 PB·ON 등 원소패턴 선분이 전부 깨졌음)
- [x] 수식 총계 150 → 184 (깨져 흩어지던 overline 항들이 온전한 수식으로 복구)
- [ ] 화학식(H₂O 등) 회귀 없음 — `Ó`·PUA마커 없는 런은 기존 경로 유지 (수학
      문서엔 화학식 없어 별도 화학 케이스로 확인 권장)
- [ ] 한글 육안 확인 (p166 선분 기호 렌더)
