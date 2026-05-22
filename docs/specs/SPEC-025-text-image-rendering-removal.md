# SPEC-025: 텍스트 이미지 렌더링 제거

## 진행 상황 (2026-05-22 검증 결과)

**구현은 되어 있으나 핵심 케이스 미작동 확인** (박현숙 1단원 소(1), 22 페이지 변환 검증):

| Tier | 항목 | 상태 | 실제 동작 |
|---|---|---|---|
| A.5 | masterPageEditable | **❌ 미작동** | 대표 사례 TF 3854 ("01. 근거를 바탕으로 작품 해석하기") `editable=False`. ExtendScript 의 `if (item.masterPageItem)` 조건은 마스터 페이지 텍스트 본체가 아닌 **override 만** 잡음 → 검출 0건 |
| A.6 | hashiraEditable | ⚪ 부분 작동 | `pageIndex=None` TF 23개 중 일부는 editable, 일부는 background. 분류 조건이 일관성 없음 |
| B | rotationEditable | **❌ 미작동** | `rotationAngle != 0` 인 TF 6개 모두 `editable=False`. 출력 HWPX 에 `rotationInfo angle!=0` 인 텍스트박스 0개 — 회전 텍스트 전부 PNG 로 떨어짐 |
| C | hideTextFrames | ⚪ 미검증 | 기존 동작 변경 없음, 별도 검증 필요 |

### 진단

- `tf.masterPageItem` 속성은 **"마스터에서 상속받은 override item"** 만 True 반환. 마스터 페이지 위의 원본 텍스트 (pageIndex=None) 는 False → SPEC-025 A.5 조건 미충족.
- ExtendScript `classifyTextFrame()` 의 조건 5는 마스터 본체를 처리하도록 확장 필요 (`tf.parentPage == masterSpread.pages[i]` 또는 `pageIndex === null` 패턴 추가).
- 회전 TF 는 `classifyTextFrame()` 보다 먼저 `isRenderableTextFrame()` 에서 PNG 렌더 결정되는 경로 점검 필요.

### 후속 변경 (2026-05-19~20 커밋)
- 인라인 텍스트프레임 + Group 단편 + 1자 라벨 editable 변환 (`94dd8d39`)
- 중첩 인라인 앵커 처리 + 밑줄 + 배지 충돌 보정 (`6d001722`)
- **[미커밋]** `WrapPhase5.java` +20줄: 행글 매달림 들여쓰기 패턴 감지 추가. 단락 첫 줄 wrapIndentLeft=0 + 연속 줄 wrapIndentLeft>0 이면 bullet hanging indent → obstacle wrap 으로 오인 방지, shrink 적용 안 함. 커밋 여부는 별도 판단.

### 다음 단계 (실제 동작 만들기)
1. ✅ `classifyTextFrame()` 조건 5 보강: 마스터 spread 직속 TF 검출 (2026-05-22 적용). 효과: 1개 인스턴스 (2453_pi20) 정상 생성.
2. ⚠️ "off-canvas override" 케이스 (parent=Spread, pageIndex=-1, y<0): TF 3854 같은 case 는 master spread 에 직접 있지 않고 일반 spread 의 pasteboard 영역에 있음. Phase 5 instanceMasterFrames 가 못 잡음 → 별도 처리 필요:
   - Phase 5 확장: `tf.masterPageItem` 있는 override 도 적용 페이지마다 인스턴스화
   - 또는 Phase 2 FramePlacer 가 pageIndex=-1 + masterPageItem 있는 TF 를 "어느 페이지에 표시할지" 결정 (y 좌표 + 페이지 사이즈로 추정)
3. Tier B 회전 텍스트: 회전 bypass 자체는 코드에 있지만 (line 2644+, 2850), 실제 출력에 회전 textbox 0개. 별도 진단 필요.
4. 재검증 후 Tier A.1.5/A.4/A.8 추가 구현

