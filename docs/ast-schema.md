# IDML 중간 표현(AST) 스키마

IDML → AST → HWPX 변환 파이프라인의 중간 포맷 스키마 문서입니다.

## 개요

```
┌─────────┐     ┌──────────────────┐     ┌──────────┐
│  IDML   │ ──> │  AST             │ ──> │  HWPX    │
│ (Adobe) │     │  (Java / JSON)   │     │ (한글)   │
└─────────┘     └──────────────────┘     └──────────┘
```

Java 클래스: `kr.dogfoot.hwpxlib.tool.idmlconverter.ast.*`
JSON 직렬화: `ASTSerializer.toJson(ASTDocument)`

## 단위 규칙

- **HWPUNIT**: 1/7200 inch (HWP/HWPX 내부 단위)
  - 1pt = 100 HWPUNIT
  - 1mm ≈ 283.46 HWPUNIT
  - 1inch = 7200 HWPUNIT

---

## 문서 트리 구조

```
ASTDocument
├── sourceFile: String
├── sourceFormat: String ("IDML")
├── colors: Map<String, String>         ← 색상 참조 → HEX
├── fonts: ASTFontDef[]
├── paragraphStyles: ASTStyleDef[]
├── characterStyles: ASTStyleDef[]
├── backgrounds: ASTPageBackground[]    ← 페이지 배경 이미지
└── sections: ASTSection[]
    ├── pageNumber: int
    ├── layout: ASTPageLayout
    └── blocks: ASTBlock[]              ← 다형성 (3종)
        │
        ├── ASTTextFrameBlock           ← 텍스트 프레임
        │   └── paragraphs: ASTParagraph[]
        │       ├── tabStops: ASTTabStop[]
        │       └── items: ASTInlineItem[]     ← 다형성 (4종)
        │           ├── ASTTextRun             ← 텍스트
        │           ├── ASTEquation            ← 수식
        │           ├── ASTBreak               ← 줄바꿈/페이지넘김
        │           └── ASTInlineObject        ← 인라인 이미지/프레임
        │               ├── paragraphs[]       ← INLINE_TEXT_FRAME
        │               ├── inlineTables[]     ← 인라인 표
        │               └── overlayFrames[]    ← 그룹 내 오버레이
        │
        ├── ASTTable                    ← 표
        │   └── rows: ASTTableRow[]
        │       └── cells: ASTTableCell[]
        │           └── paragraphs: ASTParagraph[]
        │
        └── ASTFigure                   ← 블록 이미지/도형
```

---

## 타입 정의

### ASTDocument (루트)

| JSON 키 | 타입 | 필수 | 설명 |
|---------|------|:----:|------|
| `sourceFile` | string | | 원본 파일 경로 |
| `sourceFormat` | string | ✓ | 원본 포맷 (기본 `"IDML"`) |
| `sections` | ASTSection[] | ✓ | 페이지 섹션 목록 |
| `backgrounds` | ASTPageBackground[] | | 렌더링된 페이지 배경 |
| `fonts` | ASTFontDef[] | | 폰트 정의 목록 |
| `paragraphStyles` | ASTStyleDef[] | | 단락 스타일 목록 |
| `characterStyles` | ASTStyleDef[] | | 문자 스타일 목록 |
| `colors` | object | | 색상 맵 (`{ "참조키": "#RRGGBB" }`) |

---

### ASTSection

페이지 단위 섹션. HWPX의 `SecPr`에 대응합니다.

| JSON 키 | 타입 | 필수 | 설명 |
|---------|------|:----:|------|
| `pageNumber` | int | ✓ | 페이지 번호 |
| `layout` | ASTPageLayout | ✓ | 페이지 레이아웃 |
| `blocks` | ASTBlock[] | | 블록 요소 목록 |

---

### ASTPageLayout

| JSON 키 | 타입 | 필수 | 설명 |
|---------|------|:----:|------|
| `pageWidth` | long | ✓ | 페이지 너비 (HWPUNIT) |
| `pageHeight` | long | ✓ | 페이지 높이 (HWPUNIT) |
| `marginTop` | long | | 상단 여백 |
| `marginBottom` | long | | 하단 여백 |
| `marginLeft` | long | | 좌측 여백 |
| `marginRight` | long | | 우측 여백 |
| `columnCount` | int | | 단 수 |
| `columnGutter` | long | | 단 간격 |

