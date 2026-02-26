# 다단 텍스트프레임 & 연결 글상자 HWPX 변환 설계

## 1. 배경

### 1.1 HWP/HWPX 제약사항

HWP/HWPX의 글상자(DrawText+SubList)는 **다단(multi-column)을 지원하지 않는다**.
ColPr 컨트롤을 글상자 내부에 삽입하는 방식은 본문 영역에서만 동작하며,
글상자/테이블 셀 내부에서는 무시된다.

### 1.2 InDesign 원본 구조

InDesign은 하나의 **Story**(텍스트 스트림)가 여러 TextFrame에 걸쳐 흐를 수 있다:

- **다단 텍스트프레임**: 단일 TextFrame이 `columnCount > 1`로 내부적으로 다단 분할
- **연결 텍스트프레임(Text Threading)**: 여러 TextFrame이 `previousTextFrame` / `nextTextFrame`으로 체인을 구성하여 동일 Story의 텍스트가 프레임 간 흐름

---

## 2. 다단 텍스트프레임 변환

### 2.1 선택된 전략: N개 독립 글상자 수평 배치

다단 텍스트프레임을 **N개의 독립적인 1×1 테이블**로 분할하여 수평으로 나란히 배치한다.

```
[InDesign]                          [HWPX]
┌──────────────────────────┐       ┌────────┐ ┌────────┐ ┌────────┐
│  Col1   │  Col2   │ Col3 │  →    │ Table1 │ │ Table2 │ │ Table3 │
│  text   │  text   │ text │       │ (1×1)  │ │ (1×1)  │ │ (1×1)  │
└──────────────────────────┘       └────────┘ └────────┘ └────────┘
```

**선택 이유**:
- 1×N 테이블(단일 테이블, N열) 방식은 셀 간 텍스트 흐름을 표현할 수 없음
- N개 독립 글상자는 각각 독립적으로 편집 가능하고, 한컴오피스에서 자연스럽게 렌더링됨
- 기존 `convertSingleColumnTable()` 메서드를 재사용하여 구현 복잡도 최소화

### 2.2 컬럼 폭 계산

InDesign의 `columnType`에 따라 3가지 모드를 지원한다:

| columnType | 동작 | columnWidths 필드 |
|------------|------|-------------------|
| `FixedNumber` (기본) | 균등 분할: `(전체폭 - 거터합) / N` | `null` |
| `FixedWidth` | 모든 컬럼 동일 고정 폭 | `long[N]` (모두 동일 값) |
| `FlexibleWidth` | 컬럼별 개별 폭 지정 | `long[N]` (각각 다른 값) |

```java
// computeColumnWidths() 의사코드
contentWidth = totalWidth - gutter × (colCount - 1)

if (columnWidths 지정됨) {
    // 비율 기반 분배: 지정 폭의 합 대비 각 컬럼의 비율로 contentWidth 분배
    for each column:
        result[i] = contentWidth × columnWidths[i] / sum(columnWidths)
} else {
    // 균등 분할
    result[i] = contentWidth / colCount
    result[last] = contentWidth - assigned  // 나머지 보정
}
```

### 2.3 단락 분배 전략

텍스트 레이아웃 엔진이 없으므로 **문자 수 기반 그리디 분배**를 사용한다:

```
전체 문자 수: 300
컬럼 3개 → 컬럼당 할당량: 100자

Para1(50자) → Col1 (누적 50)
Para2(60자) → Col1 (누적 110, ≥100 → 다음 컬럼으로)
Para3(40자) → Col2 (누적 40)
Para4(80자) → Col2 (누적 120, ≥100 → 다음 컬럼으로)
Para5(70자) → Col3
```

- 단락 단위로 분배 (단락 중간에서 분할하지 않음)
- 빈 단락도 최소 1문자로 카운트하여 무한 루프 방지
- 마지막 컬럼이 나머지를 모두 수용

### 2.4 수평 배치 좌표 계산

```java
long xCursor = block.x();    // 프레임 시작 X
long gutter = block.columnGutter();

for (int c = 0; c < colCount; c++) {
    convertSingleColumnTable(framePara, block, xCursor, block.y(),
            colWidths[c], h, distributed.get(c));
    xCursor += colWidths[c] + gutter;   // 다음 컬럼 시작점
}
```

---

## 3. 연결 글상자 (텍스트 플로우) 변환

### 3.1 개념

InDesign의 Text Threading을 HWPX의 **연결 글상자**(SubList linking)로 변환한다.

```
[InDesign]
TextFrame A (Story u123, prevFrame=null) → "텍스트 시작..."
TextFrame B (Story u123, prevFrame=A)    → "...텍스트 계속..."
TextFrame C (Story u123, prevFrame=B)    → "...텍스트 끝"

[HWPX]
Table1 → SubList(linkListIDRef="1", linkListNextIDRef="2")  ← 텍스트 전체
Table2 → SubList(linkListIDRef="2", linkListNextIDRef="3")  ← 빈 셀 (자동 흐름)
Table3 → SubList(linkListIDRef="3", linkListNextIDRef="0")  ← 빈 셀 (자동 흐름)
```

### 3.2 AST 빌드 (Stage4_BuildAST)

연결 체인의 **첫 번째 프레임**만 텍스트를 포함하고,
**이후 프레임**은 geometry만 갖는 빈 블록으로 생성된다:

