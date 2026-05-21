# SPEC-027: 배지 scribble 외곽선 PNG 폴백

> 작성: 2026-05-20. 상태: **Active / 설계 중**.
> 관련: [SPEC-021](SPEC-021-nested-badge-extraction.md), [SPEC-022](SPEC-022-overlapping-decoration-merge.md), [SPEC-023](SPEC-023-renderable-text-bg-merge.md), [SPEC-025](SPEC-025-text-image-rendering-removal.md)

## 문제

InDesign에서 일러스트 톤의 배지는 **scribble stroke effect** (손그림 brushed border)로 외곽선을 그린다. 현재 변환은 이런 배지를 단순 채움(fill) 라운드 사각형으로 변환하여 원본의 톤이 손실된다.

### 시각적 차이 (2026-05-20 사용자 보고)

| 측면 | 원본 (InDesign) | 변환 (HWPX) |
|------|----------------|-------------|
| 내부 | 흰색/투명 + 색상 외곽선 | 단색 채움 (외곽선 없음) |
| 외곽선 | scribble (손그림 brushed) | 매끈한 라운드 사각형 |
| 인상 | 교과서 일러스트 | 색상표/UI 컴포넌트 |

### 원리적 제약

HWPX `hp:rect`/`hp:tbl` stroke는 `solid/dash/dot` 수준만 지원. scribble/brushed/textured 외곽선은 도형 속성으로 재현 불가.

## 목표

효과가 있는 배지의 **외곽선/배경만 PNG로 렌더링**하고, 그 위에 **HWPX 텍스트 노드를 별도 배치**해 검색/편집성을 유지한다.

성공 기준:
1. scribble/sketchy stroke effect를 가진 배지가 원본 톤을 유지
2. 배지 안의 텍스트는 HWPX 안에서 검색 및 편집 가능 (PNG에 포함되지 않음)
3. 일반 단색 stroke 배지는 기존대로 도형 변환 유지 (PNG 변환 강제 안 함)
4. 골든 케이스: 박현숙 1단원, 중3영어 u1 — 배지 시각 회귀 없음

## 해결 방안 (개요)

```
ExtendScript classifyTextFrame() / classifyGroup()
   │
   ├─ stroke effect 감지 (scribble / shadow / textured ...)
   │       │
   │       └─ YES → "badge-bg-only" 분류 + renderedFrames PNG export
   │                  (텍스트는 frame 안에서 별도로 HWPX 텍스트로)
   │
   └─ NO → 기존 도형 변환 (Phase 7 RenderableFramePlacer)

resolved.json
   └─ renderedTextFrames[].mode = "badge-bg-only" (신규 모드)
        ├─ pngPath: 외곽선/배경만 (텍스트 제외)
        └─ bounds: PNG와 텍스트 공유 좌표

Java Phase 7 RenderableFramePlacer
   ├─ mode="badge-bg-only" 분기 추가
   ├─ PNG를 ASTFigure로 배치 (zOrder = 텍스트 아래)
   └─ 원본 TextFrame의 텍스트는 phase3 StoryConverter로 정상 변환
```

핵심 아이디어: PNG는 **배경 레이어**, 텍스트는 그 위 **본문 레이어**. 같은 좌표를 공유하지만 zOrder로 적층.

## 영향 범위

### ExtendScript ([scripts/extract_indd.jsx](../../scripts/extract_indd.jsx))
- `classifyTextFrame()` (L2488) — stroke effect 감지 분기 추가
- `isRenderableTextFrame()` (L2682) — `badge-bg-only` 신규 모드 도입
- `exportBadgeBackground()` (신규, 예상) — 텍스트 숨기고 PNG export하는 헬퍼
- 효과 감지 대상: `strokeType` (scribble/brushed 종류), `effects` 컬렉션 (textured stroke)

### Java 신 파이프라인
- `resolved/ResolvedRenderedFrame.java` (또는 동등 모델) — `mode` 필드 추가 (`badge` / `badge-bg-only` / `text-only-render` 등)
- `phase7/RenderableFramePlacer.java` — mode 분기, `badge-bg-only` 는 PNG + 텍스트 보존 (텍스트 변환은 phase3가 담당)
- `phase2/FramePlacer.java` — 해당 frame을 editable로도 등록 (phase3에서 텍스트 변환되도록)
- `phase3/InlineFrameHandler.java` — 충돌 없는지 확인 (배경 PNG와 텍스트가 중복 배치되지 않도록)

### 영향받지 않는 영역
- AST 모델 (zOrder는 이미 지원)
- HWPX 출력 builder
- 시멘틱 레이어

## 구현 단계 (제안)

### Step 1: 데이터 조사 (구현 전 필수)
**확인할 것**: 박현숙 1단원의 문제 배지(작품/근거/관점/적절성/해석하기/비교하기/평가하기)가 실제로 어떤 IDML 구조인지.
- TextFrame 단독인가 Group인가?
- `strokeType` 값은? (e.g. `Scribble`, `BrushPen`, 또는 일반 + `effects`)
- resolved.json에 effect 정보가 있나? 없으면 ExtendScript에서 추가 추출 필요
- 텍스트와 외곽선이 같은 객체인가 별개인가?

이 조사 결과에 따라 Step 2 분기 결정 위치가 달라진다.

### Step 2: ExtendScript — 효과 감지 + PNG export
- `classifyTextFrame()`에 effect 검사 추가
- 텍스트 숨김 + PNG export 헬퍼 (SPEC-025의 `exportPageBackgrounds()` hide 패턴 참고)
- `renderedTextFrames[]`에 `mode` 필드 신설

### Step 3: Java — Phase 7 분기 + Phase 3 보존
- `mode="badge-bg-only"` 시 PNG는 ASTFigure로, 텍스트 변환은 그대로
- zOrder 결정: PNG는 텍스트 바로 아래

### Step 4: 충돌 검증
- SPEC-022/023의 외곽선 데코 병합과 중복되지 않는지
- 현재 `frame_*.png` 6장이 어떻게 분류되는지

## 검증

- [ ] 데이터 조사: 박현숙 1단원 배지 IDML 구조 보고서
- [ ] 빌드 성공: `mvn clean package -q -DskipTests`
- [ ] CLI 변환: 박현숙 1단원 + 중3영어 u1
- [ ] 시각 회귀: HWPX 열어 배지 톤 비교 (원본 PNG vs HWPX 스크린샷)
- [ ] 텍스트 검색: 배지 안 텍스트가 HWPX에서 검색되는지
- [ ] 단순 배지 회귀 없음: scribble 없는 배지는 도형 변환 유지

## 결정 대기

- effect 감지 임계값 (어떤 stroke type까지를 "PNG 폴백"으로?)
- 충돌 시 SPEC-022/023 우선순위
- 옵션 플래그 위치 (SPEC-025처럼 `conversion-config.json`에 넣을지)
