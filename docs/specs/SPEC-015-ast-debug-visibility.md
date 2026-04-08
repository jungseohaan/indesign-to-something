# SPEC-015: AST 디버깅 가시성 강화

## 문제

변환 결과를 검증할 때 사용자(개발자)가 의지하는 채널이 두 가지뿐이다:
1. PDF 프리뷰 (원본 시각 결과)
2. 출력 HWPX를 한글로 직접 열기

AST 단계의 중간 산출물이 보이지 않아 다음과 같은 디버깅 패턴이 반복된다:

- "이 텍스트가 왜 여기에 배치됐지?" → 코드를 거꾸로 추적해야 함
- "이 색상이 어디서 왔지?" → resolved/CharacterStyle/ParagraphStyle 중 어느 경로인지 모름
- "이 frame은 어느 Phase에서 만들어진 거지?" → Phase 0~7 + 4.5 + WrapPhase5 + YGapSplit + Phase 7 중 추적 불가
- ASTTreePanel은 정보 밀도는 높지만 "전체 구조 한눈에 보기"가 어렵고, 좌표를 페이지 위에 시각화하는 모드가 없음

## 목표

1. AST의 노드가 어느 Phase에서 어떤 입력으로 만들어졌는지 메타데이터로 추적 가능
2. 한 번에 "트리 구조 + 페이지 좌표 + 노드 속성"을 볼 수 있는 데스크탑 UI
3. 변환 로그에 단계별 카운트가 자동 기록되어 회귀 시 어디가 바뀌었는지 빠르게 식별
4. AST를 별도 파일로 export하는 경로 UI 노출

## 해결 방안

### 1. AST 노드에 디버그 메타데이터 추가

`ASTBlock`, `ASTTextRun`, `ASTParagraph` 등 주요 노드에 선택적 `debug` 필드 추가:

```java
public class DebugMeta {
    public String createdAt;        // "Phase2.placeTextFrames"
    public String sourceId;         // IDML 원본 ID
    public Map<String, String> appliedFrom;  // {"fontSize": "resolved", "color": "paragraphStyle"}
    public List<String> notes;      // "linked frame merged with u123", "wrap split applied"
}
```

- 기본은 비활성화 (성능/메모리 영향 없음)
- `--debug-ast` CLI 옵션 또는 데스크탑 토글로 활성화
- AST JSON export 시에만 출력

각 Phase 진입 시 `ctx.setCurrentPhase("Phase2.FrameClassifier")` 호출 → 그 동안 만들어진 노드는 자동으로 `createdAt`이 채워짐.

### 2. RunPropertyResolver 연동

SPEC-012의 `RunPropertyResolver`가 속성 적용 시 `appliedFrom`을 함께 채움:
```java
applyFontSize(astRun, resolvedRun, styleContext);
// 내부에서:
//   astRun.debug().appliedFrom.put("fontSize", "resolved");
```

이렇게 하면 "이 색상이 어디서 왔지?" 질문이 한 줄 조회로 해결.

### 3. 단계별 카운트 로그

Java `JsonProgressReporter`를 확장해 Phase 시작/종료 시 카운트 출력:

```json
{"type":"phase", "name":"Phase2.FrameClassifier", "duration_ms":234, "frames_placed":127, "inline_to_floating":3}
{"type":"phase", "name":"Phase5.WrapPhase5", "duration_ms":89, "frames_split":12, "frames_shrunk":7}
```

Rust 측이 이 이벤트를 받아 데스크탑 로그 패널에 표시.
회귀 시 "어제는 frames_placed=127이었는데 오늘은 130이다" 같은 비교가 가능.

### 4. 데스크탑 — 와이어프레임 페이지 뷰

기존 `ASTTreePanel` + `ASTDetailPanel` + `PdfPreviewPanel`에 **"와이어프레임"** 탭을 추가:

```
┌──────────────┬──────────────────────────┐
│ AST Tree     │ [상세][PDF][와이어프레임] │
│ (좌측 50%)   │                          │
│              │   ┌──────────────────┐   │
│ ▾ Story u17  │   │  Page 5 (mm)     │   │
│   ▸ P0       │   │  ┌──────────┐    │   │
│   ▸ P1       │   │  │  TF u17  │ ←선택된 노드   │
│              │   │  └──────────┘    │   │
│ ▾ Story u23  │   │   ┌────────┐     │   │
│              │   │   │ Tbl 3×2│     │   │
│              │   │   └────────┘     │   │
│              │   └──────────────────┘   │
└──────────────┴──────────────────────────┘
```

