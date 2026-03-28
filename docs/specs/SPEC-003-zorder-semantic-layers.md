# [SPEC-003] Z-Order 의미적 레이어 분류 (Visual Stack → Logical Layer)

## 문제

### 현재 상황
인디자인의 Z-order는 수백 개의 객체가 1, 2, 3... 순서로 쌓여있고, 현재 변환기는 이를 단순한 이진 분류(BEHIND_TEXT vs IN_FRONT_OF_TEXT)로 처리한다.

**ASTToHwpxConverter의 현재 배치 순서** (lines 263~318):
```
1) BEHIND_TEXT FIGURE: 배경 이미지 (그룹 외부)
2) 배경 전용 TEXT_FRAME: BEHIND_TEXT, z-order=0
3) TABLE 플로팅
   일반 TEXT_FRAME: IN_FRONT_OF_TEXT
3.5) 그룹 내부 FIGURE: IN_FRONT_OF_TEXT
4) 승격된 오버레이: IN_FRONT_OF_TEXT
```

**isBackgroundOnly() 판별** (ASTTextFrameBlock:171):
- fillColor 있음 + 텍스트 없음 + 인라인 객체 없음 → 배경

### 문제점
1. **배경 판별이 TEXT_FRAME에만 적용**: FIGURE, TABLE은 배경/전경 구분 없음
2. **FIGURE의 이진 분류가 불충분**: `fromGroup()` 여부로만 BEHIND/IN_FRONT 결정. 실제로는 배경 삽화, 본문 이미지, 전경 장식 등 다양한 역할 존재
3. **그룹 내부 이미지의 크기 기반 휴리스틱**: HwpxImageBuilder:436에서 200pt 기준 대형→BEHIND, 소형→IN_FRONT 판단. 정확하지 않음
4. **벡터 그룹(RENDERED_GROUP)이 래스터 PNG로 변환**: HWPX와 HTML5 모두 SVG를 지원하지만 활용하지 않음
5. **Flat 모델에 의미적 레이어 정보 없음**: `FlatLayoutNode.zOrder`는 절대 정수값뿐

---

## 목표

1. 모든 레이아웃 노드에 **의미적 레이어(Semantic Layer)** 분류 부여
2. Flat 모델 단계에서 레이어 정보를 확정 → 컨버터는 **레이어 기반 배치만** 수행
3. 벡터 그룹을 SVG로 변환하여 HWPX/HTML5에서 벡터 품질 유지
4. Gateway가 레이어별 쿼리 제공 → 컨버터의 z-order 관련 하드코딩 제거

---

## 설계

### 1. 의미적 레이어 정의

```java
public enum SemanticLayer {
    BACKGROUND,  // Layer A: 배경색 도형, 배경 이미지, 마스터 페이지 장식
    CONTENT,     // Layer B: 본문 텍스트프레임, 표, 본문 삽입 이미지
    FOREGROUND   // Layer C: 오버레이 텍스트, 말풍선, 강조 기호, 이미지 위 글리프
}
```

### 2. 분류 기준

#### Layer A: BACKGROUND
| 대상 | 판별 조건 |
|------|----------|
| 배경 전용 TEXT_FRAME | `isBackgroundOnly()` = true (기존 로직 재사용) |
| 배경 FIGURE (비그룹) | `!fromGroup() && !isContentImage()` |
| 대형 그룹 내부 FIGURE | `fromGroup() && width >= 200pt` (기존 휴리스틱 흡수) |
| 마스터 페이지 요소 | `fromMasterPage() == true` (향후) |

#### Layer B: CONTENT
| 대상 | 판별 조건 |
|------|----------|
| 텍스트 있는 TEXT_FRAME | `!isBackgroundOnly()` |
| TABLE | 모든 TABLE (절대/인라인) |
| 본문 삽입 이미지 (INLINE) | `positioning == INLINE && parentComponentId != null` |
| 소형 그룹 내부 FIGURE | `fromGroup() && width < 200pt` |

#### Layer C: FOREGROUND
| 대상 | 판별 조건 |
|------|----------|
| OVERLAY 노드 | `positioning == OVERLAY` |
| isOverlay 인라인 | `isOverlay == true` |
| 승격된 오버레이 | `ctx.deferredOverlays` 대응 (셀 내부에서 페이지 레벨로) |

### 3. FlatLayoutNode 확장

```java
// 새 필드
private SemanticLayer semanticLayer;     // BACKGROUND, CONTENT, FOREGROUND
private int layerRelativeOrder;           // 레이어 내 상대 순서 (0부터)

// 접근자
public SemanticLayer semanticLayer() { return semanticLayer; }
public void semanticLayer(SemanticLayer v) { this.semanticLayer = v; }
public int layerRelativeOrder() { return layerRelativeOrder; }
public void layerRelativeOrder(int v) { this.layerRelativeOrder = v; }
```

