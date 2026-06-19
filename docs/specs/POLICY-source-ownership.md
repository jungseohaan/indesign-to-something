# POLICY: Source Ownership Pipeline

> Status: Active / Canonical.
> Goal: Decide IDML source ownership once, then execute that plan without
> case-specific reinterpretation.

This is the canonical policy for source ownership. `SPEC-035` is only a
migration note; the former render-ownership SPEC-036 policy is consolidated
here.

## 1. Core Principles

1. Stage 1 is the only owner of ownership decisions.
2. Later stages execute `ObjectPlan`; they do not reinterpret ownership,
   placement, layer, or materialization.
3. Editable/searchable text is HWPX text or HWPX table text.
4. Visual material must come from IDML source material or extractor metadata.
5. One source bundle slot has exactly one visible owner.
6. Original IDML source metadata is the source of truth: source ids, parentage,
   group membership, story/thread, anchor, table/cell structure, visibility,
   layer, z-order, bounds, and style.
7. Page number, literal text, object size, character count, color sampling,
   pixel analysis, and occlusion heuristics are not ownership reasons.

## 2. Source Bundle, Slot, Material

A source bundle is the closed source tree or extractor-declared source root that
acts as one conversion unit. Ownership is decided by slot, not by raw source id.

Required slots:

- `TEXT_SLOT`: HWPX paragraph/run, TextFrame text, and table text.
- `SHELL_SLOT`: textless label/background shell, outline, callout shell, or
  container chrome.
- `TABLE_STYLE_SLOT`: cell fill, border, inset, row/column sizing, and fixed
  table bounds.
- `CONTENT_VISUAL_SLOT`: photo, illustration, chart, QR, complete marker, or
  graphic-only complete visual.

Required materialization values:

- `HWPX_TEXT`
- `HWPX_TABLE_STYLE`
- `EXTRACTED_PNG_VECTOR`
- `NATIVE_SOURCE_SHAPE`
- `COMPLETE_PNG`

Slot ownership rules:

- Different slots in the same source bundle may be visible together.
- The same slot in the same source bundle may be visible only once.
- Parent/child source bundles may both be visible only when they own different
  slots. A parent `descendantVisualObjectIds` claim must be pruned to match the
  final visible child plans.
- `EXTRACTED_PNG_VECTOR` must reference an extracted file made from the source
  material.
- `NATIVE_SOURCE_SHAPE` must reference an original source shape and its source
  style. It is not a synthetic replacement inferred from bounds.
- `COMPLETE_PNG` owns both the visual and any text pixels inside that source
  bundle. It is allowed only when the source bundle is a complete visual owner.
- A shell material must be textless. A rendered PNG that still contains editable
  text pixels is not a shell.

## 3. ObjectPlan Contract

Every visible candidate must have one `ObjectPlan` before AST generation.

Required identity fields:

- `sourceObjectIds`: source bundle identity.
- `visualSourceObjectIds`: source ids used as visible visual material.
- `styleSourceObjectIds`: source ids absorbed as HWPX style.
- `ownedTextFrameIds`: TextFrame ids whose text is owned by HWPX.

Required decision fields:

- `textAction`
- `visualAction`
- `placement`
- `visualLayer`
- `zOrder`
- `materialization`
- `coordinateSpace`
- `anchorOwner`
- `reason`

Allowed text actions:

- `OWNED_BY_HWPX_TEXT`
- `OWNED_BY_PNG`
- `HIDDEN_SEMANTIC`
- `DROP_TEXT`

Allowed visual actions:

- `PLACE_INLINE_PNG`
- `PLACE_FLOATING_PNG`
- `PLACE_TEXT_SHELL`
- `ABSORB_TEXT_STYLE`
- `PLACE_TABLE_STYLE`
- `DROP_VISUAL`

The `reason` must cite source metadata or extractor ownership metadata, not a
document-specific symptom.

## 4. Decision Order

Stage 1 decides in this order:

1. Drop hidden source trees.
2. Resolve source bundle roots and closed membership.
3. Assign table/cell structure and table style slots from IDML table metadata.
4. Assign editable text from source story/thread/table membership.
5. Assign complete visual bundles declared by extractor/source metadata.
6. Assign textless shell material for editable text owners.
7. Assign content visual material.
8. Resolve placement from original anchor/page ownership.
9. Resolve visual layer from original layer/z and the four policy layers.
10. Validate slot uniqueness and source coverage.

No later stage may create a new owner to compensate for a missing decision.

## 5. Placement And Coordinate Space

Placement and coordinate space are a single source decision.

- Extractor `page_object` material is page-positioned material. It uses
  `placement=FLOATING` and `coordinateSpace=PAGE`.
- Extractor `inline_object` material is story-flow material. It may use
  `placement=INLINE` and `coordinateSpace=STORY_FLOW` only when the source
  anchor is the owner of that material.
- When the same source bundle is available through both channels, Stage 1 keeps
  one canonical visible owner per slot. The non-canonical channel is dropped for
  that slot.
- Editable TextFrames inside a page-positioned shell do not make the shell
  inline. They are owned text of that floating shell.

## 6. Text

- HWPX owns source text that is editable/searchable in IDML.
- TextFrames are merged only when IDML story/thread/table structure says they
  are the same text flow.
- Bounds containment is not a merge reason.
- A complete visual may own text only when the source bundle itself is declared
  a complete visual owner.
- Placed external visuals are not decomposed into HWPX text unless the extractor
  exposes source text objects and ownership metadata for them.

## 7. Textless Shell

`PLACE_TEXT_SHELL` means the shell slot and text slot are separate.

Execution requirements:

- Shell material is `EXTRACTED_PNG_VECTOR` or explicitly planned
  `NATIVE_SOURCE_SHAPE`.
- Owned TextFrames/tables are emitted as HWPX text/table objects.
- If extractor metadata says `textOwner=hwpx_tf`, source TextFrames inside that
  rendered source tree belong to `ownedTextFrameIds` even when the extractor did
  not repeat them in an editable-text id field. Text content length is not an
  ownership test.
- If multiple textless visual candidates can own the same editable TextFrame,
  Stage 1 keeps exactly one canonical shell owner: the candidate whose original
  source visual most directly contains the TextFrame. Other candidates remain
  visible only as their own non-text visual slot when their source slot is
  distinct.
- TF/table carrier outline, fill, and wrapper shapes are disabled unless they
  are themselves the planned shell/style source.
- The shell keeps the Stage 1 placement. Inline shell stays inline; floating
  shell stays floating.
- If extracted shell material is missing or contains owned text pixels, the
  conversion should expose the metadata/material problem. Java must not draw a
  similar shell as a substitute.

## 8. Table Style

Table/cell visual properties belong to `TABLE_STYLE_SLOT`.

- IDML cell fill, border, inset, and row/column structure are emitted as HWPX
  table properties.
- A separate source shape may be absorbed into table style only when Stage 1
  explicitly plans `PLACE_TABLE_STYLE`.
- A source absorbed as table style is not also emitted as shell/content visual.
- A table whose source bounds are page-local and closed keeps fixed outer bounds
  unless the original source is linked, overflowed, or multi-page.
- Writer/content-fit code must not enlarge such a table and push rows to another
  page as a side effect.

## 9. Visual Layers

Policy uses only four layers:

`BACKGROUND < DECORATION < CONTENT < TEXT`

Mapping:

- Page and container backgrounds: `BACKGROUND`
- Label shells, outlines, masks, separators, and visual chrome: `DECORATION`
- Photos, illustrations, charts, QR, complete visuals: `CONTENT`
- HWPX text/table text: `TEXT`

Original IDML layer and z-order are preserved as trace data. Policy layer is a
coarse HWPX plane decision, not a replacement for source z-order.

Default HWPX plane:

- `BACKGROUND`: behind text
- HWPX execution may place a container backdrop on the front-object plane with a
  lower z-order than its label/text owners when `BEHIND_TEXT` would hide it
  behind the page paper. This is an execution mapping only; ownership remains
  `BACKGROUND`.