**예시** (A4):
```json
{
  "pageWidth": 1512000,
  "pageHeight": 2139120,
  "marginTop": 252000,
  "marginBottom": 252000,
  "marginLeft": 216000,
  "marginRight": 216000,
  "columnCount": 1,
  "columnGutter": 0
}
```

---

## 블록 레벨 (ASTBlock)

`blockType` 필드로 구분되는 다형성 타입입니다.

| blockType 값 | Java 클래스 | 설명 |
|-------------|------------|------|
| `TEXT_FRAME_BLOCK` | ASTTextFrameBlock | 텍스트 프레임 |
| `TABLE` | ASTTable | 표 |
| `FIGURE` | ASTFigure | 블록 이미지/도형 |

---

### ASTTextFrameBlock

| JSON 키 | 타입 | 기본값 | 설명 |
|---------|------|-------|------|
| `blockType` | string | | `"TEXT_FRAME_BLOCK"` |
| `sourceId` | string | | IDML 원본 ID |
| **위치/크기** |
| `x` | long | 0 | X 좌표 (HWPUNIT) |
| `y` | long | 0 | Y 좌표 |
| `width` | long | 0 | 너비 |
| `height` | long | 0 | 높이 |
| `zOrder` | int | 0 | 렌더링 순서 |
| **단 레이아웃** |
| `columnCount` | int | 0 | 단 수 |
| `columnGutter` | long | 0 | 단 간격 |
| `verticalText` | boolean | false | 세로쓰기 (true일 때만 출력) |
| `verticalJustification` | string | | 수직 정렬 |
| **내부 여백** |
| `insetTop` | long | 0 | 상단 패딩 |
| `insetLeft` | long | 0 | 좌측 패딩 |
| `insetBottom` | long | 0 | 하단 패딩 |
| `insetRight` | long | 0 | 우측 패딩 |
| **스타일** |
| `fillColor` | string | | 배경색 (`#RRGGBB`) |
| `strokeColor` | string | | 테두리 색 |
| `strokeWeight` | double | 0 | 테두리 두께 (pt). 0이면 생략 |
| `cornerRadius` | double | 0 | 모서리 반경. 0이면 생략 |
| **내용** |
| `paragraphs` | ASTParagraph[] | | 단락 목록 |

**예시**:
```json
{
  "blockType": "TEXT_FRAME_BLOCK",
  "sourceId": "uf5",
  "x": 216000,
  "y": 252000,
  "width": 1080000,
  "height": 360000,
  "zOrder": 1,
  "columnCount": 2,
  "columnGutter": 14400,
  "insetTop": 0,
  "insetLeft": 0,
  "insetBottom": 0,
  "insetRight": 0,
  "paragraphs": [ ... ]
}
```

---

### ASTTable

| JSON 키 | 타입 | 기본값 | 설명 |
|---------|------|-------|------|
| `blockType` | string | | `"TABLE"` |
| `sourceId` | string | | IDML 원본 ID |
| **위치/크기** |
| `x` | long | 0 | X 좌표 |
| `y` | long | 0 | Y 좌표 |
| `width` | long | 0 | 전체 너비 |
| `height` | long | 0 | 전체 높이 |
| `zOrder` | int | 0 | 렌더링 순서 |
| **구조** |
| `rowCount` | int | 0 | 행 수 |
| `colCount` | int | 0 | 열 수 |
| `columnWidths` | long[] | | 열별 너비 (HWPUNIT) |
| `appliedTableStyle` | string | | 테이블 스타일 참조 |
| `borderColor` | string | | 기본 테두리 색 |
| `borderWidth` | long | 0 | 기본 테두리 두께. 0이면 생략 |
| **내용** |
| `rows` | ASTTableRow[] | | 행 목록 |

---

### ASTTableRow

| JSON 키 | 타입 | 기본값 | 설명 |
|---------|------|-------|------|
| `rowIndex` | int | 0 | 행 인덱스 (0부터) |
| `rowHeight` | long | 0 | 행 높이 |
| `autoGrow` | boolean | false | 자동 높이 조정. true일 때만 출력 |
| `cells` | ASTTableCell[] | | 셀 목록 |

---

### ASTTableCell