- 페이지 박스를 SVG로 그리고 각 ASTBlock을 `<rect>`로 표시
- 트리에서 선택한 노드는 빨간 테두리로 하이라이트
- 클릭하면 그 위치의 노드를 트리에서 선택 (양방향 동기화)
- 텍스트 첫 줄만 박스 안에 표시 (자세한 건 트리/디테일에서)
- 좌표 단위 토글 (mm / pt / hwpunit)

### 5. AST Export UI 노출

이미 Java CLI에 `--export-ast` 옵션이 있음. 데스크탑 변환 패널에 "AST JSON 저장" 버튼 추가:

- 클릭 → 변환과 동일하게 ExtendScript + Java 호출 → AST JSON을 사용자가 지정한 경로에 저장
- 디버그 메타데이터 포함 옵션 체크박스
- 저장 후 시스템 기본 에디터로 자동 열기 옵션

### 6. 로그 패널 (선택)

데스크탑에 변환 로그 누적 패널 추가 (현재는 진행률만 표시):
- Phase 단위 카운트 표시
- 경고/에러 별도 색상
- 검색 가능
- 변환 종료 후에도 유지

이건 별도 SPEC으로 분리해도 됨.

## 수정 파일

### Java
1. `src/main/java/kr/dogfoot/hwpxlib/tool/idmlconverter/ast/DebugMeta.java` — 신규
2. `src/main/java/kr/dogfoot/hwpxlib/tool/idmlconverter/ast/ASTBlock.java`, `ASTTextRun.java`, `ASTParagraph.java` — 선택적 `debug` 필드
3. `src/main/java/kr/dogfoot/hwpxlib/tool/idmlconverter/ast/ASTSerializer.java` — debug 필드 직렬화 (활성화 시에만)
4. `src/main/java/kr/dogfoot/hwpxlib/tool/idmlconverter/normalizer/resolved/ResolvedBuildContext.java` — `setCurrentPhase` 추가 (SPEC-013과 연동)
5. `src/main/java/kr/dogfoot/hwpxlib/tool/idmlconverter/JsonProgressReporter.java` — `phase` 이벤트 추가
6. `src/main/java/kr/dogfoot/hwpxlib/tool/idmlconverter/ConvertOptions.java` — `debugAst` 옵션
7. `src/main/java/kr/dogfoot/hwpxlib/tool/idmlconverter/ConverterCLI.java` — `--debug-ast` CLI 옵션

### Rust
8. `desktop/src-tauri/src/commands/conversion.rs` — `phase` 이벤트 라우팅, `debug_ast` 옵션 전달

### Frontend
9. `desktop/src/components/ASTPageWireframe.tsx` — **신규** SVG 와이어프레임
10. `desktop/src/App.tsx` — 우측 패널에 "와이어프레임" 탭 추가
11. `desktop/src/components/ASTDetailPanel.tsx` — debug 메타 표시 (`appliedFrom` 등)
12. `desktop/src/components/ConversionPanel.tsx` — "AST JSON 저장" 버튼, debug 토글
13. `desktop/src/stores/useAstStore.ts` — `selectedPath` ↔ 와이어프레임 양방향 동기화

## 검증

- [ ] `mvn clean package -q -DskipTests` 빌드 성공
- [ ] 데스크탑 빌드 성공
- [ ] `--debug-ast` 활성화 시 AST JSON에 `debug.createdAt`, `appliedFrom`, `notes` 필드 출력
- [ ] 비활성화 시 메모리/성능 영향 없음 (변환 시간 ±5% 이내)
- [ ] 와이어프레임 패널: 노드 클릭 ↔ 트리 선택 양방향 동작
- [ ] AST JSON 저장 버튼 동작
- [ ] Phase 카운트 로그가 데스크탑 진행률에 표시
- [ ] 33페이지 "예쁜" 텍스트 디버그: AST JSON에서 `appliedFrom.color = "resolved"` 확인 가능 (SPEC-012 적용 후)

## 의존

- **선행**: SPEC-012 (Resolved 우선순위 통합) — `appliedFrom` 메타 작성
- **선행**: SPEC-013 (Phase 모듈 분리) — `setCurrentPhase` 활용
- 단, 최소 기능(Phase 카운트, 와이어프레임)은 의존 없이 먼저 진행 가능

## 위험

- 와이어프레임 패널 구현 분량이 적지 않음 — MVP는 페이지 박스 + frame rect만, 텍스트/표는 후속
- debug 메타가 활성화된 상태로 빌드되어 성능 저하 발생 가능 — `if (ctx.debugEnabled)` 체크 누락 주의

## 상태: 대기
