# IDML 중간 표현(Intermediate) 스키마

IDML → Intermediate → HWPX 변환 파이프라인의 중간 포맷 스키마 문서입니다.

## 개요

```
┌─────────┐     ┌──────────────────┐     ┌──────────┐
│  IDML   │ ──> │  Intermediate    │ ──> │  HWPX    │
│ (Adobe) │     │  (JSON)          │     │ (한글)   │
└─────────┘     └──────────────────┘     └──────────┘
```

## 단위 규칙

- **HWPUNIT**: 1/7200 inch (HWP/HWPX 내부 단위)
  - 1pt = 100 HWPUNIT
  - 1mm ≈ 283.46 HWPUNIT
  - 1inch = 7200 HWPUNIT

## 문서 구조

```json
{
  "version": "1.0",
  "sourceFormat": "IDML",
  "sourceFile": "sample.idml",
  "layout": { ... },
  "fonts": [ ... ],
  "paragraphStyles": [ ... ],
  "characterStyles": [ ... ],
  "pages": [ ... ]
}
```

---

## 타입 정의

### IntermediateDocument (루트)

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| `version` | string | ✓ | 스키마 버전 (기본값: "1.0") |
| `sourceFormat` | string | ✓ | 원본 포맷 ("IDML" \| "INDD") |
| `sourceFile` | string | | 원본 파일 경로 |
| `layout` | DocumentLayout | ✓ | 페이지 레이아웃 |
| `fonts` | FontDef[] | | 폰트 정의 목록 |
| `paragraphStyles` | StyleDef[] | | 문단 스타일 목록 |
| `characterStyles` | StyleDef[] | | 문자 스타일 목록 |
| `pages` | Page[] | ✓ | 페이지 목록 |

---

### DocumentLayout

페이지 기본 레이아웃 정보입니다.

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| `defaultPageWidth` | long | ✓ | 기본 페이지 너비 (HWPUNIT) |
| `defaultPageHeight` | long | ✓ | 기본 페이지 높이 (HWPUNIT) |
| `marginTop` | long | | 상단 여백 |
| `marginBottom` | long | | 하단 여백 |
| `marginLeft` | long | | 좌측 여백 |
| `marginRight` | long | | 우측 여백 |

**예시** (A4 용지):
```json
{
  "defaultPageWidth": 1512000,
  "defaultPageHeight": 2139120,
  "marginTop": 252000,
  "marginBottom": 252000,
  "marginLeft": 216000,
  "marginRight": 216000
}
```

---

### FontDef

폰트 정의입니다.

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| `id` | string | ✓ | 폰트 고유 ID |
| `familyName` | string | ✓ | 폰트 패밀리명 |
| `styleName` | string | | 스타일명 (Regular, Bold 등) |
| `fontType` | string | | 타입 ("OTF" \| "TTF" \| "CFF" \| "Type1") |
| `hwpxMappedName` | string | | HWPX 매핑 폰트명 |

**예시**:
```json
{
  "id": "Font/NotoSansCJKkr-Regular",
  "familyName": "Noto Sans CJK KR",
  "styleName": "Regular",
  "fontType": "OTF",
  "hwpxMappedName": "본고딕"
}
```

---

### StyleDef

스타일 정의 (문단/문자 공용)입니다.

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| `id` | string | ✓ | 스타일 고유 ID |
| `name` | string | ✓ | 스타일 이름 |
| `type` | string | ✓ | 타입 ("paragraph" \| "character") |
| `basedOn` | string | | 부모 스타일 ID |
| `fontFamily` | string | | 폰트 패밀리 |
| `fontSizeHwpunits` | int | | 폰트 크기 (HWPUNIT, 1pt = 100) |
| `textColor` | string | | 색상 (HEX, 예: "#000000") |
| `bold` | boolean | | 굵게 |
| `italic` | boolean | | 기울임 |
| `alignment` | string | | 정렬 ("left" \| "center" \| "right" \| "justify") |
| `firstLineIndent` | long | | 첫 줄 들여쓰기 |
| `leftMargin` | long | | 좌측 여백 |
| `rightMargin` | long | | 우측 여백 |
| `spaceBefore` | long | | 문단 앞 간격 |
| `spaceAfter` | long | | 문단 뒤 간격 |
| `lineSpacingPercent` | int | | 행간 비율 (%) |
| `lineSpacingType` | string | | 행간 타입 ("percent" \| "fixed") |
| `letterSpacing` | short | | 자간 (-50 ~ 50) |

**예시**:
```json
{
  "id": "ParagraphStyle/본문",
  "name": "본문",
  "type": "paragraph",
  "fontFamily": "Noto Sans CJK KR",
  "fontSizeHwpunits": 1000,
  "textColor": "#000000",
  "alignment": "justify",
  "lineSpacingPercent": 160,
  "lineSpacingType": "percent"
}
```

---

### Page

페이지입니다.

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| `pageNumber` | int | ✓ | 페이지 번호 (1부터 시작) |
| `pageWidth` | long | ✓ | 페이지 너비 (HWPUNIT) |
| `pageHeight` | long | ✓ | 페이지 높이 (HWPUNIT) |
| `frames` | Frame[] | | 프레임 목록 |

---

### Frame

