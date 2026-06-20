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
8. IDML, resolved metadata, and this policy are the only ownership truth. A
   user-observed visual symptom may start investigation, but it is not itself a
   conversion rule.
9. If a requested fix would add a page/text/coordinate/color/symptom condition,
   first inspect the source metadata and either strengthen the common
   `ObjectPlan` model or report that the request needs more confirmation.

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
- Placed content source types such as Image, PDF, and EPS own a
  `CONTENT_VISUAL_SLOT`. If the same source group also contains editable
  TextFrames or label shells, those are separate text/shell slots; the placed
  content must not be reclassified as a text shell.
- An image-backed extracted render that already contains placed content plus
  its local visual-only chrome owns the closed content visual slot for those
  child sources. Stage 1 must not narrow that render to only the placed Image/PDF
  source and then recreate the local chrome as separate native shells.
- Parent/child source bundles may both be visible only when they own different
  slots. A parent `descendantVisualObjectIds` claim must be pruned to match the
  final visible child plans.
- Source containment is not visual ownership. A parent may drop a child visual
  only when the parent's `visualSourceObjectIds` actually include that child
  visual slot. `sourceObjectIds` containment alone is trace ancestry and must not
  suppress a visible child material.
- A parent group that only organizes direct child slots is an ownership
  container, not a visible shell. If its editable TextFrames are covered by
  direct child shell/table/background slots, the parent composite render is
  dropped and the direct slots own the visible material.
- A composite text-shell render does not suppress a direct native shell merely
  because it contains the same source shape. Stage 1 may split that composite
  into direct source slots, and then drop the composite parent if the direct
  slots cover the visible material.
- `EXTRACTED_PNG_VECTOR` must reference an extracted file made from the source
  material.
- `NATIVE_SOURCE_SHAPE` must reference an original source shape and its source
  style. It is not a synthetic replacement inferred from bounds.
- `NATIVE_SOURCE_SHAPE` is a fallback materialization. It is suppressed by a
  direct extracted shell for the same source shape, or by a visible non-shell
  extracted visual that owns that source as content. It is not suppressed by a
  larger text-shell composite that is only an ownership container.
- `COMPLETE_PNG` owns both the visual and any text pixels inside that source
  bundle. It is allowed only when the source bundle is a complete visual owner.
- A shell material must be textless. A rendered PNG that still contains editable
  text pixels is not a shell.
- A rendered visual whose `visualAction` is `DROP_VISUAL` must not keep editable
  text ownership. The HWPX text is owned by explicit TextFrame/table text plans
  or by the surviving visible shell slot.

## 3. ObjectPlan Contract

Every visible candidate must have one `ObjectPlan` before AST generation.

Required identity fields:

- `sourceObjectIds`: source bundle identity.
- `visualSourceObjectIds`: source ids used as visible visual material.
- `styleSourceObjectIds`: source ids absorbed as HWPX style.
- `ownedTextFrameIds`: TextFrame ids whose text is owned by HWPX.

`visualSourceObjectIds` must not contain ids from the same plan's
`ownedTextFrameIds`. TextFrames are text slots. They may remain in
`sourceObjectIds` for ancestry, but they are not visible shell material when the
text is owned by HWPX.

A TextFrame id may appear in the `ownedTextFrameIds` of only one visible
text-owning bundle. If a leaf atomic `PLACE_TEXT_SHELL` owns that TextFrame,
ancestor/composite groups must remove it from their text ownership. Ancestors may
keep only their own distinct visual/content slots.

`sourceBundleKey` is derived from page index, closed `sourceObjectIds`, and
`ownedTextFrameIds`. It must not depend on a transient rendered PNG id when
extractor metadata declares the same atomic source bundle through another render
entry.

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

- Extractor `page_object` material is page-positioned material by default. It
  uses `placement=FLOATING` and `coordinateSpace=PAGE`.
- A `page_object` textless shell may keep `placement=INLINE` only when the
  source owner is an inline leaf atom: the source group itself is inline and its
  direct visible children are exactly one editable TextFrame plus one direct
  visual shell shape. Composite or multi-text `page_object` shells remain
  page-positioned.
- A `NATIVE_SOURCE_SHAPE` child slot split out of a page-positioned composite
  keeps the page-positioned placement of that composite source channel. The
  child TextFrame's inline flag does not turn that native shell into story-flow
  material.
