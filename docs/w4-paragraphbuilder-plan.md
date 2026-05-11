# W4-2 — HwpxParagraphBuilder 분해 계획

> **임시 산출물** (W4-2 책임 식별 + 분리안). [improvement-roadmap.md](improvement-roadmap.md) Week 4 후속.
> 작성: 2026-05-11

---

## 1. 현재 상태

[src/main/java/.../converter/HwpxParagraphBuilder.java](../src/main/java/kr/dogfoot/hwpxlib/tool/idmlconverter/converter/HwpxParagraphBuilder.java) — **1206 LOC** (W4 plan 1093에서 증가).

진입점:
- `addParagraphToSubList(subList, astPara)` / `addParagraphToSubList(subList, astPara, cellHeight)` — 메인 (외부 호출: HwpxTextBoxBuilder, sub-modules)
- `setBuilders(tb, tab, img)` — Builder 4중 의존성 해결 (ASTToHwpxConverter)
- `addLineSegArray(Para)` — public 유틸
- `findParagraphStyle`, `hasParagraphOverrides`, `addTextRun`, `addBreak`, `addEquationRun`, `addEmptySubListPara`, `addColPrResetParagraph` — package-private 외부 호출

## 2. 책임 그룹 식별 (9개)

| # | 그룹 | 주요 메서드 | 라인 범위 | LOC |
|---|------|-------------|-----------|:--:|
| 1 | **메인 진입점/오케스트레이션** | 생성자, `setBuilders`, `addParagraphToSubList` (×2) | 37~150 | ~113 |
| 2 | **단락 높이 추정** | `estimateParagraphHeight` | 152~170 | ~19 |
| 3 | **인라인 객체 dispatch** | `addInlineObject`, `shouldFlattenInlineTextFrame`, `flattenInlineTextFrame` | 171~289 | ~119 |
| 4 | **줄간격 보정** | `clampLineSpacingForMixedFontSizes`, `hasMixedFontSizeRuns`, `maxInlineObjectHeight`, `maxFractionEquationHeight`, `applyBetweenLinesSpacing`, `ensureLineSpacingForInline` | 290~448 | ~159 |
| 5 | **ParaPr/단락 속성** | `findParaPrById`, `hasParagraphOverrides`, `findParagraphStyle`, `createOverrideParaPr`, `createParaShadingBorderFill`, `resolveParaLong`, `isHangingIndentParagraph` | 449~679 | ~231 |
| 6 | **TextRun + 공백 분리** | `addTextRun` (×2), `spaceRatio`, `addTextRunWithSpaceSplit`, `getOrCreateSpaceCharPr`, `addTextWithSpecialChars` | 681~832 | ~152 |
| 7 | **CharPr + 폰트 스타일** | `hasCharacterOverrides`, `charPrCacheKey`, `createOverrideCharPr`, `effectiveBoldStyle`, `isBoldStyle`, `isItalicStyle`, `isEquationFont`, `createEquationFontCharPr` | 833~999 | ~167 |
| 8 | **Break/Equation** | `addBreak`, `addEquationRun` | 1000~1092 | ~93 |
| 9 | **Tiny/LineSeg/유틸** | `addLineSegArray`, `addEmptySubListPara` (×2), `getOrCreateTinyCharPr`, `getOrCreateTinyParaPr`, `hwpunitToLineWidth`, `strokeTypeToLineType`, `addColPrResetParagraph` | 1093~1206 | ~113 |

합계 약 1166 LOC (실제 1206과 차이는 클래스 헤더/필드).

## 3. 분리안 (제안)

W3/W4-1의 7~6개보다 적게 — Group 1/9는 잔존, 4개 sub-module 추출.

