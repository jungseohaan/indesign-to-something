# SPEC-013: ResolvedToASTBuilder Phase 모듈 분리

## 문제

`ResolvedToASTBuilder.java`가 단일 클래스로 4,000줄을 초과한다.

- Phase 0~7 + Phase 4.5 + WrapPhase5 + YGapSplit + 색상 해석 + 폰트 적용 + 표 변환 + 인라인 객체 처리 + 연결 글상자 병합 + 배경 PNG 주입 + renderable 배지 배치가 모두 한 클래스
- Phase 간 의존이 private 헬퍼 호출로 얽혀 있어 한 부분을 수정하면 다른 Phase의 부수효과를 추적하기 어려움
- 신규 기여자나 며칠 후 본인이 다시 봤을 때 진입 비용이 매우 높음
- 단위 테스트 작성 불가능 (전체 빌드 메서드를 통해야만 검증 가능)

## 목표

1. Phase 단위로 클래스 분리 — 한 파일이 하나의 책임만 갖도록
2. Phase 간 의존성을 명시적 매개변수로 노출 — 암묵적 필드 공유 제거
3. Phase별 단위 테스트 가능
4. `ResolvedToASTBuilder`는 얇은 오케스트레이터로 축소 (200줄 이하 목표)
5. **점진적** 분리 — 한 PR에 한 Phase씩, 회귀 위험 최소화

## 해결 방안

### 1. 목표 디렉토리 구조

```
src/main/java/kr/dogfoot/hwpxlib/tool/idmlconverter/normalizer/resolved/
├── ResolvedToASTBuilder.java          (오케스트레이터, 얇음)
├── ResolvedBuildContext.java          (모든 Phase가 공유하는 컨텍스트)
├── phase0/
│   └── InfraSetup.java                (폰트/색상/스타일 인프라)
├── phase1/
│   └── PageLayoutBuilder.java         (페이지/섹션 생성)
├── phase2/
│   ├── FrameClassifier.java           (inline/floating 분류)
│   ├── FramePlacer.java               (좌표 → 페이지 배치)
│   └── InlineToFloatingResolver.java  (parentId 기반 보정)
├── phase3/
│   ├── StoryMatcher.java              (IDML Story ↔ resolved frame 매칭)
│   ├── ParagraphBuilder.java
│   └── RunBuilder.java                (RunPropertyResolver 사용 — SPEC-012)
├── phase4/
│   └── TableBuilder.java
├── phase4_5/
│   ├── PostProcessor.java             (오케스트레이션)
│   ├── BulletInserter.java
│   └── InlineObjectSplitter.java
├── phase5/
│   ├── WrapPhase5.java                (textwrap 분할/슈링크)
│   └── YGapSplit.java                 (Y-점프 분할)
├── phase6/
│   └── BackgroundInjector.java        (페이지 배경 PNG)
├── phase7/
│   └── RenderableFramePlacer.java     (배지 배치)
└── shared/
    ├── LinkedFrameMerger.java         (연결 글상자 병합 + pageIndex/Y-gap)
    ├── ColorResolver.java
    └── CoordinateConverter.java       (mm/in → pt → hwpunit)
```

### 2. 공유 컨텍스트

각 Phase가 매번 동일한 매개변수 묶음을 받지 않도록 컨텍스트 객체로 묶는다.

```java
public final class ResolvedBuildContext {
    public final ResolvedData resolvedData;
    public final IDMLDocument idmlDocument;       // null 가능 (IDML-Free 모드)
    public final ASTDocument astDocument;         // 누적 결과
    public final double scaleFactor;              // mm/in → pt
    public final int pngExportDpi;
    public final FontRegistry fontRegistry;
    public final ColorResolver colorResolver;
    public final StylePropertyResolver styleResolver;
    public final ProgressReporter reporter;
    // ... 빌드 도중 누적되는 인덱스
    public final Map<String, ASTTextFrameBlock> frameById;
    public final Map<String, ASTPage> pageByIndex;
}
```

각 Phase는 `void run(ResolvedBuildContext ctx)` 형태의 단일 엔트리를 가짐.

### 3. 점진적 분리 순서 (위험 최소화)