- Extractor `inline_object` material is story-flow material. It may use
  `placement=INLINE` and `coordinateSpace=STORY_FLOW` only when the source
  anchor is the owner of that material.
- When the same source bundle is available through both channels, Stage 1 keeps
  one canonical visible owner per slot. The non-canonical channel is dropped for
  that slot.
- If an IDML story run, IDML table-cell run, or resolved story run contains an
  inline anchor for that source id, the story-flow channel is the source owner
  for that slot. A paired `page_object` render is only a duplicate channel and
  must not convert the object to floating placement.
- If the canonical material for that inline-anchored source is available through
  a `page_object` extracted shell channel, Stage 1 still writes
  `placement=INLINE` and `coordinateSpace=STORY_FLOW` unless IDML explicitly
  marks the object as page-positioned with `AnchoredPosition="Anchored"`.
- When a visible `PLACE_TEXT_SHELL` owns an editable TextFrame, that TextFrame's
  HWPX text plan follows the shell plan's placement. Inline carrier recovery is
  allowed only when no visible text-shell owner exists for that TextFrame.
- A `PLACE_TEXT_SHELL` may be `INLINE` only when the source object has an
  executable inline anchor in IDML story flow, including table-cell flow, or in
  resolved story flow. A group-internal child marked inline by InDesign but
  lacking that source story anchor is page-positioned material and uses
  `placement=FLOATING`.
- A story anchor marker is not by itself an inline-placement contract. If the
  anchored source object is an IDML graphic/group with
  `AnchoredObjectSetting.AnchoredPosition="Anchored"`, Stage 1 treats the
  object as page-positioned material. A paired page-object render owns the
  visible graphic-only slot; the story-flow marker remains only the anchor
  relation.
- When a parent composite contains a child atomic shell slot, the child atomic
  shell owns its TextFrame. The parent must remove that child TextFrame from its
  ownership set instead of also claiming it or changing the child's placement.
- When a graphic-only atomic source bundle is available through both
  `inline_object` and `page_object` channels without a story inline anchor, the
  `page_object` channel owns the visible content slot because it preserves page
  coordinates. The paired inline channel is a non-canonical render channel and
  is dropped.
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
- Extractor chooses `EXTRACTED_PNG_VECTOR` textless shells from source structure:
  direct editable TextFrames paired with direct visual-only shell sources under
  the same source owner. Text length and rendered size are not ownership tests.
- The extractor records accepted/rejected textless-shell candidates as
  diagnostics. Diagnostics explain missing metadata or invalid structure; they do
  not create ownership in later stages.
- When an editable TextFrame is a child of a visible source
  Rectangle/Polygon/Oval whose bounds contain the TextFrame, that parent source
  shape may be the canonical `NATIVE_SOURCE_SHAPE` shell owner. The shell style
  comes from that original shape, not from a synthetic HWPX default.
- A direct visual shell shape and a direct editable TextFrame under the same
  source group may form a `NATIVE_SOURCE_SHAPE` sibling shell slot. This is a
  source-structure fallback, not a visual similarity fallback.
- When extractor metadata marks a child source as a native fill/shell child slot,
  the parent rendered shell must remove that child id from its
  `visualSourceObjectIds`. The parent may keep structural ancestry in
  `sourceObjectIds`, but it no longer owns that child's visible material.
- Native source shell bounds use the same page-relative source coordinate space
  as other planned visual bounds. If resolved geometry was normalized for IDML
  comparison, Stage 1 converts it back to the execution coordinate space before
  writing `ObjectPlan.bounds`.
- Native source shell style keeps IDML physical units. Geometry may be
  normalized for page coordinates, but stroke weight, tint, and other visual
  style values must not receive the geometry scale a second time when emitted as
  HWPX native shape properties.
- A textless shell's layer is derived from its source role before its visual
  style. A source shape paired with one local editable label is a label backdrop;
  a source shape whose owned TextFrame story carries inline anchors or other
  content slots is a container/carrier backdrop. Fill and stroke are copied as
  style after that role is chosen.
- The extractor may also emit native parent shell candidates as source metadata.
  Java may use that metadata or equivalent IDML parent/style facts, but it must
  still materialize the shell from the original source shape.
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
- If a candidate shell contains another shell candidate for the same owned
  TextFrame but also contains additional visible source shell material outside
  that child slot, the containing source shell is the canonical owner. The child
  candidate is already baked into that shell material and must not be emitted as
  a second visible shell.
