# Source Ownership Policy: Source Bundles And Slots

> This file is part of the canonical source ownership policy.
> The canonical index is `docs/specs/POLICY-source-ownership.md`.

## 2. Source Bundle, Slot, Material

A source bundle is the closed source tree or extractor-declared source root that
acts as one conversion unit. Ownership is decided by slot, not by raw source id.

Regression-proof ownership depends on two separate identities:

- ancestry identity: every original source id that explains where a candidate
  came from;
- visible slot identity: the exact source ids whose pixels/styles/text are
  visible through that candidate.

`sourceObjectIds` are ancestry. They may be broad. They are not enough to prove
that a plan owns a child slot.

`visualSourceObjectIds`, `styleSourceObjectIds`, and `ownedTextFrameIds` are the
visible slot claims. They must be narrow and executable. Every duplicate,
suppression, layer, and validation decision uses these slot claims, not raw
ancestry containment.

Required slots:

- `TEXT_SLOT`: HWPX paragraph/run, TextFrame text, and table text.
- `SHELL_SLOT`: textless label/background shell, outline, callout shell, or
  container chrome.
- `TABLE_STYLE_SLOT`: cell fill, border, inset, row/column sizing, and fixed
  table bounds.

An IDML table whose source story is table-only owns `TABLE_STYLE_SLOT` and is
materialized as an HWPX table unless Stage 1 assigns that same table source to a
more specific anchored/table owner.  The table cell paragraphs remain editable
HWPX text inside the table.  Nearby floating markers or source-positioned
graphics keep their own `CONTENT_VISUAL_SLOT`/`SHELL_SLOT`; their placement is
not used as a reason to dissolve the table into independent TextFrames.
- `CONTENT_VISUAL_SLOT`: photo, illustration, chart, QR, complete marker, or
  graphic-only complete visual.

Stroke-only leaf vector sources are shell material when their IDML source is a
drawable line/container/grid object: `GraphicLine`, `Rectangle`, `Oval`, or
`Polygon` with visible stroke, no visible fill, no placed visual descendant, and
no child page items.  If such a vector candidate is narrower than its recursive
source cluster, Stage 1 must declare it as a slot-only `SHELL_SLOT` by recording
the vector source in `exportSourceObjectIds` and the omitted cluster descendants
in `hiddenVisualSourceObjectIds`.  This preserves native table/grid lines and
container outlines without treating them as independent content.  Filled
vectors, placed-content frames, and vectorized glyph/content fragments that do
not meet this source contract remain outside this rule.
Because these sources are strokes/lines rather than filled label faces, their
visual layer is `CONTAINER_OUTLINE`, not `LABEL_BACKDROP`.  The native line must
be drawn from the Stage 1 `NATIVE_SOURCE_SHAPE` plan in source z-order; later
stages must not hide it behind table/text carriers or recreate it from rendered
overlap.

`NATIVE_SOURCE_SHAPE` plans are executable ownership plans, not diagnostics.
The extractor must always write planner-declared `ObjectPlan` records for these
non-PNG materializations, and the converter must import them before legacy
rendered-group ownership is normalized.  Rendered PNG channels cannot recreate
these sources because no PNG file exists by design.  Later stages therefore
must not infer, recover, or drop these native vectors from rendered bounds or
occlusion; they may only execute the Stage 1 plan or report a validation error
when required source metadata is missing.

Executable owner priority:

1. `sourceObjectIds` prove ancestry only. They never prove visible ownership by
   themselves.
2. `visualSourceObjectIds`, `styleSourceObjectIds`, and `ownedTextFrameIds`
   declare executable ownership. These fields, not ancestry containment, decide
   whether a slot is visible, hidden, dropped, or duplicated.
3. A direct child/sibling source shell slot that owns one local editable
   TextFrame is a closed executable `SHELL_SLOT` when Stage 1 declares
   `slotRole=direct_child_shell_slot`. The parent may keep that child source id
   only as provenance.
