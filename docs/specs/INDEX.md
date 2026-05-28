# SPEC 인덱스

> SPEC 문서들의 상태별 인덱스. 새 SPEC 시작/완료 시 이 파일을 갱신한다.
> 최종 동기화: 2026-05-20

## Active / WIP (작업 중)

이 SPEC들은 현재 코드에 영향 중이며, 신규 변경 시 충돌 가능성 검토 필요.

| SPEC | 제목 | 비고 |
|------|------|------|
| [SPEC-012](SPEC-012-resolved-priority-unified.md) | Resolved 속성 적용 경로 통합 | resolved → CharacterStyle → ParagraphStyle 단일 우선순위 |
| [SPEC-014](SPEC-014-font-auto-mapping.md) | 폰트 매핑 자동화 | 미매핑 폰트 후보 자동 추천 |
| [SPEC-015](SPEC-015-ast-debug-visibility.md) | AST 디버깅 가시성 강화 | `createdAt`/`appliedFrom` 메타 + 페이지 좌표 시각화 |
| [SPEC-018](SPEC-018-semantic-extraction.md) | 시멘틱 레이어 통합 | Java + TS 백엔드 통합, PPTX 출력. M3 진행 중 |
| **[SPEC-025](SPEC-025-text-image-rendering-removal.md)** | 텍스트 이미지 렌더링 제거 | **2026-05-22 검증**: A.5(masterPage) ❌ / B(rotation) ❌ 미작동. ExtendScript `classifyTextFrame` 조건 5 + `isRenderableTextFrame` 보강 필요 |
| **[SPEC-027](SPEC-027-badge-scribble-outline-png.md)** | 배지 scribble 외곽선 PNG 폴백 | **신규(2026-05-20)**. 일러스트 톤 배지 외곽선만 PNG, 텍스트는 HWPX. 데이터 조사 단계 |
| **[SPEC-028](SPEC-028-inline-anchored-group-duplicate.md)** | 인라인 앵커 Group 중복 렌더링 | **신규(2026-05-21)**. 타이틀 ③ 가 floating + 작은 inline PIC 두 번 렌더 — page 46 타이틀 줄바꿈 회귀 |
| **[SPEC-029](SPEC-029-page-30-31-merged.md)** | 페이지 30/31 합쳐져 변환 | **신규(2026-05-21)**. XML 구조상 페이지 분리 정상이나 시각 결과는 합쳐 보임. 데이터 조사 필요 |
| **[SPEC-030](SPEC-030-indesign-extraction-performance.md)** | InDesign 추출 속도 개선 | **신규(2026-05-22)**. P0. 4단계 플랜 (측정 → Tier A 즉시 → 페이지캐시/range → 대규모 리팩토링) |
| [SPEC-desktop-app](SPEC-desktop-app.md) | Desktop App 아키텍처 | Tauri 2.0 + React 18 — 상시 참조 |
| **[SPEC-032](SPEC-032-windows-cross-platform.md)** | Windows 크로스 플랫폼 지원 | **신규(2026-05-27)**. osascript→PowerShell COM, sips→image crate, Java/open 분기. Draft |

## Pending (제안/검토 대기)

구현 대기 또는 우선순위 미정.

| SPEC | 제목 | 요약 |
|------|------|------|
| [SPEC-002](SPEC-002-large-file-split.md) | 대형 파일 분할 | 1000+ LOC 모듈화 (W3/W4로 부분 진행됨) |
| [SPEC-003](SPEC-003-zorder-semantic-layers.md) | Z-Order 시멘틱 레이어 분류 | 이진 분류 → 다층 semantic layer |
| [SPEC-004](SPEC-004-textwrap-frame-split.md) | TextWrap 기반 본문 프레임 분할 | 겹치는 객체 피해 텍스트 흐름. `phase5/WrapPhase5`와 직결 |
| [SPEC-005](SPEC-005-composed-lines-extraction.md) | 조판 결과 추출 기반 텍스트 배치 | composedLines로 정확 레이아웃 |
| [SPEC-006](SPEC-006-table-in-textframe.md) | 테이블 포함 TextFrame editable | 테이블 셀 텍스트 편집 가능화 |
| [SPEC-007](SPEC-007-font-mapping-improvement.md) | 폰트 매핑 개선 | 메트릭 자동 비교 + 카테고리 분류 |
| [SPEC-008](SPEC-008-badge-group-rendering.md) | 배지 그룹 통합 렌더링 | 개별 PNG → 그룹 단일 PNG |
| [SPEC-009](SPEC-009-facing-pages-coordinate.md) | Facing Pages 좌표 보정 | parentPage null / 페이지 인덱스 |
| [SPEC-010](SPEC-010-pipeline-simplification.md) | 변환 파이프라인 단순화 | resolved 경로 유일화 |
| [SPEC-text-rendering](SPEC-text-rendering.md) | 텍스트 프레임 렌더링 추출 | 회전/TextPath/장식 PNG (SPEC-025로 이어짐) |

