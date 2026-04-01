# SPEC-010: 변환 파이프라인 단순화

## 문제

현재 파이프라인이 복잡하여 디버깅이 어렵다:

1. **두 개의 AST 빌드 경로** — 레거시(4단계) vs Resolved, 결국 같은 ASTDocument 생성
2. **Flat 레이어의 역할 불명확** — AST→Flat→AST 왕복 변환으로 좌표 추적 어려움
3. **죽은 코드** — ASTToHwpxConverter, Stage1~4 (데스크앱에서 미사용)

### 현재 구조

```
IDMLToHwpxConverter
  ├─ [경로 A] IDMLNormalizer (Stage1~4) → ASTDocument   ← resolved 없을 때만
  ├─ [경로 B] ResolvedToASTBuilder → ASTDocument         ← resolved 있을 때 (데스크앱 항상)
  │
  └─ 공통: ASTDocument
              → ASTToFlatConverter → FlatDocument    (AST→Flat 복사)
              → FlatDocumentGateway                  (인덱싱)
              → FlatToHwpxConverter                  (Flat→Builder)
              → FlatNodeAdapter                      (Flat→AST 복사)
              → HwpxTextBoxBuilder 등               (AST 타입 수신)
              → HWPX
```

### 좌표 흐름 (현재)
```
resolved.json (mm)
  → normalizeToPoints (×2.8346) → pt
  → ResolvedToASTBuilder.placeTextFrames → ASTTextFrameBlock (pt, hwpunit 변환)
  → ASTToFlatConverter → FlatLayoutNode (hwpunit 복사)
  → FlatNodeAdapter → ASTTextFrameBlock (hwpunit 복사)
  → HwpxTextBoxBuilder → HWPX sz.width
```

**문제**: 6단계 복사 과정에서 값이 어디서 바뀌는지 추적 불가능.

## 목표

1. 레거시 경로(Stage1~4) 비활성화 — resolved 경로를 유일한 경로로
2. Flat 레이어의 실질적 기능을 AST 단계로 흡수
3. AST → HWPX 직접 변환 (중간 복사 제거)
4. 좌표 흐름을 3단계 이하로 단축

### 목표 구조

```
IDMLToHwpxConverter
  └─ ResolvedToASTBuilder → ASTDocument (좌표: hwpunit)
       └─ ASTToHwpxConverter (신규, 직접 변환)
            ├─ z-order 정렬 + 시맨틱 레이어 분류
            ├─ HwpxTextBoxBuilder (ASTTextFrameBlock 직접 수신)
            ├─ HwpxTableBuilder
            ├─ HwpxImageBuilder
            └─ HwpxParagraphBuilder
            → HWPX
```

### 좌표 흐름 (목표)
```
resolved.json (mm) → normalizeToPoints (×2.8346) → pt
  → ResolvedToASTBuilder → pointsToHwpunits → ASTTextFrameBlock (hwpunit)
  → ASTToHwpxConverter → HWPX sz.width
```

## Flat 레이어 분석: 유지해야 할 기능

| 기능 | 현재 위치 | 필수? | 이동 대상 |
|------|----------|------|----------|
| z-order 정렬 | ASTToFlatConverter | **YES** | ASTToHwpxConverter (신규) |
| 시맨틱 레이어 분류 (BG/CONTENT/FG) | ASTToFlatConverter | **YES** | ASTToHwpxConverter |
| 페이지별 인덱싱 | FlatDocumentGateway | YES | ASTDocument에 인덱스 추가 |
| 좌표 복사 | ASTToFlatConverter + FlatNodeAdapter | **NO** | 제거 (직접 전달) |
| 메타데이터 복사 | ASTToFlatConverter | **NO** | 제거 (AST에서 직접 참조) |
| LAYOUT_REF 해소 | FlatNodeAdapter | **NO** | AST 단계에서 직접 참조 |
| Story 링크 체인 | FlatDocumentGateway | YES | ASTDocument에 사전 계산 |

## 해결 방안

### Phase 1: 레거시 경로 비활성화

1. `IDMLToHwpxConverter`에서 `isNewPipeline` 분기 제거
2. `resolved.json`이 없으면 빈 resolved 생성 후 IDML만으로 진행
3. Stage1~4, IDMLNormalizer → `@Deprecated` 마킹

### Phase 2: Flat 기능 흡수

1. **ASTDocument에 페이지 인덱싱 추가**
   - `getBlocksByPage(pageIdx)` → z-order 정렬된 블록 목록
   - `getBackgroundBlocks(pageIdx)`, `getContentBlocks(pageIdx)`, `getForegroundBlocks(pageIdx)`

2. **시맨틱 레이어 분류를 ResolvedToASTBuilder에서 수행**
   - ASTBlock에 `semanticLayer` 필드 추가
   - placeTextFrames에서 fillColor-only → BACKGROUND, overlay → FOREGROUND 분류

3. **z-order 정렬을 ASTDocument.build() 후처리로 이동**

### Phase 3: 직접 변환기 구축

1. **ASTDirectHwpxConverter** 생성 (FlatToHwpxConverter 로직 기반)
   - ASTDocument를 직접 순회
   - 기존 4개 Builder 재사용 (시그니처 변경 불필요)
   - Flat 레이어 의존 제거

2. **좌표를 AST에서 hwpunit으로 통일**
   - ResolvedToASTBuilder에서 `pointsToHwpunits` 호출하여 저장
   - Builder는 hwpunit 값을 그대로 사용

### Phase 4: 정리

1. ASTToFlatConverter, FlatDocument, FlatLayoutNode, FlatComponent 제거
2. FlatDocumentGateway, FlatNodeAdapter 제거
3. FlatToHwpxConverter 제거
4. ASTToHwpxConverter (구 직접 변환기) 제거

## 수정 파일

### Phase 1
1. `IDMLToHwpxConverter.java` — 레거시 분기 제거
2. `IDMLNormalizer.java` — @Deprecated

### Phase 2
3. `ASTDocument.java` — 페이지 인덱싱, 시맨틱 레이어 메서드 추가
4. `ASTBlock.java` — semanticLayer 필드 추가
5. `ResolvedToASTBuilder.java` — 시맨틱 레이어 분류, z-order 정렬

### Phase 3
6. `ASTDirectHwpxConverter.java` — 신규 직접 변환기
7. `IDMLToHwpxConverter.java` — 새 변환기 사용

### Phase 4
8. `flat/` 디렉토리 전체 제거 (8개 파일)
9. `converter/ASTToHwpxConverter.java` 제거

## 리스크

- **Phase 2~3 대규모 리팩토링** — 기존 변환 결과와 차이 발생 가능
- **z-order 정렬 로직 이관** 시 미묘한 순서 차이
- **레거시 경로 제거** 시 resolved 없는 변환 불가 (CLI 단독 사용 케이스)

## 단계적 접근

1단계 (즉시): Flat 레이어 좌표 복사 과정에서 버그 찾기 → 현재 width 문제 해결
2단계 (다음 스프린트): Phase 1 실행 (레거시 비활성화)
3단계 (이후): Phase 2~4 실행 (Flat 제거, 직접 변환기)

## 검증
- [ ] 빌드 성공 (`mvn clean package -q -DskipTests`)
- [ ] 기존 테스트 교과서 변환 결과 비교 (diff)
- [ ] 좌표 정확도 검증 (width/height/x/y)