### 2026-05-22 검증 데이터 (박현숙 1단원 소(1))
- config debug log 확인: `spec025=masterPageEditable=true hashiraEditable=true rotationEditable=true nonprintingEditable=true` ✓ 정상 로딩
- pageIndex=None TF 23개 → 모두 editable=True (분류 단계 OK)
- 회전 TF 6개 → 4개 editable (분류 OK)
- 동기화된 master 인스턴스 (synthetic id `_pi`): 1개만 생성 (대다수 master TF 가 spread root 에 있어 instanceMasterFrames 못 잡음)
- Phase 2 FramePlacer 가 pageIndex<0 인 TF 를 건너뜀 → 최종 HWPX 출력에 master/회전 텍스트 0건

## 문제

InDesign 텍스트 일부가 HWPX 변환 시 PNG 이미지로만 표시되어 검색/편집 불가능.

대표 사례 (중3-1국어 박현숙 1단원 소(1), page 10):
- "01. 근거를 바탕으로 작품 해석하기" (단원명, charStyle `**하시라_소단원`)
- frame 3854, `pageIndex` 없음, `geometricBounds y<0` (페이지 영역 밖, 마스터 페이지 머리말)
- HWPX section XML에 텍스트로 존재하지 않음 → page_bg PNG에 래스터화돼있음

### 현황 측정 (박현숙 1단원 소(1), 22 페이지)
| 종류 | 파일 수 | 의미 |
|------|:--:|------|
| `page_bg_*.png` | 22 (페이지당 1) | 페이지 전체 배경 PDF→PNG (텍스트 포함) |
| `badge_*.png` | 67 | 배지/그룹 (텍스트 포함 가능) |
| `inline_*.png` | 97 | 인라인 객체 |
| `frame_*.png` | 6 | renderable TextFrame |
| `renderedTextFrames` 배열 | 1 | (frame_* 또는 badge_*로 노출) |

→ **page_bg 22장**에 비편집 텍스트가 다수 포함되어 있을 가능성 높음. `frame_*` 6장 + `badge_*` 67장 중 텍스트만 담긴 것이 분리 대상.

## 목표

InDesign의 모든 텍스트가 HWPX에서 **검색 가능한 텍스트 요소**로 변환되도록 한다. 이미지 PNG 렌더링은 텍스트 없는 그래픽(도형, 이미지)에만 한정.

성공 기준:
1. 변환 후 HWPX 안에 InDesign 원본의 모든 텍스트가 텍스트 노드로 존재 (검색/편집 가능)
2. 페이지 배경 PNG에는 텍스트가 포함되지 않음 (또는 흐려져 시각적으로만 표시)
3. 회전/장식 효과는 가능한 한 HWPX 기본 기능으로 재현, 불가능 시 텍스트 + 별도 효과 명시
4. 골든 변환 셋 시각 회귀 없음 (페이지 레이아웃, 폰트, 위치 유지)

## 영향 범위

### ExtendScript ([scripts/extract_indd.jsx](../../scripts/extract_indd.jsx))
- `classifyTextFrame()` (L2488) — 분류 로직. `"background"`/`"renderable"` 반환 케이스 검토
- `exportPageBackgrounds()` (L3028) — 페이지 PDF export 전에 텍스트 프레임 숨김 필요
- `isRenderableTextFrame()` (L2682) — 효과/회전 텍스트 처리
- `hideTextFrames()` (L1049) — 이미 존재. 활용 범위 확장

### Java 신 파이프라인
- `phase2/FramePlacer.java` — 모든 TextFrame을 editable로 처리하도록 조건 완화
- `phase3/StoryConverter.java` 외 phase3 sub-module — 마스터 페이지 텍스트 처리 추가
- `phase3/InlineFrameHandler.java` — 인라인 텍스트는 이미 처리. 외부 위치(`y<0`, `pageIndex 없음`) 처리 추가
- `phase6/BackgroundInjector.java` — 페이지 배경 PNG 주입 시 텍스트 마스킹 처리 (옵션)

