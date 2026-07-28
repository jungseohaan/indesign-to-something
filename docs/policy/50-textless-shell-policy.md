# Source Ownership Policy: Textless Shells

> This file is part of the canonical source ownership policy.
> The canonical index is `docs/specs/POLICY-source-ownership.md`.

## 7. Textless Shell

`PLACE_TEXT_SHELL` means the shell slot and text slot are separate.
The shell is textless graphic material. Editable/searchable text owned by the
same source relation is emitted separately as HWPX text/table structure above
that graphic material. `PLACE_TEXT_SHELL` is not a request to rebuild the shell
as HWPX fill/stroke/table style, and it is not a foreground mask over editable
text. If the original source intentionally requires graphic pixels to cover
editable text, Stage 1 must choose between accepting that visual loss or
materializing the bundle as `COMPLETE_PNG`.

Execution requirements:

- Shell material is `EXTRACTED_PNG_VECTOR` or explicitly planned
  `NATIVE_SOURCE_SHAPE`.
- For a stroke-only `NATIVE_SOURCE_SHAPE` whose source bounds are effectively a
  line, the source stroke weight is the visual thickness. Executors materialize
  that original source line as a line visual, not as a HWPX text box or
  rectangle fallback.
- Extractor chooses `EXTRACTED_PNG_VECTOR` textless shells from source structure:
  direct editable TextFrames paired with direct visual-only shell sources under
  the same source owner. Text length and rendered size are not ownership tests.
- The extractor records accepted/rejected textless-shell candidates as
  diagnostics. Diagnostics explain missing metadata or invalid structure; they do
  not create ownership in later stages.
- When an editable TextFrame is a child of a visible source
  Rectangle/Oval whose bounds contain the TextFrame, that parent source
  shape may be the canonical `NATIVE_SOURCE_SHAPE` shell owner. The shell style
  comes from that original shape, not from a synthetic HWPX default.
- A direct visual shell shape and a direct editable TextFrame under the same
  source group form a source-declared sibling shell slot. The preferred
  materialization is a textless `EXTRACTED_PNG_VECTOR` made from the original
  shell source, with the editable TextFrame owned by HWPX text. A
  `NATIVE_SOURCE_SHAPE` sibling shell is only a fallback when no extracted
  shell candidate exists and the source shape is simple enough to preserve
  faithfully as HWPX native geometry.
- The source group requirement is strict for filled/complex shells: arbitrary
  page/spread-level siblings are not shell siblings. A direct sibling shell slot
  may be emitted when the shell source and exactly one editable TextFrame are
  direct children of the same source `Group`, and that shell source does not
  contain any other editable TextFrame. If the same shell source matches
  multiple editable TextFrames, it is a container/background candidate, not a
  label shell slot.
- Ungrouped page/spread-level outline fragments are allowed only as a closed
  source-local outline cluster: every fragment must be a visual-only,
  stroke-bearing, non-placed source item with the same parent and layer as one
  editable TextFrame, the cluster's union must frame that TextFrame, and no
  fragment may contain editable text. This is still a Stage 1 source ownership
  decision; later placement code must not infer or repair it from visual
  overlap. Filled backgrounds, placed images, broad masks, and fragments that
  match more than one TextFrame remain independent visual/content/background
  candidates.
- A table/carrier sibling decoration shell is local, not page-wide. If one
  source parent contains several visual-only decoration siblings and
  marker-only/table-carrier TextFrames, Stage 1 must first split the visual
  siblings into page-local connected components whose visible bounds overlap or
  touch. A candidate may include only the marker/carrier TextFrames whose
  bounds overlap or touch that component. Sharing the same parent, layer, or
  table carrier alone is not enough to make distant siblings one executable
  shell PNG.
- Suppression for such a shell is component-local. A broad parent may keep
  provenance in `sourceObjectIds`, but it may not suppress or absorb child
  shell/content visual slots outside the local connected component that the
  candidate actually exports.
- A page/floating decoration group must not be split into inferred
  `direct_child_shell_slot` children merely because it contains several visual
  shapes and TextFrames. Group splitting is allowed only for source-declared
  inline objects or for the explicit direct sibling shell slot above. This keeps
  independent markers/backgrounds from being absorbed into a parent shell and
  disappearing from their own ownership slot.