4. A broad parent/group candidate is executable only for residual visual
   material not owned by child slots. If child `SHELL_SLOT`, `CONTENT_VISUAL_SLOT`,
   or `TABLE_STYLE_SLOT` owners cover all painted descendants, the parent is
   provenance-only and must be `DROP_VISUAL`.
5. If a broad parent hides a child source id in `hiddenVisualSourceObjectIds`,
   that child is absent from the parent rendered asset. A planned child
   candidate for that source remains executable and must not be suppressed by
   the parent ancestry.
6. A parent may suppress a child shell only when the child source is explicitly
   listed in the parent's executable visible source claim
   (`visualSourceObjectIds` or `exportSourceObjectIds`) for the same slot. Raw
   containment in `sourceObjectIds` is never enough.
7. Two shell plans may reference the same editable TextFrame while owning
   different visual sources. In that case the `TEXT_SLOT` is still single-owner,
   but each disjoint `SHELL_SLOT` remains executable. A duplicate-text-owner
   resolver may drop secondary text ownership, but it must not drop the visual
   shell unless the executable visual source sets overlap or one is explicitly
   claimed by the other.

Required materialization values:

- `HWPX_TEXT`
- `HWPX_TABLE_STYLE`
- `EXTRACTED_PNG_VECTOR`
- `TEXTLESS_VISUAL_FRAGMENT`
- `NATIVE_SOURCE_SHAPE`
- `COMPLETE_PNG`

Slot ownership rules:

- Different slots in the same source bundle may be visible together.
- The same slot in the same source bundle may be visible only once.
- A `PLACE_TEXT_SHELL` plan materialized as `TEXTLESS_VISUAL_FRAGMENT` owns
  `SHELL_SLOT`, not `TEXT_SLOT`. If editable text is deduplicated to a direct
  HWPX TextFrame owner, Stage 1 must keep the textless shell visual unless a
  different visible plan already owns that same `SHELL_SLOT`.
- A visible parent composite plan may keep broad `sourceObjectIds` for tracing,
  but it must remove from `visualSourceObjectIds` every descendant source id
  whose `TEXT_SLOT`, `SHELL_SLOT`, `TABLE_STYLE_SLOT`, or `CONTENT_VISUAL_SLOT`
  is owned by another visible plan. If a parent PNG file still contains those
  descendant pixels, the parent plan is not a valid visible owner for that file.
  Stage 1 must either split the parent into source-slot owners or drop the
  parent composite. Later stages must not rely on z-order to hide the duplicate.
- A parent composite whose extracted PNG bakes editable text pixels or a child
  text-shell marker into the image cannot be retained as a background/container
  visual for that same area unless Stage 1 assigns that text/marker slot to
  `OWNED_BY_PNG`. If the text/marker is HWPX-owned, the parent composite is an
  ancestry carrier only and must not emit the baked PNG.
- A broad parent/background render is allowed only when its
  `visualSourceObjectIds` describe a pure background/container slot after child
  text, label, table-style, and content slots are subtracted. If subtraction
  would require editing pixels out of an already exported PNG, that render is
  not executable; the extractor must provide a textless/childless render or
  Stage 1 must choose direct child slots instead.
- Extraction planning must prefer not creating duplicate pixels over creating
  them and deleting them later. When a parent decoration/background source owns
  a `SHELL_SLOT` but contains a child source with its own `CONTENT_VISUAL_SLOT`
  or independent text-owning shell slot, the extraction candidate for the parent
  must be `SLOT_ONLY`: its executable `sourceObjectIds`/visible source set
  exclude the child content owner, and the exporter hides those child sources
  before rendering the parent shell asset. The child content owner remains a
  separate candidate. A child shell that owns the same editable TextFrame as the
  parent shell is not emitted separately when the parent provides the executable
  childless/textless shell render for that same slot.
