# SPEC-006: 테이블 포함 TextFrame의 editable 변환

## 문제

InDesign에서 TextFrame 안에 Table이 있는 경우:
- 현재: `parentStory.tables.length > 0`이면 무조건 배경 PNG에 포함
- 결과: 테이블 내부 텍스트를 편집할 수 없음
- 예: "위의 대화에 제시된 심리 연구에서..." (인간과심리 15p)

## 현재 아키텍처

### 레거시 파이프라인 (동작)
```
IDMLStoryParser.parseTable() → IDMLTable
  → ASTTableConverter.convertTable() → ASTTable
  → HwpxTableBuilder.convertTable() → HWPX Table
```

### 새 파이프라인 (미구현)
```
ResolvedToASTBuilder.placeTextFrames()
  → 테이블 포함 프레임: skip (배경 PNG 포함)
  → placeTables(): TODO, 비활성
```

## 목표

테이블 포함 TextFrame을 editable로 변환하여 테이블 셀 텍스트를 편집 가능하게 만든다.

## 해결 방안

### 접근: IDML Story XML에서 테이블 직접 파싱

기존 `IDMLStoryParser.parseTable()`과 `ASTTableConverter`를 재활용한다.

### Phase 1: ExtendScript 변경

`extract_indd.jsx`:

1. 테이블 포함 TextFrame의 editable 조건 변경:
   - `cells.contents` 합산 > 30자이면 editable 유지 (이미 시도, 셀 텍스트 접근 가능)
   - 테이블 포함 프레임도 배경에서 숨김

2. **단, Java 테이블 파싱이 구현된 후에 활성화**

### Phase 2: ResolvedToASTBuilder 변경

`placeTextFrames()`에서 테이블 포함 프레임 처리:

```java
// editable 프레임의 Story에 테이블이 있으면
if (idmlStory != null && idmlStory.hasTables()) {
    // 1. Story의 일반 텍스트 → ASTTextFrameBlock (기존 경로)
    // 2. Story의 테이블 → ASTTable (새 경로)
    for (IDMLTable table : idmlStory.tables()) {
        ASTTable astTable = ASTTableConverter.convertTable(table, ...);
        section.addBlock(astTable);
    }
}
```

### Phase 3: convertStories 통합

`convertStories()`에서 테이블 포함 Story 처리:

```java
List<ASTParagraph> paragraphs = convertStoryFromIDML(storyId);
// IDML Story에 테이블이 있으면 인라인 테이블로 삽입
IDMLStory story = loadIDMLStory(storyId);
if (story != null && story.hasTables()) {
    for (IDMLTable table : story.tables()) {
        // paragraphIndexBefore로 테이블 위치 결정
        int insertIdx = table.paragraphIndexBefore();
        // ASTTable → 해당 위치에 삽입
    }
}
```

## 수정 파일

| 파일 | 변경 내용 |
|------|----------|
| `scripts/extract_indd.jsx` | 테이블 포함 프레임 editable 조건 (Phase 1) |
| `normalizer/ResolvedToASTBuilder.java` | placeTables() 구현, convertStories 테이블 통합 |
| `normalizer/ASTTableConverter.java` | 기존 코드 재활용 (변경 최소) |

## 기존 코드 재활용 포인트

| 기존 클래스 | 위치 | 재활용 내용 |
|------------|------|-----------|
| `IDMLStoryParser.parseTable()` | idml/ | IDML Table XML → IDMLTable |
| `ASTTableConverter.convertTable()` | normalizer/ | IDMLTable → ASTTable (좌표 변환) |
| `HwpxTableBuilder.convertTable()` | converter/ | ASTTable → HWPX Table (이미 동작) |
| `ASTTableSpacerMerger` | ast/ | 스페이서 행 병합 |

## 검증

- [ ] 인간과심리 15p: "위의 대화에 제시된..." 테이블 텍스트 editable
- [ ] 인간과심리 13p: "심리학을 배우면..." 테이블 텍스트 editable
- [ ] 기존 변환 결과 회귀 없음 (테이블 없는 프레임)

## 상태

- [x] SPEC 작성
- [ ] Phase 2: ResolvedToASTBuilder 테이블 파싱
- [ ] Phase 3: convertStories 테이블 통합
- [ ] Phase 1: ExtendScript editable 조건 활성화
- [ ] 검증