- If a source-declared closed page/floating decoration group has multiple
  editable TextFrame children whose source metadata shows non-reflowable
  decoration behavior, such as rotated TextFrames, overflowing TextFrames, or
  duplicated shadow/outline text for the same glyph run, Stage 1 materializes
  that closed group as `COMPLETE_PNG`. In that case the PNG owns the group's
  `TEXT_SLOT` as `OWNED_BY_PNG`, the extractor must not hide the TextFrames
  during export, and no separate HWPX TF is emitted for those child TextFrames.
  Ordinary source-declared shells without those source metadata signals remain
  textless `PLACE_TEXT_SHELL` plus HWPX text.
- A broad parent/group candidate must not itself be labeled
  `direct_child_shell_slot`. That role belongs only to the closed local pair of
  one visual shell source and one editable TextFrame source, or to an explicitly
  anchored inline source object. A parent that contains several such pairs is a
  carrier/provenance group. It may export only residual background/container
  material after the child slots are removed, or it must be dropped.
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
- If a candidate shell contains another shell candidate for the same complete
  owned TextFrame set but also contains additional visible source shell material
  outside that child slot, the containing source shell is the canonical owner.
  The child candidate is already baked into that shell material and must not be
  emitted as a second visible shell.
- A `direct_child_shell_slot` is allowed only when the child is an independent
  source cluster. Independence is a source-structure fact, not a size/bounds
  fact: the child cluster must have its own closed source root, its own
  `TEXT_SLOT`/`SHELL_SLOT` channel, and removing it from the parent must not
  break the parent's container/chrome source role.
- A parent container/chrome cluster must not be split into direct child shell
  slots when the child visual sources are structural parts of that same
  container, for example a browser/window frame, speech-bubble frame, table-like
  frame, callout frame, or other UI chrome whose outline is completed by
  multiple descendants. In that case the parent owns one `SHELL_SLOT`, exported
  textless, and editable child TextFrames remain separate HWPX text.
- A source root may still be only a multi-slot carrier. If a direct child
  cluster owns one complete `TEXT_SLOT`/`SHELL_SLOT` and the parent has residual
  direct shell/style visual children, with or without residual editable
  TextFrame children, Stage 1 must split the root into the direct child shell
  slot and a residual sibling shell slot. The carrier root itself is not
  exported as one visible PNG. The split is based on source parentage/channel
  ownership, not on bounds, area, color, or visual overlap.
- If a parent composite owns additional TextFrames outside a truly independent
  direct child shell slot, Stage 1 may split the bundle: the direct child shell
  keeps its own TextFrame/source ownership and placement, while the parent
  retains only remaining source material that forms a complete residual source
  cluster. The parent may keep broad `sourceObjectIds` for provenance, but its
  `exportSourceObjectIds` must exclude the direct child shell's visible source
  ids and list those omitted child ids in `hiddenVisualSourceObjectIds`.
- A residual parent shell is executable only when its residual
  `exportSourceObjectIds` describe a complete source-structure slot. A leftover
  fragment of a container/chrome shell is not executable merely because it has
  bounds or visible pixels. Stage 1 must choose either the parent container shell
  or independent child shell atoms before export; later stages must not repair
  this by layer changes, drops, or promotion.
- When deciding whether child shell slots fully cover a composite carrier, the
  carrier root source id is not automatically structural. If the root page item
  itself has visible fill/stroke/vector material, that root material is residual
  `SHELL_SLOT` material and the carrier must remain executable unless another
  canonical plan explicitly owns that same root visual source.
- A source item whose type is `Group` is not automatically non-visual. If IDML
  metadata reports visible stroke/fill on the group root, that root participates
  in the shell slot just like a Rectangle/Oval/Polygon source.
- For `pass.inline_objects`, a parent inline composite that contains multiple
  direct text-owning shell children is a carrier, not the executable shell slot.
  The extraction plan must emit direct child shell candidates before render/export
  and suppress the parent composite candidate when those children cover multiple
  editable TextFrames. Exporters may render the declared child source target even
  when that child is not itself the story anchor; this is execution of the Stage 1
  plan, not a post-plan inline/floating reclassification.
- If such an inline carrier has a direct child badge group whose TextFrames use
  only built-in/no paragraph styles, include at least one source-declared simple
  marker TextFrame, and the carrier has a separate residual content/title
  TextFrame sibling, Stage 1 may materialize that child badge as a `COMPLETE_PNG`
  owner. The residual content/title TextFrame remains `OWNED_BY_HWPX_TEXT`.
