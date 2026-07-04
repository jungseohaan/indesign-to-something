# POLICY: Source Ownership Pipeline

> Status: Active / Canonical.
> Goal: Decide IDML source ownership once, then execute that plan without
> case-specific reinterpretation.

This is the canonical index for source ownership. The detailed policy is split
into the linked modules below, and those modules are canonical by reference from
this file. `SPEC-035` is only a migration note; the former render-ownership
SPEC-036 policy is consolidated here.

## Reading Order

1. [Overview](../policy/00-overview.md)
   - core principles
   - refactoring direction
   - performance-oriented ownership policy
2. [Source Bundles And Slots](../policy/10-source-bundle.md)
   - source bundle identity
   - slot ownership
   - materialization rules
   - `PLACE_TEXT_SHELL` roles
3. [ObjectPlan Contract And Decision Order](../policy/20-object-plan.md)
   - required ObjectPlan fields
   - action/materialization contract
   - Stage 1 decision order
4. [Placement And Inline Ownership](../policy/30-placement-inline-policy.md)
   - page vs story-flow placement
   - inline/floating source ownership
   - clipping and page-local fragment placement
5. [Text](../policy/40-text-policy.md)
   - HWPX text ownership
   - TextFrame merge/layout constraints
6. [Textless Shells](../policy/50-textless-shell-policy.md)
   - native/extracted shell ownership
   - direct child/sibling shell slots
   - text-owning shell execution requirements
7. [Table Style](../policy/60-table-policy.md)
   - table/cell style ownership
   - table-like carrier rules
   - table placement and row geometry
8. [Layers And Z-Depth](../policy/70-layer-zdepth.md)
   - four policy layers
   - IDML source depth
   - HWPX plane mapping
9. [Executor Rules](../policy/80-executor-rules.md)
   - allowed executor behavior
   - forbidden fallback execution paths
10. [Validation, Forbidden Patterns, Cleanup](../policy/90-validation-invariants.md)
    - invariants
    - forbidden patterns
    - cleanup direction

## Canonical Shortcut

When a page issue is reported, use this lookup first:

| Symptom | First Policy Module |
|---|---|
| duplicate/missing source slot | [Source Bundles And Slots](../policy/10-source-bundle.md) |
| wrong ObjectPlan/action/materialization | [ObjectPlan Contract And Decision Order](../policy/20-object-plan.md) |
| inline vs floating mismatch | [Placement And Inline Ownership](../policy/30-placement-inline-policy.md) |
| text merge/font/layout issue | [Text](../policy/40-text-policy.md) |
| missing label/shell/background shell | [Textless Shells](../policy/50-textless-shell-policy.md) |
| table fill/stroke/row placement issue | [Table Style](../policy/60-table-policy.md) |
| object covering another object | [Layers And Z-Depth](../policy/70-layer-zdepth.md) |
| Java/executor fallback suspicion | [Executor Rules](../policy/80-executor-rules.md) |
| regression guard or forbidden workaround | [Validation, Forbidden Patterns, Cleanup](../policy/90-validation-invariants.md) |

## Non-Negotiable Summary

- Stage 1 is the only owner of ownership decisions.
- Later stages execute `ObjectPlan`; they do not reinterpret ownership,
  placement, layer, or materialization.
- Editable/searchable text is HWPX text or HWPX table text.
- Visual material must come from IDML source material or extractor metadata.
- One source bundle slot has exactly one visible owner.
- Original IDML source metadata is the source of truth: source ids, parentage,
  group membership, story/thread, anchor, table/cell structure, visibility,
  layer, z-order, bounds, and style.
- Page number, literal text, object size, character count, color sampling,
  pixel analysis, and occlusion heuristics are not ownership reasons.
- IDML, resolved metadata, this index, and the linked canonical policy modules
  are the only ownership truth.
- If a requested fix would add a page/text/coordinate/color/symptom condition,
  first inspect the source metadata and either strengthen the common
  `ObjectPlan` model or report that the request needs more confirmation.
- After Stage 1 writes `ObjectPlan`, no later code may mutate ownership fields.
- Bypass code is not allowed.
