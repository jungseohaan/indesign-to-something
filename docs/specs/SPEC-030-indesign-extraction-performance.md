# SPEC-030: InDesign 추출 속도 개선

> 작성: 2026-05-22. 상태: **Active / 설계 중**. 우선순위: P0 (재추출 대기 시간이 사용자 워크플로우의 주된 병목).

## 문제

데스크탑 앱에서 InDesign 파일을 변환할 때, ExtendScript 추출 단계가 전체 변환의 90% 이상을 차지한다. 이전 측정에서 256페이지 교과서 한 권 추출에 **수십 분 ~ 한 시간**이 걸렸고, 사용자가 변경 사항을 검증하기 위해 재추출을 반복해야 하는 워크플로우에서 가장 큰 시간 소비점이다.

## 시간 분포 가설

`extract_indd.jsx` 의 `_marker` / `writeProgress` 호출 패턴 + 전 추출 (페이지 30~55, 26페이지) 의 stale 타임아웃 30분 케이스 기반.

| 단계 | 위치 | 추정 비중 | 비고 |
|---|---|---|---|
| `02_idml_export` | doc.exportFile(IDML) | 5% | 단발성 |
| `03_allPageItems` | allItems flatten + 분류 | 5% | O(N) |
| **`04_pageRendering`** | **PDF 전문서 export + 페이지별 PNG** | **35%** | 가장 큰 단일 비용 |
| **`05_badgeRendering`** | **개별 PNG export** (배지/그래픽/이미지/벡터 등) | **40%** | 25+ 개별 exportFile 호출 |
| `10_collectResolved` | 스토리/스타일/페이지아이템 직렬화 | 10% | JSON 크기 ~10MB |
| `11_writeJson` | JSON.stringify + 파일 쓰기 | 5% | 메모리 spike |

> **다음 단계**: 실측 — 진행률 로그에 phase 별 elapsed ms 를 기록해 가설 검증.

## 최신 실측 기준선

2026-06-29 기준,
`22개정_중등국어(박)_3-1학기-3단원(096~135)1차수정_OK.indd`
전체 추출 캐시(`fba9cb7e...`)의 `_phase_timing.log`에서 확인한 병목이다.

| 단계 | 실측 시간 | 의미 |
|---|---:|---|
| `02_idml_export` | 약 16.7초 | InDesign IDML export |
| `03_allPageItems` | 약 5.0초 | page item flatten |
| `03c_preClassify` | 약 28.3초 | TextFrame 분류 캐시 작성 |
| `04_pageRendering` | 약 26.5초 | 페이지 배경/inline 후보 전 준비 |
| `06_imgFrames` 내부 개별 이미지 export/restore | 약 73.6초 | source 단위 PNG export, hide/export/restore 반복 |
| `08_complexFrames` | 약 30.7초 | 복합 그래픽 PNG export |
| `09b_masterGraphics` | 약 12.5초 | master graphics export |
| `09c_editableTFVisualShells` | 약 6.5초 | editable TF shell export |
| `10i_collectStories` | 약 30.0초 | story/textStyleRanges 직렬화 |
| `10m_collectPageItems` | 약 10.3초 | resolved pageItems 직렬화 |
| `13_done` 이후 PDF/후처리 대기 | 약 263.7초 | preview PDF/외부 후처리/앱 대기 포함 |

검증 결과:

- `extraction-results.json.validation.status = OK`
- validation issue는 0건
- 현재 병목은 correctness 실패가 아니라 export/restore 반복, story 수집, 후처리 대기이다.

이 실측은 기존 "PDF/page rendering이 최대 병목" 가설을 보정한다. 현재 구조에서는
개별 source export의 hide/restore 비용과 resolved story 직렬화가 커졌고, 마지막
앱 대기 구간도 별도로 계측해야 한다.

## 개선 영역

### Tier P — 정책 기반 후보 수 축소 (최우선 / 고효과)

최근 계측에서는 단순 코드 최적화보다 ownership 정책 구조가 더 큰 병목으로
확인되었다. 문제는 PNG export 자체만이 아니라, 같은 IDML source material을
여러 pass가 중복 후보로 만들고, 이후 normalize/drop/deduplicate 단계가 이를
정리한다는 점이다. 이 구조에서는 페이지 수가 늘수록 비용이
`executable object 수`가 아니라 `pass 수 × page item 수 × 후보 정리 비용`에
가깝게 증가한다.

