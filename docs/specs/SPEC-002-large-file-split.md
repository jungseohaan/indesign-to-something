# [SPEC-002] 대형 파일 분할

## 문제

7개 Java 파일이 1,000줄 초과 (최대 1,747줄). 가독성과 유지보수성 저하.

## 목표

각 파일을 500줄 이하의 응집도 높은 클래스로 분할.

## 대상 파일 및 분할 전략

### 1. ASTImageLoader.java (1,747줄, converter/)

**분할 안:**
- `ImagePathResolver` (~150줄) — 이미지 경로 해석, 캐싱
- `ImageTransformUtil` (~250줄) — 클리핑, 회전, 플립
- `VectorShapeRasterizer` (~400줄) — 벡터 도형 래스터화
- `ASTImageLoader` (리팩토링, ~200줄) — 파사드, 진입점

**공유 상태:** `idmlDoc`, `options`, `resolvedPathCache`, `dirListingCache` → `ImagePathResolver`로 이동

### 2. IDMLTemplateCreator.java (1,717줄, devtool/)

**분할 안:**
- 템플릿 생성 로직 vs 스키마 처리 로직 분리

### 3. IDMLPageRenderer.java (1,501줄, converter/)

**분할 안:**
- `IDMLPageRenderer` (~250줄) — 오케스트레이터
- `VectorPathRenderer` (~500줄) — 벡터 경로 렌더링
- `RenderingUtilities` (~300줄) — 좌표 변환, 색상 해석, 파일 I/O

**공유 상태:** `dpi`, `idmlDoc`, `colorMap`, `linksDirectory` → 생성자 주입

### 4. ASTInlineObjectBuilder.java (1,388줄, normalizer/)

**분할 안:** 인라인 감지 vs 변환 로직 분리

### 5. IDMLStoryParser.java (1,361줄, idml/)

**분할 안:** 스토리 파싱 vs 런 파싱 분리

### 6. ConverterCLI.java (1,291줄, root)

**분할 안:**
- `CliUtils` (~30줄) — escapeJson, outputJsonError, getStyleName
- `CliCommandHandlers` (~850줄) — 명령 핸들러 분리
- `ConverterCLI` (~85줄) — 진입점, 디스패치만

### 7. ASTStoryConverter.java (1,211줄, normalizer/)

**분할 안:** 스토리 변환 vs 프레임 배치 분리

## 우선순위

1. ASTImageLoader (가장 큰 파일, 명확한 도메인 분리)
2. ConverterCLI (진입점, 분리 용이)
3. IDMLPageRenderer (렌더링 로직 분리)
4. 나머지 (normalizer, idml 파일들)

## 검증

- [ ] `mvn clean package -q -DskipTests` 빌드 성공
- [ ] CLI 변환 테스트 정상 (기존 기능 회귀 없음)

## 상태: 대기