## Done (완료, 참고용)

이미 코드에 반영된 SPEC들. 회귀 방지 차원에서 보존.

| SPEC | 제목 | 완료 시점 |
|------|------|----------|
| [SPEC-011](SPEC-011-extract-cache.md) | ExtendScript 추출 캐싱 | 동일 INDD 재추출 0초 |
| [SPEC-013](SPEC-013-resolved-builder-modularize.md) | ResolvedToASTBuilder Phase 모듈 분리 | Phase 0~7 + sub-module |
| [SPEC-016](SPEC-016-resolved-selective-override.md) | 고신뢰 매칭 기반 선택적 오버라이드 | MatchConfidence |
| [SPEC-017](SPEC-017-table-quality-gate-v2.md) | 테이블 셀 품질 게이트 v2 | Phase 4 게이트 |
| [SPEC-019](SPEC-019-desktop-menu-restructure.md) | 데스크탑 메뉴 재구성 | 시멘틱 워크플로우 단축키 |
| [SPEC-020](SPEC-020-empty-container-png-preserve.md) | 인라인 PNG 누락 | `` 앵커 마커 + 빈 컨테이너 보존 |
| [SPEC-021](SPEC-021-nested-badge-extraction.md) | 중첩 뱃지 추출 | Group 내 Oval+TextFrame 서브패턴 |
| [SPEC-022](SPEC-022-overlapping-decoration-merge.md) | 뱃지 위 데코 병합 | Polygon 외곽선 + 뱃지 합성 |
| [SPEC-023](SPEC-023-renderable-text-bg-merge.md) | 외곽선 텍스트 배지 배경 병합 | TextFrame 외곽선 + 라운드 |
| [SPEC-024](SPEC-024-rulebelow-idml-doc-null.md) | RuleBelow 감지 실패 | lazy IDMLDocument 초기화 순서 |
| [SPEC-031](SPEC-031-dsl-rule-engine.md) | Java-Kotlin 하이브리드 DSL 규칙 엔진 | 2026-05-27. ConversionRules.kt 단일 파일로 서식 규칙 정의 |
| [SPEC-badge-extraction](SPEC-badge-extraction.md) | 배지 추출/변환 (초기 안) | SPEC-021/022로 진화 |
| [SPEC-idml-free-pipeline](SPEC-idml-free-pipeline.md) | 하이브리드 파이프라인 (초기 안) | 신 파이프라인 진입점 |
| [SPEC-pdf-export](SPEC-pdf-export.md) | PDF 프리뷰 생성 | 링크 재연결 + 고해상도 |

## Archived (폐기 / 다른 SPEC에 흡수)

`docs/specs/archived/`에 보존. 신규 작업 영향 없음.

| SPEC | 제목 | 사유 |
|------|------|------|
| [SPEC-004-semantic](archived/SPEC-004-semantic-layer-architecture.md) | Semantic Layer Architecture | SPEC-018에 흡수 (2026-05-20). SPEC-004 번호는 textwrap-frame-split이 차지 |

## 운영 규칙

- **새 SPEC 시작**: 이 파일의 **Pending** 또는 **Active**에 추가. 다음 비어있는 번호 사용 (현재 사용 가능: SPEC-026, SPEC-028~)
- **SPEC 완료**: **Done**으로 이동, [CLAUDE.md "활성 작업"](../../CLAUDE.md#활성-작업-2026-05-20-기준) 갱신
- **SPEC 폐기**: 본문 상단에 "Archived" 표기 + 파일을 `docs/specs/archived/`로 `git mv`, 이 인덱스 Archived 섹션에 등록