```java
String prevFrame = tf.previousTextFrame();
boolean isLinkedContinuation = prevFrame != null && !prevFrame.isEmpty()
        && !"n".equals(prevFrame) && !"null".equalsIgnoreCase(prevFrame);

if (isLinkedContinuation) {
    // 빈 블록 생성 (geometry + storyId만, 텍스트 없음)
    ASTTextFrameBlock emptyBlock = createTextFrameBlock(tf, page, ...);
    emptyBlock.storyId(storyId);
    section.addBlock(emptyBlock);
    continue;  // Story 텍스트 변환 건너뜀
}
```

### 3.3 링크 ID 사전 할당 (ASTToHwpxConverter)

HWPX 변환 전에 전체 AST를 스캔하여 연결이 필요한 storyId를 식별하고,
순차적 linkId를 사전 할당한다:

```java
private void buildStoryLinkMap() {
    // 1단계: storyId별 블록 수 카운트
    Map<String, Integer> storyBlockCount = new LinkedHashMap<>();
    for (ASTSection section : doc.sections()) {
        for (ASTBlock block : section.blocks()) {
            if (block.blockType() == TEXT_FRAME_BLOCK) {
                String sid = ((ASTTextFrameBlock) block).storyId();
                storyBlockCount.merge(sid, 1, Integer::sum);
            }
        }
    }

    // 2단계: 2개 이상 블록을 가진 storyId에 linkId 할당
    int linkIdCounter = 1;
    for (entry : storyBlockCount) {
        if (entry.getValue() > 1) {
            List<String> linkIds = [linkIdCounter, linkIdCounter+1, ...];
            ctx.storyLinkIds.put(storyId, linkIds);
        }
    }
}
```

### 3.4 SubList 링크 적용 (HwpxTextBoxBuilder)

각 글상자의 SubList 생성 시 사전 할당된 linkId를 순차적으로 적용한다:

```java
private void applySubListLink(SubList subList, String storyId) {
    List<String> linkIds = ctx.storyLinkIds.get(storyId);
    if (linkIds == null) {
        // 단독 프레임 — 연결 없음
        subList.linkListIDRef("0").linkListNextIDRef("0");
        return;
    }

    int idx = ctx.storyLinkIndex.getOrDefault(storyId, 0);
    String myLinkId   = linkIds.get(idx);       // 현재 글상자의 ID
    String nextLinkId = linkIds.get(idx + 1);   // 다음 글상자의 ID (없으면 "0")

    subList.linkListIDRef(myLinkId).linkListNextIDRef(nextLinkId);
    ctx.storyLinkIndex.put(storyId, idx + 1);   // 인덱스 진행
}
```

**결과**: 한컴오피스에서 첫 번째 글상자의 텍스트가 넘치면 자동으로 다음 연결 글상자로 흘러간다.

---

## 4. 수정된 파일 요약

| 파일 | 작업 |
|------|------|
| `ast/ASTTextFrameBlock.java` | `columnWidths` 필드 추가, `storyId` 필드 (기존) |
| `normalizer/Stage4_BuildAST.java` | 컬럼 폭 채우기, 연결 프레임 빈 블록 생성 |
| `ast/ASTSerializer.java` | `columnWidths` long[] 직렬화 |
| `ast/ASTDeserializer.java` | `columnWidths` long[] 역직렬화 |
| `converter/HwpxTextBoxBuilder.java` | N개 글상자 변환, ColPr 제거, `applySubListLink()` |
| `converter/ASTToHwpxConverter.java` | `buildStoryLinkMap()` 사전 스캔 |
| `converter/HwpxConverterContext.java` | `storyLinkIds`, `storyLinkIndex` 추적 맵 |

---

## 5. 데이터 흐름

```
IDML TextFrame
    │
    ▼
Stage4_BuildAST
    ├─ columnType → columnWidths[] 계산
    ├─ 첫 프레임: Story 텍스트 → paragraphs
    └─ 이후 프레임: 빈 블록 (storyId만)
    │
    ▼
ASTDocument (JSON 직렬화 가능)
    │
    ▼
ASTToHwpxConverter
    ├─ buildStoryLinkMap(): storyId별 linkId 사전 할당
    │
    ▼
HwpxTextBoxBuilder.convertTextFrameBlock()
    ├─ colCount == 1 → convertSingleColumnTable()
    ├─ colCount > 1  → computeColumnWidths() + distributeParagraphs()
    │                   → N × convertSingleColumnTable() (수평 배치)
    └─ applySubListLink(): SubList에 linkListIDRef/nextIDRef 설정
    │
    ▼
HWPX 파일 (Table × N, SubList 연결 체인)
```

---

## 6. 제한사항 및 향후 개선

### 현재 제한사항

1. **단락 분배 정밀도**: 텍스트 레이아웃 엔진 없이 문자 수 기반으로 분배하므로, 폰트 크기/스타일이 다른 단락이 섞인 경우 실제 레이아웃과 차이가 발생할 수 있음
2. **인라인 다단 미지원**: `addTextBox()` (treatAsChar=true) 경로의 인라인 글상자는 다단이 있어도 단일 컬럼으로 처리됨 (인라인 위치에 N개 테이블 배치 시 레이아웃 깨짐 우려)
3. **컬럼 구분선**: InDesign의 Column Rule(컬럼 사이 세로선)은 현재 변환하지 않음

### 향후 개선 가능

- **resolved.json 기반 단락 분배**: ExtendScript로 실제 컬럼별 텍스트를 추출하면 정확한 분배 가능
- **컬럼 구분선**: 글상자 사이에 별도 GraphicLine 객체를 배치하는 방식으로 구현 가능
- **연결 글상자 텍스트 재분배**: 현재는 첫 프레임에 모든 텍스트를 넣고 HWPX 자동 흐름에 위임하지만, resolved 데이터로 프레임별 텍스트를 정확히 분배할 수 있음
