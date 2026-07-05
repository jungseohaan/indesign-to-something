# POLICY: Source Ownership Pipeline

> Status: Active / Canonical.
> Goal: Decide IDML source ownership once, then execute that plan without
> case-specific reinterpretation.

This is the canonical index for source ownership. The detailed policy is split
into the linked modules below, and those modules are canonical by reference from
this file. Removed ownership SPECs are intentionally not linked from this
policy; their usable rules have been consolidated here.

## Reading Order

1. [Overview](../policy/00-overview.md)
   - core principles
   - refactoring direction
   - performance-oriented ownership policy
2. [Source Coverage Planning](../policy/05-source-coverage-planning.md)
   - complete IDML source traversal
   - SourceObject coverage status
   - SourceBundle / OwnershipSlot / SlotOwner / RenderUnit model
   - ObjectPlan handoff without Java bridge recovery
3. [Source Bundles And Slots](../policy/10-source-bundle.md)
   - source bundle identity
   - slot ownership
   - materialization rules
   - `PLACE_TEXT_SHELL` roles
4. [ObjectPlan Contract And Decision Order](../policy/20-object-plan.md)
   - required ObjectPlan fields
   - action/materialization contract
   - Stage 1 decision order
5. [Placement And Inline Ownership](../policy/30-placement-inline-policy.md)
   - page vs story-flow placement
   - inline/floating source ownership
   - clipping and page-local fragment placement
6. [Text](../policy/40-text-policy.md)
   - HWPX text ownership
   - TextFrame merge/layout constraints
7. [Textless Shells](../policy/50-textless-shell-policy.md)
   - native/extracted shell ownership
   - direct child/sibling shell slots
   - text-owning shell execution requirements
8. [Table Structure](../policy/60-table-policy.md)
   - table structure ownership
   - table decoration visual ownership
   - table-like carrier rules
   - table placement and row geometry
9. [Layers And Z-Depth](../policy/70-layer-zdepth.md)
   - three HWPX policy strata
   - background graphic separation
   - textless image grouping
   - HWPX plane mapping
10. [Executor Rules](../policy/80-executor-rules.md)
   - allowed executor behavior
   - forbidden fallback execution paths
11. [Validation, Forbidden Patterns, Cleanup](../policy/90-validation-invariants.md)
    - invariants
    - forbidden patterns
    - cleanup direction

## Canonical Shortcut

When a page issue is reported, use this lookup first:

| Symptom | First Policy Module |
|---|---|
| missing object with no clear source owner | [Source Coverage Planning](../policy/05-source-coverage-planning.md) |
| duplicate/missing source slot | [Source Bundles And Slots](../policy/10-source-bundle.md) |
| wrong ObjectPlan/action/materialization | [ObjectPlan Contract And Decision Order](../policy/20-object-plan.md) |
| inline vs floating mismatch | [Placement And Inline Ownership](../policy/30-placement-inline-policy.md) |
| text merge/font/layout issue | [Text](../policy/40-text-policy.md) |
| missing label/shell/background shell | [Textless Shells](../policy/50-textless-shell-policy.md) |
| table structure/row placement issue | [Table Structure](../policy/60-table-policy.md) |
| table fill/stroke/decoration issue | [Layers And Z-Depth](../policy/70-layer-zdepth.md) |
| object covering another object | [Layers And Z-Depth](../policy/70-layer-zdepth.md) |
| Java/executor fallback suspicion | [Executor Rules](../policy/80-executor-rules.md) |
| regression guard or forbidden workaround | [Validation, Forbidden Patterns, Cleanup](../policy/90-validation-invariants.md) |

## Non-Negotiable Summary

- Stage 1 is the only owner of ownership decisions.
- Later stages execute `ObjectPlan`; they do not reinterpret ownership,
  placement, layer, or materialization.
- Editable/searchable text is HWPX text or HWPX table text.
- HWPX table structure is editable, but table/cell visual decoration is
  textless graphic material unless Stage 1 explicitly says otherwise.