- A `SLOT_ONLY` parent may record broad ancestry ids for traceability, but that
  ancestry must not suppress a child candidate listed in the parent's
  `hiddenVisualSourceObjectIds`. A hidden child visual is, by definition, absent
  from the parent rendered file; if it has its own planned source candidate, that
  candidate remains executable and may materialize as one atomic textless graphic
  PNG.
- A leaf `Polygon` vector inside a text-shell carrier may be a non-editable
  outline glyph, for example an outlined label word converted to vector paths.
  If its ancestor source tree contains editable TextFrames and the polygon has
  its own `VECTOR_CANDIDATE`, Stage 1 treats that polygon as a distinct
  `CONTENT_VISUAL_SLOT`, not as part of the carrier `SHELL_SLOT`. The parent
  `SLOT_ONLY` shell must list that polygon in `hiddenVisualSourceObjectIds` and
  export without those pixels; the polygon keeps its own ObjectPlan. This is a
  source-tree slot split, not a text/content heuristic.
- A Rectangle/Oval/Polygon source that has nested page items is not a leaf
  vector source. It must not also emit a `pass.vector_shape_frames` candidate for
  the ancestor source. Stage 1 must choose a closed complex/content/shell
  fragment contract for that source tree instead of creating competing vector
  and complex render channels for the same `CONTENT_VISUAL_SLOT`.
- Conversely, a child shell source id that remains in a visible executable
  `SLOT_ONLY` parent's `sourceObjectIds` and is not listed in
  `hiddenVisualSourceObjectIds` is already baked into that parent shell asset.
  Stage 1 must not create a second vector/source-shape owner for the same shell
  slot, even if the child source shape is individually drawable as a
  Rectangle/Oval.
- If a parent `SLOT_ONLY` shell declares a child source id in its
  `exportSourceObjectIds`, that child source's `SHELL_SLOT` is already the
  parent's visible slot. Stage 1 must not emit the same decoration child as a
  separate visible candidate. If the parent instead lists the child in
  `hiddenVisualSourceObjectIds`, the child is absent from the parent asset and
  may remain executable as its own slot owner.
- The same rule applies to migrated ObjectPlan records that do not yet expose
  `exportSourceObjectIds`: if a visible composite shell carrier declares a
  non-placed visual child source in `sourceObjectIds`/`visualSourceObjectIds`,
  that child is part of the carrier's executable shell asset. Stage 1 must not
  also emit the child as a separate visible vector/native shape plan unless the
  child owns a distinct text/shell/content slot.
- A nested `TEXTLESS_VISUAL_FRAGMENT` shell carrier follows the same slot
  closure rule. If it has `DROP_TEXT`, has no placed-content source tree, and
  all of its text/source relations are contained by a broader visible composite
  shell carrier, it is provenance for the broader shell asset, not a second
  visible shell owner.
- A `pass.editable_textframe_visual_shells` candidate is a shell candidate for
  ownership normalization. If a broader same-page shell owns the same editable
  TextFrame id and contains that TextFrame source, the narrower text-frame shell
  is not emitted separately. The text remains HWPX-owned once, and the broader
  shell provides the visual shell slot.
- Placed content source types such as Image, PDF, and EPS own a
  `CONTENT_VISUAL_SLOT`. If the same source group also contains editable
  TextFrames or label shells, those are separate text/shell slots; the placed
  content must not be reclassified as a text shell.
- A frame/group whose source tree contains a placed content child
  (`Image`, `PDF`, or `EPS`) is a content-bearing source for that child slot.
  A parent Rectangle that clips or frames a PDF/Image must not be reclassified
  as `CONTAINER_FACE`, shadow, text shell, or other decoration merely because
  its own source type is a shape or because its rendered pixels look paper-like.
- The extractor must not emit a `SHELL_CANDIDATE` from a source tree that
  contains placed content (`Image`, `PDF`, or `EPS`). Such a tree is a
  `CONTENT_VISUAL_SLOT` candidate. Any sibling/child label shell in the same
  parent group must be emitted from its own source subtree, so the placed
  content PNG cannot bake editable-text shell material into the same extracted
  file.