- Direct child shell slots are planned in Stage 1 before normalization/render
  execution, after the parent composite's closed source cluster and residual
  export sources are known. This ordering is part of ownership, not an executor
  optimization: the child slot must become its own executable plan before any
  later stage can place or drop the parent carrier.
- A `direct_child_shell_slot` produced through `pass.inline_objects` is an export
  channel, not automatic proof of `STORY_FLOW` placement. Unless the child slot
  itself is the executable inline source slot, Stage 1 keeps the child shell in
  page/source coordinates (`FLOATING`/`PAGE`) and emits its editable TextFrame as
  HWPX text above the shell. When Stage 1 does declare the child slot as the
  executable inline source slot, the executor may use an inline `drawText`
  carrier with the planned native/extracted shell as image fill. It must not
  create the same shell from Java fill/stroke/corner fallback.
- This rule has precedence over inherited `sourceInlineFlow` flags: a child
  shell/text slot inside an inline composite layout is placed from its resolved
  source geometry (`FLOATING`/`PAGE`) unless the child source itself is the
  executable story/table anchor owner.
- If the TextFlow has a native `INLINE_SLOT` for a source group and that group is
  exported as a textless shell atom with editable child TextFrame ownership
  (`TEXTLESS_SHELL_WITH_TF` or equivalent source-declared atom metadata), that
  inline atom is the canonical owner of the `SHELL_SLOT` and its child
  `TEXT_SLOT` placement.  Direct child shell candidates from other extraction
  passes are alternate export channels only and must not win merely because they
  have fewer source objects, smaller bounds, or a more local source set.
- Child TextFrames owned by such a native inline text-shell atom inherit
  `INLINE` / `STORY_FLOW` placement from the atom in Stage 1. They must not be
  emitted again as independent floating TextFrames.
- Same-story floating-anchored non-text decoration fragments that are recorded
  in that atom's closed `atomicVisualSourceObjectIds` belong to the same
  `SHELL_SLOT`. In legacy bridge paths where that closed atomic source set is
  not available, the same rule may be applied only when the fragment's source
  story has exactly one inline text-shell owner on the page. Stage 1 absorbs
  those fragments into the inline shell owner's `exportSourceObjectIds` and
  expands that owner's bounds to cover the absorbed fragment. The alternate
  floating shell plans are hidden/dropped; the absorbed pixels themselves are
  not hidden from the owner export. Later stages must not emit the same terminal
  cap, tail, connector, or backdrop fragment as an independent
  `PLACE_TEXT_SHELL`.
- A composite rendered text shell that contains multiple direct shell slots is a
  layout container, not the canonical visible shell, only when every owned
  TextFrame is covered by distinct direct shell slots and the parent contains
  no additional visible shell material outside those child slots. Stage 1 then
  drops the composite parent visual.
- If the parent composite also contains connector/arrow/line/marker shell
  material that no direct child slot owns, the parent composite remains visible
  only when that residual material is a complete independent source cluster. It
  does not re-own child shell slots merely because they share a recursive source
  cluster. Conversely, a child shell does not become executable merely because it
  has editable text descendants; it must pass the independent source cluster rule
  above.
- Inline composite carriers follow the same source-slot coverage rule. Splitting
  an inline composite into child shell/text slots removes those child visual
  source ids from the parent export; any remaining connector/line/marker source
  ids stay on the parent as a residual `SHELL_SLOT`. The parent is dropped only
  when no visible residual source remains.
- A textless connector/line/marker source inside an inline composite is still an
  exportable residual shell source for that composite. The generic duplicate
  guard that excludes inline-owned descendants does not apply to this explicit
  residual decoration slot, because the inline composite is the source-declared
  owner of that visual channel.
- TextFrames listed through a residual parent's `hiddenVisualSourceObjectIds`
  are excluded from inferred HWPX text ownership for that parent. They may be
  owned only by the explicit child shell/text plan that removed them from the
  parent visual channel.
- A text-hidden composite shell carrier is an extracted source bundle with
  editable TextFrames assigned to HWPX text, a textless PNG/vector shell, no
  placed Image/PDF/EPS content in the shell slot, and visual-only child shell
  fragments from the same closed source tree. If the carrier also has visible
  shell material outside those child fragments, the carrier owns only a residual
  `SHELL_SLOT` for that outside material. Child `deco_*`, `graphic_*`, or
  equivalent render-channel fragments remain executable only when Stage 1
  declares them as direct child shell slots; otherwise they are non-executable
  diagnostics. They are not placed and then removed later.
