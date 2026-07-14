# POLICY: Extraction Planning Pipeline

> Status: Active / Canonical for INDD extraction.
> Goal: Decide extraction candidates before rendering, execute the extraction
> plan without hidden ownership decisions, and let Java `OwnershipPlanner`
> decide final HWPX ownership.

This policy governs the InDesign ExtendScript extraction layer. It complements
`POLICY-source-ownership.md`.

## 0. Target Model

The final V2 target is not a candidate-recovery pipeline. Extraction planning is
the materialization part of the closed source-coverage model:

`SourceObject -> SourceBundle -> OwnershipSlot -> SlotOwner -> RenderUnit -> ObjectPlan`

The normal conversion path uses compact proof references for that model. Full
source traversal is required, but full recursive source arrays are not copied
through every transitional record. Stage 0/1 owns the source-set intern table
and proof indexes; candidates, RenderUnits, ObjectPlans, validators, and
diagnostics reference those facts by stable ids unless trace/dev expansion or a
failing record requires human-readable detail.

During migration, `ExtractionPlan.candidates` may remain as export instructions.
They are transitional records. The final extractor executes confirmed
`RenderUnit`s, and each exported result must correspond to one prior RenderUnit.
If the extractor emits a PNG/vector result that has no RenderUnit, Stage 1 is
incomplete and validation must fail.

Even during migration, a candidate that stands in for a future `RenderUnit` must
be slot-closed before export. It may carry broad ancestry for diagnostics, but
its executable export source set and hidden child declarations must already
match the slot it will render. Extraction planning must not emit a broad parent
candidate and rely on normalization, result matching, Java duplicate removal, or
post-export validation to narrow the visible ownership later.

For large textless vector content, a slot-closed candidate may carry both the
expanded `exportSourceObjectIds` proof set and an atomic
`exportTargetObjectId`/`atomicExportTargetObjectIds` reference. The atomic
target is valid only when source metadata proves that one closed Group root, or
a small closed set of sibling Group roots, contains the executable visual set.
Editable text descendants may be omitted only when they are explicitly declared
in the hidden/omitted source set and remain owned by HWPX text. The executor
should render that root set once, hiding the declared omitted descendants, and
must not independently render the internal polygon children.

The same atomic contract applies to `resolved.json` collection. Once Stage 1
has declared an atomic textless vector `CONTENT_VISUAL_SLOT`, resolved
`pageItems` may compact the internal non-text polygon/vector descendants and
record only the atomic root metadata plus counts/target references. This is a
collection optimization, not a new ownership decision: the expanded source
proof remains in the in-memory plan, ObjectPlan/RenderUnit diagnostics, and
export metadata. Hidden/editable TextFrame descendants must still be collected
as text sources.

## 1. Core Principle

Extraction is not ownership.

- `ExtractionPlan` currently decides which source material candidates to export.
  In the V2 target, those candidates are replaced by confirmed `RenderUnit`s.
- The extractor executes `ExtractionPlan` and records facts.
- Java `OwnershipPlanner` decides final `TEXT_SLOT`, `SHELL_SLOT`,
  `TABLE_STYLE_SLOT`, and `CONTENT_VISUAL_SLOT` owners.
- The extractor must not silently replace, suppress, or reinterpret export
  units after the plan is written.
- `resolved.json` is canonical source metadata for final text composition facts.
  E0 must preserve TextFrame `composedLines`, line bounds, wrap indents, inset,
  leading, story ids, page indexes, and source bounds needed by TextWrap policy.
  These fields must not be discarded as diagnostics-only data, because Stage 1
  may need them to declare source-composed TextWrap contracts.

## 2. Pipeline

The extraction pipeline has four stages:

| Stage | Name | Responsibility | Forbidden |
|---|---|---|---|
| E0 | Metadata Scan | Read InDesign DOM facts, page range, source hierarchy, style facts | PNG export |
| E1 | Extraction Planner | Write export candidate instructions | Final HWPX ownership |
| E2 | Directed Extractor | Execute planned export passes and record results | Hidden fallback or suppress |
| E3 | Extraction Validate | Check plan/result consistency | New export decisions |

## 2.1 Chunked Execution

Spread/page chunking is a performance partition of extraction execution, not a
new ownership policy.

- IDML export, IDML z-order maps, resolved source metadata, source indexes, and
  source-set intern tables may be prepared once for the selected document/range
  and reused by chunk-local planning.
- Chunk-local E1 planning must still use the chunk's page/placement context for
  page-local fragments, applied-master fragments, hidden children, and export
  closure.
