# Source Ownership Policy: Source Coverage Planning

> This file is part of the canonical source ownership policy.
> The canonical index is `docs/specs/POLICY-source-ownership.md`.

## 1. Closed Source Coverage

Stage 1 starts from complete IDML source coverage, not from rendered candidates.
Every IDML/resolved source object in the selected page range must be registered
before any ownership decision is made.

The planner must visit and index at least these source classes when present:

- page items: `Group`, `TextFrame`, `Rectangle`, `Oval`, `Polygon`,
  `GraphicLine`, and other drawable page-item roots;
- placed content: `Image`, `PDF`, `EPS`, linked graphics, and their clipping
  frames;
- story content: stories, paragraphs, runs, object replacement characters,
  inline anchors, anchored objects, and threaded TextFrames;
- table content: tables, rows, columns, cells, cell TextFrames, cell borders,
  fills, insets, and anchored content inside cells;
- master content: authored master page items and page-applied master fragments;
- execution context: page, spread, layer, visibility, lock/print state,
  parent/child source tree, source z-order, source bounds, and resolved bounds.

Each registered source object must receive one coverage status:

- `VISIBLE_OWNED`: a visible slot owner will emit it directly.
- `TEXT_OWNED`: HWPX text/table text owns the object's editable text channel.
- `STYLE_OWNED`: HWPX style/table/cell/native style absorbs the object.
- `HIDDEN_BY_OWNER`: the object is intentionally hidden because another planned
  owner materializes the same slot without this object's pixels/text.
- `DROPPED_INTENTIONAL`: hidden layer, non-printing source, empty layout source,
  or another explicit policy says it has no output.
- `PROVENANCE_ONLY`: the object explains ancestry for a bundle but owns no
  visible/text/style slot.
- `UNRESOLVED`: Stage 1 failed to assign the object to a policy state.

`UNRESOLVED` is a blocking Stage 1 error. A later renderer, Java bridge, or
executor must not convert `UNRESOLVED` into visible material.

## 2. Model Layers

The closed planner model has five layers. They must be created in this order.

### 2.1 SourceObject

`SourceObject` is one original IDML/resolved object with stable source identity.
It records source facts only:

- `sourceObjectId`
- `kind`
- `parentId`
- `childIds`
- `pageIndex`
- `spreadIndex`
- `storyId`
- `tableId`
- `cellId`
- `anchorOwnerId`
- `masterSourceId`
- `appliedPageIndex`
- `bounds`
- `zOrder`
- `layer`
- `visibility`
- `hasText`
- `hasEditableText`
- `hasPlacedContent`
- `hasDrawableStyle`

These fields are facts, not ownership decisions.

### 2.2 SourceBundle

`SourceBundle` is the source group that must be understood as one conversion
unit before it is split into roles. A bundle may be a closed source tree, a
page-local fragment of a spread/master tree, a table/cell source unit, or an
inline/anchored source chain.

Examples:

- speech bubble shell plus its child editable TextFrame;
- table cell fill/border plus the cell text and inline objects;
- applied master graphic clipped to one page;
- clipping frame plus its placed image/PDF;
- inline label shell plus the TextFrame text it decorates.

A bundle is not automatically one output object. It is only the unit whose
source relationships are stable enough to decide slot ownership.

### 2.3 OwnershipSlot

`OwnershipSlot` is the role inside a bundle. The required slots are:

- `TEXT_SLOT`
- `SHELL_SLOT`
- `TABLE_STYLE_SLOT`
- `CONTENT_VISUAL_SLOT`

The same source bundle may have multiple visible slots. The same bundle and the
same slot may have only one visible owner.

### 2.4 SlotOwner

`SlotOwner` is the final owner of a bundle slot. Allowed owner classes are:

- `HWPX_TEXT`
- `HWPX_TABLE_STYLE`
- `NATIVE_SOURCE_SHAPE`
- `TEXTLESS_PNG`
- `CONTENT_PNG`
- `COMPLETE_PNG`
- `DROP`

Slot ownership must be decided from source metadata and extractor-declared
source facts before rendering. It must not be decided from page number, text
string, coordinates, color, alpha pixels, occlusion, file existence, or a later
Java fallback.

### 2.5 RenderUnit