- `sourceObjectIds` may describe the full source ancestry of a composite
  carrier, but shell execution is defined by `exportSourceObjectIds`. A
  textless shell export must exclude descendant placed-visual branches
  (`Image`/`PDF`/placed graphic carriers); those branches are separate
  `CONTENT_VISUAL_SLOT` owners. Conversely, a content composite that exists
  only to expose placed visual material keeps the broad carrier as provenance
  but uses the placed-visual branch as its export source channel. Later render
  steps must not widen that channel back to the full ancestry.
- A text-hidden carrier may still list editable TextFrame ids for provenance.
  When `textAction=DROP_TEXT`, those ids do not make the carrier a text owner;
  they describe the split between the carrier's visual `SHELL_SLOT` and the
  direct HWPX text plans. Planner duplicate suppression must therefore evaluate
  the carrier by its visual slot and shell role, not by the mere presence of
  `ownedTextFrameIds`.
- A visible `PLACE_TEXT_SHELL` with no `ownedTextFrameIds` is textless visual
  material. It must use `textAction=DROP_TEXT`; it must not claim
  `OWNED_BY_HWPX_TEXT` simply because its source role is a text shell or because
  a nearby TextFrame exists elsewhere in the source tree.
- A visible `PLACE_TEXT_SHELL` may switch from text-owning to visual-only when
  every listed `ownedTextFrameId` already has a separate materialized HWPX
  text/table owner. In that case the shell keeps the ids only as relation
  metadata and keeps its visual shell action; it does not create another text
  owner and does not become a complete PNG.
- The same applies when deciding whether a parent composite is covered by direct
  child shell slots. A child carrier with `textAction=DROP_TEXT` may still list
  editable TextFrame ids as provenance; that must not exclude it from child
  shell coverage. Only a child plan that still owns text (`OWNED_BY_HWPX_TEXT`)
  is a text owner rather than a visual-only child carrier.
- A parent textless composite carrier is executable only while it owns at least
  one visible source slot that is not already owned by a smaller canonical plan.
  If every visible source in the parent carrier is either a non-painting grouping
  source, an editable TextFrame owned by HWPX text, or a visual source owned by a
  smaller visible plan, the parent carrier must be `DROP_VISUAL`. This is a
  Stage 1 source-slot coverage decision, not a later occlusion or z-order repair.
- Source-slot coverage is keyed by source object identity. For inline/story-flow
  sources, the canonical owner can have a different `pageIndex` from a page-local
  carrier because the owner follows story placement while the carrier follows
  page placement. Page index mismatch must not keep a parent composite visible
  when the same inline source id already has a canonical inline owner.
- A direct child shell slot may be suppressed as "baked into" a parent composite
  only when the parent's `visualSourceObjectIds` explicitly include the child's
  visible shell `visualSourceObjectIds`, or when the parent satisfies the
  text-hidden composite shell carrier rule above. Source containment alone is not
  proof that the parent PNG owns the child shell slot.
- Parent-composite shell restoration is limited to the canonical rendered
  page-object channel, currently `rendered_floating_item:*:page_object`. A
  `rendered_graphic_frame` fallback is not allowed to become the restored
  canonical shell carrier because it may use broader/untranslated source bounds
  and duplicate the same visual slot already represented by the canonical
  rendered floating item.
- `rendered_graphic_frame` material is never a second chance for a source bundle
  whose shell/content slot already has a canonical `rendered_floating_item` or
  inline owner. It may be canonical only when Stage 1 proves from source metadata
  that it is the sole material channel for that slot, or that it owns a distinct
  content slot not represented by the page/inline channel. Different crop size,
  translated bounds, or fallback availability are not ownership reasons.
- A multi-TextFrame composite wrapper must not be restored as a second text shell
  when a more direct child shell already owns the same complete TextFrame set.
  Any additional visible material outside that child must survive only through
  distinct non-text visual slots.
- For a given page and complete `ownedTextFrameIds` set, only one visible
  `PLACE_TEXT_SHELL` plan may own that text set. If multiple rendered channels
  claim it, Stage 1 keeps the most direct source bundle as the canonical shell
  owner and drops wrapper render channels from text/shell ownership.
- Direct child slots are derived from source parentage and source style, but
  parentage/style are only necessary signals, not sufficient ownership proof. A
  local source shape paired with editable text owns the shell slot only when it
  also satisfies the independent source cluster rule above. A carrier source
  shape paired with a table/text carrier owns the carrier/style slot and must not
  be split merely because it contains direct editable TextFrame descendants.
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