정책 방향은 `POLICY-source-ownership.md`의
`Performance-Oriented Ownership Policy`를 따른다.

핵심 변경:

- Stage 0/1에서 source-cluster index와 source-slot registry를 먼저 만든다.
- 후보 생성기는 registry에 있는 executable slot만 만든다.
- 같은 source bundle/slot의 alternate render channel은 파일을 export하지 않고
  diagnostics로만 기록한다.
- parent/composite PNG는 child text/shell/content/table-style slot이 이미
  빠진 export source set이 있을 때만 생성한다.
- normalize 단계는 더 이상 후보를 수정하지 않고 validation만 한다.

예상 효과:

- 중복 PNG export 제거: 같은 shell/content가 page object, inline object,
  decoration group, vector fallback 등으로 반복 export되는 비용 제거.
- normalize 비용 제거: 후보 생성 후 source tree를 반복 스캔하며 slot을
  좁히거나 drop하는 비용 제거.
- 회귀 감소: "나중에 정리"가 없어지므로 layer, inline/floating, shell/text
  소유권이 후속 단계에서 흔들리지 않는다.

성공 기준:

- PNG/vector export 수 <= visible visual ObjectPlan 수.
- non-canonical candidate는 image file을 쓰지 않는다.
- `NATIVE_SOURCE_SHAPE` materialization은 ObjectPlan/resolved source item으로
  실행하며, `shape_*.png` 같은 보조 PNG를 export하지 않는다.
- `normalizeExtractionCandidateOwnershipSlots`류 함수는 ObjectPlan
  validation report만 생성하고, ownership field를 변경하지 않는다.
- live InDesign recursive API (`allPageItems` 등)는 source index 작성 단계
  밖의 candidate/page loop에서 호출하지 않는다.

### Tier A — 즉시 적용 가능 (저비용 / 고효과)

#### A.1 PNG 해상도 다운그레이드 옵션 (예상 -25~40%)
- 현재: `pngExportResolution = 220` (CONFIG)
- 제안: 작업 모드별 분기
  - **fast** (검증/iterate): 150 DPI
  - **standard** (배포 전): 220 DPI (현재)
  - **high** (출력용): 300 DPI
- 적용: `conversion-config.json` + 데스크탑 UI 토글
- 트레이드오프: 인쇄 품질 ↓ , 화면용에는 차이 미미

#### A.2 PDF 미사용 시 생성 스킵 (예상 -15%)
- 현재: PDF 전문서 export 가 모든 추출에 포함됨 (`exportFile(PDF_TYPE)`)
- 용도: 데스크탑 앱의 미리보기 PDF + 고해상도 이미지 추출
- 제안: `--no-pdf` 옵션 → 미리보기/배경 PDF 둘 다 건너뜀
  - Java 변환 자체는 PDF 없이도 동작 (PNG + IDML 만으로 충분)
  - 데스크탑 UI 미리보기는 PNG sequence 로 대체 가능
- 적용: `extract_indd.jsx` 의 PDF export 블록을 config flag 로 감싸기

#### A.3 페이지 범위 추출 (예상 -50~80% on partial workflows)
- 현재: `--page-range start:end` 가 ExtendScript 인자로 전달되지만 일부 함수만 사용 (e.g., 모든 페이지 PNG 가 렌더링됨)
- 제안: startPage/endPage 를 모든 export 루프에 강제 — `pi >= startPage && pi <= endPage` 게이트
- 적용: 25+ for-loop 에 페이지 게이트 추가
- 데스크탑 UI: "특정 페이지만 다시 추출" 버튼

#### A.4 Source ownership fully editable 모드에서 PNG 렌더 스킵 (예상 -10~20%)
- 단일 캡슐 배지 (예: "가" / "나") — Phase 3 가 INLINE_TEXT_FRAME 으로 처리 가능
- 다중 박스 배지 (ㅍㅎㅂㅅ) — Phase 3 가 tryInlineGroupAsBoxList 로 처리
- 노란 강조 도형 — Phase 3 가 floating ASTFigure 로 처리
- **현재**: 위 3 케이스 모두 ExtendScript 가 inline_NNN.png 를 만들지만 사용 안 함
- **제안**: ExtendScript 가 위 패턴 (Anchored+None Group, single-text-capsule-badge, multi-box-jamo-Group) 을 사전 감지 → PNG export 스킵

