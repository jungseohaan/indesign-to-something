# Ambiguous `planner_declared_rendered` Plans On Split Master Fragments

## Status

Paused for later follow-up.

## Problem

Extractor side now preserves local source ownership for split master render fragments, but Stage 1 can still reintroduce a broad composite ownership contract.

The failure mode is:

1. one extraction candidate is split into multiple rendered fragments
2. each fragment has its own local `sourceObjectIds`
3. a preplanned `planner_declared_rendered:*` plan is imported for one fragment render id
4. that imported plan still carries the parent composite candidate's broad `sourceObjectIds` / shell intent
5. Stage 1 then also creates local rendered plans for sibling fragments
6. result: duplicate visible source-slot ownership, wrong plane assignment, or layer drift

This is not a page-specific bug. It is a candidate-to-rendered-fragment contract bug.

## Confirmed Evidence

### Extractor is already localizing fragment sources

After the latest extractor patch, direct master fragments keep local source ids.

Examples:

- `output/issues/중3-2국어교과서(박현숙)/u2-1/p082-20260711-140318/extract/resolved.json`
  - `master_2291_0_2_direct_0.png`
  - `sourceObjectIds: [18734, 18737, 18736]`
  - `slotRole: CONTENT_VISUAL_SLOT`

- `output/issues/중3-2국어교과서(박현숙)/u2-1/p083-20260711-140326/extract/resolved.json`
  - `master_2291_1_0_direct_0.png`
  - `sourceObjectIds: [2565, 2567, 2566]`
  - `slotRole: CONTENT_VISUAL_SLOT`
  - `textOwner: none`

### Stage 1 still revives a broad composite shell plan

In:

- `output/issues/중3-2국어교과서(박현숙)/u2-1/p083-20260711-140831/extract/ownership-plan.jsonl`

there is still a planner-declared rendered plan for:

- `rendered_frames/master_2291_1_0_direct_0.png`

with:

- `kind: planner_declared_rendered:pass.master_page_graphics:MASTER_ITEM`
- `candidateId: cand.pass.master_page_graphics.composite.page.17.src.2313.2567.n8.h841622607`
- `sourceObjectIds: [2313, 2314, 2517, 2537, 2559, 2565, 2566, 2567]`
- `visualAction: PLACE_TEXT_SHELL`
- `visualLayer: LABEL_BACKDROP`

That plan is broader than the actual rendered fragment and does not match the fragment's channel.

### The resulting warnings prove duplicate ownership

In:

- `output/issues/중3-2국어교과서(박현숙)/u2-1/p083-20260711-140831/extract/ownership-warnings.jsonl`

we get:

- `DUPLICATE_VISIBLE_SOURCE_SLOT_REPAIR_SUPPRESSED`
- `DUPLICATE_VISIBLE_SOURCE`
- `STAGE4_RENDERED_PLAN_ARTIFACT_MISMATCH`

This means the pipeline still has both:

- a broad planner-declared shell owner
- exact local rendered fragment owners

for overlapping visible sources.

## What Was Already Changed

### Extractor

File:

- `scripts/indd/render_master_graphics.jsx`

Change:

- direct master child fragments now emit their own local `sourceObjectIds` / `exportSourceObjectIds`
- parent composite candidate provenance is still kept for validation

### Build-context lookup guard

File:

- `converter/src/main/java/kr/dogfoot/hwpxlib/tool/idmlconverter/normalizer/resolved/ResolvedBuildContext.java`

Change:

- planner-declared rendered plans are rejected for lookup when rendered slot channel and planned slot channel differ
- planner-declared rendered plans are also rejected for `CONTENT` fragments when the rendered source set is a strict subset of the planned visual/source set

This helps downstream lookup, but it does **not** fix the Stage 1 ownership-plan itself.

## Root Cause

`OwnershipPlanner` still treats imported `planner_declared_rendered:*` plans as valid preplanned contracts even when:

- the same `candidateId` maps to multiple rendered fragments
- the imported plan is attached to one fragment's `renderId`
- but its source/slot contract still describes the whole composite candidate

So Stage 1 is mixing two levels of truth:

- candidate-level ownership
- rendered-fragment-level ownership

Those must not stay visible at the same time.

## Correct Fix Direction

The fix should stay general and source-metadata-driven.

### Rule

When a `planner_declared_rendered:*` plan has a `candidateId` shared by multiple rendered fragments:

1. bind the imported plan to the exact rendered fragment, not to the broad candidate
2. if the imported plan channel disagrees with the exact rendered fragment channel, the imported plan must not stay as the visible owner for that fragment
3. only one visible owner per slot may remain for each fragment-local source set

### Recommended implementation point

File:

- `converter/src/main/java/kr/dogfoot/hwpxlib/tool/idmlconverter/normalizer/resolved/ownership/OwnershipPlanner.java`

Likely touch points:

- `importPreplannedObjectPlans()`
- `canonicalizeImportedPreplannedObjectPlan(...)`
- `hasPreplannedRenderedPlan(...)`
- possibly a dedicated normalization pass immediately after `planRenderedItems()`

### Safer implementation strategy

For ambiguous candidate ids:

- resolve the exact `RenderedGroup` by `pageIndex + renderId + candidateId/file`
- compare:
  - planned slot channel
  - rendered slot channel
  - planned source set
  - fragment-local rendered source set

Then:

- if compatible, localize the imported plan to the fragment-local source contract
- if incompatible, do not let that imported plan suppress the fragment-local rendered plan

## Important Constraints

- no page-specific exception
- no text literal, color, or coordinate exception
- no late visual symptom patching
- use only source metadata, rendered fragment metadata, candidate/render-unit identity, and Stage 1 object-plan rules

## Expected Success Condition

For split master candidates:

- no visible broad composite shell plan remains on a content fragment
- no duplicate visible source-slot warnings remain for the same fragment-local sources
- same-plane order still comes from source metadata and survives to HWPX placement

## Resume Checklist

1. inspect `OwnershipPlanner.canonicalizeImportedPreplannedObjectPlan(...)`
2. make ambiguous planner-declared rendered plans fragment-aware
3. ensure `hasPreplannedRenderedPlan(...)` only suppresses local rendered plan creation when the imported plan is executable for that exact fragment
4. rerun:
   - `python3 scripts/dev/issue.py --case '중3-2국어교과서(박현숙)/u2-1' --page 82 --skip-pdf --no-open`
   - `python3 scripts/dev/issue.py --case '중3-2국어교과서(박현숙)/u2-1' --page 83 --skip-pdf --no-open`
5. confirm `ownership-warnings.jsonl` no longer contains the duplicate/mismatch warnings above