- An image-backed extracted render that already contains placed content plus
  its local visual-only chrome owns the closed content visual slot for those
  child sources. Stage 1 must not narrow that render to only the placed Image/PDF
  source and then recreate the local chrome as separate native shells.
- Parent/child source bundles may both be visible only when they own different
  slots. A parent `descendantVisualObjectIds` claim must be pruned to match the
  final visible child plans.
- Render channels for the same source root are not parent/child ownership. A
  `rendered_floating_item`, `rendered_graphic_frame`, image-frame render, or
  fallback render with the same source root must be resolved as competing
  channels for the same slot. One channel may win, but it must not drop another
  same-root channel using a "child baked into composite parent" rule.
- Planner passes are not ownership namespaces. If `pass.decoration_groups`,
  `pass.complex_graphic_frames`, `pass.inline_objects`, image-frame extraction,
  or any fallback pass emits the same closed source bundle for the same visible
  slot, those candidates are competing render channels for one slot. Stage 1
  must choose one canonical visible `ObjectPlan` and write non-canonical pass
  candidates as `DROP_VISUAL` for that slot. A later pass must not "restore" a
  duplicate shell/content/background from the same bundle using a different
  rendered id or broader bounds.
- Master page graphics are page-applied source material. The extractor must
  emit one candidate for every applied page where the master source bundle has a
  visible intersection with that page. Facing-page or spread-spanning master
  bundles must be clipped/intersected per applied page before Stage 1; a padded
  overlap may keep only small bleed edge decorations, not large page/spread
  composites. A generated master PNG without a corresponding applied-page result
  row is an extraction defect, not a planner decision.
- Applied-master candidate identity is the authored master source tree for the
  visible cluster. Page-local indexes may be used only to decide which document
  page receives the fragment. When the PNG exporter can only materialize a
  visible direct master fragment, the exported source-set may be a closed subset
  of the Stage 1 applied-master source-set. That subset is still planned
  material only when every exported source belongs to one authored source tree
  already covered by the Stage 1 candidate. A broad applied-master composite
  must not become an inline object merely because it contains one inline source.
- Master page graphic PNGs that participate in `SHELL_SLOT`/background
  ownership are textless source material. Visible master TextFrames in the same
  planned source bundle are hidden before export and recorded as
  `hiddenTextFrameIds`, even though those TextFrames may not have a document
  `parentPage` until they are instantiated. If the same title/body text appears
  both in HWPX text and inside a master PNG, the extractor violated the
  textless-material contract.
- An applied-master visual bundle is executable when Stage 1 has the
  applied-page candidate and non-empty `visualSourceObjectIds`, even if those
  source ids do not appear as normal document page items. The applied-page
  candidate is the page-local source contract; missing page-local source-index
  metadata must not turn the bundle into `DROP_VISUAL`.
- If InDesign cannot override a visible master item onto a temporary page, the
  extractor may export that original master item directly as the source
  material for the same applied-page visible intersection. This is still an
  extractor result for the original source bundle, not a Stage 1 fallback or a
  symptom-based restoration. The result row must carry the original source ids
  and page-local clipped bounds. It must not use a fallback filename/channel,
  and Java must not promote or restore it as an alternate owner after planning.
- If that directly exported master fragment is the closed source tree of an
  object that IDML/resolved story flow declares as an inline anchor, Stage 1
  binds the material to that inline slot and writes `placement=INLINE` /
  `coordinateSpace=STORY_FLOW`. The fact that the material came through a
  master/page render channel does not make it floating.
- Source containment alone is not visual ownership. A parent may suppress a child
  visual only when Stage 1 can prove an ownership slot relationship from source
  metadata: either the parent's `visualSourceObjectIds` claim includes that child
  visual slot, or the parent is a text-hidden composite shell carrier as defined
  below. Raw `sourceObjectIds` containment without that carrier/source-slot proof
  is trace ancestry and must not suppress visible child material.
