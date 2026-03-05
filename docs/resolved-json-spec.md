# resolved.json 스펙 문서

## 개요

`resolved.json`은 ExtendScript(`extract_indd.jsx`)가 InDesign DOM에서 **최종 계산된 값**(스타일, 색상, 폰트, 지오메트리, 레이아웃)을 수집하여 생성하는 JSON 파일이다. IDML에는 없는 시각적/기능적 메타데이터를 보충한다.

---

## 최상위 구조

```json
{
  "documentInfo": {...},
  "paragraphStyles": [{...}],
  "characterStyles": [{...}],
  "colors": [{...}],
  "fonts": [{...}],
  "stories": [{...}],
  "textFrames": [{...}],
  "pages": [{...}],
  "pageItems": [{...}]
}
```

---

## 1. documentInfo — 문서 메타데이터

| InDesign DOM 속성 | resolved.json 필드 | 설명 |
|---|---|---|
| `doc.name` | `name` | 문서 파일명 |
| `doc.fullName.fsName` | `fullName` | 절대 경로 |
| `doc.pages.length` | `pageCount` | 페이지 수 |
| `doc.spreads.length` | `spreadCount` | 스프레드 수 |
| `doc.stories.length` | `storyCount` | 스토리 수 |
| `doc.documentPreferences.pageWidth` | `pageWidth` | 페이지 폭 (pt) |
| `doc.documentPreferences.pageHeight` | `pageHeight` | 페이지 높이 (pt) |
| `doc.documentPreferences.facingPages` | `facingPages` | 양면 페이지 여부 |

---

## 2. paragraphStyles — 문단 스타일

| InDesign DOM 속성 | resolved.json 필드 | 설명 |
|---|---|---|
| `ps.name` | `name` | 스타일 이름 |
| `ps.basedOn?.name` | `basedOn` | 부모 스타일 |
| `ps.appliedFont?.fontFamily` | `fontFamily` | 글꼴 패밀리 |
| `ps.fontStyle` | `fontStyle` | 글꼴 스타일 (Regular, Bold 등) |
| `ps.pointSize` | `fontSize` | 글자 크기 (pt) |
| `ps.leading` | `leading` | 행간 (pt 또는 "Auto") |
| `ps.autoLeading` | `autoLeading` | 자동 행간 비율 (%) |
| `ps.justification.toString()` | `justification` | 정렬 |
| `ps.spaceBefore` | `spaceBefore` | 문단 전 간격 (pt) |
| `ps.spaceAfter` | `spaceAfter` | 문단 후 간격 (pt) |
| `ps.firstLineIndent` | `firstLineIndent` | 첫 줄 들여쓰기 (pt) |
| `ps.leftIndent` | `leftIndent` | 왼쪽 들여쓰기 (pt) |
| `ps.rightIndent` | `rightIndent` | 오른쪽 들여쓰기 (pt) |
| `ps.hyphenation` | `hyphenation` | 하이픈 사용 여부 |
| `ps.dropCapLines` | `dropCapLines` | 드롭캡 줄 수 |
| `ps.dropCapCharacters` | `dropCapCharacters` | 드롭캡 글자 수 |
| `ps.tabStops[]` | `tabStops[]` | 탭 정지점 목록 |

### tabStops (탭 정지점)

| 필드 | 설명 |
|---|---|
| `position` | 위치 (pt) |
| `alignment` | 정렬 (LeftAlign, CenterAlign 등) |
| `leader` | 지시선 문자 |

---

## 3. characterStyles — 글자 스타일

| InDesign DOM 속성 | resolved.json 필드 | 설명 |
|---|---|---|
| `cs.name` | `name` | 스타일 이름 |
| `cs.basedOn?.name` | `basedOn` | 부모 스타일 |
| `cs.appliedFont?.fontFamily` | `fontFamily` | 글꼴 패밀리 |
| `cs.fontStyle` | `fontStyle` | 글꼴 스타일 |
| `cs.pointSize` | `fontSize` | 글자 크기 (pt) |
| `cs.underline` | `underline` | 밑줄 |
| `cs.strikeThru` | `strikeThru` | 취소선 |

---

## 4. colors — 색상/견본