- Chunk merge may concatenate and de-duplicate planned results, RenderUnits,
  page hashes, and resolved records. It must not reinterpret ownership,
  placement, layer, materialization, hidden children, or source-slot identity.
- Chunk-local resolved records may retain atomic compaction from Stage 1. Merge
  must preserve the compact root records and diagnostics instead of expanding
  internal descendants again.
- Reusing a previously exported IDML package is valid for test/dev reruns when
  the source INDD identity, size, and modification time match the cache key.
  Reuse skips immutable Stage 0 extraction work only; it does not skip Stage 1
  ownership planning or validation.

## 3. ExtractionPlan

`extraction-plan.json` is written before render/export passes.

Required top-level fields:

- `schemaVersion`
- `scriptVersion`
- `mode`
- `sourceDocument`
- `pageRange`
- `sourceItems`
- `candidates`
- `exportPasses`

`sourceItems` are facts. They do not own conversion slots.

The in-memory `ExtractionPlan` keeps full `sourceItems` for planning,
validation, source-graph generation, and exporter lookup. Normal conversion
output may replace the full array with `sourceItemSummary` plus bounded preview
rows. Full source facts for trace tooling belong to diagnostics/debug output;
normal `source-graph.json` may also be a summary artifact.

The same split applies to `candidates`: the in-memory `ExtractionPlan` keeps
full candidate instructions for renderer lookup and validation, while normal
`extraction-plan.json` may replace the full array with `candidateSummary` and
bounded preview rows after the execution lookup has been built.

Recommended source item fields:

- `id`
- `pageIndex`
- `kind`
- `parentId`
- `parentKind`
- `bounds`
- `layerName`
- `visible`
- `textFrameClass`
- `textLength`
- `hasText`

`candidates` are source-level export instructions. They are still material
candidates, not final HWPX ownership.

Required candidate fields:

- `candidateId`
- `passId`
- `sourceObjectIds`
- `sourceSetId` or an equivalent interned source-set ref when the expanded
  `sourceObjectIds` array is diagnostic rather than required for execution
- `pageIndex`
- `unit`
- `mode`
- `candidatePurpose`
- `bounds`
- `required`

Composite candidate fields:

- `composite`
- `compositeRole`
- `sourceObjectIds` with two or more source ids
- `slotRole` when the candidate is deliberately narrowed to one visible slot
- `hiddenVisualSourceObjectIds` when child visual sources must be hidden during
  export to make the rendered file match the candidate's visible source set
- compact proof refs such as `slotClosureRef`, `exportClosureRef`, and
  `hiddenChildrenRef` when the candidate stands in for a future RenderUnit

Composite candidates represent one materialized visual candidate produced from
multiple source objects. A result may attach to a composite candidate when its
`sourceObjectIds` are a subset of the planned composite source set for the same
pass and page.

A composite candidate may not bake child slots that have their own candidate.
When a parent shell/background candidate contains child content visuals, E1 must
write a `SLOT_ONLY` candidate for the parent shell and direct E2 to hide those
child visual sources before export. Creating a full composite and relying on
Java duplicate removal is an extraction-policy violation.

The same rule applies to text, table, and shell child slots. A parent composite
candidate is exportable only when its `sourceObjectIds`/`exportSourceObjectIds`,
hidden text ids, hidden visual ids, and closure refs already describe the
rendered pixels. Candidate normalization may stabilize ids and attach missing
diagnostic refs, but it must not invent executable export/hidden sets that
should have been chosen by source-slot planning.

Candidate normalization is therefore a migration validation aid. On the normal
conversion path it may canonicalize representation and report missing refs, but
it must not be the mechanism that discovers slot ownership, creates export
sets, removes duplicate visible owners, or rebuilds ownership after broad
candidates have already been generated.

`exportPasses` describe allowed candidate production. They do not decide final
visible owners.

Recommended pass fields:

- `id`
- `phase`
- `executor`
- `unit`
- `mode`
- `candidatePurpose`
- `mayHideText`
- `outputDir`

Allowed `mode` values:

- `ORIGINAL_VISUAL`
- `TEXTLESS_CANDIDATE`
- `SLOT_ONLY`
- `METADATA_ONLY`

Allowed `candidatePurpose` values:

- `BACKGROUND_CANDIDATE`
- `SHELL_CANDIDATE`
- `CONTENT_CANDIDATE`
- `VECTOR_CANDIDATE`
- `INLINE_CANDIDATE`
- `MASTER_CANDIDATE`
- `METADATA_CANDIDATE`

## 4. ExtractionResult

