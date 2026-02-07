# IDML to HWPX 변환기 리팩토링 계획

## 현재 상태 분석

### 파일 크기 및 복잡도

| 파일 | 라인 수 | 메서드 수 | 문제점 |
|------|--------|----------|--------|
| IDMLToIntermediateConverter.java | 2,535 | 60 | 단일 책임 원칙 위반, 거대 클래스 |
| IntermediateToHwpxConverter.java | 2,185 | 45 | 단일 책임 원칙 위반, 거대 클래스 |
| IDMLLoader.java | 1,782 | - | XML 파싱과 객체 생성 혼재 |
| IDMLPageRenderer.java | 1,070 | - | 렌더링 로직 분리 필요 |

---

## Phase 1: IDMLToIntermediateConverter 분리

### 목표
2,535줄의 거대 클래스를 기능별로 분리

### 신규 클래스

#### 1. `converter/idml/TextFrameConverter.java`
```
- convertTextFrame()
- convertParagraph()
- convertTextRun()
- resolveLinkedTextFrames()
```

#### 2. `converter/idml/ImageFrameConverter.java`
```
- convertImageFrame()
- calculateImageClipping()
- applyImageTransform()
```

#### 3. `converter/idml/VectorShapeConverter.java`
```
- convertVectorShape()
- convertInlineVectorShape()
- convertRectangle()
- convertOval()
- convertPolygon()
```

#### 4. `converter/idml/TableConverter.java`
```
- convertTable()
- convertTableRow()
- convertTableCell()
```

#### 5. `converter/idml/FramePositionCalculator.java`
```
- calculateFramePosition()
- applyTransformMatrix()
- convertCoordinates()
```

### 리팩토링 후 IDMLToIntermediateConverter
```java
public class IDMLToIntermediateConverter {
    private final TextFrameConverter textFrameConverter;
    private final ImageFrameConverter imageFrameConverter;
    private final VectorShapeConverter vectorShapeConverter;
    private final TableConverter tableConverter;

    public IntermediateDocument convert(IDMLDocument doc) {
        // 조율(orchestration) 로직만 유지
    }
}
```

---

## Phase 2: IntermediateToHwpxConverter 분리

### 목표
2,185줄을 기능별로 분리

### 신규 클래스

#### 1. `converter/hwpx/HwpxTextFrameWriter.java`
```
- convertTextFrame()
- convertMultiColumnTextFrame()
- addParagraphToSubList()
```

#### 2. `converter/hwpx/HwpxImageWriter.java`
```
- convertImageFrame()
- registerImage()
- setImageClipping()
```

#### 3. `converter/hwpx/HwpxShapeWriter.java`
```
- convertShapeFrame()
- convertRectangleShape()
- convertOvalShape()
- convertPolygonShape()
- setupShapeCommon()
- setupShapeLineAndFill()
```

#### 4. `converter/hwpx/HwpxTableWriter.java`
```
- convertTableFrame()
- createTableStructure()
- createTableCell()
```

#### 5. `converter/hwpx/HwpxSectionBuilder.java`
```
- createSection()
- createParagraph()
- setupSecPr()
```

---

## Phase 3: IDMLLoader 분리

### 목표
1,782줄의 XML 파싱 로직을 분리

### 신규 클래스

#### 1. `idml/parser/SpreadParser.java`
```
- parseSpread()
- parsePage()
- parseSpreadItems()
```

#### 2. `idml/parser/StoryParser.java`
```
- parseStory()
- parseParagraphStyleRange()
- parseCharacterStyleRange()
- collectInlineGraphics()
```

#### 3. `idml/parser/StyleParser.java`
```
- parseParagraphStyles()
- parseCharacterStyles()
- parseObjectStyles()
```

#### 4. `idml/parser/GraphicsParser.java`
```
- parseTextFrame()
- parseImageFrame()
- parseVectorShape()
- parsePathGeometry()
```

---

## Phase 4: IDMLPageRenderer 개선

### 목표
렌더링 로직을 더 모듈화

### 신규 클래스

#### 1. `renderer/ShapeRenderer.java`
```
- renderRectangle()
- renderOval()
- renderPolygon()
- renderBezierPath()
```

#### 2. `renderer/TextRenderer.java`
```
- renderTextFrame()
- applyTextStyles()
```

#### 3. `renderer/ImageRenderer.java`
```
- renderImage()
- applyImageTransform()
```

---

## Phase 5: 공통 유틸리티 정리

