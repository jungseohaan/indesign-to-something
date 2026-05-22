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

## 개선 영역

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

#### A.4 SPEC-025 fully editable 모드에서 PNG 렌더 스킵 (예상 -10~20%)
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

### Phase 1 (1~2 일): 측정 + 즉시 적용
1. `extract_indd.jsx` 에 phase 별 elapsed ms 로깅 추가
2. 기준 측정: 26페이지 / 256페이지 두 케이스
3. Tier A.1 (DPI 옵션) 구현
4. Tier A.2 (PDF skip 옵션) 구현
5. 재측정 → 효과 검증

### Phase 2 (3~5 일): 페이지 범위 + 캐시
6. Tier A.3 (페이지 범위 게이트) — 모든 for-loop 에 startPage/endPage 적용
7. Tier B.2 (per-page 캐시) 설계 + 프로토타입
8. 데스크탑 UI: 페이지 범위 / 빠른 모드 토글

### Phase 3 (1주+): Tier B 풀구현
9. Tier B.1 batch PNG export
10. Tier B.3 resolved.json 슬림화
11. Tier B.4 진행률 fine-grained

### Phase 4 (옵션): Tier C 대규모 작업
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
- [SPEC-025](SPEC-025-text-image-rendering-removal.md) (텍스트 이미지 렌더링 제거 — A.4 의 기반)
