# SPEC-035: IDML Render Ownership Migration Note

> Status: Migration note.
> Canonical policy: `POLICY-source-ownership.md`.

This document keeps only the migration boundary for the old render pipeline.
When this file conflicts with `POLICY-source-ownership.md`, the policy wins.

## Problem

The converter mixes two output families.

- InDesign-rendered visual material preserves appearance.
- HWPX text, table, and shape objects preserve editability and searchability.

The repeated regressions came from allowing later phases to decide again which
family owns the same source object. The migration goal is to make ownership a
single Stage 1 decision and make every later phase an executor.

## Target Stage Model

| Stage | Name | Responsibility | Must Not Do |
|---|---|---|---|
| 0 | Input Prepare | Build IDML, resolved, style, page, layer, and source indexes. | Decide visible output. |
| 1 | Ownership Planner | Decide source bundle slots, text owner, visual owner, placement, layer, and materialization. | Build AST output. |
| 2 | Text Builder | Execute text/table parts of `ObjectPlan`. | Reinterpret PNG placement or text ownership. |
| 3 | Visual Builder | Execute visual parts of `ObjectPlan`. | Reinterpret text ownership or create new shell/style owners. |
| 4 | Validate | Check invariants and report violations. | Create or repair ownership. |

Legacy phase classes may remain temporarily, but only as bridges that execute
`ObjectPlan`. They must not add page-specific, text-specific, coordinate-specific,
or bounds-only policy.

## Migration Rules

1. `OwnershipPlanner` is the only place that may create or change ownership.
2. `FramePlacer`, `InlineFrameHandler`, `BackgroundInjector`,
   `RenderableFramePlacer`, and successor executors only run the plan.
3. Inline/floating placement is decided once in Stage 1 and is not flipped later.
4. A source bundle slot may have only one visible owner.
5. Editable text is owned by HWPX text/table objects unless the extractor marks
   the whole source bundle as a complete visual owner.
6. Shell material comes from IDML source material: extracted textless PNG/vector
   or an explicitly planned native source shape.
7. Java code must not synthesize a similar shell from text length, bounds,
   color sampling, pixel analysis, or page-specific symptoms.
8. Table/cell fill, border, inset, and fixed bounds are table style ownership,
   not shell fallback.
9. Hidden source trees remain hidden; later phases do not revive descendants.
10. Validator failures are fixed by correcting Stage 1 metadata/plan, not by
    adding executor-side suppression sets.

## Legacy Removal Checklist

- Remove executor-local duplicate suppression once the same invariant exists in
  `ObjectPlan` and validator output.
- Remove late visual placement decisions that inspect descendants after planning.
- Remove synthetic shell/table/drawText fallback paths that do not cite source
  material in `ObjectPlan`.
- Remove text-frame merge paths that are not IDML story/thread/table structure.
- Remove policy comments or tests that rely on a page number, literal text, or
  coordinates as the reason for conversion behavior.

## Related Specs

- `POLICY-source-ownership.md`: canonical source ownership policy.
- `SPEC-025-text-image-rendering-removal.md`: historical text-in-image removal
  constraints.
- `SPEC-028-inline-anchored-group-duplicate.md`: historical inline duplicate
  constraints.
- `SPEC-033-badge-inline-simplification.md`: historical badge simplification
  constraints.
