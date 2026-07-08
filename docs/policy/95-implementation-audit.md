# Source Ownership Policy: Implementation Audit Map

> This file is part of the canonical source ownership policy.
> The canonical index is `docs/specs/POLICY-source-ownership.md`.

## 14. Code Path Audit

This map records the code paths that may touch source ownership. The audit
status is policy-oriented: `conforming` means the path executes Stage 1 facts;
`migration bridge` means the path is allowed temporarily but must not create a
new visible owner; `cleanup target` means the path still contains legacy logic
that must be removed or guarded by validation.

| Path | Stage | Status | Ownership rule |
|---|---|---|---|
| `scripts/indd/extraction_plan_builder.jsx` | E1 / Stage 0-1 input | migration bridge | Creates extraction candidates and source facts. It may prepare candidate material, but final source-slot ownership belongs to ObjectPlan. |
| `scripts/indd/planner_bundles.jsx` | Stage 1 | conforming target | Converts candidates/source clusters into bundle-slot facts. Inline visual candidates with no text ownership use `CONTENT_VISUAL_SLOT`; text shells use `SHELL_SLOT`. |
| `scripts/indd/object_plans.jsx` | Stage 1 | conforming target | Writes the executable ObjectPlan contract. `inlineAnchorSourceObjectId` may affect placement only when `sourceInlineFlow=true`. |
| `scripts/indd/source_slot_registry.jsx` | Stage 1 validation | conforming target | Checks slot identity and duplicate ownership. It must validate visible slot closure, not just direct ids. |
| `scripts/indd/extraction_orchestrator.jsx` | E2 coordinator | migration bridge | Executes only planned passes and passes planned candidate lists into renderers. It must not create replacement candidates. |
| `scripts/indd/render_inline_objects.jsx` | E2 executor | migration bridge | Executes `pass.inline_objects` candidates. It must preserve `INLINE` / `STORY_FLOW` ownership and never recreate missing anchors by scanning stories after planning. |
| `scripts/indd/render_master_graphics.jsx` | E2 executor | migration bridge | Executes applied-master page/floating candidates. Master-origin story-flow inline material may use a master direct-export file only when the result is stamped back to the inline candidate. |
| `scripts/indd/render_groups.jsx` / decoration group helpers | E2 executor | migration bridge | Executes planned textless image groups. It must not use overlap, color, or occlusion to decide visible ownership after planning. |
| `scripts/indd/render_images.jsx` | E2 executor | migration bridge | Executes planned placed-image/frame candidates. Clip-carrying parents, not raw child images, own clipped visible slots. |
| `converter/.../ownership/OwnershipPlanner.java` | Stage 1 / Java bridge | cleanup target | Consumes ObjectPlan and applies remaining Java normalization. It may validate and execute planner-declared facts, but bridge-added or bridge-mutated ownership is a planning defect. |
| `converter/.../ownership/OwnershipPlanValidator.java` | Stage 4 | conforming target | Must fail duplicate visible slots, inline/floating duplicates, source-less visible visuals, and visible master owner for story-flow inline source slots. |
| `converter/.../ResolvedBuildContext.java` | Stage 2-3 lookup | cleanup target | Must resolve rendered material by exact plan/source-slot identity. Alternate placement/source-only fallback lookups are not V2 ownership policy. |
| `converter/.../phase4/TableBuilder.java` | Stage 2-3 table executor bridge | cleanup target | Must execute Stage 1 table-structure ownership only. Same-story nested tables stay inside the parent HWPX cell, and wrapper/nested table relationships must not reappear as separate page-level tables or PNG fallback. |
| `converter/.../InlineFrameHandler.java` | Stage 2 text executor | cleanup target | Must execute planned inline anchors only. It must not turn floating/page material into inline material or synthesize missing inline owners. |
| `converter/.../FramePlacer.java` and visual placement helpers | Stage 3 visual executor | cleanup target | Must place planned visuals only and map HWPX plane from ObjectPlan. It must not repair z-depth from bounds, pixels, or observed overlap. |
| `converter/.../VisualShellPreparationRules.java` | Stage 3 image prep | cleanup target | May prepare planned textless material, but must not decide shell/background/content ownership from visual symptoms. |
| `converter/.../HwpxImageBuilder.java` / `ASTToHwpxConverter.java` | Stage 3 writer | cleanup target | Must preserve Stage 1 placement/layer/z-order. It may translate to HWPX primitives but must not create a new foreground/background role band. |

## 15. Current Known Policy Checks

- ObjectPlan validation is a pre-render gate in `scripts/indd/extraction_orchestrator.jsx`.
- Extraction result validation rejects unplanned or mismatched result rows before
  HWPX conversion.
- `pass.master_page_graphics` receives both master and inline candidates so it
  can route master direct-export bytes back to the inline owner when the source
  slot is story-flow inline.
- `OwnershipPlanner.isStoryFlowInlineVisualMaterialSlot` excludes master-page
  plans from late inline normalization. Master-origin inline material must be
  declared as inline before this Java path, not inferred afterward.

## 16. Remaining Cleanup Targets

- Remove or hard-fail any Java legacy bridge path that adds or mutates
  ObjectPlans instead of reporting a planning defect.
- Remove alternate plan lookup strategies that search by source-only,
  inline/floating alternates, overlap score, or file existence after Stage 1.
- Replace any executor-local foreground/background role lists with the
  canonical three-strata mapping:
  `BACKGROUND_GRAPHIC < TEXTLESS_IMAGE_GROUP < TEXT_TABLE_STRUCTURE`.
- Promote duplicate/missing source-slot warnings to blocking validation in
  dev/test conversion paths.
- Keep master grouping limited to page/floating master fragments. Story-flow
  inline material authored on a master spread stays inline.