### 영향받지 않는 영역
- AST 모델
- HWPX 출력 builder (이미 텍스트 변환 능력 보유)
- 시멘틱 레이어 (SPEC-018)
- 레거시 파이프라인 (이번 SPEC은 신 파이프라인만 대상)

## 해결 방안 (3-Tier)

### Tier A: classifyTextFrame "background" 조건 완화
"background" 반환 조건을 검토하고 가능한 한 "editable"로 분류. 각 조건의 텍스트 변환 가능성:

| # | 조건 | 현재 분류 | 변경안 |
|---|------|----------|--------|
| 1.5 | 비균일 변환 (scale<0.6 또는 >1.6) | background | **editable + 폰트 크기 보정** (HWPX 텍스트로 변환 후 fontSize × scale) |
| 2 | 숨겨진 레이어 | background | **유지** (안 보여야 함) |
| 3 | nonprinting | background | **유지** (인쇄 안 되는 마커) |
| 4 | 자동 페이지 번호 마커 (``) | background | **editable + 페이지번호 처리** (HWPX `<hp:pageNum/>` 변환) |
| **5** | **`item.masterPageItem`** | **background** | **editable** (마스터 페이지 텍스트도 변환) |
| 6 | 마진 영역 + 하시라 paragraph style | background | **editable** (하시라도 텍스트로) |
| 7 | `isInlineItem` | background | **유지** (인라인은 부모 frame이 관리) |
| 8 | Group 안 짧은 장식 (≤10자 + 비검정) | background | **editable + 색상 보존** |
| 8.7 | 멀티레이어 텍스트 컴포지트 | background | **editable + 그림자/이중 텍스트 시각 효과는 손실** (트레이드오프) |
| 10 | 빈 TextFrame + fill/stroke | background | **유지** (텍스트 없음) |
| 11 | 실질적 빈 frame | background | **유지** |

### Tier B: classifyTextFrame "renderable" 조건 → "editable + 효과 메타"
`isRenderableTextFrame()`이 반환하는 PNG 렌더 케이스를 텍스트 + 효과로 변환:

| 효과 | HWPX 재현 가능성 | 처리 |
|------|:--:|------|
| 회전 > 3° | △ (TextBox `rotationInfo`) | **시도** — 현재도 일부 회전 처리 (`FrameTransformations.convertRotatedFloatingBlock`) |
| 텍스트 스트로크 (외곽선) | ✓ (`hp:lineShape` 글자 외곽선) | **시도** |
| 드롭 섀도우 | ✓ (`hp:shadow`) | **시도** |
| 외부/내부 광선 | ✗ (HWPX 미지원) | **텍스트만 변환, 효과 손실** |
| 베벨/엠보스, 새틴 | ✗ | **텍스트만** |
| 투명도 < 100% | ✓ (`hp:alpha`) | **시도** |
| 장식 대형 텍스트 | (효과 무관) | **editable로 그대로** |
| 장식 스타일 텍스트 (배경/테두리/둥근모서리) | ✓ (HwpxTextBoxBuilder가 처리) | **editable로** |

### Tier C: page_bg PDF/PNG에서 텍스트 숨김
classifyTextFrame이 "editable" 반환하면 텍스트프레임이 텍스트로 변환됨. 하지만 PDF export 시 그 텍스트가 PDF에 포함되면 PNG 배경에도 들어가 **이중 표시**.

해결: `exportPageBackgrounds()`에서 PDF export 전에 editable 텍스트프레임 일시 숨김:
- 현재 `hideTextFrames(renderTarget)` (L1049) 함수가 group 단위 숨김 지원
- 페이지 PDF export 시 editable frame 리스트 받아 일시 visibility=false → export → 복원

## 단계별 구현 계획

### Phase 1: 진단 + 측정 도구 (1~2일)
- [ ] 변환된 HWPX vs 원본 InDesign 텍스트 누락 비교 도구 추가
  - resolved.json의 모든 텍스트 vs HWPX section XML의 텍스트 차이 검출
  - `scripts/text_coverage.py` (또는 Java util)