### Tier B — 중기 개선 (모더레이트 비용)

#### B.1 페이지 단위 batch PNG export (예상 -20%)
- 현재: 각 PageItem 마다 개별 `exportFile(PNG_FORMAT)` 호출 (25+ 함수에서 분산)
- ExtendScript exportFile 1회당 InDesign 내부 초기화/마무리 오버헤드가 큼
- 제안: 같은 페이지 내 모든 export 대상을 **임시 그룹**으로 묶어 단일 export 후 PNG 를 splitting (PIL/sips post-processing 으로 분할)
  - 또는 InDesign script 가 한 페이지 PDF 를 한 번만 export 한 뒤, post-script 가 PNG 로 변환
- 적용: 페이지별로 export 대상 수집 → 일괄 export

#### B.2 incremental 추출 캐시 (SPEC-011 확장) (예상 -100% on no-change runs)
- 현재: SPEC-011 의 추출 캐시는 INDD 파일 해시 단위 (전체 재추출 or 캐시 hit)
- 제안: **per-page** 캐시 (페이지 단위 SHA + per-page output)
  - 페이지가 변경되지 않으면 그 페이지의 PNG/resolved 항목 재사용
  - 한 페이지만 수정한 INDD 의 경우 1 페이지만 재추출
- 데이터 구조: `~/Library/Caches/idml-to-hwpx/extracts/<indd_sha>/page_<n>/` 디렉토리 + meta
- 적용: extract_indd.jsx 가 페이지별 SHA (page text + page items snapshot) 를 계산해 캐시 사용

#### B.3 resolved.json 슬림화 (예상 -30% on collectResolved + writeJson)
- 현재 ~10MB. 사용되지 않는 필드 다수:
  - `visibleBounds` (대부분 geometricBounds 와 동일)
  - `paragraphYOffsets` (resolved 가 IDML XML 보다 정확하지 않아 거의 미사용)
  - `composedLines` 의 일부 메타 필드
  - `paragraphStyles` 의 일부 nested 속성
- 제안: Java 측이 실제로 읽는 필드 목록을 추출 → ExtendScript 가 그 필드만 직렬화
- 적용: ResolvedData/Resolved\*.java 의 getter 호출 + writer 패턴 매칭

#### B.4 진행률 fine-grained reporting (예상 0% perf, UX 개선)
- 현재: 5개 매크로 단계 (idml/rendered_frames/resolved_items/pdf/done)
- 사용자가 "어떤 페이지에서 stuck 인지" 모름 → 600s/1800s stale timeout 으로 강제 중단
- 제안: 각 export 직전 `writeProgress("render", pageIdx, totalPages, itemDesc)`
- 추가: stale timer 를 phase 별로 차등 (idml: 60s, rendered_frames: 1800s, resolved: 300s)

### Tier C — 대규모 리팩토링 (고비용)

#### C.1 ExtendScript → Node + InDesign Server (또는 UXP)
- 현재: ExtendScript (구식 JavaScript, single-thread)
- 제안: Adobe InDesign Server / UXP (Node) 로 마이그레이션
  - 병렬 처리 가능
  - 더 좋은 IPC (stdout streaming vs file polling)
- 비용: InDesign Server 라이선스 / UXP 호환성 검토 / ExtendScript 코드 5500줄 마이그레이션
- 시기: 다른 개선이 한계에 도달했을 때

#### C.2 PNG 렌더링 우회 (vector → SVG → 직접 변환)
- 현재: InDesign 도형 → PNG raster (해상도 dependant)
- 제안: 도형을 SVG path 로 export → Java 측에서 HWPX shape primitive 로 직접 변환
- 비용: shape 변환 매핑 (Bezier → HWPX curve)
- 효과: PNG render 자체 제거 → 가장 큰 시간 소비점 제거

## 단계별 실행 플랜

### Phase 0 (완료/진행 중): 계측과 source-slot 기반 정리

1. `extract_indd.jsx`에 phase 별 elapsed ms 로깅을 추가했다.
2. `source_index.jsx`로 source tree query를 인덱스화했다.
3. `source_slot_registry.jsx`와 planner diagnostics를 추가해 source bundle/slot 단위 중복을 추적한다.
4. fast mode에서는 full diagnostics 파일 대신 `planner-diagnostics-summary.json`만 쓰도록 하여 대형 JSON 쓰기 비용을 줄였다.
5. source-set 단위 PNG cache를 도입해 같은 export payload의 재-export를 줄였다.