### 4. FlatPage 확장

```java
// 레이어별 노드 ID 목록 (기존 zOrderedNodeIds를 대체/보완)
private List<String> backgroundNodeIds;   // Layer A
private List<String> contentNodeIds;      // Layer B (독해 순서)
private List<String> foregroundNodeIds;   // Layer C
```

### 5. 분류 수행 위치: ASTToFlatConverter

`sortByZOrder()` 이후 `classifySemanticLayers()` 호출:

```java
private static void classifySemanticLayers(FlatPage page, FlatDocument flat,
                                           FlatDocumentGateway tempGw) {
    List<String> bgIds = new ArrayList<>();
    List<String> contentIds = new ArrayList<>();
    List<String> fgIds = new ArrayList<>();

    for (String nodeId : page.zOrderedNodeIds()) {
        FlatLayoutNode node = tempGw.layoutNode(nodeId);
        SemanticLayer layer = classifyNode(node, tempGw);
        node.semanticLayer(layer);

        switch (layer) {
            case BACKGROUND: bgIds.add(nodeId); break;
            case CONTENT:    contentIds.add(nodeId); break;
            case FOREGROUND: fgIds.add(nodeId); break;
        }
    }

    // 레이어 내 상대 순서 부여
    assignRelativeOrder(bgIds, tempGw);
    assignRelativeOrder(contentIds, tempGw);
    assignRelativeOrder(fgIds, tempGw);

    page.backgroundNodeIds(bgIds);
    page.contentNodeIds(contentIds);
    page.foregroundNodeIds(fgIds);
}
```

### 6. Gateway 확장

```java
// 레이어별 쿼리
List<FlatLayoutNode> backgroundNodes(String pageId);   // Layer A
List<FlatLayoutNode> contentNodes(String pageId);      // Layer B (독해 순서)
List<FlatLayoutNode> foregroundNodes(String pageId);   // Layer C

// 기존 isBackgroundOnly() → semanticLayer == BACKGROUND로 대체 가능
```

### 7. HWPX 컨버터 변환

**현재** (ASTToHwpxConverter:263~318):
```java
// 1) BEHIND_TEXT FIGURE
// 2) 배경 전용 TEXT_FRAME
// 3) TABLE + 플로팅 TEXT_FRAME
// 3.5) 그룹 내부 FIGURE
// 4) 승격 오버레이
```

**변환 후** (FlatToHwpxConverter):
```java
// Layer A: BEHIND_TEXT, z-order=0
for (FlatLayoutNode node : gw.backgroundNodes(pageId)) {
    convertNode(secPrPara, node);  // 타입별 분기 내부
}

// Layer B: IN_FRONT_OF_TEXT, 실제 z-order 사용
for (FlatLayoutNode node : gw.contentNodes(pageId)) {
    convertNode(secPrPara, node);
}

// Layer C: IN_FRONT_OF_TEXT, 최상위 z-order
for (FlatLayoutNode node : gw.foregroundNodes(pageId)) {
    convertNode(secPrPara, node);
}
```

### 8. SVG 변환 (벡터 그룹)

#### 8.1 현재 경로
```
RENDERED_GROUP → rendered_frames/frame_XXXX.png (래스터) → HWPX BinData/imageN.png
```

#### 8.2 신규 경로
```
RENDERED_GROUP → SVG 변환 → HWPX BinData/imageN.svg
                           → HTML5 <svg> 인라인 또는 <img src="*.svg">
```

#### 8.3 SVG 변환 전략

| 요소 | IDML 원본 | SVG 출력 |
|------|----------|---------|
| 사각형/타원 | `Rectangle`, `Oval` | `<rect>`, `<ellipse>` |
| 다각형 | `Polygon` | `<polygon>` |
| 경로 | `GraphicLine`, 자유곡선 | `<path d="...">` |
| 텍스트 글리프 | `TextFrame` 내 단일 글리프 | `<text>` |
| 채우기 | `FillColor`, `FillTint` | `fill`, `fill-opacity` |
| 선 | `StrokeColor`, `StrokeWeight` | `stroke`, `stroke-width` |
| 그라데이션 | `GradientFill` | `<linearGradient>`, `<radialGradient>` |

#### 8.4 PNG 폴백

SVG 변환이 불가능한 경우 (복잡한 효과, 투명도 합성 등) 기존 rendered_frames PNG를 사용.

```java
public enum VectorFormat { SVG, PNG_FALLBACK }

// FlatLayoutNode에 추가
private VectorFormat vectorFormat;
private String svgContent;      // SVG XML 문자열 (vectorFormat == SVG일 때)
// imageData는 기존 PNG 폴백용으로 유지
```

### 9. HTML5 컨버터 적용 (향후)