| InDesign DOM 속성 | resolved.json 필드 | 설명 |
|---|---|---|
| `c.name` | `name` | 색상 이름 |
| `c.model.toString()` | `model` | 색상 모델 (PROCESS, SPOT 등) |
| `c.space.toString()` | `space` | 색상 공간 (CMYK, RGB) |
| `c.colorValue` | `colorValue` | 색상 값 배열 |
| (계산) | `hex` | #RRGGBB 16진수 |

### 색상 변환 로직
- **RGB**: `colorValue[0..2]` (0-255) → 직접 변환
- **CMYK**: `colorValue[0..3]` (0-100) → `R = 255 × (1-C/100) × (1-K/100)` 방식으로 RGB 변환

### 기본 색상
| 이름 | Hex |
|---|---|
| Paper | #FFFFFF |
| Black | #000000 |
| White | #FFFFFF |

---

## 5. fonts — 글꼴 정보

| InDesign DOM 속성 | resolved.json 필드 | 설명 |
|---|---|---|
| `f.name` | `name` | 전체 이름 |
| `f.fontFamily` | `fontFamily` | 패밀리 |
| `f.fontStyleName` | `fontStyleName` | 스타일 이름 |
| `f.fontType.toString()` | `fontType` | 유형 (OPENTYPE 등) |
| `f.status.toString()` | `status` | 상태 (OK, MISSING 등) |

---

## 6. stories — 스토리 (텍스트 컨테이너)

### 스토리 기본 정보

| InDesign DOM 속성 | resolved.json 필드 | 설명 |
|---|---|---|
| `story.id.toString()` | `id` | 10진수 ID (예: "1265") |
| `story.length` | `length` | 문자 수 |
| `story.paragraphs.length` | `paragraphCount` | 문단 수 |

### 문단 속성 (paragraphs[])

| InDesign DOM 속성 | resolved.json 필드 | 설명 |
|---|---|---|
| `para.appliedParagraphStyle.name` | `styleName` | 적용된 문단 스타일 |
| `para.leading` | `leading` | 행간 (pt 또는 "Auto") |
| `para.autoLeading` | `autoLeading` | 자동 행간 비율 (%) |
| `para.justification.toString()` | `justification` | 정렬 |
| `para.spaceBefore` | `spaceBefore` | 전 간격 (pt) |
| `para.spaceAfter` | `spaceAfter` | 후 간격 (pt) |
| `para.firstLineIndent` | `firstLineIndent` | 첫 줄 들여쓰기 (pt) |
| `para.leftIndent` | `leftIndent` | 왼쪽 들여쓰기 (pt) |
| `para.rightIndent` | `rightIndent` | 오른쪽 들여쓰기 (pt) |
| `para.paragraphShadingOn` | `shadingOn` | 배경 음영 사용 |
| `para.paragraphShadingColor?.name` | `shadingColor` | 배경 음영 색상 이름 |
| `para.paragraphShadingTint` | `shadingTint` | 배경 음영 틴트 (%) |
| `para.tabStops[]` | `tabStops[]` | 탭 정지점 |

### 텍스트 런 (paragraphs[].runs[])

| InDesign DOM 속성 | resolved.json 필드 | 설명 |
|---|---|---|
| `rng.contents` | `text` | 텍스트 내용 |
| `rng.appliedFont?.fontFamily` | `fontFamily` | 글꼴 패밀리 |
| `rng.pointSize` | `fontSize` | 글자 크기 (pt) |
| `rng.fontStyle` | `fontStyle` | 글꼴 스타일 |
| `rng.fillColor?.name` | `fillColor` | 채움 색상 (이름) |
| `rng.appliedCharacterStyle?.name` | `charStyle` | 적용된 글자 스타일 |
| `rng.tracking` | `tracking` | 자간 (1/1000 em) |
| `rng.horizontalScale` | `horizontalScale` | 수평 축척 (%) |
| `rng.verticalScale` | `verticalScale` | 수직 축척 (%) |
| `rng.baselineShift` | `baselineShift` | 베이스라인 이동 (pt) |
| `rng.position.toString()` | `position` | 위치 (NORMAL, SUPERSCRIPT, SUBSCRIPT) |
| `rng.underline` | `underline` | 밑줄 |
| `rng.strikeThru` | `strikeThru` | 취소선 |

### 테이블 (stories[].tables[])

