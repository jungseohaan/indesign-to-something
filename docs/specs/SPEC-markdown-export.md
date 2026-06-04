# SPEC-markdown: AST → Markdown 내보내기

## 문제

현재 변환기는 HWPX 출력만 지원한다.

## 목표

**LLM 학습 / RAG 데이터 생성** 목적으로 `ASTDocument`의 계층적 구조를 Markdown으로 표현한다.

- 글 블럭과 이미지 블럭을 **읽기 순서**로 배치
- **단락 스타일 이름**을 HTML 주석으로 모든 단락 앞에 기록
- 연결 글상자 체인(같은 storyId)은 전체 문서에서 **한 번만** 연속 블럭으로 합침
- 이미지는 Phase 7 ExtendScript 렌더 PNG (HWPX와 동일한 소스)

---

## 출력 형식 규칙

### 1. YAML front matter

```markdown
---
source: input.idml
pages: 22
---
```

`pages` = `doc.sections().size()`

---

### 2. 페이지 구분

페이지 번호는 `ASTSection.pageNumber()` 직접 사용.

```markdown
<!-- page 1 -->

(내용)

---

<!-- page 2 -->

(내용)
```

- 첫 번째 섹션: `---` 없이 주석만
- 이후 섹션: 빈 줄 + `---` + 빈 줄 + `<!-- page N -->`
- 섹션에 출력할 블럭이 하나도 없으면 (`<!-- page N -->` 포함) **해당 섹션 전체 스킵**

---

### 3. 블럭 읽기 순서 결정

#### Pre-pass — 전체 문서 storyId 그룹핑

변환 시작 시 `doc.sections()` 전체를 순회해 `Map<String, StoryGroup>`을 빌드한다.

```
for each section in doc.sections():
  for each block in section.blocks():
    if block.blockType() != TEXT_FRAME_BLOCK: continue
    tf = (ASTTextFrameBlock) block
    if tf.sourceId() != null && tf.sourceId().startsWith("page_num_"): continue
    if tf.isBackgroundOnly(): continue
    key = (tf.storyId() != null) ? tf.storyId()
          : (tf.sourceId() != null) ? "tf_" + tf.sourceId()
          : null
    if key == null: continue   ← storyId, sourceId 둘 다 null이면 무시
    group = map.getOrCreate(key)
    group.addFrame(tf, section.pageNumber())
```

각 `StoryGroup` 필드:

| 필드 | 계산 방법 |
|------|-----------|
| `key` | storyId 또는 `"tf_" + sourceId` |
| `frames` | 전체 문서 동일 key TF들 — pageNumber 오름차순 → 같은 페이지 내 Y 오름차순 |
| `ownerPage` | frames 중 첫 번째 TF가 속한 섹션의 `pageNumber` |
| `pages` | 걸친 pageNumber 목록 (중복 제거, 오름차순) |
| `representativeY` | ownerPage의 첫 번째 TF의 `y()` |
| `centerX` | ownerPage의 첫 번째 TF의 `x() + width() / 2` |
| `width` | ownerPage의 첫 번째 TF의 `width()` |
| `fillColor` | frames 중 첫 번째 비-null `fillColor()` |

**출력 규칙**: StoryGroup은 `group.ownerPage == section.pageNumber()`인 섹션에서만 출력.

> `tf.paragraphs()`는 Phase 3 ParagraphDistributor가 `skipParagraphs`/`excludedParagraphIndices`를
> 이미 적용한 상태다. Markdown 변환기는 별도 필터링 없이 `tf.paragraphs()`를 그대로 읽는다.

#### 섹션별 블럭 수집

각 섹션에서 출력 대상 블럭:

| 종류 | 수집 방법 | representativeY | centerX |
|------|-----------|----------------|---------|
| StoryGroup | `ownerPage == section.pageNumber()` | 위 정의 참조 | 위 정의 참조 |
| ASTFigure | `section.blocks()` FIGURE 타입 필터 | `fig.y()` | `fig.x() + fig.width()/2` |
| ASTTable | `section.blocks()` TABLE 타입 필터 | `table.y()` | `table.x() + table.width()/2` |
| page_num TF | `tf.sourceId().startsWith("page_num_")` | `tf.y()` | `tf.x() + tf.width()/2` |