- textless label/backdrop shell: behind its owned text
- A textless shell whose source visual is a colored IDML shape belongs to
  `LABEL_BACKDROP`; a paper-like container shell belongs to
  `CONTAINER_BACKDROP`.
- A source shape with `Paper`/white fill is a container/background face even
  when it also has stroke. Stroke-only source shapes may become outlines.
- Image preparation must not knock out `Paper`/white pixels when that fill is
  the planned container/background face.
- outline/mask that must appear above content: in front of text only when source
  role and z-order require it
- `CONTENT`: follows source z-order against other content
- `TEXT`: highest semantic layer

## 10. Executor Rules

Text Builder:

- Emits only planned HWPX text/table text.
- Builds source story/cell flow before container placement. The flow already
  contains source inline anchors and planned inline objects.
- Tables, TextFrames, and other containers consume the prepared flow; they do
  not recover missing inline objects by matching visible strings after layout.
- Executes original inline anchors from either IDML story markers or resolved
  `inline_anchor` runs. Both are source anchor metadata; neither may be replaced
  by child TextFrame text merging.
- An inline text shell with extracted shell material remains a single inline
  shell object. Its owned text is not flattened into the parent paragraph.
- A TextFrame listed in a `PLACE_TEXT_SHELL` plan's `ownedTextFrameIds` is
  emitted only through that planned shell/text owner. It must not also be
  converted into parent-flow text runs.
- A TextFrame whose source story contains an object-replacement character is an
  inline-object carrier. When such a frame is owned by a text shell, its source
  anchors remain source anchors; its visible text is not flattened into the
  shell body as a late duplicate-removal workaround.
- Does not merge unrelated TextFrames.
- Does not create shell/table fallback visuals.

Visual Builder:

- Emits only planned visual actions.
- Does not change inline/floating placement.
- Uses `ObjectPlan.zOrder`, `ObjectPlan.visualLayer`, and the policy-layer
  plane mapping as execution inputs; it does not recompute z-order or foreground
  plane from overlap, bounds, or rendered pixels.
- Does not replace missing extracted material with synthetic bounds-based
  graphics.
- May crop or prepare an already planned image only as image preparation, never
  as ownership refinement.

Validator:

- Reports invariant failures.
- Does not create, suppress, or move output objects.

## 11. Invariants

- One source bundle slot has one visible owner.
- One TextFrame cannot be both `OWNED_BY_PNG` and `OWNED_BY_HWPX_TEXT`.
- The same source bundle slot cannot be emitted both inline and floating.
- `PLACE_TEXT_SHELL` must not contain pixels for its owned HWPX text.
- `PLACE_TEXT_SHELL` must be behind the text it owns unless the source explicitly
  defines a front mask/outline slot.
- Hidden source trees have no visible output.
- `TABLE_STYLE_SLOT` sources cannot also appear as shell/content material.
- HWPX table cell fill, border, inset, and source-bounds ownership survive to
  the writer.
- Source layer/z/anchor/page ownership must be traceable in output diagnostics.

## 12. Forbidden Patterns

- Page-specific, literal-text-specific, or coordinate-specific exceptions.
- Character-count or box-size rules for semantic ownership.
- Bounds/occlusion-only duplicate suppression.
- Pixel/color/alpha analysis to decide text vs shell ownership.
- Java-created lookalike shell PNGs or drawText boxes when source shell material
  is missing.
- Executor-local `already handled` sets as the primary duplicate policy.
- Late conversion from inline to floating or floating to inline.
- TextFrame merge that does not come from IDML story/thread/table structure.
- Treating a `textOwner=hwpx_tf` render as a complete PNG.

## 13. Cleanup Direction

Code that still contains scenario names or visual symptom names should be folded
into one of the source-driven concepts above:

- source bundle root detection
- slot assignment
- materialization selection
- placement from anchor/page ownership
- layer from source layer/z and policy layer
- validator invariant

If a new regression seems to need another condition, first check which source
metadata is missing or which `ObjectPlan` field is too weak. Add metadata or
strengthen the plan model before adding a new branch.