| InDesign DOM 속성 | resolved.json 필드 | 설명 |
|---|---|---|
| `tbl.id.toString()` | `id` | 테이블 ID |
| `tbl.rows.length` | `rowCount` | 행 수 |
| `tbl.columns.length` | `columnCount` | 열 수 |
| `tbl.columns[].width` | `columnWidths[]` | 열 폭 (pt) |
| `tbl.rows[].height` | `rowHeights[]` | 행 높이 (pt) |

---

## 7. textFrames — 텍스트 프레임

| InDesign DOM 속성 | resolved.json 필드 | 설명 |
|---|---|---|
| `tf.id.toString()` | `id` | 10진수 ID |
| `tf.parentStory.id.toString()` | `storyId` | 소속 스토리 ID |
| `tf.overflows` | `overflows` | 오버플로 여부 |
| `tf.lines.length` | `lineCount` | 행 수 |
| (계산) | `paragraphStart` | 프레임 내 시작 문단 인덱스 (스토리 기준) |
| (계산) | `paragraphEnd` | 프레임 내 끝 문단 인덱스 (스토리 기준) |
| (계산) | `paragraphYOffsets[]` | 각 문단의 프레임 상단 기준 Y 오프셋 (pt) |
| `tf.geometricBounds` | `geometricBounds` | [top, left, bottom, right] (pt) |
| `tf.textFramePreferences.textColumnCount` | `columnCount` | 단 수 |
| `tf.textFramePreferences.textColumnGutter` | `columnGutter` | 단 간격 (pt) |
| `tf.textFramePreferences.insetSpacing` | `insetSpacing` | 내부 여백 [상,좌,하,우] (pt) |
| `tf.textFramePreferences.verticalJustification` | `verticalJustification` | 수직 정렬 |
| `tf.absoluteRotationAngle` | `rotationAngle` | 회전 각도 |

---

## 8. pages — 페이지

| InDesign DOM 속성 | resolved.json 필드 | 설명 |
|---|---|---|
| `pg.documentOffset` | `index` | 0부터 시작하는 인덱스 |
| `pg.name` | `name` | 페이지 번호 라벨 (예: "240") |
| `pg.bounds` | `bounds` | [top, left, bottom, right] (대지 좌표, pt) |
| `pg.marginPreferences.top` | `marginPreferences.top` | 위쪽 여백 |
| `pg.marginPreferences.bottom` | `marginPreferences.bottom` | 아래쪽 여백 |
| `pg.marginPreferences.left` | `marginPreferences.left` | 왼쪽 여백 |
| `pg.marginPreferences.right` | `marginPreferences.right` | 오른쪽 여백 |

---

## 9. pageItems — 페이지 아이템 (벡터/이미지/그룹)

모든 페이지 아이템의 플래튼된 지오메트리 및 스타일 속성.

### 기본 속성

| InDesign DOM 속성 | resolved.json 필드 | 설명 |
|---|---|---|
| `pi.id.toString()` | `id` | 10진수 ID |
| `pi.constructor.name` | `type` | 타입 (Rectangle, Oval, Group, TextFrame 등) |
| `pi.name` | `name` | 이름 |
| `pi.parent.id.toString()` | `parentId` | 부모 ID (Spread/Page가 아닌 경우) |
| `pi.parentPage.documentOffset` | `pageIndex` | 소속 페이지 인덱스 (0-based) |

### 지오메트리 (절대 좌표, 대지 기준, pt)

| 필드 | 설명 |
|---|---|
| `geometricBounds` | [top, left, bottom, right] |
| `visibleBounds` | 스트로크/효과 포함 범위 |

### 변환 (절대값)

| InDesign DOM 속성 | resolved.json 필드 | 설명 |
|---|---|---|
| `pi.absoluteRotationAngle` | `absoluteRotationAngle` | 회전 (도) |
| `pi.absoluteShearAngle` | `absoluteShearAngle` | 기울기 (도) |
| `pi.absoluteHorizontalScale` | `absoluteHorizontalScale` | 수평 축척 (%) |
| `pi.absoluteVerticalScale` | `absoluteVerticalScale` | 수직 축척 (%) |
| `pi.absoluteFlip.toString()` | `absoluteFlip` | 뒤집기 (NONE, HORIZONTAL 등) |

### 채움/선