- A composite rendered text shell that contains multiple direct shell slots is a
  layout container, not the canonical visible shell, only when every owned
  TextFrame is covered by distinct direct shell slots. Stage 1 then drops the
  composite parent visual.
- A multi-TextFrame composite wrapper must not be restored as a second text shell
  when a more direct child shell already owns the same complete TextFrame set.
  Any additional visible material outside that child must survive only through
  distinct non-text visual slots.
- For a given page and complete `ownedTextFrameIds` set, only one visible
  `PLACE_TEXT_SHELL` plan may own that text set. If multiple rendered channels
  claim it, Stage 1 keeps the most direct source bundle as the canonical shell
  owner and drops wrapper render channels from text/shell ownership.
- Direct child slots are derived from source parentage and source style. A
  local source shape paired with editable text owns the shell slot; a carrier
  source shape paired with a table/text carrier owns the carrier/style slot.
  Fill/stroke values are copied as source style, not used as a new ownership
  classifier.
- A rendered shell source that owns multiple editable TextFrames is a carrier
  shell, not a label shell. It belongs to `CONTAINER_BACKDROP` unless the source
  supplies separate direct label slots for each owned TextFrame.
- An extracted child shell can be a direct slot when it has one visible source
  material root and owns HWPX text from that same child source bundle. Such a
  child slot counts toward decomposing and dropping the composite parent.
- Stage 1 must not create a native sibling shell that would only partially split
  a still-visible composite shell, or that reuses a source visual already visible
  in another extracted text shell for a different owned TextFrame.
- TF/table carrier outline, fill, and wrapper shapes are disabled unless they
  are themselves the planned shell/style source.
- Planned shell material must preserve its visual layer when written to HWPX.
  A shell slot must not be converted into the owned TF/table carrier fill when
  that would move it into the text carrier plane. Use a separate low-z visual
  for `BACKGROUND` shell material and keep the carrier transparent.
- The shell keeps the Stage 1 placement. Inline shell stays inline; floating
  shell stays floating.
- If extracted shell material is missing or contains owned text pixels, the
  conversion should expose the metadata/material problem. Java must not draw a
  similar shell as a substitute.

## 8. Table Style

Table/cell visual properties belong to `TABLE_STYLE_SLOT`.

- IDML cell fill, border, inset, and row/column structure are emitted as HWPX
  table properties.
- When a table-only carrier cell contains an inline anchored composite object,
  source shapes already planned into the table/style carrier slot are not
  emitted again as separate shell PNGs. Distinct label shells inside the same
  composite remain separate shell slots.
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
- `CONTAINER_BACKDROP` is a `BACKGROUND` slot and must not be emitted on the
  front-object plane merely to keep it visible. If it disappears behind a page
  paper/background object, fix the page/background z-plane contract instead of
  moving the carrier in front of text.
- textless label/backdrop shell: logical `DECORATION`, below its owned text and
  above container/background faces. When HWPX table carriers would occlude a
  behind-plane label shell, materialize the label shell in the front plane with a
  decoration z-band below the HWPX-owned text band, and materialize its owned TF
  in the higher text z-band. This is an execution mapping of `DECORATION < TEXT`,
  not a new ownership decision.
- A textless shell whose source role is an independent label slot belongs to
  `LABEL_BACKDROP`. A textless shell whose source role is a text/table/inline
  carrier belongs to `CONTAINER_BACKDROP`, even when the carrier has colored fill
  or stroke. Visual color is style, not a layer classifier.
- A graphic-only sibling contained by a text-shell cluster is label chrome, not
  independent content. It belongs to `LABEL_OVERLAY_BACKDROP` so it stays above
  the cluster's container/table carrier while the editable text remains HWPX
  text.
- A native source shell used as a local text container face must be emitted above
  broad page/PDF/background rasters and below label shells and editable text. It
  must not reuse the same lowest backdrop z-band as full-page backgrounds.
- The same visual layer must use the same execution z-band regardless of
  materialization. `NATIVE_SOURCE_SHAPE` and `EXTRACTED_PNG_VECTOR` container
  backdrops are both local backgrounds, not page backgrounds.
- HWPX execution plane and z-band must agree. A visual emitted in the page
  background z-band is emitted behind text; it must not remain on the front
  object plane because of a stale generic content layer.