남은 문제:

- hide/export/restore 루프가 아직 source 후보마다 반복된다.
- `collectStories`는 여전히 전체 story/textStyleRanges를 무겁게 직렬화한다.
- desktop 쪽 완료 대기/PDF/후처리 시간이 추출 시간처럼 보이는 구간이 있다.

### Phase 1 (다음): 병목별 추가 개선

0. **eager TextFrame pre-classify 제거**
   - 이전: `_runRenderPhases`가 모든 `TextFrame`을 먼저 `classifyTextFrame()`으로
     분류한 뒤, `_buildSourceIndexFromAllItems()`가 다시 같은 정보를
     `classifyTextFrameCached()`로 소비했다.
   - 변경: Stage 0 source index가 필요한 순간 cache를 채우도록 하고,
     `03c_preClassify`는 lazy mode marker/stats만 남긴다.
   - 정책 의미: source metadata의 진실은 source index이며, 별도 pre-pass가
     ownership 결정을 선행하지 않는다.
   - 검증: `3-1 박영민 3단원` page 31 단일 추출에서
     `03c_preClassify`가 `167.497s`(전체 run 기준) 병목에서 `0.166s`로
     축소되었고 validation issue는 0건이다.

0.1. **page hash 중복 DOM 순회 제거**
   - 이전: `buildPageData()`가 `computePageHash(page)`에서
     `page.allPageItems`와 `page.textFrames`를 다시 순회한 뒤, 이미 수집한
     `allItems`를 다시 순회해 `page_item_map`과 hash를 갱신했다.
   - 변경: page hash와 item map은 Stage 0에서 이미 수집한 range-local
     `allItems`만 사용한다. TextFrame 내용 샘플은 해당 item이 page map에
     등록될 때 hash에 반영한다.
   - 정책 의미: 입력 인덱스는 한 번 만든 source set을 재사용하며, page hash
     생성을 위해 InDesign DOM을 다시 펼치지 않는다.
   - 검증: `3-1 박영민 3단원` physical page 31 단일 추출에서
     `03b_pageHashes_start → 03b_pageHashes_done`이 `90ms`,
     `03c_preClassify`가 `5ms`, validation issue는 0건이다.
   - 전체 검증: 같은 문서 전체 추출에서 validation issue는 0건이며,
     `03b_pageHashes_start → 03b_pageHashes_done`은 `1.093s`,
     `03c_preClassify`는 `1ms`이다.
   - 효과: 이전 전체 계측에서 `03c_preClassify`로 보이던 `92.428s` 병목은
     실제로 page hash 중복 순회였다. 수정 후 전체 추출 총 시간은
     `423.053s → 255.626s`로 감소했다.

0.2. **다음 병목 재정렬**
   - page hash/pre-classify 병목 제거 후 상위 병목은 다음 순서로 바뀌었다.
   - `10i_collectStories_done`: `33.587s`
   - `02_idml_export`: `18.230s`
   - `08_complexFrames`: `17.616s`
   - `10m_collectPageItems_done`: `11.491s`
   - `03g_writeExtractionPlan_done`: `9.301s`
   - 다음 최적화는 `collectStories` slim mode와 diagnostics JSON 쓰기 비용
     감소를 우선한다.

0.3. **collectStories 문자 보정 스캔 축소**
   - 이전: paragraph style에 `nestedGrepStyles` / `nestedStyles` /
     `nestedLineStyles`가 존재하기만 하면 모든 textStyleRange를 문자 단위로
     다시 스캔했다.
   - 문제: 상당수 GREP 스타일은 자간/폭 조정처럼 현재 TextFlow run에
     내보내지 않는 속성만 바꾼다. 또한 GREP 규칙이 있어도 현재 run text에
     해당 표현식의 명시적 trigger 문자가 없는 경우가 많았다.
   - 변경:
     - character style이 export 대상 run 속성(`fillColor`, `pointSize`,
       `fontFamily`, `fontStyle`)을 바꾸는 경우만 보정 후보로 본다.
     - GREP 보정 후보라도 run text가 해당 표현식의 명시적 대상 문자를
       포함하지 않으면 문자 단위 DOM 스캔을 생략한다.
     - 보정이 필요 없는 문단은 `splitRunByStoryChars()` 호출 자체를 생략한다.
   - 정책 의미: 텍스트 ownership이나 layer를 재판정하지 않고, Stage 0
     source style metadata로 TextFlow run 속성 추출 비용만 줄인다.
   - 검증: `3-1 박영민 3단원` 전체 추출에서 validation issue는 0건이고
     결과 수는 730개로 유지되었다.
   - 효과:
     - `correctionParagraphs`: `635 → 301`
     - `splitCalls`: `899 → 446`
     - `splitMs`: `26.983s → 7.479s`
     - `paragraphLoopMs`: `33.277s → 12.420s`
     - 전체 추출 총 시간: `255.626s → 228.113s`
   - 남은 병목:
     - `02_idml_export`: `17.262s`
     - `08_complexFrames`: `17.078s`
     - `10i_collectStories_done`: `13.502s`
     - `10m_collectPageItems_done`: `11.518s`
     - plan/object/source-slot JSON 쓰기 구간: 약 `20s+`