### 신규/개선 클래스

#### 1. `util/TransformUtils.java`
```
- multiplyMatrices()
- applyTransform()
- invertMatrix()
```

#### 2. `util/ColorUtils.java`
```
- resolveColor()
- rgbToHex()
- applyTint()
```

#### 3. `util/GeometryUtils.java`
```
- calculateBounds()
- intersectRectangles()
- clipPath()
```

---

## 구현 우선순위

| 순서 | 작업 | 예상 시간 | 의존성 |
|------|------|----------|--------|
| 1 | VectorShapeConverter 분리 | 1일 | 없음 |
| 2 | HwpxShapeWriter 분리 | 1일 | 1 |
| 3 | ImageFrameConverter 분리 | 0.5일 | 없음 |
| 4 | HwpxImageWriter 분리 | 0.5일 | 3 |
| 5 | TextFrameConverter 분리 | 1일 | 없음 |
| 6 | HwpxTextFrameWriter 분리 | 1일 | 5 |
| 7 | IDMLLoader 파서 분리 | 2일 | 없음 |
| 8 | IDMLPageRenderer 분리 | 1일 | 7 |
| 9 | 공통 유틸리티 정리 | 1일 | 1-8 |
| **총계** | | **9일** | |

---

## 리팩토링 원칙

### 1. 단일 책임 원칙 (SRP)
- 각 클래스는 하나의 책임만 가진다
- 변환 로직과 빌더 로직 분리

### 2. 인터페이스 분리
```java
interface FrameConverter<S, T> {
    T convert(S source, ConversionContext context);
}

interface HwpxWriter<T> {
    void write(T intermediate, Run anchorRun);
}
```

### 3. 의존성 주입
```java
public class IDMLToIntermediateConverter {
    @Inject TextFrameConverter textFrameConverter;
    @Inject ImageFrameConverter imageFrameConverter;
    // ...
}
```

### 4. 테스트 용이성
- 각 변환기는 독립적으로 테스트 가능
- Mock 객체를 통한 단위 테스트

---

## 패키지 구조 (리팩토링 후)

```
kr.dogfoot.hwpxlib.tool.idmlconverter/
├── IDMLToHwpxConverter.java          # 진입점 (변경 없음)
├── ConvertOptions.java
├── ConvertResult.java
│
├── converter/
│   ├── IDMLToIntermediateConverter.java  # 조율자 (축소)
│   ├── IntermediateToHwpxConverter.java  # 조율자 (축소)
│   │
│   ├── idml/                             # IDML → Intermediate
│   │   ├── TextFrameConverter.java
│   │   ├── ImageFrameConverter.java
│   │   ├── VectorShapeConverter.java
│   │   ├── TableConverter.java
│   │   └── FramePositionCalculator.java
│   │
│   ├── hwpx/                             # Intermediate → HWPX
│   │   ├── HwpxTextFrameWriter.java
│   │   ├── HwpxImageWriter.java
│   │   ├── HwpxShapeWriter.java
│   │   ├── HwpxTableWriter.java
│   │   └── HwpxSectionBuilder.java
│   │
│   └── registry/                         # 기존 유지
│       ├── FontRegistry.java
│       └── StyleRegistry.java
│
├── idml/
│   ├── IDMLDocument.java
│   ├── IDMLSpread.java
│   ├── ...
│   │
│   └── parser/                           # XML 파싱
│       ├── SpreadParser.java
│       ├── StoryParser.java
│       ├── StyleParser.java
│       └── GraphicsParser.java
│
├── intermediate/                         # 기존 유지
│   ├── IntermediateDocument.java
│   ├── IntermediateFrame.java
│   └── ...
│
├── renderer/                             # PNG 렌더링
│   ├── IDMLPageRenderer.java             # 조율자 (축소)
│   ├── ShapeRenderer.java
│   ├── TextRenderer.java
│   └── ImageRenderer.java
│
└── util/
    ├── TransformUtils.java
    ├── ColorUtils.java
    └── GeometryUtils.java
```

---

## 예상 효과

| 항목 | 현재 | 리팩토링 후 |
|------|------|------------|
| 최대 파일 크기 | 2,535줄 | ~500줄 |
| 최대 메서드 수/클래스 | 60개 | ~15개 |
| 테스트 커버리지 | 낮음 | 높음 (가능) |
| 코드 재사용성 | 낮음 | 높음 |
| 유지보수성 | 어려움 | 용이 |