- For a complete rendered composite PNG (`PLACE_FLOATING_PNG` /
  `EXTRACTED_PNG_VECTOR` from `mixed_group_text_hidden`,
  `image_group_text_hidden`, or `complex_graphic_text_hidden`), `sourceObjectIds`
  are ancestry/tracing metadata and `visualSourceObjectIds` are the final visible
  slot claim. If Stage 1 keeps that complete parent visible, a visual-only child
  shell may drop only when the parent's `visualSourceObjectIds` include that child
  shell slot. Editable TextFrame descendants still keep their own HWPX text
  owners, and source containment alone never suppresses their shell material.
- A parent group that only organizes direct child slots is an ownership
  container, not a visible shell. If its editable TextFrames are covered by
  direct child shell/table/background slots, the parent composite render is
  dropped and the direct slots own the visible material.
- A composite text-shell render does not suppress a direct native shell merely
  because it contains the same source shape. Stage 1 may split that composite
  into direct source slots, and then drop the composite parent if the direct
  slots cover the visible material.
- A composite text-shell carrier must not suppress a direct inline/anchored
  atomic text shell slot. If the source item or owned TextFrame is inline, that
  direct shell remains the `SHELL_SLOT` owner even when a broader page-positioned
  composite render contains the same visual pixels. The broader composite may
  own only its extra background/container material.
- `EXTRACTED_PNG_VECTOR` must reference an extracted file made from the source
  material.
- `NATIVE_SOURCE_SHAPE` must reference an original source shape and its source
  style. It is not a synthetic replacement inferred from bounds.
- A visible leaf vector candidate from `pass.vector_shape_frames` is executable
  visual material, not a diagnostic native fallback. It must materialize as
  `EXTRACTED_PNG_VECTOR` using the declared IDML source set and must preserve
  the source z-order in the resulting ObjectPlan. It must not be skipped with a
  no-op/native-source-shape export path.
- A clipped or clip-carrying ancestor does not automatically become the visible
  owner of child visual material. Stage 1 may execute only the source set
  declared by the extractor/planner for that slot. Later Java planning must not
  restore a clip ancestor as owner or drop the child visual merely because of
  source ancestry.
- `NATIVE_SOURCE_SHAPE` is limited to source shapes that HWPX can represent
  faithfully as simple native geometry. Polygon source shapes are not
  materialized as HWPX native polygons; they must use `EXTRACTED_PNG_VECTOR` or
  another extracted visual owner.
- A visible page/spread-level `Rectangle` or `Oval` source shape with no text
  frame descendant and no placed-content descendant may be the page-local
  `BACKGROUND` owner when its original source geometry materially intersects a
  page. If the same source shape crosses a spread boundary, Stage 1 creates one
  page-local `PAGE_BACKGROUND` plan per intersected page in the same spread. The
  facing page is selected from the source page's spread-side bounds, not by
  scanning every page whose bounds happen to overlap repeated spread
  coordinates. Later stages must only execute those plans and must not resurrect
  or drop the background from rendered PNG symptoms.
- `NATIVE_SOURCE_SHAPE` is a fallback materialization for explicit shell plans
  whose executor is guaranteed by Stage 1. It is not allowed for
  `pass.vector_shape_frames`; visible vector source material must have an
  extracted visual owner instead of relying on Java/HWPX stages to redraw it.
- When duplicate visible-source-slot normalization compares an
  `EXTRACTED_PNG_VECTOR` `SHELL_SLOT` owner with one or more
  `NATIVE_SOURCE_SHAPE` owners for the same original source shape, the extracted
  shell is the canonical owner. Native source shapes are executed only when no
  extracted owner exists for that source slot.
- For an extracted text shell, duplicate-source-slot comparison uses executable
  visual/style claims only: `visualSourceObjectIds`, `styleSourceObjectIds`, and
  equivalent slot-owner fields declared by Stage 1. `sourceObjectIds` remain
  provenance and must not suppress a child inline/content/shell slot merely
  because the child source is structurally contained in the parent bundle.
  Editable `ownedTextFrameIds` remain in `TEXT_SLOT` and are excluded from shell
  duplicate comparison.