0.4. **full planner diagnostics 기본 비활성화**
   - 이전: `perfMode=standard/high`에서도 `source-clusters.json`,
     `planner-bundles.json`, `object-plans.json`, `source-slot-registry.json`을
     기본 생성했다.
   - 문제: 이 파일들은 Stage 1 정책 검수용 diagnostic artifact이며, 변환 실행
     권한은 `extraction-results.json`과 `resolved.json`에 있다. 기본 추출에서
     매번 대형 diagnostics를 쓰면 약 18~20초가 추가된다.
   - 변경:
     - 기본 `standard/high` 추출은 `planner-diagnostics-summary.json`만 쓴다.
     - full diagnostics는 `--diagnostics`, `diagnostics`, `args[13]=1`,
       `perfMode=diagnostics/debug`에서만 생성한다.
     - `extraction-plan.json`은 desktop cache/inspection 경로가 참조하므로
       계속 생성한다.
   - 정책 의미: diagnostics는 ownership source of truth가 아니다. 기본 실행은
     Stage 1 결정 결과만 실행하고, 자세한 감사 파일은 요청 시에만 남긴다.
   - 검증: `3-1 박영민 3단원` 전체 추출에서 validation issue는 0건이고
     결과 수는 730개로 유지되었다. 생성 파일은
     `extraction-plan.json` + `planner-diagnostics-summary.json`만 남는다.
   - 효과:
     - 전체 추출 총 시간: `228.113s → 209.687s`
     - 대형 diagnostic write 구간(`source-clusters`, `planner-bundles`,
       `object-plans`, `source-slot-registry`) 제거
   - 현재 상위 병목:
     - `02_idml_export`: `17.706s`
     - `08_complexFrames`: `17.623s`
     - `10i_collectStories_done`: `13.708s`
     - `10m_collectPageItems_done`: `10.967s`
     - `03g_writeExtractionPlan_done`: `8.919s`

0.5. **기본 extraction-plan 디스크 출력 slim화**
   - 이전: 메모리에서 실행에 사용하는 full `ExtractionPlan`을 그대로
     `extraction-plan.json`에 썼다.
   - 변경:
     - 실행 중 메모리 plan은 full field를 유지한다.
     - 기본 디스크 출력은 정책 필수/권장 source item field와 candidate 실행
       field 중심의 slim plan으로 기록한다.
     - full plan dump는 diagnostics 모드에서만 남긴다.
   - 정책 의미: extractor 실행은 이미 Stage 1 plan in-memory를 기준으로 끝났고,
     디스크 plan은 cache/inspection artifact다. 따라서 기본 파일은 정책 필수
     계약을 보존하되 불필요한 audit field는 생략한다.
   - 검증: `3-1 박영민 3단원` 전체 추출에서 validation issue는 0건이고
     결과 수는 730개로 유지되었다.
   - 효과:
     - `extraction-plan.json`: `4.8MB → 3.8MB`
     - `03g_writeExtractionPlan_done`: `8.919s → 7.833s`
     - 전체 추출 총 시간은 `209.687s → 209.282s`로 소폭 개선.
   - 판단: 파일 크기와 cache artifact 비용은 줄었지만 총 시간에는 큰 영향이
     없다. 다음 큰 병목은 `08_complexFrames`와 개별 PNG export batch화다.