- HWPX execution has three policy strata:
  `BACKGROUND_GRAPHIC < TEXTLESS_IMAGE_GROUP < TEXT_TABLE_STRUCTURE`.
  Background graphics are source-owned textless material, but they are excluded
  from ordinary page-local textless image grouping and always form the bottom
  graphic stratum.
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

## V2 Implementation Baseline

V2 development starts from this policy, not from page regressions. A change is
V2-compatible only when it can be expressed as source metadata -> `ObjectPlan`
-> executor behavior.

Before changing a placer, injector, inline handler, renderer, or writer:

1. Identify the source bundle and ownership slot involved.
2. Confirm which `ObjectPlan` field should carry the decision.
3. Add or strengthen the Stage 1 decision if the field is missing or ambiguous.
4. Make the later stage execute the plan without changing ownership,
   placement, layer, materialization, or text ownership.
5. Add validation for the invariant that would have caught the regression.

The first V2 code target is therefore the `ObjectPlan` contract and
`OwnershipPlanner` output. Legacy phases may remain as execution bridges, but
they are not allowed to become a second policy layer.

## Stage Boundary Contract

| Stage | Owner | May Decide | Must Not Decide |
|---|---|---|---|
| 0 Input Prepare | IDML/resolved/extractor import | source indexes, resolved metadata, extractor candidates | ownership, placement, layer, materialization |
| 1 Ownership Planner | `OwnershipPlanner` / `ObjectPlan` | text/visual/table-structure/graphic slot owner, placement, coordinate space, HWPX plane contract, z-order contract | AST construction or HWPX emission |
| 2 Text Builder | text executor | HWPX text emitted from `textAction` and `ownedTextFrameIds` | PNG placement, shell ownership, inline/floating conversion |
| 3 Visual Builder | visual executor | PNG/vector/native/table structure emitted from `visualAction` and materialization | text ownership, slot reassignment, fallback visual owner creation, visual layer repair |
| 4 Validate | validator | invariant checks and diagnostics | new ownership, new visible material, late mutation of `ObjectPlan` |

Any code path outside Stage 1 that needs page number, literal text, coordinates,
color, pixel/alpha analysis, occlusion, or an `alreadyHandled` set to decide
whether a source is visible is evidence that the required source-slot fact is
missing from Stage 1.

## Code Mapping

The Java ownership enums are policy terms, not legacy SPEC terms:

- `TextAction`: `OWNED_BY_HWPX_TEXT`, `OWNED_BY_PNG`,
  `HIDDEN_SEMANTIC`, `DROP_TEXT`
- `VisualAction`: `PLACE_INLINE_PNG`, `PLACE_FLOATING_PNG`,
  `PLACE_TEXT_SHELL`, `ABSORB_TEXT_STYLE`, `PLACE_TABLE_STYLE`,
  `DROP_VISUAL`
- `Materialization`: `HWPX_TEXT`, `HWPX_TABLE_STYLE`,
  `NATIVE_SOURCE_SHAPE`, `EXTRACTED_PNG_VECTOR`,
  `TEXTLESS_VISUAL_FRAGMENT`, `COMPLETE_PNG`
- `Placement`: `INLINE`, `FLOATING`, `TABLE`, `NONE`
- `CoordinateSpace`: `STORY_FLOW`, `PAGE`, `SOURCE_LOCAL`
- `VisualLayer`: implementation compatibility label. In V2 policy it must map
  to one of the three HWPX policy strata: background graphic, ordinary textless
  image group, or text/table structure. Legacy role labels such as
  `DECORATION` and `CONTENT` may exist only as diagnostics or migration hints;
  they must not create foreground graphic exceptions above editable text/table
  structure.

If a new enum value seems necessary, first update the canonical policy module
that defines the source-owned behavior, then add validation showing why the
existing HWPX plane contract or slot actions cannot express it.