| 필드 | 설명 |
|---|---|
| `fillColorName` | 채움 색상 이름 (colorHexMap으로 해석) |
| `fillTint` | 채움 틴트 (%) |
| `strokeColorName` | 선 색상 이름 |
| `strokeTint` | 선 틴트 (%) |
| `strokeWeight` | 선 두께 (pt) |
| `strokeAlignment` | 선 정렬 (CENTER_ALIGNMENT 등) |

### 효과

| 필드 | 설명 |
|---|---|
| `opacity` | 불투명도 (%) |
| `gradientFeather` | 그라디언트 페더 {applied, angle, length, type} |
| `dropShadow` | 드롭 섀도우 {angle, distance, size, opacity, colorName} |
| `cornerRadius` | 모서리 반경 (pt) |

---

## ID 변환 체계

IDML과 resolved.json 간의 ID 매칭:

| 소스 | 형식 | 예시 |
|---|---|---|
| IDML | 16진수 `"u" + hex` | `"u1735"` |
| resolved.json (InDesign DOM) | 10진수 | `"5941"` |
| **변환**: `parseInt("1735", 16)` | | `= 5941` |

---

## 단위 정규화 프로세스

1. InDesign DOM의 측정 단위는 문서 설정에 따라 다름 (mm, in, pt 등)
2. `ResolvedData.normalizeToPoints(idmlPageWidthPts)` 에서:
   - IDML 첫 페이지 폭(pt)과 resolved 첫 페이지 폭 비교
   - `scale = idmlPageWidth / resolvedPageWidth`
   - 모든 지오메트리/치수 필드에 scale 적용
3. 정규화 후 모든 좌표는 포인트(pt) 단위
4. HWPX 변환: `pts × 100 = hwpunits`

---

## AST 보강 시 사용

### ResolvedMerger.enrich() — 문단/런 보강

| resolved 필드 | AST 필드 | 변환 |
|---|---|---|
| `leading` (고정) | `lineSpacingType="fixed"`, `lineSpacing` | pt × 100 → hwpunit |
| `autoLeading` | `lineSpacingType="percent"`, `lineSpacing` | 비율 값 |
| `spaceBefore` | `spaceBefore` | pt × 100 |
| `spaceAfter` | `spaceAfter` | pt × 100 |
| `firstLineIndent` | `firstLineIndent` | pt × 100 |
| `leftIndent` | `leftMargin` | pt × 100 |
| `rightIndent` | `rightMargin` | pt × 100 |
| `justification` | `alignment` | "LEFT_JUSTIFIED"→"left", "CENTER"→"center" 등 |
| `shadingOn` + `shadingColor` | `shadingOn`, `shadingColor` | 색상 이름 → colorHexMap 변환 |
| `tabStops[]` | `tabStops[]` | ASTTabStop 객체 생성 |

### 런 보강 (텍스트 기반 매칭)

| resolved 필드 | AST 필드 | 변환 |
|---|---|---|
| `fillColor` | `textColor` (hex) | 색상 이름 → colorHexMap |
| `fontFamily` | `fontFamily` | 직접 |
| `fontStyle` | `fontStyle` | 직접 |
| `fontSize` | `fontSizeHwpunits` | pt × 100 |
| `tracking` | `letterSpacing` | 1/1000 em ÷ 10 → HWPX % |
| `horizontalScale` | `horizontalScale` | 직접 % |
| `verticalScale` | `verticalScale` | 직접 % |
| `baselineShift` | `baselineShift` | (pt ÷ fontSize) × 100 → % |
| `position` | `superscript`/`subscript` | SUPERSCRIPT→true 등 |
| `underline` | `underline` | 직접 |
| `strikeThru` | `strikeThrough` | 직접 |

### ResolvedFrameDistributor.distribute() — 연결 프레임 문단 분배

- `textFrames[].paragraphStart/End` 기준으로 스토리의 문단을 각 프레임에 분배
- `paragraphYOffsets[]`로 각 문단의 프레임 내 수직 위치 설정
- 텍스트 기반 매칭: 첫 15자 비교 (정규화 후)

### ResolvedOverlayEnricher.enrich() — 오버레이 좌표 설정

- 이미지 위 오버레이 텍스트 프레임의 페이지 기준 절대 좌표 설정
- `pageItems[].geometricBounds` → `resolvedPageX/Y/Width/Height` (pt → hwpunit)