| JSON 키 | 타입 | 기본값 | 설명 |
|---------|------|-------|------|
| **위치** |
| `rowIndex` | int | 0 | 행 인덱스 |
| `columnIndex` | int | 0 | 열 인덱스 |
| `rowSpan` | int | 1 | 행 병합. 1이면 생략 |
| `columnSpan` | int | 1 | 열 병합. 1이면 생략 |
| **크기** |
| `width` | long | 0 | 셀 너비 |
| `height` | long | 0 | 셀 높이 |
| **스타일** |
| `fillColor` | string | | 배경색 |
| `verticalAlign` | string | `"TopAlign"` | 수직 정렬 |
| **여백** (0이면 생략) |
| `marginTop` | long | 0 | 상단 여백 |
| `marginBottom` | long | 0 | 하단 여백 |
| `marginLeft` | long | 0 | 좌측 여백 |
| `marginRight` | long | 0 | 우측 여백 |
| **테두리** (null이면 생략) |
| `topBorder` | CellBorder | | 상단 테두리 |
| `bottomBorder` | CellBorder | | 하단 테두리 |
| `leftBorder` | CellBorder | | 좌측 테두리 |
| `rightBorder` | CellBorder | | 우측 테두리 |
| **대각선** |
| `topLeftDiagonalLine` | boolean | false | 좌상→우하 대각선. true일 때만 출력 |
| `topRightDiagonalLine` | boolean | false | 우상→좌하 대각선. true일 때만 출력 |
| `diagonalBorder` | CellBorder | | 대각선 스타일 |
| **내용** |
| `paragraphs` | ASTParagraph[] | | 셀 내용 |

#### CellBorder

| JSON 키 | 타입 | 기본값 | 설명 |
|---------|------|-------|------|
| `color` | string | | 색상 (`#RRGGBB`) |
| `weight` | double | 0 | 두께 (pt). 0이면 생략 |
| `strokeType` | string | | 선 스타일 (`"Solid"`, `"Dashed"`, `"Dotted"`) |
| `tint` | double | 100 | 투명도 (0-100). 100이면 생략 |

---

### ASTFigure

블록 레벨 이미지/도형입니다.

| JSON 키 | 타입 | 기본값 | 설명 |
|---------|------|-------|------|
| `blockType` | string | | `"FIGURE"` |
| `sourceId` | string | | IDML 원본 ID |
| `kind` | enum | | `"IMAGE"`, `"RENDERED_SHAPE"`, `"RENDERED_GROUP"` |
| **위치/크기** |
| `x` | long | 0 | X 좌표 |
| `y` | long | 0 | Y 좌표 |
| `width` | long | 0 | 너비 |
| `height` | long | 0 | 높이 |
| `zOrder` | int | 0 | 렌더링 순서 |
| `rotationAngle` | double | 0 | 회전 각도 (도). 0이면 생략 |
| **이미지 데이터** |
| `imageFormat` | string | | 포맷 (`"PNG"`, `"JPG"` 등) |
| `imagePath` | string | | 파일 경로 |
| `pixelWidth` | int | 0 | 픽셀 너비. 0이면 생략 |
| `pixelHeight` | int | 0 | 픽셀 높이. 0이면 생략 |
| `hasImageData` | boolean | | 바이너리 데이터 존재 여부 |

> **참고**: `imageData` (byte[])는 JSON 직렬화에서 제외됩니다. `hasImageData`로 존재 여부만 표시합니다.

---

## 인라인 레벨 (ASTInlineItem)

`itemType` 필드로 구분되는 다형성 타입입니다.

| itemType 값 | Java 클래스 | 설명 |
|------------|------------|------|
| `TEXT_RUN` | ASTTextRun | 동일 서식 텍스트 |
| `EQUATION` | ASTEquation | 수식 |
| `BREAK` | ASTBreak | 줄바꿈/페이지넘김 |
| `INLINE_OBJECT` | ASTInlineObject | 인라인 이미지/프레임 |

---

### ASTTextRun

