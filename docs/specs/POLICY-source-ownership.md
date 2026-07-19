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
7. [TextWrap](../policy/45-text-wrap-policy.md)
   - resolved composed-line wrap
   - source floating obstacle wrap contracts
8. [Textless Shells](../policy/50-textless-shell-policy.md)
   - native/extracted shell ownership
   - direct child/sibling shell slots
   - text-owning shell execution requirements
9. [Table Structure](../policy/60-table-policy.md)
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
12. [Implementation Audit Map](../policy/95-implementation-audit.md)
    - source ownership code paths
    - current conformance status
    - remaining cleanup targets

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
- HWPX table structure is editable, and table/cell visual decoration becomes
  HWPX table style only when Stage 1 declares the source objects in
  `TABLE_STYLE_SLOT` / `styleSourceObjectIds`.
- HWPX execution has three policy strata:
  `BACKGROUND_GRAPHIC < TEXTLESS_IMAGE_GROUP < TEXT_TABLE_STRUCTURE`.
  `BACKGROUND_GRAPHIC` is not a source-object meaning. It is a page-local
  execution plane assembled in Stage 1 from source-connected floating
  `SHELL_SLOT` visual components after `TEXT_SLOT`, `CONTENT_VISUAL_SLOT`,
  `TABLE_STYLE_SLOT`, and story-flow inline sources are excluded. The component
  still owns only its textless visual slot; editable text is hidden from the
  asset and re-emitted in `TEXT_TABLE_STRUCTURE`.
- Non-background visual material is one source-ordered visual stream. Historical
  roles such as `DECORATION` and `CONTENT` are diagnostics/source-role labels
  only; they must not create separate foreground/background execution bands or
  invert original IDML source depth.
- Visual material must come from IDML source material or extractor metadata.
- One source bundle slot has exactly one visible owner.
- Performance is an ownership property. Stage 1 must avoid creating
  non-executable visual candidates and then relying on later normalize,
  suppression, deduplication, or validation passes to repair ownership.
- Complete source coverage means every selected source object has a decided
  owner/status. It does not require copying every recursive source-id set into
  every candidate, bundle, `ObjectPlan`, registry row, coverage row, or
  validation row on the normal conversion path.
- Stage 0/1 must intern repeated source-id sets and expose stable proof
  references for coverage, slot closure, export closure, hidden children, and
  page-local fragments. Expanded recursive arrays are trace/debug evidence,
  not the normal executor contract.
- Original IDML source metadata is the source of truth: source ids, parentage,
  group membership, story/thread, anchor, table/cell structure, visibility,
  layer, z-order, bounds, and style.
- Page number, literal text, object size, character count, color sampling,
  pixel analysis, and occlusion heuristics are not ownership reasons.
- Source origin and placement owner are separate facts. A graphic authored on a
  master spread is not automatically `pass.master_page_graphics`; if IDML story
  flow owns its visible slot, its `ObjectPlan` remains `placement=INLINE` and
  `coordinateSpace=STORY_FLOW` even when the executable pixels are obtained
  through an applied-master direct export.
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
| 0 Input Prepare | IDML/resolved/extractor import | source indexes, resolved metadata, immutable source facts, interned source-set/proof indexes | ownership, placement, layer, materialization |
| 1 Ownership Planner | `OwnershipPlanner` / `ObjectPlan` | text/visual/table-structure/graphic slot owner, source-slot registry, compact proof refs, placement, coordinate space, HWPX plane contract, z-order contract | AST construction or HWPX emission |
| 2 Text Builder | text executor | HWPX text emitted from `textAction` and `ownedTextFrameIds` | PNG placement, shell ownership, inline/floating conversion |
| 3 Visual Builder | visual executor | PNG/vector/native/table structure emitted from `visualAction` and materialization | text ownership, slot reassignment, fallback visual owner creation, visual layer repair |
| 4 Validate | validator | invariant checks, proof-ref/indexed membership checks, diagnostics | new ownership, new visible material, late mutation of `ObjectPlan` |

Any code path outside Stage 1 that needs page number, literal text, coordinates,
color, pixel/alpha analysis, occlusion, or an `alreadyHandled` set to decide
whether a source is visible is evidence that the required source-slot fact is
missing from Stage 1.

Normal-path validation must validate the compact ownership proof:
`coverageClaimRef`, `slotClosureRef`, `exportClosureRef`, `hiddenChildrenRef`,
and interned source-set refs. Validators may expand recursive source trees for
trace/dev diagnostics and failing records, but they must not require every
successful plan to duplicate full descendant arrays.