- A multi-source extracted `SHELL_SLOT` must be exported from the declared
  `exportSourceObjectIds` as a source set. The executor may use their common
  source parent as the InDesign export target, but it must hide out-of-slot
  siblings before export. It must not narrow the export target to only one root
  source when the slot declares multiple visual sources.
- `COMPLETE_PNG` owns both the visual and any text pixels inside that source
  bundle. It is allowed only when the source bundle is a complete visual owner.
- A source tree that contains editable TextFrame descendants is not a complete
  visual owner unless Stage 1 explicitly assigns those descendants to
  `OWNED_BY_PNG`.
- The extractor must not shrink a text-owning inline source tree to only the
  inline root id when emitting an `inline_object` candidate. It must carry the
  closed source ids and descendant TextFrame ids, or not emit the inline
  candidate when an existing shell candidate already owns that same source
  bundle. Otherwise Stage 1 sees the same shell slot as two unrelated bundles.
- An `inline_object` candidate belongs to the page of the inline source item
  itself, not to the first TextFrame scanned for the parent story. Threaded
  stories may span pages; using the story's first frame page makes candidate
  identity disagree with the rendered inline result.
- A shell material must be textless. A rendered PNG that still contains editable
  text pixels is not a shell.
- A rendered visual whose `visualAction` is `DROP_VISUAL` must not keep editable
  text ownership. The HWPX text is owned by explicit TextFrame/table text plans
  or by the surviving visible shell slot.

### `PLACE_TEXT_SHELL` roles

`PLACE_TEXT_SHELL` is only the materialization action. It is not enough to decide
layering, crop behavior, duplicate suppression, or text ownership. Every visible
`PLACE_TEXT_SHELL` plan is classified by Stage 1 into exactly one execution role:

- `BACKGROUND_SHELL`: page or container background shell. It belongs to the
  `BACKGROUND` policy layer and must not own editable text. It may participate
  in page-intersection/overflow background handling, but it must not be treated
  as a label or text-owner.
- `LABEL_SHELL`: label backdrop or overlay shell. It is decorative chrome for
  nearby/owned HWPX text and is placed behind that text unless its source layer
  explicitly says it is a foreground mask.
- `TEXT_OWNING_SHELL`: shell whose slot owns one or more editable TextFrames.
  The shell visual and the HWPX text are separate channels in the same source
  bundle. Later stages may place the shell and text, but may not split,
  inline/floating-flip, or recompute ownership.
- `CONTENT_SHELL`: textless shell-like material that is visually content rather
  than a background or label. It follows `CONTENT` layer rules and must not
  suppress editable text unless Stage 1 assigned that text to `OWNED_BY_PNG`.

Colored fill/stroke alone does not make a shell a `LABEL_SHELL`. If a textless
shell is the broad carrier/backdrop for editable body text, Stage 1 classifies
it as `BACKGROUND_SHELL`/container backdrop even when the source shape has a
non-paper fill. A smaller label/button shell in the same source group may remain
`LABEL_SHELL`; this distinction must come from source role, owned TextFrame
relationship, and source bounds, not from page text or post-render occlusion.

Later stages may ask for this role, but may not reclassify a shell from pixels,
page number, literal text, color, bounds, or occlusion symptoms. If the role is
wrong, the fix belongs in Stage 1 ownership planning or extractor metadata.

Within one recursive source cluster, shell duplicate suppression is role-aware.
A parent shell may absorb descendant visual-only fragments only when those
fragments are part of the same shell role/source slot. A `BACKGROUND_SHELL` or
container carrier must not absorb a distinct `LABEL_SHELL`; a label shell must
not absorb a content visual; and a carrier shell must not suppress an independent
child shell merely because the bounds overlap. Source ancestry proves the
relationship, but the shell role decides whether the descendant is the same slot
or a separate visible slot.