| JSON 키 | 타입 | 설명 |
|---------|------|------|
| `itemType` | string | `"TEXT_RUN"` |
| `text` | string | 텍스트 내용 |
| `characterStyleRef` | string | 문자 스타일 참조 |
| `fontFamily` | string | 폰트 (로컬 오버라이드) |
| `fontStyle` | string | 폰트 스타일 (로컬 오버라이드) |
| `fontSizeHwpunits` | int | 폰트 크기 (HWPUNIT). null이면 생략 |
| `textColor` | string | 글자색 (로컬 오버라이드) |
| `letterSpacing` | short | 자간. null이면 생략 |
| `subscript` | boolean | 아래첨자. true일 때만 출력 |
| `superscript` | boolean | 위첨자. true일 때만 출력 |

**예시**:
```json
{
  "itemType": "TEXT_RUN",
  "text": "안녕하세요",
  "characterStyleRef": "CharacterStyle/강조",
  "fontFamily": "Noto Sans CJK KR",
  "fontSizeHwpunits": 1000,
  "textColor": "#333333"
}
```

---

### ASTEquation

BT수식M 또는 NP 커스텀 폰트에서 추출된 인라인 수식입니다.

| JSON 키 | 타입 | 필수 | 설명 |
|---------|------|:----:|------|
| `itemType` | string | ✓ | `"EQUATION"` |
| `hwpScript` | string | ✓ | HWP 수식 스크립트 |
| `sourceType` | string | | 원본 타입 (`"BT_FONT"`, `"NP_FONT"`) |

**예시**:
```json
{
  "itemType": "EQUATION",
  "hwpScript": "{a} over {b} + sqrt {c}",
  "sourceType": "NP_FONT"
}
```

**HWP 수식 스크립트 문법 참고**:
| 수식 구조 | 스크립트 | 설명 |
|----------|---------|------|
| 분수 | `{분자} over {분모}` | 분수 |
| 아래첨자 | `x_{i}` | 아래첨자 |
| 위첨자 | `x^{2}` | 위첨자 |
| 근호 | `sqrt{x}` | 제곱근 |
| 적분 | `int` | ∫ |
| 시그마 | `sum` | Σ |
| 극한 | `lim` | lim |
| 오버라인 | `bar x` | x̄ |
| 중괄호 | `left lbrace ... right rbrace` | { ... } |
| 무한대 | `inf` | ∞ |
| 파이 | `pi` | π |
| 말줄임 | `cdots` | ⋯ |
| 이하 | `LEQ` | ≤ |
| 이상 | `GEQ` | ≥ |
| 그러므로 | `therefore` | ∴ |

---

### ASTBreak

| JSON 키 | 타입 | 설명 |
|---------|------|------|
| `itemType` | string | `"BREAK"` |
| `breakType` | enum | `"LINE"`, `"COLUMN"`, `"PAGE"` |

---

### ASTInlineObject

인라인으로 앵커된 이미지, 텍스트 프레임, 그룹 등입니다.

| JSON 키 | 타입 | 기본값 | 설명 |
|---------|------|-------|------|
| `itemType` | string | | `"INLINE_OBJECT"` |
| `kind` | enum | | `"IMAGE"`, `"RENDERED_GROUP"`, `"INLINE_TEXT_FRAME"`, `"SPACER_RECT"` |
| `sourceId` | string | | IDML 원본 ID |
| **크기** |
| `width` | long | 0 | 너비 (HWPUNIT) |
| `height` | long | 0 | 높이 |
| **이미지 데이터** |
| `imageFormat` | string | | 포맷 |
| `imagePath` | string | | 파일 경로 |
| `pixelWidth` | int | 0 | 픽셀 너비. 0이면 생략 |
| `pixelHeight` | int | 0 | 픽셀 높이. 0이면 생략 |
| `hasImageData` | boolean | | 바이너리 데이터 존재 여부 |
| **앵커/감싸기** |
| `anchoredPosition` | string | | `"InlinePosition"`, `"AboveLine"`, `"Anchored"` |
| `textWrapMode` | string | | `"None"`, `"BoundingBoxTextWrap"`, `"JumpObjectTextWrap"` |
| `textWrapSide` | string | | `"BothSides"`, `"LeftSide"`, `"RightSide"`, `"LargestArea"` |
| **프레임 스타일** |
| `fillColor` | string | | 배경색 |
| `fillTint` | double | 100 | 투명도 (0-100). 100이면 생략 |
| **내용** (kind에 따라) |
| `paragraphs` | ASTParagraph[] | | INLINE_TEXT_FRAME일 때 단락 내용 |