> `doc.backgrounds()`의 `ASTPageBackground`는 section.blocks()에 없다. Markdown에서 이 리스트는 순회하지 않는다.
>
> Phase 6 BackgroundInjector가 배치한 배경 ASTFigure(zOrder=0)은 section.blocks()에 있으며,
> 다른 ASTFigure와 동일하게 처리한다. `<!-- figure zOrder=0 -->` 주석으로 소비자가 필터링 가능.

#### Y 밴드 정렬 알고리즘

컬럼 분류 (`ASTSection.layout().pageWidth()` 기준):

| 조건 | Column |
|------|--------|
| `width > pageWidth * 0.6` | FULL (0) |
| `centerX < pageWidth / 2` | LEFT (1) |
| `centerX >= pageWidth / 2` | RIGHT (2) |

정렬 구현 (Java):

```java
// Step 1: Y 오름차순 정렬 (stable)
blocks.sort(Comparator.comparingLong(b -> b.representativeY));

// Step 2: 인접 블럭끼리 Y 차이 ≤ 300 hwpunit이면 같은 밴드로 묶기
//         (체인 방식 — A↔B, B↔C 이면 A,B,C가 같은 밴드)
List<List<SortableBlock>> bands = new ArrayList<>();
List<SortableBlock> current = new ArrayList<>();
for (SortableBlock b : blocks) {
    if (!current.isEmpty()
        && b.representativeY - current.get(current.size()-1).representativeY > 300) {
        bands.add(current);
        current = new ArrayList<>();
    }
    current.add(b);
}
if (!current.isEmpty()) bands.add(current);

// Step 3: 각 밴드 내에서 FULL → LEFT → RIGHT 순 (stable)
List<SortableBlock> sorted = new ArrayList<>();
for (List<SortableBlock> band : bands) {
    band.sort(Comparator.comparingInt(b -> b.column.ordinal()));
    sorted.addAll(band);
}
return sorted;
```

> **한계**: 불규칙 자유 배치에서는 순서가 어긋날 수 있다. LLM 목적에서 허용 가능.

---

### 4. StoryGroup 출력

#### 4.1 블럭 헤더 주석

```markdown
<!-- textframe storyId=u1a3b pages=1,2 fillColor=#F5E6D3 -->
```

`fillColor` 없으면 해당 속성 생략.

#### 4.2 쪽 번호 TF

`tf.sourceId().startsWith("page_num_")` → 텍스트런 concat 값이 정수이면:

```markdown
<!-- page-number: 47 -->
```

#### 4.3 writeParagraph() 알고리즘

```
1. inlineTable 체크
   para.inlineTable() != null → writeTable(para.inlineTable()); return

2. 빈 단락 스킵
   para.items() 전체가 공백 텍스트 또는 비어있음 → return

3. 스타일 이름 조회
   styleName = styleMap.getOrDefault(para.paragraphStyleRef(), para.paragraphStyleRef())
   emit "<!-- style: {styleName} -->\n"

4. 접두사 결정
   level = detectHeadingLevel(styleName)
   prefix = level > 0 ? "#".repeat(level) + " "
          : para.bulletParagraph() ? "- "
          : ""

5. 인라인 항목 렌더링
   buf = new StringBuilder()
   for item in para.items():
     TEXT_RUN      → buf.append(renderRun(run))
     BREAK         → buf.append(renderBreak(br))
     INLINE_OBJECT → buf.append(renderInlineObject(obj))
     EQUATION      → buf.append(renderEquation(eq))

6. 줄 맨 앞 이스케이프
   if prefix.isEmpty():
     content = escapeLineStart(buf.toString())
   else:
     content = buf.toString()  ← prefix가 있으면 escapeLineStart 생략

7. 출력
   emit prefix + content + "\n"
```

#### 4.4 detectHeadingLevel(styleName)

대소문자 무시 부분 일치:

