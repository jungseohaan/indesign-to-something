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
| [SPEC-061](SPEC-061-hwpx-structural-golden-gate.md) | Active support | HWPX 구조 시그니처 골든 게이트 — verify_hwpx.py + test-data/golden, PR 전 회귀 비교 관례 |
| [SPEC-067](SPEC-067-grep-normalization-and-math-grouping.md) | Active | GREP 변환 정상화 + 수식 그룹 경계 재설계. **수정 A(GREP BasedOn 상속, #131)·수정 B(DOM 글자속성 차단, #134) 완료** — DOM 글자속성 11,396→0, 텍스트 수집 -52%. 남은 것: 수식 그룹 경계 재설계(수학 조각화) |
| [SPEC-068](SPEC-068-page-plane-foreign-visual-owner-hide.md) | Done | 페이지 배경 평면에 남는 도형 중복 — 소유권 게이트와 숨김 게이트 분리, `hiddenForeignVisualOwnerSourceObjectIds` 채널 추가 (#140 머지, 20p 회귀 PASS) |
| [SPEC-069](SPEC-069-bleeding-shape-page-plane-absorb.md) | Done | 도련(bleed) 도형이 작게·빈틈 있게 배치 — 개별 PNG 로는 불가(한글 음수 오프셋 미지원), textless 도련 도형을 배경 평면에 흡수 (#142 머지, 20p 회귀 PASS) |
| [SPEC-070](SPEC-070-table-frame-hastext.md) | Done | 표에 담긴 본문이 셸 PNG 로 구워짐 — hasText 가 표 셀 텍스트 미인식 + contentOpacity 가 표에 미적용 (#142 머지, 20p 회귀 PASS) |
| [SPEC-072](SPEC-072-cell-char-attr-investigation.md) | Done | 표 셀 글자속성(색·폰트) 복원 — 파서에 IDML 명시/상속 구분 플래그 추가 후 명시값만 주입 (#144 머지). 선행 실패 SPEC-071 의 원인 분석 포함 |
| [SPEC-073](SPEC-073-text-wrap-shell-gap.md) | Active | 라벨 셸과 본문이 겹침(영어 u1 p12 Listen & Number) — plan 이 감싸기를 버리던 것은 수정, **Java floating 경로가 `allowOverlap=true`로 무력화하는 본질은 미해결** |
| [SPEC-074](SPEC-074-image-textless-group-render-missing.md) | Active | Group 으로 묶인 배치 이미지 유실(영어 u1 p13 동영상 사진) — plan 이 참조하는 `pass.image_textless_groups` 에 렌더 실행기가 미등록. pass 등록으로 수정 (구현 완료, 회귀 검증 중) |
| [SPEC-075](SPEC-075-graphic-short-text-button-group-complete-png.md) | Done | 그래픽+짧은텍스트 버튼/배지 그룹 통PNG(영어 u1 p16 YES/NO 글자 안 보임·줄바꿈) — 편집 셸 대신 그룹째 통PNG. 짧은TF≥2+그래픽+긴TF<3 게이트 (#149 머지, 회귀 PASS 10개 정확 전환, 2026-07-25 한글 육안 확인 완료) |
| [SPEC-041](SPEC-041-anchored-edge-label-floating.md) | Active | 앵커 가장자리 라벨(구절 풀이 배지) 테이블 셀 흡수 — 컨버터 오버레이 채널 보존으로 수정 (구현 완료, 육안 확인 대기) |
| [SPEC-048](SPEC-048-floating-anchored-gauge-inline-misplacement.md) | Done | FLOATING_ANCHORED 게이지(눈금+100%+연필)의 인라인 오배치 — 추출기 3-seed 통짜PNG 통일 + FLOATING/PAGE 배치 (2026-07-22 한글 육안 확인 완료) |
| [SPEC-056](SPEC-056-cell-answer-blank-inline-loss.md) | Done | 표 셀 답란(연필+밑줄) 인라인 객체 유실 — 스페이서 오판·□ 삼킴·fallback 순서 손실 3중 수정 (2026-07-22 한글 육안 확인 완료) |
| [SPEC-057](SPEC-057-labeled-figure-inline-complete-png.md) | Done | 라벨 달린 삽화 그룹 시각 유실·라벨 유령 여백 — 인라인 통짜 PNG 승격 + 셸 여백 단위 정합 (2026-07-23 한글 육안 확인 완료) |
| [SPEC-058](SPEC-058-equation-font-box-formula.md) | Done | 수식 폰트 박스 반응식(2H₂+O₂→2H₂O) 통짜 hp:equation 승격 — BT/EH 교차 그룹핑 프리패스 + equation-only 셸 재주입 차단 (2026-07-23 골든 +2 수식 검증) |
| [SPEC-044](SPEC-044-blank-paren-spacer.md) | Active | 괄호 빈칸 스페이서(빈 답란 Rectangle) → NBSP 고정폭 공백 치환 (구현 완료, 육안 확인 대기) |
| [SPEC-045](SPEC-045-coefficient-subscript-bleed.md) | Active | 화학 반응식 계수 첨자 오염(resolved 첨자 위치 흘림) — IDML 비첨자 증거 우선 (구현 완료, 육안 확인 대기) |
| [SPEC-046](SPEC-046-formula-textrange-shell-misfire.md) | Active | 화학식 첫 글자의 text-range-shell 오분리 — 화학식 문단 range 분리 제외 (구현 완료, 육안 확인 대기) |
| [SPEC-047](SPEC-047-inline-formula-body-textrange-split.md) | Active | 문장 중 화학식 진입이 앞 한글 본문을 text-range-shell 로 오분리(말풍선 배경 누락) — 추출기 화학식 폰트 진입 경계 제외 (구현 완료, 육안 확인 대기) |
| [SPEC-059](SPEC-059-reaction-equation-lhs-truncation.md) | Done | 반응식 좌변 잘림(#107 소유 전환으로 노출) — 화살표 시작 클러스터의 좌변 흡수 (2026-07-23 기준선 대조 검증) |
| [SPEC-060](SPEC-060-nested-table-style-supersede.md) | Done | 중첩 데이터 표 스타일 흡수 소실(#93 포괄 supersede) — stage0 대체 억제를 per-plan 커버리지 검사로 (2026-07-23 육안 확인 완료) |
| [SPEC-062](SPEC-062-sibling-shell-bounds-multi-claim.md) | Done | p16-17 수식 토큰 겹침 — 형제 셸 bounds 치환을 단독 점유로 제한 + 셸 치환 블록 수직 중앙 정렬 (2026-07-23 육안 확인 완료) |
| [SPEC-064](SPEC-064-blank-rule-underline-loss.md) | Done | 답란 밑줄 빈칸(RuleBelow) 3중 유실(plan 게이트·ruleBelowOn 상속·가드 오탐) — 영어 u1 p22, 빈칸 22곳 복구 (2026-07-23 육안 확인 완료) |
| [SPEC-065](SPEC-065-char-style-color-authority.md) | Done | 문자 스타일 색이 resolved DOM 본문색 오보고에 덮임 — 영어 u1 p15 @색자, IDML 색 권위화 (2026-07-23 골든 PASS) |
| [SPEC-066](SPEC-066-prose-blank-false-equation.md) | Done | 답란 빈칸(□)이 낀 영어 산문이 통째로 HWP 수식화 — 산문 판정 가드 추가 (2026-07-23 육안 확인 완료) |

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