프레임 (텍스트 또는 이미지)입니다.

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| `frameId` | string | ✓ | 프레임 고유 ID |
| `frameType` | string | ✓ | 타입 ("text" \| "image") |
| `x` | long | ✓ | X 좌표 (페이지 좌상단 기준) |
| `y` | long | ✓ | Y 좌표 (페이지 좌상단 기준) |
| `width` | long | ✓ | 너비 |
| `height` | long | ✓ | 높이 |
| `zOrder` | int | | Z-순서 (레이어 순서) |
| `paragraphs` | Paragraph[] | | 문단 목록 (text 타입일 때) |
| `image` | Image | | 이미지 정보 (image 타입일 때) |

**예시 (텍스트 프레임)**:
```json
{
  "frameId": "tf_001",
  "frameType": "text",
  "x": 216000,
  "y": 252000,
  "width": 1080000,
  "height": 360000,
  "zOrder": 1,
  "paragraphs": [ ... ]
}
```

---

### Paragraph

문단입니다.

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| `paragraphStyleRef` | string | | 적용된 문단 스타일 ID |
| `contentItems` | ContentItem[] | | 콘텐츠 항목 목록 |

---

### ContentItem

콘텐츠 항목 (텍스트 런 또는 수식)입니다.

| 필드 | 타입 | 설명 |
|------|------|------|
| `textRun` | TextRun | 텍스트 런 (textRun 또는 equation 중 하나) |
| `equation` | Equation | 수식 (textRun 또는 equation 중 하나) |

---

### TextRun

동일 서식의 텍스트 조각입니다.

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| `characterStyleRef` | string | | 적용된 문자 스타일 ID |
| `text` | string | ✓ | 텍스트 내용 |
| `bold` | boolean | | 굵게 (로컬 오버라이드) |
| `italic` | boolean | | 기울임 (로컬 오버라이드) |
| `fontSizeHwpunits` | int | | 폰트 크기 (로컬 오버라이드) |
| `textColor` | string | | 색상 (로컬 오버라이드) |
| `fontFamily` | string | | 폰트 패밀리 (로컬 오버라이드) |

**예시**:
```json
{
  "textRun": {
    "characterStyleRef": "CharacterStyle/강조",
    "text": "중요한 내용",
    "bold": true,
    "fontSizeHwpunits": 1200
  }
}
```

---

### Equation

수식입니다.

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| `hwpScript` | string | ✓ | HWP 수식 스크립트 |
| `sourceType` | string | | 원본 타입 ("NP_FONT" \| "MATHML" \| "LATEX") |

**예시**:
```json
{
  "equation": {
    "hwpScript": "{a} over {b} + sqrt {c}",
    "sourceType": "NP_FONT"
  }
}
```

---

### Image

이미지입니다.

| 필드 | 타입 | 필수 | 설명 |
|------|------|------|------|
| `imageId` | string | ✓ | 이미지 고유 ID |
| `originalPath` | string | | 원본 이미지 경로 |
| `format` | string | | 포맷 ("png" \| "jpeg" \| "gif" \| "tiff" \| "bmp" \| "eps" \| "pdf") |
| `pixelWidth` | int | | 픽셀 너비 |
| `pixelHeight` | int | | 픽셀 높이 |
| `displayWidth` | long | | 표시 너비 (HWPUNIT) |
| `displayHeight` | long | | 표시 높이 (HWPUNIT) |
| `base64Data` | string | | Base64 인코딩 이미지 데이터 |

**예시**:
```json
{
  "frameId": "img_001",
  "frameType": "image",
  "x": 216000,
  "y": 720000,
  "width": 720000,
  "height": 540000,
  "image": {
    "imageId": "image_001",
    "originalPath": "Links/photo.jpg",
    "format": "jpeg",
    "pixelWidth": 1920,
    "pixelHeight": 1440,
    "displayWidth": 720000,
    "displayHeight": 540000,
    "base64Data": "/9j/4AAQSkZJRg..."
  }
}
```

---

## 전체 예시

```json
{
  "version": "1.0",
  "sourceFormat": "IDML",
  "sourceFile": "sample.idml",
  "layout": {
    "defaultPageWidth": 1512000,
    "defaultPageHeight": 2139120,
    "marginTop": 252000,
    "marginBottom": 252000,
    "marginLeft": 216000,
    "marginRight": 216000
  },
  "fonts": [
    {
      "id": "Font/NotoSansCJKkr-Regular",
      "familyName": "Noto Sans CJK KR",
      "styleName": "Regular",
      "fontType": "OTF",
      "hwpxMappedName": "본고딕"
    }
  ],
  "paragraphStyles": [
    {
      "id": "ParagraphStyle/본문",
      "name": "본문",
      "type": "paragraph",
      "fontFamily": "Noto Sans CJK KR",
      "fontSizeHwpunits": 1000,
      "alignment": "justify",
      "lineSpacingPercent": 160
    }
  ],
  "characterStyles": [],
  "pages": [
    {
      "pageNumber": 1,
      "pageWidth": 1512000,
      "pageHeight": 2139120,
      "frames": [
        {
          "frameId": "tf_001",
          "frameType": "text",
          "x": 216000,
          "y": 252000,
          "width": 1080000,
          "height": 360000,
          "zOrder": 1,
          "paragraphs": [
            {
              "paragraphStyleRef": "ParagraphStyle/본문",
              "contentItems": [
                {
                  "textRun": {
                    "text": "안녕하세요, 이것은 예시 문서입니다."
                  }
                }
              ]
            }
          ]
        }
      ]
    }
  ]
}
```