| 매칭 패턴 | 반환 |
|-----------|------|
| `heading 1`, `제목1`, `대단원`, `chapter` | 1 |
| `heading 2`, `제목2`, `소단원`, `section` | 2 |
| `heading 3`, `제목3`, `subsection` | 3 |
| `heading 4`, `제목4` | 4 |
| 그 외 | 0 |

---

### 5. ASTFigure

```markdown
<!-- figure kind=IMAGE zOrder=3 width=300pt height=200pt -->
![](out_images/fig_001.png)
```

- `zOrder` 항상 기록 (소비자가 배경 필터링에 활용)
- `width`/`height`: `hwpunit / 100` → pt
- `fallbackRendered = true` → 주석에 `fallback=true` 추가

이미지 소스 우선순위:
1. `fig.imageData()` → **Phase 7 ExtendScript 렌더 PNG** (최종 레이아웃). 바이트를 파일로 기록.
2. `imageData` null + `fig.imagePath()` 있음 → Links 원본 이미지 복사 (fallback)
3. 둘 다 없음 → `<!-- figure: no-image-data -->` 주석만, 파일 생성 안 함

파일명: `fig_<3자리 시퀀스>.<ext>`
- `ext` = `fig.imageFormat()` 소문자 (null이면 `png`)

---

### 6. ASTTable

```markdown
<!-- table rows=3 cols=4 -->
| 셀1 | 셀2 | 셀3 | 셀4 |
|-----|-----|-----|-----|
| 내용 | 내용 | 내용 | 내용 |
```

**셀 렌더링**:
- 셀의 `ASTTableCell.paragraphs()`를 순회
- 각 단락의 items를 `renderRun()` / `renderInlineObject()` / `renderEquation()`으로 렌더링
- **`<!-- style: -->` 주석은 출력 안 함** — GFM 테이블 문법을 깨뜨림
- 단락 사이 줄바꿈 → `<br>`
- 단락 items 내 `ASTBreak(LINE)` → `<br>` (인라인이므로 trailing-space 방식 사용 불가)
- colspan/rowspan → 내용만 기록 (표현 불가)
- 셀 내용에 이스케이프 적용 (단, `|` → `\|` 포함)

---

### 7. 인라인 항목 렌더링

#### 7.1 renderRun(ASTTextRun run) 처리 순서

```
text = normalizeSpaces(run.text())
text = escape(text)

bold   = run.fontStyle() != null && run.fontStyle().contains("Bold")
italic = run.fontStyle() != null && run.fontStyle().contains("Italic")

if bold && italic : text = "**_" + text + "_**"
else if bold      : text = "**"  + text + "**"
else if italic    : text = "_"   + text + "_"

if run.underline()      : text = "<u>"   + text + "</u>"
if run.strikeThrough()  : text = "~~"    + text + "~~"
if run.superscript()    : text = "<sup>" + text + "</sup>"
if run.subscript()      : text = "<sub>" + text + "</sub>"

return text
```

#### 7.2 normalizeSpaces(String text)

escape() 호출 전에 적용. 아래 코드포인트를 U+0020으로 치환:

```java
text = text.replace('　', ' ');  // Ideographic Space — 전각 공백 (CJK)
text = text.replace(' ', ' ');  // Em Space          — 전각 (1각)
text = text.replace(' ', ' ');  // En Space          — 반각 (1/2각)
text = text.replace(' ', ' ');  // Four-Per-Em Space — 1/4각
text = text.replace(' ', ' ');  // Six-Per-Em Space  — 1/6각
text = text.replace('\t',     ' ');  // 탭 (tabStop 보존된 경우)
return text;
```

#### 7.3 escape(String text)