> **참고**: Java 클래스에는 `inlineTables`, `overlayFrames`, `containerWidth/Height`, `imageOffsetX/Y` 등 추가 필드가 있습니다. JSON 직렬화에서는 일부만 출력됩니다.

---

## 단락 (ASTParagraph)

| JSON 키 | 타입 | 기본값 | 설명 |
|---------|------|-------|------|
| **스타일** |
| `paragraphStyleRef` | string | | 단락 스타일 참조 |
| `alignment` | string | | 정렬 (`"left"`, `"center"`, `"right"`, `"justify"`) |
| **여백/간격** (null이면 생략) |
| `firstLineIndent` | long | | 첫줄 들여쓰기 (HWPUNIT) |
| `leftMargin` | long | | 좌측 여백 |
| `rightMargin` | long | | 우측 여백 |
| `spaceBefore` | long | | 단락 앞 간격 |
| `spaceAfter` | long | | 단락 뒤 간격 |
| `lineSpacing` | int | | 줄간격 값 |
| `letterSpacing` | short | | 자간 |
| **음영/배경** (shadingOn=false이면 전체 생략) |
| `shadingOn` | boolean | false | 배경 사용 여부 |
| `shadingColor` | string | | 배경색 |
| `shadingTint` | double | | 투명도 |
| `shadingLeftOffset` | long | | 좌측 패딩 |
| `shadingRightOffset` | long | | 우측 패딩 |
| `shadingTopOffset` | long | | 상단 패딩 |
| `shadingBottomOffset` | long | | 하단 패딩 |
| **탭 정지** |
| `tabStops` | ASTTabStop[] | | 탭 정지 목록 |
| **내용** |
| `items` | ASTInlineItem[] | | 인라인 요소 (플랫 시퀀스) |

---

### ASTTabStop

| JSON 키 | 타입 | 설명 |
|---------|------|------|
| `position` | long | 위치 (HWPUNIT) |
| `alignment` | string | `"left"`, `"center"`, `"right"`, `"decimal"` |
| `leader` | string | 리더 문자 (`.`, `-`, `_`, 또는 null) |

---

## 스타일/폰트 정의

### ASTStyleDef

단락 스타일과 문자 스타일 공용입니다.

| JSON 키 | 타입 | 설명 |
|---------|------|------|
| `styleId` | string | 스타일 고유 ID |
| `styleName` | string | 스타일 이름 |
| `basedOnStyleRef` | string | 부모 스타일 참조 |
| **단락 속성** (null이면 생략) |
| `alignment` | string | 정렬 |
| `firstLineIndent` | long | 첫줄 들여쓰기 |
| `leftMargin` | long | 좌측 여백 |
| `rightMargin` | long | 우측 여백 |
| `spaceBefore` | long | 단락 앞 간격 |
| `spaceAfter` | long | 단락 뒤 간격 |
| `lineSpacing` | int | 줄간격 값 |
| `lineSpacingType` | string | `"percent"` 또는 `"fixed"` |
| `autoLeading` | double | 자동 줄간격 비율 (%). null이면 생략 |
| `tabStops` | ASTTabStop[] | 스타일 내 탭 정지점 |
| **문자 속성** (null이면 생략) |
| `fontFamily` | string | 폰트 패밀리 |
| `fontStyle` | string | 폰트 스타일 |
| `fontSizeHwpunits` | int | 폰트 크기 (HWPUNIT) |
| `textColor` | string | 글자색 |
| `letterSpacing` | short | 자간 |
| `bold` | boolean | 볼드. true일 때만 출력 |
| `italic` | boolean | 이탤릭. true일 때만 출력 |
| `horizontalScale` | short | 장평 (%). null이면 생략. HWPX CharPr ratio에 반영 |
| `wordSpacing` | double | 어간 desired (%). HWPX 직접 대응 없음 |

---

### ASTFontDef

| JSON 키 | 타입 | 설명 |
|---------|------|------|
| `fontId` | string | 폰트 고유 ID |
| `fontFamily` | string | 폰트 패밀리명 |
| `fontType` | string | 폰트 타입 |

---

### ASTPageBackground

렌더링된 페이지 배경 이미지입니다.