## Code Mapping

The Java ownership enums are policy terms, not legacy SPEC terms:

- `TextAction`: `OWNED_BY_HWPX_TEXT`, `OWNED_BY_PNG`,
  `HIDDEN_SEMANTIC`, `DROP_TEXT`
- `VisualAction`: `PLACE_INLINE_PNG`, `PLACE_FLOATING_PNG`,
  `PLACE_PAGE_BACKGROUND_PNG`, `PLACE_TEXT_SHELL`,
  `ABSORB_TEXT_STYLE`, `PLACE_TABLE_STYLE`, `DROP_VISUAL`
- `Materialization`: `HWPX_TEXT`, `HWPX_TABLE_STYLE`,
  `EXTRACTED_PNG_VECTOR`, `TEXTLESS_VISUAL_FRAGMENT`, `PAGE_PLANE_PNG`,
  `COMPLETE_PNG`, `NATIVE_SOURCE_SHAPE`. `NATIVE_SOURCE_SHAPE` is executable
  only when Stage 1 has a direct source-style contract that HWPX can represent
  natively. Typical allowed cases are source-declared line/vector slots and
  story-flow inline TextFrames whose own fill/stroke/text must travel as one
  inline drawText carrier. It is not a fallback for recreating arbitrary missing
  graphics from bounds, color, or visual symptoms.
- `Placement`: `INLINE`, `FLOATING`, `TABLE`, `NONE`
- `CoordinateSpace`: `STORY_FLOW`, `PAGE`, `SOURCE_LOCAL`
- `VisualLayer`: implementation compatibility label. In V2 policy it must map
  to one of the three HWPX policy strata: background graphic, ordinary textless
  image group, or text/table structure. Legacy role labels such as
  `DECORATION` and `CONTENT` may exist only as diagnostics or migration hints;
  they must not create foreground graphic exceptions above editable text/table
  structure.

## Terminology Reconciliation

Older code and diagnostics may still mention four visual roles:
`BACKGROUND`, `DECORATION`, `CONTENT`, and `TEXT`. They are source-role
diagnostics only. They do not create four HWPX execution layers.

## Canonical Graphic Materialization Path

The default graphic materialization path is `single-textless-plane`.
For every page in the extraction range, Stage 3 exports one page-sized PNG that
contains page/floating textless visual source material with editable text
hidden. HWPX text and table text remain owned by `TEXT_TABLE_STRUCTURE`.

This page plane is the main execution path, not an experimental fallback. It
replaces per-object page/floating vector export for ordinary page graphics.
The legacy per-object `policy` graphics path may remain available only for
comparison, debugging, or rollback while migration is in progress.

The page plane must not absorb:

- editable/searchable `TEXT_SLOT` content;
- `TABLE_STYLE_SLOT` content that is represented by HWPX table structure/style;
- story-flow inline or anchored images that must travel with text;
- complete marker PNGs that intentionally own their own tiny text marker.

If one of those exclusions is violated, the fix belongs in Stage 1 source-slot
ownership or in the page-plane export mask. It must not be patched with
page/text/coordinate exceptions after extraction.

The executable HWPX policy has exactly three strata:

1. `BACKGROUND_GRAPHIC`: an explicit page-root textless plane ObjectPlan
   assembled from page/floating source-connected non-text visual components. It
   is never inferred from bounds, layer name, color, semantic role, or low
   z-depth alone, and it must not include `TEXT_SLOT`, `TABLE_STYLE_SLOT`,
   `COMPLETE_PNG` text owners, or story-flow inline material.
2. `TEXTLESS_IMAGE_GROUP`: all ordinary non-text source graphics, including
   historical decoration/content roles, undeclared table/cell decoration, masks,
   charts, photos, shells, badges, and master page graphic fragments.
3. `TEXT_TABLE_STRUCTURE`: editable HWPX text and editable HWPX table
   structure/style.

If a rule appears to require a separate decoration/content foreground layer,
that rule is legacy wording. Stage 1 must instead decide whether the source
bundle is editable HWPX text/table, a textless image group, a background
graphic, or a complete PNG that intentionally gives up editability.

If a new enum value seems necessary, first update the canonical policy module
that defines the source-owned behavior, then add validation showing why the
existing HWPX plane contract or slot actions cannot express it.
