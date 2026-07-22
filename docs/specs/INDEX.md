# SPEC Index

> Last synced: 2026-07-04.
> This index is organized for V2 source ownership development. Historical SPECs
> are preserved for audit, but they are not policy unless this file marks them
> as canonical or active.

## V2 Development Reading Order

Read these first for IDML -> HWPX V2 ownership work:

| Doc | Status | Use |
|---|---|---|
| [POLICY-source-ownership](POLICY-source-ownership.md) | Canonical | Stage 1/ObjectPlan ownership contract and V2 stage boundaries |
| [docs/policy/](../policy/README.md) | Canonical modules | Complete source coverage, bundle, slot, owner, render unit, text, shell, table, layer, executor, validation rules |
| [POLICY-extraction-planning](POLICY-extraction-planning.md) | Canonical for extraction | InDesign extraction planning, extraction facts, and Java ownership handoff |
| [SPEC-037](SPEC-037-issue-debug-cycle-acceleration.md) | Active support | Faster page issue loop, ObjectPlan tracing, regression audit workflow |
| [SPEC-038](SPEC-038-extract-indd-modularization-map.md) | Active support | `extract_indd.jsx` modularization map and removal of legacy extraction paths |
| [SPEC-041](SPEC-041-anchored-edge-label-floating.md) | Active | 앵커 가장자리 라벨(구절 풀이 배지) 테이블 셀 흡수 — 컨버터 오버레이 채널 보존으로 수정 (구현 완료, 육안 확인 대기) |
| [SPEC-048](SPEC-048-floating-anchored-gauge-inline-misplacement.md) | Active | FLOATING_ANCHORED 게이지(눈금+100%+연필)의 인라인 오배치 — 원인 규명 완료·수정 미완결(추출기 다중 seed 통일 필요) |
| [SPEC-044](SPEC-044-blank-paren-spacer.md) | Active | 괄호 빈칸 스페이서(빈 답란 Rectangle) → NBSP 고정폭 공백 치환 (구현 완료, 육안 확인 대기) |
| [SPEC-045](SPEC-045-coefficient-subscript-bleed.md) | Active | 화학 반응식 계수 첨자 오염(resolved 첨자 위치 흘림) — IDML 비첨자 증거 우선 (구현 완료, 육안 확인 대기) |
| [SPEC-046](SPEC-046-formula-textrange-shell-misfire.md) | Active | 화학식 첫 글자의 text-range-shell 오분리 — 화학식 문단 range 분리 제외 (구현 완료, 육안 확인 대기) |
| [SPEC-047](SPEC-047-inline-formula-body-textrange-split.md) | Active | 문장 중 화학식 진입이 앞 한글 본문을 text-range-shell 로 오분리(말풍선 배경 누락) — 추출기 화학식 폰트 진입 경계 제외 (구현 완료, 육안 확인 대기) |

For V2 ownership changes, start from the canonical policies above. Historical
ownership SPECs have been removed so they cannot be reused as implementation
guidance.

## Active Non-Ownership Work

These may affect the product, but they are not ownership policy. Check them only
when touching the related subsystem.

| SPEC | Area | Note |
|---|---|---|
| [SPEC-012](SPEC-012-resolved-priority-unified.md) | text styling | resolved -> style priority path |
| [SPEC-014](SPEC-014-font-auto-mapping.md) | fonts | font mapping automation |
| [SPEC-015](SPEC-015-ast-debug-visibility.md) | debugging UI | AST provenance and page visualization |
| [SPEC-018](SPEC-018-semantic-extraction.md) | semantic/PPTX | semantic layer and PPTX output |
| [SPEC-030](SPEC-030-indesign-extraction-performance.md) | performance | extraction timing and caching direction |
| [SPEC-054](SPEC-054-dev-content-extract-modes.md) | dev workflow | 개발용 content-mode (graphic-only/text-only) + issue.py 96dpi 기본 (Active) |
| [SPEC-055](SPEC-055-chem-formula-hancom-equation.md) | equations | 화학식 전면 한컴 수식(hp:equation) 변환 — 출력 강등 제거 + 첨자 문자속성 커버리지 (Active, 계획) |
| [SPEC-032](SPEC-032-windows-cross-platform.md) | desktop platform | Windows support |
| [SPEC-034](SPEC-034-llm-semantic-extraction.md) | semantic/LLM | LLM assisted content extraction |
| [SPEC-desktop-app](SPEC-desktop-app.md) | desktop app | Tauri app architecture |
| [SPEC-markdown](SPEC-markdown-export.md) | export | AST -> Markdown export |

## Done / Historical

These are retained for audit or subsystem history. They should not drive V2
ownership decisions.

| SPEC | Area |
|---|---|
| [SPEC-002](SPEC-002-large-file-split.md) | code organization |
| [SPEC-007](SPEC-007-font-mapping-improvement.md) | fonts |
| [SPEC-010](SPEC-010-pipeline-simplification.md) | old pipeline simplification |
| [SPEC-011](SPEC-011-extract-cache.md) | extraction cache |
| [SPEC-013](SPEC-013-resolved-builder-modularize.md) | builder modularization |
| [SPEC-016](SPEC-016-resolved-selective-override.md) | resolved overrides |
| [SPEC-017](SPEC-017-table-quality-gate-v2.md) | table quality gate |
| [SPEC-019](SPEC-019-desktop-menu-restructure.md) | desktop menu |
| [SPEC-024](SPEC-024-rulebelow-idml-doc-null.md) | bug fix |
| [SPEC-031](SPEC-031-dsl-rule-engine.md) | DSL rules engine |
| [SPEC-idml-free-pipeline](SPEC-idml-free-pipeline.md) | old hybrid pipeline entry |
| [SPEC-pdf-export](SPEC-pdf-export.md) | PDF preview |
| [SPEC-040](SPEC-040-eh-equation-converter-rewrite.md) | EH 수식 변환기 재작성 (Lexer/Parser/Emitter, 재귀하강 근호 문법) |
| [SPEC-042](SPEC-042-chemical-formula-run-style.md) | 화학식 런 스타일 보존 — 크기·첨자·색상·반응식 박스 (PR #41 머지, 2026-07-19). 숫자 런 속성 선주입 금지·CharPr 캐시 키 제약은 본문 참조 |

## Semantic / Adjacent Drafts

These are outside V2 ownership unless the user explicitly asks for semantic
extraction work.

| SPEC | Area |
|---|---|
| [SPEC-Semantic Block Discovery Integration](SPEC-Semantic%20Block%20Discovery%20Integration.md) | semantic block discovery |

## Maintenance Rules

- New V2 ownership behavior must be added to `POLICY-source-ownership.md` or one
  of the `docs/policy/` modules first.
- Page-specific issue notes must not define page/text/coordinate/color
  ownership exceptions.
- Do not add links from V2 ownership policy to removed ownership SPECs. If a
  deleted legacy note seems necessary, express the source-owned rule in
  `POLICY-source-ownership.md` or `docs/policy/` instead.
