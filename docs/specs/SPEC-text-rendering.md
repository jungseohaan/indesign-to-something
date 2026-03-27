# SPEC: 텍스트 프레임 렌더링 추출 및 변환

## 개요

InDesign 문서에서 텍스트로 변환하기 어려운 장식 텍스트/TextPath를 PNG로 사전 렌더링하여 HWPX에 이미지로 삽입하는 기능. 배지 추출(SPEC-badge-extraction.md)과 연계.

## 문제

일부 TextFrame은 텍스트→텍스트 변환으로 원본 재현이 불가능:
- **회전 텍스트**: HWPX는 텍스트 회전 미지원
- **TextPath**: 곡선/경로 위의 텍스트 (HWPX 미지원)
- **장식 텍스트**: 비흑색 채움, 외곽선, 배경 도형 위 텍스트
- **흰색/희미한 텍스트**: 배경 없이 보이지 않는 텍스트
- **비본문 폰트**: 장식용 특수 폰트

→ InDesign이 직접 렌더링한 PNG를 사용하면 시각적 충실도 보장.

## 렌더링 대상 판별

### TextPath (항상 렌더링)

```
item.textPaths.length > 0 → 무조건 PNG 렌더링
```

곡선 위의 텍스트는 HWPX로 변환 불가. 부모 도형(Polygon/GraphicLine 등)을 렌더링 대상으로 사용.

### TextFrame 판별: `isRenderableTextFrame(tf, bodyFonts)`

#### 사전 제외 조건

| 조건 | 이유 |
|------|------|
| `!tf.parentStory` | 스토리 없음 → 텍스트 없음 |
| 부모가 Character/InsertionPoint/Story/Cell | 인라인/앵커/표 셀 텍스트 → 별도 변환 경로 |
| 제어문자/FFFC 포함 | 앵커 객체 마커 → 복합 콘텐츠 |
| 공백 제거 후 0자 | 빈 텍스트 |
| 공백 제거 후 ≥30자 | 본문 텍스트 → 텍스트로 변환 |

#### 렌더링 결정 흐름

```
                   TextFrame
                      │
              ┌───────┴───────┐
              │ 사전 제외 조건  │
              │ (위 표 참조)   │
              └───────┬───────┘
                      │ 통과
                      ▼
              ┌─── 회전 텍스트? ──── YES → ✅ 렌더링
              │   (|angle| > 0.1°)
              │       NO
              ▼
              ┌─── 흰색/희미한? ─── YES → ✅ 렌더링
              │   (Paper/tint≤30%/
              │    CMYK합≤20)
              │       NO
              ▼
              ┌─── ≥16pt + 비블랙? ── YES → ✅ 렌더링
              │   (대형 장식 텍스트)
              │       NO
              ▼
              ┌─── Spread/Page/     ┐
              │    MasterSpread     │
              │    직속 자식?       │
              │       YES           │
              │   ┌── ≥16pt? ──┐   │
              │   │    NO      │   │
              │   │ 비블랙? ───┤   │
              │   │    NO      │   │
              │   └── ❌ 제외 ─┘   │
              │       NO (다른 부모) │
              └─────────┬──────────┘
                        ▼
              ┌─── 장식 텍스트? ───── NO → ❌ 제외
              │                           (일반 콘텐츠)
              │   장식 = 아래 중 ANY:
              │   • 비블랙 채움색
              │   • 보이는 외곽선 (strokeColor ≠ None)
              │   • 부모 컨테이너에 채움
              │     (Rectangle/Polygon/Oval)
              │   • 비본문 폰트
              │       YES
              └──────── ✅ 렌더링
```

### 흰색/희미한 텍스트 판별: `isLightColoredText(tf)`

| 조건 | 판별 |
|------|------|
| `fillColor.name == "Paper"` | 흰색 |
| `fillTint ≤ 30` | 매우 희미한 틴트 |
| CMYK 합계 `C+M+Y+K ≤ 20` | CMYK 밝은 색 |

### 본문 폰트 감지: `detectBodyFonts(doc)`

문서 전체 Story의 문자별 폰트 패밀리를 수집, 빈도순 상위 폰트를 본문 폰트로 간주. 본문 폰트가 아닌 텍스트는 장식 텍스트로 분류.

## 렌더링 실행

### PNG 내보내기 설정

```javascript
app.pngExportPreferences.exportResolution = 220;       // DPI (conversion-config.json의 pngExportResolution)
app.pngExportPreferences.antiAlias = true;              // 안티앨리어싱
app.pngExportPreferences.transparentBackground = true;  // 투명 배경
app.pngExportPreferences.pngQuality = MAXIMUM;          // 최대 품질
```