`RenderUnit` is created only for a slot owner that needs extracted PNG/vector
material. It is a confirmed export plan, not a candidate.

Required `RenderUnit` fields:

- `renderUnitId`
- `bundleId`
- `ownershipSlot`
- `owner`
- `sourceObjectIds`
- `visualSourceObjectIds`
- `exportSourceObjectIds`
- `hiddenTextFrameIds`
- `hiddenVisualSourceObjectIds`
- `placement`
- `coordinateSpace`
- `visualLayer`
- `zOrder`
- `bounds`
- `cropSourceBounds` when the visible page fragment is clipped

The extractor executes `RenderUnit`s. It does not create ownership by emitting
extra PNGs.

## 3. Master And Page-Local Material

Master page material is planned in two steps:

1. authored master source objects are registered as normal `SourceObject`s;
2. every visible application to a document page creates a page-local
   `SourceBundle` or bundle fragment with its own page index, bounds,
   placement, and slot owners.

Master source origin is provenance, not placement ownership.

- `pass.master_page_graphics` owns only applied-master material whose placement
  owner is page/floating. It groups master-origin graphics with other
  master-origin page/floating graphics for the same applied page fragment.
- A master-origin source object that appears in IDML story flow as an inline
  slot is owned by the story-flow channel. Its visible `RenderUnit` and
  `ObjectPlan` remain `placement=INLINE`, `coordinateSpace=STORY_FLOW`, and
  `planPassId=pass.inline_objects`.
- If InDesign can render that story-flow inline source only through an
  applied-master direct export, the exported PNG is still the executable
  material for the inline `RenderUnit`. It must be stamped with the inline
  candidate/plan identity, not re-owned as a floating master graphic.
- A broad master cluster may keep such inline source ids as provenance only
  when its visible material proves that those pixels are absent. Otherwise the
  cluster must be narrowed or dropped; it must not become a second visible owner
  for the same inline source slot.

A directly exported master fragment is valid only when it is the materialization
of an existing page-local `RenderUnit`. Rendering a direct master file and then
adding an ObjectPlan afterward is not allowed.

## 4. Tables And Inline Objects

Tables and inline objects participate in the same coverage model.

- A table cell is a bundle context. Its text uses `TEXT_SLOT`, its fill/border
  uses `TABLE_STYLE_SLOT`, and its inline graphics/shells use the appropriate
  visual slot.
- An inline anchor is a placement context, not a separate ownership policy. The
  anchored source chain is still split into `TEXT_SLOT`, `SHELL_SLOT`,
  `TABLE_STYLE_SLOT`, and `CONTENT_VISUAL_SLOT`.
- Inline/floating placement is decided by the source anchor/page context before
  `ObjectPlan` is written and must not be flipped later.

## 5. ObjectPlan Handoff

`ObjectPlan` is derived from `SourceBundle`, `OwnershipSlot`, `SlotOwner`, and
`RenderUnit`. It is the executor contract.

Java legacy bridge code may remain temporarily as a reporter during migration,
but it must not add or mutate ownership plans. A bridge-discovered missing plan
means one of these upstream models is incomplete:

- source coverage missed a `SourceObject`;
- bundle construction missed a source relationship;
- slot decomposition missed a role;
- slot ownership failed to choose an owner;
- a PNG owner lacked a `RenderUnit`;
- ObjectPlan serialization dropped a planned field.

The fix belongs in Stage 0 or Stage 1, not in Java recovery code.

## 6. Required Diagnostics

Stage 1 diagnostics must include:

- `source-coverage.json`: one row per `SourceObject` with coverage status.
- `source-bundles.json`: bundle membership and bundle type.
- `ownership-slots.json`: bundle slot decomposition.
- `slot-owners.json`: final owner per bundle slot.
- `render-units.json`: confirmed PNG/vector export units.
- `object-plans.json`: executor contract derived from the above.

The validation gate must fail when:

- any source object is `UNRESOLVED`;
- a visible source object is not reachable from a visible/style/text owner;
- the same bundle slot has more than one visible owner;
- a `RenderUnit` has no matching `ObjectPlan`;
- an extracted PNG result has no prior `RenderUnit`;
- Java bridge adds or changes an ownership plan.