- A source shape with `Paper`/white fill is a container/background face even
  when it also has stroke. Stroke-only source shapes may become outlines.
- Image preparation must not knock out `Paper`/white pixels when that fill is
  the planned container/background face.
- outline/mask that must appear above content: in front of text only when source
  role and z-order require it
- `CONTENT`: follows source z-order against other content
- A content visual that keeps its editable labels as HWPX text is emitted below
  those owned TextFrames and above container/background shells from the same
  source cluster.
- `TEXT`: highest semantic layer

## 10. Executor Rules

Text Builder:

- Emits only planned HWPX text/table text.
- Builds source story/cell flow before container placement. The flow already
  contains source inline anchors and planned inline objects.
- A source story/cell flow builder returns an empty flow when there is no
  executable content; it does not return null. Container executors treat empty
  flow as empty source content, not as a signal to reinterpret ownership.
- A shell-only fallback may run only when the source cell/story flow is empty.
  It must never replace a non-empty cell paragraph, because that drops text runs
  before or after the inline shell.
- Tables, TextFrames, and other containers consume the prepared flow; they do
  not recover missing inline objects by matching visible strings after layout.
- Executors must not synthesize a new inline text-shell `ObjectPlan` from
  resolved/page-item metadata. If Stage 1 did not plan an inline text shell, the
  executor leaves that anchor to the already planned text/visual owner.
- Table-cell flow executes planned inline text shells by source anchor and
  `ownedTextFrameIds`, not by the rendered channel type. If Stage 1 says a
  `page_object` render is the canonical extracted shell for an inline source,
  the table-cell executor attaches that shell material to the same inline shell
  carrier.
- Any fallback that rebuilds cell text from a resolved story must preserve
  source inline anchors. If it cannot execute those anchors, it must leave the
  prepared flow unchanged instead of recreating an anchorless text-only flow.
- Executes original inline anchors from either IDML story markers or resolved
  `inline_anchor` runs. Both are source anchor metadata; neither may be replaced
  by child TextFrame text merging.
- If an inline source-chain owns HWPX text but has no executable source story
  carrier, Stage 1 promotes that TextFrame to a page text carrier using resolved
  page bounds. Inline children inside that promoted story remain inline source
  anchors.
- An inline text shell with extracted shell material remains one ownership
  plan. The default materialization is a single inline shell object, but if the
  source children are already an inline flow composition of visual leaves before
  and after editable TextFrames, the executor materializes that same plan as
  source-ordered inline fragments: visual leaf, HWPX text, visual leaf. This is
  execution of source child order, not a new ownership or placement decision.
- If that inline shell has extracted PNG material, the executor uses the
  extracted image as the shell carrier fill before native vector
  reconstruction. The owned text uses the source TextFrame story paragraphs,
  paragraph alignment, and TextFrame inset/bounds relation; it is not centered
  or reflowed by a generic badge fallback.
- A TextFrame listed in a `PLACE_TEXT_SHELL` plan's `ownedTextFrameIds` is
  a source relation, not a merge instruction. The shell visual is emitted by the
  shell plan; the editable text is emitted by its own TextFrame/table plan unless
  the source explicitly declares complete-PNG text ownership.
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
- Does not treat an existing cached/rendered file path as ownership evidence.
  Fallback PNGs are visible only when Stage 1 already planned that source bundle.
- May crop or prepare an already planned image only as image preparation, never
  as ownership refinement.
- Image preparation may use PNG sanity metadata such as exported byte size and
  source/page bounds to diagnose extraction failures. That metadata is not a
  text/shell ownership classifier.
- If a planned extracted inline visual has degenerate bounds on one axis,
  execution may restore the missing display dimension from source stroke width
  or the extracted PNG aspect ratio. This is size reconstruction for an already
  owned material, not a new ownership or placement decision.
- Textless shell extraction must not synthesize transparent guard canvases or
  expanded transparent bounds. The extracted shell material comes from the
  original source object; padding/cropping fixes belong to image preparation only.

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
- Executor-local promotion of a `page_object` shell into an inline companion
  shell. If a page-object render is the canonical material for an inline source
  chain, Stage 1 must say so explicitly with `placement=INLINE`.
- Dropping a TextFrame merely because a `PLACE_TEXT_SHELL` plan references it.
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