- [ ] 박현숙 1단원 소(1) 등 골든 케이스 3개로 누락 텍스트 카탈로그 수집

### Phase 2: Tier A — background 조건 완화 (3~5일)
- [ ] 조건 5 (`masterPageItem`) 완화: editable로 변경. 마스터 페이지 텍스트 페이지마다 반복 배치하는 로직 추가
  - 신 파이프라인의 `FramePlacer` 또는 새 `MasterPagePlacer` sub-module 신설 검토
- [ ] 조건 6 (마진+하시라): paragraph style 외 character style도 검사
- [ ] 조건 1.5 (비균일 scale): fontSize × scale로 보정해서 editable
- [ ] 조건 4 (페이지 번호 마커): HWPX 페이지 번호 control character로 변환
- [ ] 조건 8 (Group 안 짧은 장식): 색상 정보 유지하며 editable

각 변경 후 골든 변환 + HEAD baseline 시각 비교

### Phase 3: Tier C — page_bg 텍스트 숨김 (2~3일)
- [ ] `exportPageBackgrounds()` 수정:
  - `editableFrames` 리스트 확장 (Phase 2 변경으로 더 많은 frame이 editable됨)
  - PDF export 전에 `hideTextFrames(editableFrames)` 호출
  - Export 후 복원
- [ ] 검증: 새 page_bg PNG에 텍스트 없음 확인

### Phase 4: Tier B — renderable 조건 완화 (3~5일)
- [ ] 회전 텍스트: 기존 `FrameTransformations.convertRotatedFloatingBlock`로 처리 — `renderable` 분류 대신 `editable`로 분류하되 신 파이프라인이 rotation 인식
- [ ] 텍스트 스트로크: HWPX `<hp:lineShape>` 매핑 시도
- [ ] 드롭 섀도우, 투명도: HWPX 속성 매핑
- [ ] 광선/베벨/엠보스: 효과 손실 허용, 텍스트만 변환 (config 옵션 `allowEffectLoss` 추가)

### Phase 5: 마스터 페이지 처리 (3~5일)
- [ ] ExtendScript: 마스터 페이지 텍스트프레임을 page별로 instance화하여 resolved.json에 포함
  - 또는 마스터 페이지 정보를 별도 섹션으로 저장 (`masterPageItems: [...]`)
- [ ] Java: 마스터 페이지 텍스트를 각 페이지에 ASTTextFrame으로 배치
  - 위치는 마스터 페이지 좌표 그대로 사용
  - 동일 텍스트가 페이지마다 반복되므로 중복 방지 캐싱 가능

### Phase 6: 검증 + 회귀 방지 (2~3일)
- [ ] 박현숙 1단원 소(1) 변환 → 페이지 10 "01. 근거를 바탕으로 작품 해석하기" 텍스트 확인
- [ ] 다른 골든 셋 (영어교과서 부속 등) 변환 → 회귀 없음
- [ ] 텍스트 커버리지 도구로 누락 텍스트 < 5% 확인
- [ ] 시각 검증: 페이지 레이아웃, 폰트, 위치 유지

## 시각 효과 보존 전략

### 가능한 효과 (HWPX 기본 기능)
- 회전 텍스트박스
- 외곽선 (`<hp:lineShape>`)
- 드롭 섀도우 (`<hp:shadow>`)
- 투명도 (`<hp:alpha>`)
- 배경색/테두리/둥근모서리 (이미 처리됨)

### 손실 허용 효과 (config 옵션으로 제어)
- 외부/내부 광선 (Outer/Inner Glow)
- 베벨/엠보스
- 새틴
- 멀티레이어 텍스트 컴포지트 그림자 (예: 검정+흰색 이중 텍스트)

`conversion-config.json`에 옵션:
```json
{
  "rendering": {
    "textFrame": {
      "allowEffectLoss": false,
      "_comment_allowEffectLoss": "true면 HWPX 미지원 효과는 손실하고 텍스트만 변환. false면 이미지 렌더 fallback"
    }
  }
}
```