| 새 클래스 | 포함 그룹 | LOC | 책임 |
|-----------|-----------|:--:|------|
| `HwpxParagraphBuilder` (잔존) | 1, 9 | ~400 | 메인 + 정적 유틸 + Tiny 헬퍼 |
| `InlineItemDispatcher` | 3, 8 | ~210 | 인라인 객체 dispatch + Break + Equation |
| `LineSpacingResolver` | 2, 4 | ~180 | 단락 높이 추정 + 줄간격 자동 보정 |
| `ParaPrFactory` | 5 | ~230 | ParaPr 생성 + 스타일 override + paragraph shading |
| `CharPrFactory` | 6, 7 | ~320 | TextRun/CharPr + 공백 분리 + 폰트 스타일 판별 |

잔존 클래스 ~400 LOC. 각 sub-module 180~320 LOC.

### 의존 관계
```
HwpxParagraphBuilder (메인)
  ├─ ParaPrFactory          ParaPr 생성 (메인이 호출)
  ├─ CharPrFactory          CharPr 생성 (메인 + sub-module이 호출)
  ├─ InlineItemDispatcher   인라인/Break/Equation (메인이 호출)
  │     └─ CharPrFactory   런 생성 시 charPr 필요
  └─ LineSpacingResolver    줄간격 보정 (메인이 호출)
        └─ (외부 의존 적음)
```

`InlineItemDispatcher`와 `CharPrFactory`가 약한 결합 — InlineItemDispatcher가 등식/break 런 만들 때 CharPr 필요. 현재 textBoxBuilder/tableBuilder/imageBuilder 의존도 인라인 dispatch 안에 있음 (`addInlineObject` 내부).

## 4. 단계별 추출 순서

위험 낮음 → 높음:

| Step | 추출 클래스 | 위험도 | 이유 |
|------|-------------|:---:|------|
| **A** | `LineSpacingResolver` (Group 2+4) | 낮음 | 줄간격 보정은 결합도 낮음. ctx만 의존 |
| **B** | `ParaPrFactory` (Group 5) | 중 | ParaPr 생성은 큰 그룹이지만 응집도 높음 |
| **C** | `CharPrFactory` (Group 6+7) | 중 | TextRun + CharPr — 핵심 로직 |
| **D** | `InlineItemDispatcher` (Group 3+8) | **높음** | textBoxBuilder/tableBuilder/imageBuilder 4중 의존 — Builder 패턴 복잡 |
| **E** | 잔여 정리 | 낮음 | 메인 + 정적 헬퍼만 |

각 step 후 HEAD baseline byte-identical 검증.

## 5. 위험 요소 (W4-1과 차이)

| # | 위험 | 완화책 |
|---|------|--------|
| 1 | 4중 Builder 의존 (textBoxBuilder/tableBuilder/imageBuilder) — InlineItemDispatcher 분리 시 복잡 | Step D에서만 신경. ctx와 paragraphBuilder, 그리고 sub-module이 ParagraphBuilder를 통해 다른 builder 호출 |
| 2 | `findParagraphStyle`/`hasParagraphOverrides`는 외부 호출 다수 — 잔존 또는 ParaPrFactory에 두기 결정 필요 | ParaPrFactory에 두고 HwpxParagraphBuilder delegate 메서드 추가. 외부 호출자 변경 없이 |
| 3 | `static` 메서드 (isBoldStyle, isItalicStyle, isEquationFont, hwpunitToLineWidth, strokeTypeToLineType, resolveParaLong)는 외부에서 `HwpxParagraphBuilder.X`로 호출 | 잔존 클래스에 유지 (static 호출자 갱신 안 함). 또는 sub-module 로 옮기되 static 호출 위치 갱신 |

## 6. 검증 전략

W3/W4-1 패턴 동일:
- 각 Step 후 `mvn clean package -q -DskipTests` 성공
- `mvn test -q` baseline 유지 (291/18/43)
- HEAD baseline byte-identical 확인 (`md5` 비교)

## 7. 완료 기준

- [ ] HwpxParagraphBuilder 1206 → ~400 LOC (-67%)
- [ ] 4개 sub-module 생성, 각 ~180~320 LOC
- [ ] 빌드/테스트 baseline 유지
- [ ] 시각 검증 0 warnings

---

## 다음 행동

Step A (LineSpacingResolver) 추출부터 진행.

또는 분리안 조정.
