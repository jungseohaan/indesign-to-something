# SPEC-051: 각 표기·도(°) 조각을 억지 수식화하지 않고 텍스트로

> 작성: 2026-07-21. 상태: **구현 완료, 한글 육안 확인 대기**. 우선순위: P1.
> 관련: [SPEC-050](SPEC-050-overline-marker-excluded-from-chemical-split.md) (화학식 오분류 계열),
> 메모리 `no-forced-equation-from-glyphs`.

## 문제

수학 교과서(중3수학 u5, p166 문제 2번)에서 `∠PAO=∠PBO=90°` 가 HWPX에서
낱글자로 흩어지고 `°` 자리에 raw ` DEG` (공백+D+E+G)가 노출됐다:
`∠`·`P`·`A`·`O`·`=`·… 각각 개별 텍스트 런, `90` 뒤에 `D`·`E`·`G` 낱글자.

## 근본 원인 (계측으로 확정)

원본 IDML은 수식 오브젝트가 아니라 **각 기호 `∠`(별도 charStyle `∠(11pt)`,
한글 폰트) + 라틴 텍스트 `PAO=`/`PBO=90ù`** 조판이다(`ù`=U+00F9는 EH상부자
폰트의 도° 글리프). resolved 는 이 런들을 `style=Italic`, font=None 으로 보고.

두 경로가 이 약한 조각을 **억지로 수식화**하려다 실패:

1. **resolved MathProcessor** (`collectMixedFormulaEquationCluster`,
   [MathProcessor.java:586](../../converter/src/main/java/kr/dogfoot/hwpxlib/tool/idmlconverter/normalizer/resolved/phase3/MathProcessor.java#L586))
   가 조각을 `sourceType=CHEM_FORMULA` ASTEquation 으로 방출. CHEM_FORMULA 는
   `FormulaRenderer.toChemicalTextRuns` 로 **낱글자 텍스트화**되는데, `ù→"DEG"`
   (`EHFontEquationConverter.java:164`, HWP 수식 도 키워드)가 포함된
   `PBO=90 DEG` 가 `D`·`E`·`G` 낱글자로 깨지고 raw 노출.

2. **이탤릭 수식 버퍼** (`RunPostProcessor.flushItalicMathBuf` →
   `shouldEmitItalicEquation`,
   [RunPostProcessor.java:310](../../converter/src/main/java/kr/dogfoot/hwpxlib/tool/idmlconverter/normalizer/resolved/phase3/RunPostProcessor.java#L310))
   가 `=90ù`(→`=90 DEG`)를 `=` 때문에 수식(EH_FONT)으로 방출. 이 조각은 각도값
   텍스트이지 수식 오브젝트가 아니다.

핵심 원칙(메모리 `no-forced-equation-from-glyphs`): **원본에 수식 구조 근거가
없으면 글리프만 보고 억지로 수식화하지 말고, 텍스트로 두고 글리프만 디코딩한다**
(`ù`→`°`). plain 텍스트 경로였다면 `decodeStrayGlyphInBodyFont` 가
`PBO=90ù`→`PBO=90°` 로 깨끗이 처리했을 것이다.

## 해결 방안

두 경로 모두에서, 실질 수식 구조 없이 각(angle)/도(DEG) 키워드만 있는 약한
조각은 수식으로 방출하지 않고 텍스트로 폴백시킨다.

1. **MathProcessor** — `collectMixedFormulaEquationCluster` 의 CHEM_FORMULA 방출
   직전에 `containsNonChemicalFormulaKeyword(hwpScript)` 가드. 다글자 HWP 수식
   키워드(angle/DEG/TIMES/div/sqrt/over/LEQ…)가 단어 경계로 존재하면 null 반환
   → 원본 텍스트 런 보존(`toChemicalTextRuns` 낱글자화 회피).

2. **RunPostProcessor** — `shouldEmitItalicEquation` 에
   `isWeakAngleDegreeScript(script)` 가드. 강한 수식 구조(over/sqrt/^/_/²³ 등)
   없이 `=`·숫자·공백 + angle/DEG 뿐이면 텍스트로 폴백. 그리고
   `emitItalicMathRunsAsText` 가 텍스트 폴백 시 `EHFontGlyphMap.decodeStrayGlyphText`
   로 EH 글리프(ù→°)를 디코딩(기존엔 raw 그대로였음).

## 수정 파일

1. `converter/.../resolved/phase3/MathProcessor.java`
   — `containsNonChemicalFormulaKeyword` 헬퍼 + CHEM_FORMULA 방출 가드
2. `converter/.../resolved/phase3/RunPostProcessor.java`
   — `isWeakAngleDegreeScript` 헬퍼 + `shouldEmitItalicEquation` 가드
   + `emitItalicMathRunsAsText` 에 EH 글리프 디코딩 추가

## 검증

- [x] `mvn -pl converter -am -DskipTests package` — exit 0
- [x] u5 재변환 → p166 문제 2번 `∠PAO=∠PBO=90°` 온전한 텍스트, DEG raw 노출 없음, ° 정상
- [x] 회귀: DEG 텍스트 노출 4→0, overline 수식 104개 정상 유지(빈 overline{} 0)
- [x] angle/DEG 포함 수식 16개는 `<hp:equation>` 안에 정상 유지(텍스트 노출 아님)
- [ ] 남은 `ù` raw 5개는 별건(각도값 없는 `x=ù`·`∠ù` 문맥, 수정 전에도 5개로 동일 —
      본 SPEC 범위 밖)
- [ ] 한글 육안 확인 (p166)

## 범위 밖

- **수식 이탤릭→정체(노말)**: 사용자 요청이나 광범위 영향으로 별개 작업으로 분리.
- **단독 `ù` 런 디코딩**: 앞 숫자가 다른 런에 있어 `decodeStrayGlyphInBodyFont`
  가 못 잡는 케이스(수정 전과 동일 5개). 별건.