### 렌더링 대상 결정

```
TextFrame의 부모가 Rectangle/Polygon/GraphicLine/Oval?
    YES → 부모 컨테이너를 렌더링 (배경/테두리/모서리 포함)
    NO  → TextFrame 자체를 렌더링
```

부모 컨테이너 렌더링 시:
- 같은 부모에 여러 TextFrame이 있으면 최초 1회만 렌더링
- 후속 TextFrame은 같은 PNG 파일을 참조하는 엔트리만 추가

### 좌표 캡처

```javascript
// 1. visibleBounds 우선 (PNG 렌더링 영역과 일치)
bounds = renderTarget.visibleBounds;  // [top, left, bottom, right]

// 2. 폴백: geometricBounds (바운딩 박스)
bounds = renderTarget.geometricBounds;

// 3. 스프레드→페이지 상대 좌표 변환
bounds[0] -= pageBounds[0];  // top
bounds[1] -= pageBounds[1];  // left
bounds[2] -= pageBounds[0];  // bottom
bounds[3] -= pageBounds[1];  // right
```

### 페이지 없는 프레임 처리

Group 내부 TextFrame 등 `parentPage == null`인 경우:
- 조건: 1~30자 + ≥16pt 폰트
- `visibleBounds` 중심점으로 페이지 매칭 (점이 어느 페이지 영역에 속하는지)

## resolved.json 데이터 구조

```json
{
    "renderedTextFrames": [
        {
            "id": 6001,
            "file": "rendered_frames/frame_6001.png",
            "bounds": [105.3, 50.0, 125.8, 200.0],
            "pageIndex": 0
        },
        {
            "id": 6002,
            "file": "rendered_frames/frame_6001.png",
            "bounds": [105.3, 50.0, 125.8, 200.0],
            "pageIndex": 0
        }
    ]
}
```

- `id`: InDesign DOM ID (decimal)
- `file`: PNG 파일 상대 경로 (resolved.json 기준)
- `bounds`: 페이지 상대 좌표 [top, left, bottom, right] (points)
- `pageIndex`: 0-based 페이지 번호
- 부모 컨테이너 렌더링 시 부모 ID + 자식 ID 모두 등록 (같은 file 참조)

## Java 변환 파이프라인

### 데이터 로드

```
ResolvedDataReader.parseRenderedGroup()
    ├─ JSON → RenderedGroup 객체
    ├─ badge_group / badge_group_child → 별도 처리 (SPEC-badge-extraction 참조)
    └─ renderedTextFrameMap[domId] = RenderedGroup
        ↓
getRenderedTextFrameByIdmlId(idmlHexId)
    ├─ "u1735" → parseInt("1735", 16) → 5941
    └─ renderedTextFrameMap.get(5941)
```

### TextPath 교체 (IDMLToHwpxConverter, Phase 1.7)

```
for each Spread:
    Pass 1: TextPath 렌더링 파일 수집
        for each VectorShape with rendered PNG:
            textPathRenderedFiles.add(file)

    Pass 2: 중복 TextFrame 제거
        for each TextFrame:
            if rendered file in textPathRenderedFiles:
                remove TextFrame (TextPath가 우선)

    Pass 3: TextPath → synthetic ImageFrame
        IDMLImageFrame syn = new IDMLImageFrame()
        syn.linkResourceURI = pngFile.absolutePath
        syn.zOrder = originalVectorShape.zOrder
        spread.addImageFrame(syn)
```

TextPath가 우선하는 이유: TextPath의 부모 도형이 렌더링 대상이므로, 같은 영역의 TextFrame보다 정확한 z-order 유지.

### 플로팅 TextFrame 교체

```
for each ASTSection:
    for each ASTTextFrameBlock:
        RenderedGroup rg = resolvedData.getRenderedTextFrameByIdmlId(block.sourceId())
        if rg == null: skip

        File png = new File(resolvedDir, rg.file())
        if !png.exists(): skip

        BufferedImage img = ImageIO.read(png)

        // 크기: bounds 폭 기반, 높이는 PNG 종횡비로 계산
        figW = pointsToHwpunits(bounds[3] - bounds[1])
        figH = figW * img.height / img.width

        // ASTTextFrameBlock → ASTFigure 교체
        ASTFigure fig = new ASTFigure()
        fig.imageData(Files.readAllBytes(png))
        fig.width(figW), fig.height(figH)
        fig.pageRelativeCenterX/Y = block.pageRelativeCenterX/Y
```