`extraction-results.json` is written after render/export passes.

It records what actually happened:

- exported file path
- source ids
- actual bounds
- text hide request/success facts
- extractor reason as metadata
- export status or error

It must not invent ownership. Fields such as `textOwner`, `visualOwner`, and
`reason` are legacy metadata facts until migrated; Java must treat them as
evidence, not as final ownership.

## 5. Textless Rules

Textless extraction means:

- The extractor was instructed to export a visual candidate without editable
  text pixels.
- The extractor records which TextFrames were hidden.
- The extractor records whether the attempt succeeded.

Textless extraction does not mean:

- the PNG owns `SHELL_SLOT`;
- the hidden TextFrames are automatically `OWNED_BY_HWPX_TEXT`;
- sibling or child visuals should be dropped.

Those are Stage 1 source ownership decisions in Java.

## 6. No Hidden Fallback

If a planned export fails:

- record a failed extraction result;
- do not silently export a different unit;
- do not convert group export to child export or child export to group export;
- do not infer final ownership from the failure.

Alternative material may be emitted only when it is present in
`ExtractionPlan` as another candidate.

## 6.1 Pass Gate

During migration, legacy render functions may still operate at pass level.
Even then, every executed render pass must be present and enabled in
`ExtractionPlan`.

Rules:

- a pass missing from `ExtractionPlan` must not run;
- a disabled pass must not run;
- every exported result must include `planPassId`;
- `planPassId` must reference an enabled planned pass;
- every exported result must include `candidateId`;
- `candidateId` must reference a planned source candidate;
- validation failure must stop extraction after writing `extraction-results.json`.

This pass-level gate is only a migration safety rail. The final target remains
per-source candidate instructions, where each result references a specific
planned source candidate rather than only a broad pass.

## 6.2 Candidate Gate

During migration, the first candidate gate may be validation-based: the legacy
pass can still scan broad DOM collections, but every emitted result must attach
to a planned candidate.

That migration allowance is for metadata indexing only. A migrated pass must
not use a broad DOM scan for export discovery, candidate replacement, or
ownership recovery. New export work must be driven by the planned candidate or
RenderUnit list, and broad scans may only populate the Stage 0/1 source index
used by those planned records.

Pre-export gates use candidate-return lookups as executable instructions, not
as hints for a second source-set search. A positive gate result returns the exact
candidate/RenderUnit identity to stamp into the extraction result. A miss means
the item is skipped or reported; it does not trigger a fallback search through
all source-set members, descendants, page items, or alternate render channels.

The final candidate gate is stricter:

- each exporter receives candidate instructions instead of scanning freely;
- an item without a matching candidate is skipped before export;
- no exporter creates replacement candidates after planning;
- failed candidates are recorded as failed results, not replaced by ad hoc
  fallback exports.
- master page graphics are planned as applied-page cluster composite
  candidates using the same top-level item, side-boundary padding, and cluster
  grouping rules as the exporter; if InDesign cannot override a master item,
  direct source export is allowed only as the same applied-page planned source
  material, not as a fallback channel or a replacement candidate. Page-less
  master candidates are not valid for visible export results.
- master source origin does not override placement ownership. When an applied
  master source object is also the direct source of a planned
  `pass.inline_objects` story-flow candidate, `pass.master_page_graphics` must
  not stamp it as a floating master result. If InDesign requires a direct master
  export to produce the pixels, that result is stamped with the inline
  candidate, `placement=INLINE`, and `coordinateSpace=STORY_FLOW`.
- master page graphic candidates are `TEXTLESS_CANDIDATE` exports. Master
  TextFrames in the planned source cluster are hidden before export even when
  they do not have a document `parentPage`; the result row records those source
  TextFrame ids in `hiddenTextFrameIds`. A master PNG that still contains
  editable title/body text while the same text is emitted as HWPX text is an
  extraction defect, not a Java duplicate-suppression problem.
- placed image frames without hidden text use `ORIGINAL_VISUAL`; placed-image
  groups that hide editable text use a separate `TEXTLESS_CANDIDATE` pass and
  must record the hidden TextFrame ids.
- page background candidates and story-anchored inline object candidates are
  separate passes. `pass.page_backgrounds` must not be used as a broad candidate
  bucket for every page item; inline results use `pass.inline_objects`.
- inline object candidates must be discovered from the same editable
  TextFrame universe that the exporter scans (`allItems` for the requested
  range plus editable master TextFrames). Scanning `doc.textFrames` separately
  is not equivalent and can drop story-anchored inline objects before export.