각 단계마다 빌드 + 회귀 테스트 5종 통과 후 다음 단계 진행.

| 단계 | 작업 | 위험도 |
|---|---|---|
| 1 | `ResolvedBuildContext` 도입 (필드만, 로직 이동 없음) | 낮음 |
| 2 | `shared/CoordinateConverter`, `shared/ColorResolver` 추출 | 낮음 |
| 3 | `Phase0/InfraSetup` 분리 | 낮음 |
| 4 | `Phase1/PageLayoutBuilder` 분리 | 낮음 |
| 5 | `Phase6/BackgroundInjector`, `Phase7/RenderableFramePlacer` 분리 (독립적이어서 안전) | 낮음 |
| 6 | `Phase4/TableBuilder` 분리 | 중간 |
| 7 | `Phase4_5` 후처리 분리 | 중간 |
| 8 | `Phase2` 분류/배치 분리 | **높음** |
| 9 | `Phase3` 스토리/단락/런 빌드 분리 (RunBuilder는 SPEC-012 결과 활용) | **높음** |
| 10 | `Phase5` WrapPhase5 + YGapSplit 분리 | **높음** |
| 11 | `LinkedFrameMerger` 분리 | 중간 |
| 12 | `ResolvedToASTBuilder` 잔존 코드 정리 | 낮음 |

### 4. 분리 시 규칙

- **Phase 클래스는 stateless** — 모든 상태는 `ResolvedBuildContext`에 둔다
- **메서드 시그니처 변경 금지** — 내부 헬퍼 호출은 새 클래스의 public static으로 옮기고 원본은 위임 후 제거
- **각 단계는 하나의 PR/커밋** — 회귀 발생 시 즉시 롤백 가능
- **공통 패턴 추출 금지** — 분리만 하고 리팩터링은 별도 SPEC. 욕심내면 회귀 발생

### 5. 단위 테스트 추가

분리 후 각 Phase에 대해 fixture 기반 테스트:
- `phase0/InfraSetupTest.java` — 가짜 IDMLDocument로 인프라 셋업 검증
- `phase4/TableBuilderTest.java` — 표 변환 검증
- `phase5/WrapPhase5Test.java` — composedLine indent 입력 → 분할 결과 검증

테스트는 점진적으로 추가, SPEC 완료 조건은 아님.

## 수정 파일

> 매우 광범위. 단계별로 SPEC을 세분화할 수도 있음.

1. `normalizer/resolved/ResolvedBuildContext.java` — 신규
2. `normalizer/resolved/shared/*.java` — 공통 헬퍼 추출
3. `normalizer/resolved/phase0/InfraSetup.java` ~ `phase7/RenderableFramePlacer.java` — Phase별 신규
4. `normalizer/ResolvedToASTBuilder.java` — 점진적 축소, 최종적으로 오케스트레이터만 남김
5. `IDMLToHwpxConverter.java` — 호출 시그니처 유지 (변경 없음)

## 검증

각 단계마다:
- [ ] `mvn clean package -q -DskipTests` 빌드 성공
- [ ] 회귀 테스트 5종 변환 결과가 분리 전과 **bit-for-bit 동일**해야 함 (실패 시 즉시 롤백)
  - 중3 영어 1단원
  - 중3 국어 4단원
  - 중3 과학 6단원
  - 22개정 비상 고등 논술
  - 수식 많은 문서 1종
- [ ] AST JSON export 비교 (`--export-ast` CLI) — 분리 전후 동일
- [ ] HWPX 출력 파일 크기/구조 비교

전체 SPEC 완료 조건:
- [ ] `ResolvedToASTBuilder.java` 200줄 이하
- [ ] 12단계 모두 통과
- [ ] 회귀 0건

## 의존

- **선행**: SPEC-012 (Resolved 우선순위 통합) — Phase3/RunBuilder 분리 시 활용
- **후속**: SPEC-010 (레거시 파이프라인 종료) — 분리 완료 후 진행 권장

## 위험

- 단계가 많아 시간이 오래 걸림
- Phase 간 암묵적 의존이 분리 도중 드러나 회귀를 일으킬 가능성
- 비용 대비 효과가 사용자에게 직접 보이지 않음 (내부 정리)

## 상태: 대기