| JSON 키 | 타입 | 설명 |
|---------|------|------|
| `pageNumber` | int | 페이지 번호 |
| `pageWidth` | long | 페이지 너비 |
| `pageHeight` | long | 페이지 높이 |
| `pixelWidth` | int | 이미지 픽셀 너비 |
| `pixelHeight` | int | 이미지 픽셀 높이 |
| `hasPngData` | boolean | PNG 데이터 존재 여부 |

---

## JSON 직렬화 규칙

`ASTSerializer`의 직렬화 규칙:

| 규칙 | 설명 |
|------|------|
| **null 필드** | 출력 생략 |
| **기본값 필드** | boolean=false, double=0.0, span=1 등은 생략 |
| **byte[] 필드** | `imageData`, `pngData` → 제외, `hasImageData`/`hasPngData`로 대체 |
| **JSON 키** | camelCase (Java 필드명 그대로) |
| **다형성 구분** | `blockType` (블록), `itemType` (인라인) |

---

## 전체 예시

```json
{
  "sourceFile": "sample.idml",
  "sourceFormat": "IDML",
  "sections": [
    {
      "pageNumber": 1,
      "layout": {
        "pageWidth": 1512000,
        "pageHeight": 2139120,
        "marginTop": 252000,
        "marginBottom": 252000,
        "marginLeft": 216000,
        "marginRight": 216000,
        "columnCount": 1,
        "columnGutter": 0
      },
      "blocks": [
        {
          "blockType": "TEXT_FRAME_BLOCK",
          "sourceId": "uf5",
          "x": 216000,
          "y": 252000,
          "width": 1080000,
          "height": 360000,
          "zOrder": 1,
          "columnCount": 1,
          "columnGutter": 0,
          "insetTop": 0,
          "insetLeft": 0,
          "insetBottom": 0,
          "insetRight": 0,
          "paragraphs": [
            {
              "paragraphStyleRef": "ParagraphStyle/본문",
              "alignment": "justify",
              "lineSpacing": 160,
              "items": [
                {
                  "itemType": "TEXT_RUN",
                  "text": "f(x)의 값이 ",
                  "fontFamily": "Noto Sans CJK KR"
                },
                {
                  "itemType": "EQUATION",
                  "hwpScript": "{a} over {b} + sqrt {c}",
                  "sourceType": "NP_FONT"
                },
                {
                  "itemType": "TEXT_RUN",
                  "text": "일 때"
                }
              ]
            }
          ]
        },
        {
          "blockType": "TABLE",
          "sourceId": "t1",
          "x": 216000,
          "y": 720000,
          "width": 1080000,
          "height": 360000,
          "zOrder": 2,
          "rowCount": 2,
          "colCount": 3,
          "columnWidths": [360000, 360000, 360000],
          "rows": [
            {
              "rowIndex": 0,
              "rowHeight": 180000,
              "cells": [
                {
                  "rowIndex": 0,
                  "columnIndex": 0,
                  "width": 360000,
                  "height": 180000,
                  "verticalAlign": "TopAlign",
                  "paragraphs": [
                    {
                      "items": [
                        { "itemType": "TEXT_RUN", "text": "x" }
                      ]
                    }
                  ]
                }
              ]
            }
          ]
        },
        {
          "blockType": "FIGURE",
          "sourceId": "img1",
          "kind": "IMAGE",
          "x": 216000,
          "y": 1200000,
          "width": 720000,
          "height": 540000,
          "zOrder": 3,
          "imageFormat": "PNG",
          "imagePath": "Links/graph.png",
          "pixelWidth": 1920,
          "pixelHeight": 1440,
          "hasImageData": true
        }
      ]
    }
  ],
  "fonts": [
    {
      "fontId": "Font/NotoSansCJKkr-Regular",
      "fontFamily": "Noto Sans CJK KR",
      "fontType": "OTF"
    }
  ],
  "paragraphStyles": [
    {
      "styleId": "ParagraphStyle/본문",
      "styleName": "본문",
      "alignment": "justify",
      "fontFamily": "Noto Sans CJK KR",
      "fontSizeHwpunits": 1000,
      "lineSpacing": 160,
      "textColor": "#000000"
    }
  ],
  "characterStyles": [],
  "colors": {
    "Color/Black": "#000000",
    "Color/Red": "#FF0000"
  }
}
```