순서 중요 (`\` 먼저):

```java
text = text.replace("\\", "\\\\");
text = text.replace("`",  "\\`");
text = text.replace("*",  "\\*");
text = text.replace("_",  "\\_");
text = text.replace("{",  "\\{");
text = text.replace("}",  "\\}");
text = text.replace("[",  "\\[");
text = text.replace("]",  "\\]");
text = text.replace("(",  "\\(");
text = text.replace(")",  "\\)");
text = text.replace("|",  "\\|");
text = text.replace("~",  "\\~");
return text;
```

#### 7.4 escapeLineStart(String content)

content의 **첫 줄 맨 앞**만 검사. prefix(`#`, `-`)가 이미 붙은 단락에는 호출 안 함.

```java
// ordered list 방지: /^\d+\. / 패턴
if (content.matches("^\\d+\\.\\s.*")) content = content.replaceFirst("\\.", "\\\\.");
// heading 방지
if (content.startsWith("#")) content = "\\" + content;
// unordered list 방지
if (content.startsWith("+") || content.startsWith("-")) content = "\\" + content;
return content;
```

#### 7.5 renderBreak(ASTBreak br)

| BreakType | 반환값 |
|-----------|--------|
| `LINE` | `"  \n"` (trailing 2-space + newline) |
| `COLUMN` | `""` |
| `PAGE` | `""` |

단락 속성 `columnBreakAfter()`, `pageBreakBefore()` → 무시.

#### 7.6 renderInlineObject(ASTInlineObject obj)

| kind | 처리 |
|------|------|
| `IMAGE` | 이미지 저장 → `![](out_images/inline_NNN.ext)` |
| `RENDERED_GROUP` | 이미지 저장 → `![](out_images/inline_NNN.ext)` |
| `INLINE_BADGE_GROUP` | 이미지 저장 → `![](out_images/inline_NNN.ext)` |
| `INLINE_TEXT_FRAME` | 내부 paragraphs 평탄화 (아래) |
| `SPACER_RECT` | `""` |

**이미지 소스 우선순위** (IMAGE / RENDERED_GROUP / INLINE_BADGE_GROUP):
1. `obj.imageData()` → Phase 7 렌더 PNG. 바이트를 파일로 기록.
2. null + `obj.imagePath()` → 원본 파일 복사
3. 둘 다 없음 → `""` (출력 없음)

파일명: `inline_<3자리 시퀀스>.<ext>`
- `ext` = `obj.imageFormat()` 소문자 (null이면 `png`)

**INLINE_TEXT_FRAME 평탄화** (1단계만, 재귀 없음):

```java
if (obj.paragraphs() == null) return "";
StringBuilder buf = new StringBuilder();
for (ASTParagraph para : obj.paragraphs()) {
    for (ASTInlineItem item : para.items()) {
        if (item.itemType() == TEXT_RUN)
            buf.append(renderRun((ASTTextRun) item));
        else if (item.itemType() == BREAK
                 && ((ASTBreak)item).breakType() == LINE)
            buf.append(" ");  // 인라인이므로 줄바꿈 대신 공백
        // INLINE_OBJECT, EQUATION — 무시
    }
}
return buf.toString();
```

`overlayFrames` → 무시 (레이아웃 개념 없음).

#### 7.7 renderEquation(ASTEquation eq)

`ASTEquation.hwpScript()`는 HWP 수식 스크립트 문자열.

```java
String script = eq.hwpScript();
return (script != null && !script.isEmpty())
    ? "`[수식: " + script + "]`"
    : "`[수식]`";
```

---

## 구현 설계

### 신규 파일

**`converter/ASTToMarkdownConverter.java`** (약 500 LOC)

```java
public class ASTToMarkdownConverter {
    public void convert(ASTDocument doc, File outputFile)

    // Pre-pass
    private Map<String, StoryGroup>  buildStoryGroups(ASTDocument doc)
    private Map<String, String>      buildStyleMap(ASTDocument doc)

    // 섹션
    private void             writeSection(ASTSection section, Map<String, StoryGroup> all)
    private List<SortableBlock> collectAndSort(ASTSection section, Map<String, StoryGroup> all)

    // 블럭
    private void writeStoryGroup(StoryGroup group)
    private void writeParagraph(ASTParagraph para)
    private void writeFigure(ASTFigure fig)
    private void writeTable(ASTTable table)
    private void writePageNumberTf(ASTTextFrameBlock tf)

    // 인라인
    private String renderRun(ASTTextRun run)
    private String renderBreak(ASTBreak br)
    private String renderInlineObject(ASTInlineObject obj)
    private String renderEquation(ASTEquation eq)

    // 텍스트 처리
    private String normalizeSpaces(String text)
    private String escape(String text)
    private String escapeLineStart(String content)
    private int    detectHeadingLevel(String styleName)

    // 이미지
    private String saveImage(byte[] data, String ext, String prefix)
    private String copyImage(String srcPath, String prefix)

    // 상태
    private StringBuilder      out
    private Map<String,String> styleMap       // styleId → styleName
    private File               imagesDir      // out_images/
    private int                figSeq
    private int                inlineSeq
}
```

**내부 클래스**:

```java
static class StoryGroup {
    String key;
    List<FrameEntry> frames;  // 정렬 완료
    int ownerPage;
    List<Integer> pages;
    long representativeY, centerX, width;
    String fillColor;        // null 가능
}
static class FrameEntry {
    ASTTextFrameBlock tf;
    int pageNumber;
}
static class SortableBlock {
    enum Column { FULL, LEFT, RIGHT }
    Object block;            // StoryGroup | ASTFigure | ASTTable | ASTTextFrameBlock
    long representativeY;
    long centerX, width;
    Column column;
}
```

### 수정 파일

**`ConverterCLI.java`** — `runConvert()` 출력 경로 확장자 분기:

```java
if (outputPath.endsWith(".md")) {
    new ASTToMarkdownConverter().convert(ast, new File(outputPath));
} else {
    // 기존 HWPX 경로
}
```

이미지 디렉토리: `out.md` → `out_images/` (`.md` 제거 + `_images`)

### Desktop UI 변경

**`ConversionPanel.tsx`** — `save()` 대화상자:
```ts
filters: [
  { name: "HWP 문서", extensions: ["hwpx"] },
  { name: "Markdown",  extensions: ["md"]   },
]
```
출력 파일이 `.md`이면 변환 완료 후 "폴더 열기" 버튼 추가 (`out_images/`).

**`InddBatchModal.tsx`** — 출력 형식 라디오 버튼 (`HWPX` / `Markdown`) 추가.
배치 출력: `markdown` 선택 시 `<name>.hwpx` → `<name>.md`.

---

## 수정 파일 목록

1. `converter/ASTToMarkdownConverter.java` — **신규**
2. `ConverterCLI.java` — `.md` 확장자 분기 (5줄)
3. `desktop/src/components/ConversionPanel.tsx` — 저장 필터 + 결과 표시
4. `desktop/src/components/InddBatchModal.tsx` — 출력 형식 선택

---

## 검증

```bash
mvn clean package -q -DskipTests

java -jar target/idml-to-something-1.0.9-cli.jar \
  --convert output.idml out.md \
  --resolved resolved.json \
  --links-directory /path/to/Links
```

- [ ] 빌드 성공
- [ ] `out.md` + `out_images/` 생성
- [ ] YAML front matter `pages:` 정확
- [ ] `section.pageNumber()` 값이 `<!-- page N -->` 에 올바르게 반영
- [ ] storyId 동일 TF가 여러 페이지 걸친 경우 → 첫 페이지에만 한 번, `pages=1,2` 표시
- [ ] `page_num_*` TF → `<!-- page-number: N -->` 주석
- [ ] 모든 단락에 `<!-- style: ... -->` 주석
- [ ] inlineTable 단락 → `writeTable()` 위임 (단락 텍스트 스킵)
- [ ] 테이블 셀 안에 `<!-- style: -->` 주석 없음
- [ ] ASTFigure 주석에 `zOrder=` 포함
- [ ] 빈 단락 스킵
- [ ] `isBackgroundOnly()` TF 스킵
- [ ] `doc.backgrounds()` 미순회
- [ ] `normalizeSpaces`: 전각/반각/1/4각/1/6각 → 일반 공백
- [ ] `escape()`: `|` → `\|`, `*` → `\*`, `_` → `\_`
- [ ] GFM 테이블 렌더링 정확 (GitHub에서 표로 보이는지)
- [ ] 인라인/블럭 이미지 모두 `imageData()` 우선 사용
- [ ] `ASTEquation.hwpScript()` → `` `[수식: ...]` `` 출력

---

## 상태

- [x] 구현 (2026-06-04)
- [ ] 테스트