### 인라인 TextFrame 교체

```
for each paragraph item:
    if item is INLINE_TEXT_FRAME:
        RenderedGroup rg = resolvedData.getRenderedTextFrameByIdmlId(obj.sourceId())
        if rg != null:
            obj.kind = IMAGE
            obj.imageData = png bytes
            obj.imageFormat = "png"
            obj.pixelWidth = img.width
            obj.pixelHeight = img.height
            obj.paragraphs = null  // 텍스트 콘텐츠 제거
```

### 중복 제거

인라인으로 교체된 텍스트가 플로팅으로도 남아있는 경우 방지:

```
Set<String> replacedTexts = new HashSet<>()

// 인라인 교체 시: replacedTexts.add(normalizedText)

// 플로팅 교체 후:
for each remaining ASTTextFrameBlock:
    if block.text in replacedTexts:
        remove block  // 인라인에서 이미 처리됨
```

## 관련 파일

### ExtendScript
| 파일 | 함수/라인 | 역할 |
|------|-----------|------|
| `extract_indd.jsx:282-501` | `exportRenderedTextFrames()` | 메인 렌더링 루프 |
| `extract_indd.jsx:381-498` | Pass 2 | 개별 TextFrame/TextPath 렌더링 |
| `extract_indd.jsx:610-700` | `isRenderableTextFrame()` | TextFrame 렌더링 판별 |
| `extract_indd.jsx:706-727` | `isLightColoredText()` | 흰색/희미한 텍스트 판별 |
| `extract_indd.jsx:565-604` | `detectBodyFonts()` | 본문 폰트 감지 |

### Java
| 파일 | 메서드/라인 | 역할 |
|------|-----------|------|
| `resolved/RenderedGroup.java` | 데이터 모델 | id, file, bounds, pageIndex, type |
| `resolved/ResolvedData.java:133-150` | `getRenderedTextFrameByIdmlId()` | IDML hex ID → RenderedGroup 조회 |
| `resolved/ResolvedDataReader.java:303-324` | `parseRenderedGroup()` | JSON 파싱 |
| `IDMLToHwpxConverter.java:260-331` | TextPath 교체 | VectorShape → synthetic ImageFrame |
| `IDMLToHwpxConverter.java:337-425` | `replaceFloatingRenderedTextFrames()` | 플로팅 교체 |
| `IDMLToHwpxConverter.java:441-567` | `replaceInlineRenderedInParagraph()` | 인라인 교체 |

## 배지 추출과의 관계

| 단계 | 배지 (Pass 1) | 텍스트 렌더링 (Pass 2) |
|------|--------------|----------------------|
| 대상 | Group (도형+짧은텍스트) | TextFrame / TextPath |
| 파일명 | `badge_{domId}.png` | `frame_{domId}.png` |
| 자식 스킵 | childIds → badgeGroupChildIds | 부모 컨테이너 렌더링 시 자식 참조만 추가 |
| Java 제외 | badgeGroupShapeIdmlIds → 벡터 제거 | textPathRenderedFiles → TextFrame 제거 |

Pass 1이 먼저 실행되어 배지 자식을 `badgeGroupChildIds`에 등록 → Pass 2에서 배지 자식 TextFrame을 건너뜀.

## 실패 처리

| 실패 시점 | 동작 | 결과 |
|-----------|------|------|
| PNG 내보내기 실패 | try-catch로 건너뜀 | 텍스트로 정상 변환 |
| PNG 파일 누락 | Java에서 스킵 | 원본 TextFrameBlock 유지 |
| bounds 없음 | AST 블록 크기 + PNG 종횡비 폴백 | 위치 근사 |
| parentPage 없음 | visibleBounds 중심점으로 페이지 매칭 | ≥16pt만 시도 |

## 검증

- [ ] 회전 텍스트가 PNG로 렌더링되어 HWPX에 이미지로 삽입
- [ ] TextPath(곡선 텍스트)가 PNG로 렌더링
- [ ] 비블랙 장식 텍스트가 PNG로 렌더링
- [ ] 30자 이상 본문 텍스트는 텍스트로 정상 변환 (PNG 아님)
- [ ] 부모 컨테이너(Rectangle 등)의 배경이 PNG에 포함
- [ ] 같은 부모 컨테이너가 중복 렌더링되지 않음
- [ ] 배지 자식 TextFrame이 Pass 2에서 스킵됨