```html
<!-- Layer A: CSS background -->
<div class="page" style="position: relative;">
  <div class="bg-layer" style="z-index: -1;">
    <div style="position: absolute; ..."><!-- 배경 도형 --></div>
    <svg ...><!-- 배경 벡터 --></svg>
  </div>

  <!-- Layer B: 본문 (정상 흐름) -->
  <div class="content-layer">
    <div class="text-frame">...</div>
    <table>...</table>
  </div>

  <!-- Layer C: 오버레이 -->
  <div class="fg-layer" style="z-index: 10;">
    <div style="position: absolute; ..."><!-- 말풍선 --></div>
  </div>
</div>
```

### 10. Z-Order 붕괴(Collapse) 전략

복잡한 겹침을 단순화하는 특수 처리:

#### 10.1 이미지-텍스트 합성 (Flattening to Image)
도형+이미지+글리프가 복잡하게 겹쳐 데이터로 재현 불가 시, 해당 그룹을 하나의 고해상도 이미지(또는 SVG)로 병합.

**판별 기준**:
- 동일 영역에 3개 이상의 서로 다른 타입 객체가 겹침
- RENDERED_GROUP의 자식 중 텍스트+도형+이미지가 혼재

**처리**: `FlatLayoutNode.nodeType = FIGURE`, `vectorFormat = PNG_FALLBACK`

#### 10.2 그룹화 및 앵커링 (Anchoring)
이미지 위의 텍스트는 z-order 숫자 대신 **부모-자식 관계**로 치환:
- 기존 OVERLAY 메커니즘 활용 (`overlayParentId`)
- HWPX: '그림에 고정된 글상자'
- HTML5: `position: relative` 컨테이너 내 `position: absolute` 자식

#### 10.3 HWPX 텍스트 감싸기(TextWrap) 매핑

| Semantic Layer | HWPX TextWrap | z-order |
|----------------|---------------|---------|
| BACKGROUND | BEHIND_TEXT | 0 (고정) |
| CONTENT | IN_FRONT_OF_TEXT | layerRelativeOrder |
| FOREGROUND | IN_FRONT_OF_TEXT | 1000 + layerRelativeOrder |

---

## 수정 파일

### Phase 1: 데이터 모델 확장
1. `flat/FlatLayoutNode.java` — `SemanticLayer` enum, `semanticLayer`, `layerRelativeOrder`, `vectorFormat`, `svgContent` 필드 추가
2. `flat/FlatPage.java` — `backgroundNodeIds`, `contentNodeIds`, `foregroundNodeIds` 필드 추가

### Phase 2: 분류 로직
3. `flat/ASTToFlatConverter.java` — `classifySemanticLayers()`, `classifyNode()` 메서드 추가. `sortByZOrder()` 이후 호출
4. `flat/FlatDocumentGateway.java` — `backgroundNodes()`, `contentNodes()`, `foregroundNodes()` 쿼리 추가. 레이어별 인덱스 구축

### Phase 3: 직렬화
5. `flat/FlatSerializer.java` — `semanticLayer`, `layerRelativeOrder`, `vectorFormat`, `svgContent` 직렬화
6. `flat/FlatDeserializer.java` — 역직렬화 (해당 파일 있을 경우)

### Phase 4: SVG 변환 (별도 서브 스펙 가능)
7. `flat/SvgConverter.java` **(신규)** — IDML 벡터 요소 → SVG XML 변환
8. `flat/ASTToFlatConverter.java` — RENDERED_GROUP 처리 시 SVG 변환 시도, 실패 시 PNG 폴백

### Phase 5: HWPX 컨버터 포팅
9. `flat/FlatToHwpxConverter.java` — Gateway의 레이어별 쿼리 기반 배치 (기존 5단계 배치 → 3단계 레이어)

### Phase 6: 검증
10. `ConverterCLI.java` — Flat JSON에 레이어 분류 결과 포함, 통계 출력

---

## 검증

- [ ] `mvn clean package -q -DskipTests` 빌드 성공
- [ ] Flat JSON에 모든 ABSOLUTE 노드의 `semanticLayer` 필드 존재
- [ ] 레이어 분류 통계 정상 (BACKGROUND/CONTENT/FOREGROUND 비율 확인)
- [ ] 기존 HWPX 출력과 레이어 기반 HWPX 출력 비교 — 배치 순서 동등성
- [ ] SVG 변환: RENDERED_GROUP 중 단순 벡터 그룹이 SVG로 출력됨
- [ ] SVG 폴백: 복잡한 그룹은 기존 PNG 유지
- [ ] AST 라운드트립: `semanticLayer` 필드 보존 확인

## 의존성

- SPEC-003은 Flat AST Phase 1~4 완료를 전제 (현재 완료 상태)
- SVG 변환(Phase 4)은 독립적으로 진행 가능 — 필요시 별도 SPEC으로 분리

## 상태: 대기
