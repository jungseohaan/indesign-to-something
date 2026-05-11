# W4 — HwpxTextBoxBuilder 분해 계획

> **임시 산출물** (W4-1 책임 식별 + 분리안). [improvement-roadmap.md](improvement-roadmap.md) Week 4 작업.
> 작성: 2026-05-10

---

## 1. 현재 상태

[src/main/java/.../converter/HwpxTextBoxBuilder.java](../src/main/java/kr/dogfoot/hwpxlib/tool/idmlconverter/converter/HwpxTextBoxBuilder.java) — **1980 LOC** (W4 plan 시점 1619에서 증가, 사용자가 SPEC 작업으로 추가).

진입점:
- `convertTextFrameBlock(framePara, block)` — 일반 글상자 (외부 호출: ASTToHwpxConverter)
- `addInlineTextFrame(para, obj)` — 인라인 텍스트프레임 (외부 호출: HwpxParagraphBuilder)
- `addPageLevelOverlay(anchorPara, obj, pageX, pageY)` — 페이지 오버레이 (외부 호출: ASTToHwpxConverter, FlatToHwpxConverter)

## 2. 책임 그룹 식별

| # | 그룹 | 주요 메서드 | 라인 범위 | 추정 LOC |
|---|------|-------------|-----------|:--------:|
| 1 | **메인 변환 + 다단/래퍼** | `convertTextFrameBlock`, `addMultiColumnFrameBorder`, `addWrapperRoundedRect`, `convertRoundedWrapperDrawText` | 339~711 | ~370 |
| 2 | **회전/라운드 변형** | `convertRotatedFloatingBlock`, `convertRoundedFloatingBlock` | 712~953 | ~240 |
| 3 | **단일 컬럼 테이블 변환** | `convertSingleColumnTable` (×2 오버로드) | 954~1204 | ~250 |
| 4 | **단락/열 분배 헬퍼** | `adjustHeightByFontMetrics`, `distributeVerticalSpace`, `estimateParagraphHeight`, `computeColumnWidths`, `distributeParagraphs`, `countParagraphChars` | 240~338, 1235~1382 | ~250 |
| 5 | **인라인 텍스트프레임** | `addInlineTextFrame` | 1383~1618 | ~236 |
| 6 | **페이지 오버레이** | `addPageLevelOverlay`, `createOverlayBorderFill`, `getOrCreateSpacerImage` + `SPACER_PNG_1x1` | 1619~1936 | ~420 |
| 7 | **연결 글상자** | `applySubListLink` | 1205~1234 | ~30 |
| 8 | **정적 헬퍼** | `computeCornerRatio`, `blendColorWithWhite` | 1937~1979 | ~40 |

합계 약 1840 LOC (실제 1980과 차이는 클래스 헤더/필드/주석).

## 3. 외부 호출 분석

| 메서드 | 호출자 | 분리 시 visibility |
|--------|--------|-------------------|
| `convertTextFrameBlock` | `ASTToHwpxConverter` | `public` 유지 (메인 진입점) |
| `addInlineTextFrame` | `HwpxParagraphBuilder` | `package-private` 유지 |
| `addPageLevelOverlay` | `ASTToHwpxConverter`, `FlatToHwpxConverter` | `public` 유지 |
| Group 2/3 메서드 | 내부에서만 호출 | `package-private` (같은 converter/ 패키지) |
| Group 4 헬퍼 | 내부에서만 호출 | `package-private` |

## 4. 분리안 (제안)

### 권고: 5개 클래스로 분리

| 새 클래스 | 포함 그룹 | 추정 LOC | 비고 |
|-----------|-----------|:--------:|------|
| `HwpxTextBoxBuilder` (잔존) | 1, 7, 8 | ~440 | 메인 변환 + 연결 + 정적 헬퍼 |
| `FrameTransformations` | 2 | ~240 | 회전/라운드 분기 |
| `SingleColumnTableConverter` | 3 | ~250 | 1x1 테이블 변환 |
| `TextBoxLayoutHelpers` | 4 | ~250 | 단락/열 분배 (package-private static) |
| `InlineFrameBuilder` | 5 | ~236 | 인라인 텍스트프레임 |
| `PageOverlayBuilder` | 6 | ~420 | 페이지 오버레이 + 헬퍼 |

각 sub-module 230~440 LOC. 잔존 HwpxTextBoxBuilder는 ~440 LOC.

### 의존 관계
```
HwpxTextBoxBuilder (메인)
  ├─ TextBoxLayoutHelpers (정적 헬퍼)
  ├─ FrameTransformations (회전/라운드)
  │     └─ HwpxParagraphBuilder (단락 빌더 — ctx 통해)
  ├─ SingleColumnTableConverter
  │     └─ HwpxParagraphBuilder
  ├─ InlineFrameBuilder
  │     └─ HwpxParagraphBuilder (외부 호출자)
  └─ PageOverlayBuilder
        └─ (ImageInserter 등 ctx 의존)
```

순환 의존 없음. 각 sub-module은 ctx + paragraphBuilder만 의존.

## 5. 단계별 추출 순서 (W4 작업)

위험 낮음 → 높음 순:

| Step | 추출 클래스 | 위험도 | 이유 |
|------|-------------|:------:|------|
| **A** | `TextBoxLayoutHelpers` (Group 4) | 낮음 | 정적 헬퍼, 외부 의존 없음 |
| **B** | `PageOverlayBuilder` (Group 6) | 낮음 | 큰 그룹이지만 응집도 높음, 외부 호출 명확 |
| **C** | `InlineFrameBuilder` (Group 5) | 중 | 단일 메서드, paragraphBuilder 의존 |
| **D** | `SingleColumnTableConverter` (Group 3) | 중 | 두 오버로드 함께 처리 |
| **E** | `FrameTransformations` (Group 2) | 중 | 메인 변환에서 호출되는 분기 |
| **F** | 잔여 정리 (HwpxTextBoxBuilder ~440 LOC) | 낮음 | 메인 + 연결 + 정적 헬퍼만 |

각 step은 별도 커밋. 골든 변환 + HEAD baseline byte-identical 검증.

## 6. 검증 전략

W3 Step A 패턴 동일:
- 각 Step 후 `mvn clean package -q -DskipTests` 성공
- `mvn test -q` baseline 유지 (291/18/43)
- HEAD 직전 commit과 변환 결과 byte-identical 확인 (md5 비교)

## 7. 완료 기준

- [ ] HwpxTextBoxBuilder 1980 LOC → ~440 LOC (-78%)
- [ ] 5개 sub-module 생성, 각 ~230~420 LOC
- [ ] 빌드/테스트 baseline 유지
- [ ] 시각 검증 0 warnings
- [ ] [improvement-roadmap.md](improvement-roadmap.md) W4-1 체크박스 갱신

## 8. HwpxParagraphBuilder (W4-2 후속)

[HwpxParagraphBuilder.java](../src/main/java/kr/dogfoot/hwpxlib/tool/idmlconverter/converter/HwpxParagraphBuilder.java) — **1206 LOC** (W4 plan 1093에서 증가).

W4-1 완료 후 동일 패턴으로 처리. 분리안은 별도 plan 문서 (`docs/w4-paragraphbuilder-plan.md`).

---

## 다음 행동

Step A (TextBoxLayoutHelpers 추출)부터 진행.

또는 사용자가 분리안 조정을 원하면 본 문서를 수정 후 진행.