## 위험 & 완화

| # | 위험 | 가능성 | 완화책 |
|---|------|:--:|--------|
| 1 | 마스터 페이지 텍스트가 페이지마다 중복돼 파일 크기 증가 | 중 | 동일 텍스트 캐싱, HWPX의 master 기능 활용 검토 |
| 2 | 비균일 scale 텍스트가 폰트 크기 보정 후에도 시각 차이 | 중 | scale × fontSize 정확 매핑, 글상자 폭 보정 |
| 3 | renderable 효과 손실로 시각 회귀 | 중 | config `allowEffectLoss=false` 기본, 각 효과별 단계적 적용 |
| 4 | page_bg PNG 변경으로 기존 변환 결과와 byte-diff | 높음 | 새 baseline 캡처. 시각 비교로 검증 |
| 5 | 마스터 페이지 처리 추가로 변환 시간 증가 | 낮음 | 페이지 수 × 마스터 frame 수, 보통 작음 |
| 6 | 마스터 페이지 오버라이드 (페이지에서 수정한 마스터 텍스트) | 중 | 오버라이드 우선, 마스터 fallback 로직 구현 |

## 검증

- [ ] 각 Phase 후 `mvn clean package -q -DskipTests` + `mvn test`
- [ ] 박현숙 1단원 소(1) 변환 → 페이지 10 "01. 근거를 바탕으로 작품 해석하기" 텍스트 검색 가능
- [ ] 텍스트 커버리지 측정 도구로 누락 < 5%
- [ ] 영어교과서 부속(001-009), 박현숙 1단원 소(1)/(2) 등 3+개 케이스 시각 검증
- [ ] 한글에서 열어 텍스트 검색/편집 확인

## 보류 가능 항목

- **InDesign의 마스터 페이지 → HWPX 마스터 기능 매핑** (HWPX `<hm:masterPage>` 활용) — Phase 5의 더 깊은 통합. 우선 단순 페이지 반복으로 처리하고 후속 SPEC에서 검토
- **회전 텍스트의 정밀 보존** — InDesign의 미세 회전 (예: 1.2°)을 HWPX rotation에 정확히 반영 (현재는 3° 이하 무시)
- **텍스트 광선/베벨 등 미지원 효과** — 별도 SPEC. Phase 4에서는 손실 처리

## 작업 우선순위

| Phase | 우선순위 | 예상 기간 |
|-------|:--:|:--:|
| 1 진단/측정 도구 | ★★★ | 1~2일 |
| 2 Tier A (background 완화) | ★★★ | 3~5일 |
| 5 마스터 페이지 처리 | ★★★ | 3~5일 |
| 3 Tier C (page_bg 텍스트 숨김) | ★★ | 2~3일 |
| 4 Tier B (renderable 완화) | ★ | 3~5일 |
| 6 검증/회귀 방지 | ★★★ | 2~3일 |

**합계 추정: 14~23일 (3~5주)**

## 참고

- 박현숙 1단원 소(1): `/Users/seohan/Documents/변환작업/중3-1국어(박현숙)/중등국어 3-1학년_1단원 소(1)(008~029)/`
- 영어교과서 부속: `~/Library/Caches/idml-to-hwpx/extracts/89f890fc...`
- [scripts/extract_indd.jsx](../../scripts/extract_indd.jsx)
- [classifyTextFrame](../../scripts/extract_indd.jsx#L2488), [isRenderableTextFrame](../../scripts/extract_indd.jsx#L2682), [exportPageBackgrounds](../../scripts/extract_indd.jsx#L3028)
- [phase2/FramePlacer.java](../../src/main/java/kr/dogfoot/hwpxlib/tool/idmlconverter/normalizer/resolved/phase2/FramePlacer.java)
- 관련 SPEC: SPEC-023 (외곽선 텍스트 배지 라운드 배경 병합) — 일부 텍스트 분류 로직 영향