- candidates with multiple `sourceObjectIds` must expose a
  `primarySourceObjectId`. Result matching and pre-export gates must prefer this
  direct render target before any composite source-set fallback. A child source
  contained in a parent composite is not, by itself, permission to render the
  child through the parent candidate.
- candidate normalization may sort or close `sourceObjectIds` for stable
  identity, but it must preserve the planned `primarySourceObjectId` whenever
  that id is still present in the source set. Numeric source-id order is not
  ownership order and must not select a TextFrame as the render target for a
  visual shell candidate.

Migration status:

- `pass.vector_shape_frames` executes planned candidates directly and no longer
  scans broad `allItems` collections;
- `pass.editable_textframe_visual_shells` executes planned candidates directly;
  broad item collections may be read only as metadata for legacy shell
  qualification, not as export discovery;
- `pass.complex_graphic_frames` executes planned candidates directly by
  `primarySourceObjectId` and no longer discovers render targets by scanning
  broad `allItems` collections;
- `pass.image_textless_groups` and `pass.image_placed_frames` execute planned
  candidates directly. Group image candidates are executed before standalone
  child image candidates so parent/child duplicate export remains impossible;
- `pass.decoration_groups` executes planned candidate primary objects as its
  broad traversal input. Direct item/group results stamp candidate identity from
  that candidate; composite label-backdrop results must still resolve to an
  explicit planned source-set candidate;
- `pass.inline_objects` executes planned inline candidates directly by
  `primarySourceObjectId`; the exporter no longer re-scans every editable story
  to discover additional inline page items after planning;
- `pass.page_backgrounds` is currently a planned no-op. Full-page background
  PNG export is not an implicit fallback route; page visuals must be represented
  by explicit object candidates in their own passes before export;
- source-backed background/container candidates must be expanded to every
  applied page in the same applied spread that their source bounds visibly
  intersect. The source item's `pageIndex` anchors the source object, but it is
  not by itself the visible-page boundary for spread-spanning background
  material. E1 must write a page-local candidate for each same-spread
  intersection instead of relying on `pass.page_backgrounds`, adjacent-page
  geometry fallback, or later duplicate recovery;
- `pass.master_page_graphics` resolves applied-page master clusters against the
  planned composite candidates passed into the exporter, and stamps result
  identity from that direct candidate match rather than a global post-export
  source-set lookup;
- remaining single-source exporters must gate before export;
- composite exporters must expose their source set as composite candidates
  before they are considered migrated;
- single-source and source-set exporters stamp candidate ids at result creation
  time from the same candidate-return lookup used by the pre-export gate;
- result validation remains mandatory even after pre-export gating.
- once a pass is marked migrated, result validation must reject legacy matching
  strategies such as `primary_source_page`, `primary_item_page`, and
  `exact_source_set_page`. Migrated passes may only stamp direct candidate
  strategies defined by the exporter contract.

## 7. Validation Targets

The extractor must eventually validate:

- every result references a planned pass or candidate;
- every result candidate belongs to the same pass as `planPassId`;
- every page-scoped candidate result stays on the candidate page;
- every result `sourceObjectIds` set is a subset of its candidate source set;
- every non-composite candidate has at most one visible result; composite
  candidates may temporarily produce multiple child results during migration,
  but must remain explicitly marked `composite=true`;
- result candidate identity comes from the candidate-return lookup used before
  export. Validators must not re-resolve un-stamped results into another
  candidate after export;
- every result records `candidateMatchStrategy`; any strategy containing
  `fallback`, or `source_set_legacy_all_members`, is a validation error;
- every migrated pass rejects legacy lookup strategies including
  `primary_source_page`, `primary_item_page`, and `exact_source_set_page`;
- pre-export gates use candidate-return lookups as the canonical API. Boolean
  helper gates are not allowed because they discard the candidate identity that
  must be stamped into results.
- exporters must stamp `candidateId` and `candidateMatchStrategy` into each
  result. Missing stamps are fatal.
- every candidate `mode` and `candidatePurpose` matches its planned pass;
- every planned required candidate has a result or explicit failure;
- textless results that actually hide text list hidden TextFrame ids;
- a pass with `mayHideText=false` never emits `textHiddenBeforeExport=true`;
- `textHiddenBeforeExport=true` is not used as final ownership;
- no post-plan render pass adds unplanned source candidates.
- validation failure is fatal, not advisory.

## 8. Migration Rule

Legacy JSX render functions may remain temporarily, but each render pass must be
represented in `ExtractionPlan` before it runs. The migration target is to
replace broad legacy render passes with per-source candidate instructions.