1. **hide/restore batch화**
   - 현재: 이미지 후보마다 hide → export → restore 반복.
   - 변경: 같은 page/source-set export 후보를 pass별로 묶고, hide/restore 대상 계산을 한 번만 한다.
   - 금지: export 후 crop/drop/layer 재판정. batch는 실행 최적화일 뿐 ownership 변경이 아니다.
   - 기대 효과: `06_imgFrames` 내부 70초대 구간을 30~50% 절감.

2. **source-set PNG cache를 pass 공통 cache로 승격**
   - 현재: 일부 source-set cache hit가 있으나 pass별 export path가 여전히 다르다.
   - 변경: cache key를 `(document fingerprint, page fragment, exportSourceObjectIds, hiddenVisualSourceObjectIds, materialization, resolution)`으로 통일한다.
   - 같은 payload는 page background, decoration, complex frame 어느 pass에서 요청해도 같은 PNG를 재사용한다.
   - 기대 효과: 중복 shell/content가 많은 문서에서 개별 export 수 감소.

3. **collectStories 슬림 모드**
   - 현재: story 전체와 textStyleRanges를 광범위하게 직렬화한다.
   - 변경: Java converter가 실제로 읽는 field만 `resolved.json`에 쓴다.
   - TextFlow가 이미 paragraph/run/inline-slot을 별도 구조로 만들 수 있으므로, 원본 story dump는 fallback/debug 모드에서만 full로 남긴다.
   - 기대 효과: `collectStories` 30초 구간과 JSON write/read 비용 절감.

4. **desktop 완료 대기 구간 분리 계측**
   - 현재: `.done` 작성 이후 앱 쪽 PDF/cache/store/preview 시간이 추출 시간처럼 합산되어 보인다.
   - 변경: `indesign.rs`에서 `extract_done`, `crop_manifest`, `cache_store`, `preview_ready`, `command_done` 타임스탬프를 별도 기록한다.
   - 기대 효과: 실제 ExtendScript 병목과 desktop 후처리 병목을 분리한다.

5. **성능 회귀 검증 고정 세트**
   - 국어 3-1 박영민 1단원, 3단원
   - 고등문학 지도서 0총론, 1단원
   - 중3 과학 1단원
   - 각 케이스에서 export count, validation issue count, elapsed phase를 기록한다.

### Phase 2 (유지): 즉시 옵션형 개선

6. Tier A.1 (DPI 옵션) 유지 및 UI 노출 확인
7. Tier A.2 (PDF skip 옵션)과 preview fallback 분리
8. Tier A.3 (페이지 범위 게이트) — 모든 for-loop 에 startPage/endPage 적용
9. Tier A.4 fully editable 모드에서 미사용 PNG 렌더 스킵

### Phase 3 (3~5 일): 페이지 범위 + 캐시

10. Tier B.2 (per-page 캐시) 설계 + 프로토타입
11. 데스크탑 UI: 페이지 범위 / 빠른 모드 토글

### Phase 4 (1주+): Tier B 풀구현

12. Tier B.1 batch PNG export
13. Tier B.3 resolved.json 슬림화
14. Tier B.4 진행률 fine-grained

### Phase 5 (옵션): Tier C 대규모 작업
- 추후 필요 시

## 검증

- [ ] 실측 baseline (26p / 256p)
- [ ] Tier A.1~A.4 각각의 효과
- [ ] 누적 효과 (목표: 256p < 5분)
- [ ] 출력 품질 회귀 없음 (변환 결과 hwpx 비교)

## 위험

- DPI 다운: 인쇄 품질 회귀 (옵션화로 mitigation)
- PDF 스킵: 데스크탑 미리보기 UX 영향 (PNG sequence 대체)
- 페이지 범위: 페이지 간 의존성 (예: 연결 글상자) 깨질 가능 — Java 측 검증 강화 필요
- 캐시: 부분 갱신 후 일관성 문제 — 캐시 무효화 규칙 명확화

## 수정 파일 (예상)

```
scripts/extract_indd.jsx                  # 모든 Tier A/B 변경
conversion-config.json                    # DPI / PDF / 페이지범위 옵션
desktop/src-tauri/src/indesign.rs         # config 전달 + UX 옵션
desktop/src/components/ExtractOptions.tsx # UI 토글 (신규)
src/main/java/.../resolved/ResolvedDataReader.java  # 슬림화된 JSON 호환
```

## 관련 SPEC

- [SPEC-011](SPEC-011-extract-cache.md) (추출 캐시)
- [POLICY-source-ownership](POLICY-source-ownership.md) (텍스트/PNG ownership 기준)
