# Source Ownership Policy: Placement And Inline Ownership

> This file is part of the canonical source ownership policy.
> The canonical index is `docs/specs/POLICY-source-ownership.md`.

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
- `STORY_FLOW` plans must carry executable story-flow geometry. If the extractor
  provides spread/page-object bounds for an inline shell or inline graphic,
  Stage 1 must normalize or replace those bounds from the source story/table
  anchor and its owned TextFrame/run geometry before writing `ObjectPlan`.
  A `STORY_FLOW` `ObjectPlan` must not keep spread coordinates that place the
  material outside the owning page or outside the owning story/table flow. If
  the source metadata is insufficient to derive executable inline geometry,
  Stage 1 must report a validation defect instead of placing the object at the
  raw extracted coordinate.
- `PLACE_INLINE_PNG` executors preserve the planner/source-declared inline
  bounds. They may fill in missing dimensions from exported PNG DPI, but must
  not shrink shape-only or group-based inline graphics with arbitrary oversize
  caps. If an inline object is too large, its source slot geometry or Stage 1
  materialization is wrong and must be corrected before execution.
- When the same source bundle is available through both channels, Stage 1 keeps
  one canonical visible owner per slot. The non-canonical channel is dropped for
  that slot.
- IDML Story XML token order is the structural truth for story-flow placement.
  A `CharacterStyleRange` inline `Group`, `TextFrame`, `Rectangle`, `Polygon`,
  `Oval`, or `GraphicLine` creates an inline slot at its authored object
  replacement position even when resolved story runs omit that anchor. Resolved
  story runs may supply composed style, geometry, and diagnostic observations,
  but they must not erase an IDML inline slot or turn it into floating material.
  When IDML and resolved disagree, Stage 1 records the disagreement and plans
  from the IDML token stream plus source object metadata.
- If an IDML story run, IDML table-cell run, or resolved story run contains an
  inline anchor for that source id, the story-flow channel is usually the source
  owner for that slot. A paired `page_object` render is only a duplicate channel
  and must not convert the object to floating placement by itself.
- A `Character` or `InsertionPoint` source parent is an anchor relation, not an
  inline-placement contract by itself. Stage 0 must classify each
  story-anchored visual from the actual story token stream and source object
  metadata. A direct IDML story inline slot whose paragraph also contains
  visible story text is story-flow material when that slot owns the visual
  source tree; Stage 1 writes `placement=INLINE` and
  `coordinateSpace=STORY_FLOW` even if resolved DOM diagnostics also report
  `storyAnchorPlacement=FLOATING_ANCHORED` for the same root. A descendant
  object's page-positioned anchored setting does not move the root inline slot
  out of story flow.
- Compact direct story inline visuals such as marker/deco curves that are
  emitted from `pass.inline_objects` remain `INLINE/STORY_FLOW` when Stage 0
  records `storyTextInlineSlot=true` and the source tree is owned by that inline
  anchor. Page-positioned repair must not promote these small direct story
  markers to floating merely because InDesign also reports `Anchored`.
- Direct story inline visuals whose source paint/style name declares a text
  emphasis role, such as highlighter, underline, or equivalent editorial marker
  names, are not inline content visuals. Stage 1 plans them as
  `ABSORB_TEXT_STYLE` on `TEXT_SLOT`/style sources, or as non-advancing text
  decoration if native HWPX style cannot express the appearance. They must not
  be emitted as `PLACE_INLINE_PNG`, because an inline PNG consumes text advance
  and changes the paragraph flow.
- If the source bounds live in page space outside the carrier content box and
  the source is not the direct story-flow slot owner, Stage 0 writes
  `storyAnchorPlacement=FLOATING_ANCHORED`; Stage 1 then plans the object as
  `placement=FLOATING` and `coordinateSpace=PAGE`. Story/Text Builder must not
  emit an inline slot for those page-positioned anchored sources.
  Missing `storyAnchorPlacement` is not treated as `INLINE`; it is a metadata
  defect to fix in Stage 0.
- `PLACE_INLINE_PNG` requires explicit inline source evidence:
  `storyAnchorPlacement=INLINE`, `anchoredPosition=INLINE_POSITION`, or a
  Stage 0 `storyTextInlineSlot=true` fact obtained from an actual story anchor
  marker (`U+FFFC` or `U+0016`) that directly owns the source object.
  `parentKind=Character` / `InsertionPoint` alone is never sufficient because a
  floating anchored object also has a story character anchor. Explicit
  page-positioned source metadata normally wins over a generic marker: if the
  same source bundle contains `storyAnchorPlacement=FLOATING_ANCHORED` or
  `AnchoredPosition="Anchored"`, Stage 1 uses `placement=FLOATING` and
  `coordinateSpace=PAGE` unless Stage 0 also records a more specific direct
  story-flow ownership fact. Valid direct story-flow ownership facts include
  `storyTextInlineSlot=true` from the actual IDML story token stream and
  `tableCellStoryTextInlineSlot=true` when the direct story anchor is in a table
  cell and the source bounds overlap the cell carrier bounds. In that case the
  story/table-cell flow owns the slot, Stage 1 writes `placement=INLINE` /
  `coordinateSpace=STORY_FLOW`, and Story/Text Builder may insert the PNG at
  that paragraph position.
- An empty inline TextFrame with only frame paint is not automatically
  `CONTENT_VISUAL_SLOT`. When the same source pattern appears as a repeated
  baseline run of small empty inline frames, the run represents a text-flow
  blank/underline placeholder rather than independent content images. Stage 1
  must assign those frames to layout/text ownership (`DROP_VISUAL` or an
  explicit HWPX underline representation) instead of exporting each frame as a
  separate `PLACE_INLINE_PNG`. Isolated empty inline markers such as checkboxes
  may still own `CONTENT_VISUAL_SLOT` when source metadata does not show a
  repeated placeholder run.
- A table-cell inline anchor is not sufficient when the anchored source is a
  text-shell label whose source-positioned bounds sit outside the table cell's
  content area while the cell also contains real text runs. In that case the
  anchor records logical association with the table, but the shell/text owner is
  page-positioned label material and Stage 1 writes `placement=FLOATING` and
  `coordinateSpace=PAGE`.
- This table-cell external-label rule does not apply when the same paragraph as
  the direct inline anchor contains visible story text. In that case the anchor
  is part of the paragraph flow, and the shell keeps `INLINE` / `STORY_FLOW`.
- If the canonical material for that inline-anchored source is available through
  a `page_object` extracted shell channel, Stage 1 still writes
  `placement=INLINE` and `coordinateSpace=STORY_FLOW` unless source metadata
  marks the object as page-positioned. `AnchoredPosition="Anchored"` is
  page-positioned evidence for generic story anchors, but not for a source with
  valid `tableCellStoryTextInlineSlot=true`; the table-cell external label
  condition above remains page-positioned.
- The same rule applies when the source object is authored on a master spread.
  Master authorship is provenance. If IDML story tokens or resolved story flow
  declare the source as the direct inline slot owner, `pass.inline_objects`
  remains the visible ownership channel. A master direct export may provide the
  PNG bytes for that inline `RenderUnit`, but it must be stamped back onto the
  inline candidate and must not become a separate `pass.master_page_graphics`
  floating owner.
- When the direct inline anchor paragraph contains visible story text before or
  after the anchor, the shell must flow with that paragraph. Stage 1 may choose
  the higher-quality `page_object` `deco_*` extracted shell file as the visible
  `SHELL_SLOT` material, but it still writes `placement=INLINE` and
  `coordinateSpace=STORY_FLOW`; the file channel never overrides the direct
  story/table anchor owner.
- If a visible `PLACE_TEXT_SHELL` owns only inline TextFrames and at least one
  of its source or visual source ids is an inline source object, Stage 1 writes
  `placement=INLINE` and `coordinateSpace=STORY_FLOW` unless source metadata
  explicitly marks the shell as page-positioned, such as
  `AnchoredPosition="Anchored"` or the table-cell external label rule below.
  A valid `tableCellStoryTextInlineSlot=true` fact is inline source ownership,
  not page-positioned shell evidence.
  This rule is based on source inline ownership, not on the rendered pass or
  file prefix that supplied the shell PNG.
- When a visible `PLACE_TEXT_SHELL` owns an editable TextFrame, that TextFrame's
  HWPX text plan follows the shell plan's placement. Inline carrier recovery is
  allowed only when no visible text-shell owner exists for that TextFrame.
- A `PLACE_TEXT_SHELL` may be `INLINE` only when the source object has an
  executable inline anchor in IDML story flow, including table-cell flow, or in
  resolved story flow. A group-internal child marked inline by InDesign but
  lacking that source story anchor is page-positioned material and uses
  `placement=FLOATING`.
- A TextFrame/PageItem `isInline=true` flag alone is not an executable inline
  contract. If the extracted canonical shell is a `page_object` with page
  bounds and no direct story/table anchor owner, Stage 1 keeps the shell in
  `FLOATING`/`PAGE` placement even when the source TextFrame reports
  `isInline=true`.
- A story anchor marker is not by itself an inline-placement contract. If the
  anchored source object is an IDML graphic/group with
  `AnchoredObjectSetting.AnchoredPosition="Anchored"`, Stage 1 treats the
  object as page-positioned material. A paired page-object render owns the
  visible graphic-only slot; the story-flow marker remains only the anchor
  relation.
- A resolved story anchor in a paragraph with no visible text is also not by
  itself an inline-placement contract for a local label/shell inside a larger
  page-positioned composite carrier. That anchor records association with the
  surrounding story, but the label/shell keeps the composite carrier's
  `FLOATING`/`PAGE` geometry unless the anchor paragraph contributes visible
  story text that the shell must flow with.
- When a parent composite contains a child atomic shell slot, the child atomic
  shell owns its TextFrame. The parent must remove that child TextFrame from its
  ownership set instead of also claiming it or changing the child's placement.
- A story inline anchor executes only the `ObjectPlan` whose `domId` or
  `renderId` is that anchor. Parent or composite plans that merely include the
  anchor in `sourceObjectIds` or `visualSourceObjectIds` are trace ancestry, not
  executable inline material for that story slot.
- If one inline source anchor is a composite visual object whose descendants
  include editable TextFrames and other visual material, and that composite must
  travel as one inline atom, Stage 1 assigns the composite
  `materialization=COMPLETE_PNG` and `textAction=OWNED_BY_PNG`. HWPX cannot
  faithfully express that InDesign structure as a single inline group with
  nested floating/editable TextFrames. The extractor therefore keeps the child
  text visible in the PNG and no separate HWPX TextFrame owner is created for
  those child TextFrames. Direct child shell slots that are independently
  executable remain separate source slots and are not folded by this rule.
- The same complete-PNG rule applies when the inline atom is owned by table-cell
  story flow (`tableCellStoryTextInlineSlot=true`) and the closed inline source
  root contains multiple editable TextFrame descendants plus non-text visual
  material. A HWPX table cell can host the resulting inline PNG, but it cannot
  preserve the source's nested group-local coordinate system as editable cell
  content. Stage 1 therefore assigns `CONTENT_VISUAL_SLOT`,
  `placement=INLINE`, `coordinateSpace=STORY_FLOW`,
  `materialization=COMPLETE_PNG`, and `textAction=OWNED_BY_PNG` to the direct
  inline source root.
- If that direct inline-anchor `ObjectPlan` is `DROP_TEXT` plus `DROP_VISUAL`,
  executors must not recreate the anchor through rendered PNG, group, badge, or
  text-shell fallback heuristics.
- A parent `PLACE_TEXT_SHELL` / `CONTAINER_BACKDROP` or composite image group
  may not own a descendant `CONTENT_VISUAL_SLOT` that contains a placed
  image/PDF/EPS. The most specific image-backed child keeps the direct visual
  owner, and the parent keeps only its text/shell channel after trimming that
  content source from its visible visual slot.
- For clipped image content, “most specific image-backed child” means the
  smallest source bundle that preserves the original visible image shape. If an
  `Image` is clipped by an `Oval`, `Polygon`, or clipping `Rectangle`, the raw
  `Image` alone is not the visible `CONTENT_VISUAL_SLOT` owner. Stage 1 must
  select the clip-carrying frame/group/render bundle as the owner and drop the
  raw image channel. This decision is based on source parent/child metadata and
  clipping bounds, not on output pixels or page-specific symptoms.
- After replacing a raw clipped `Image` source with its clip-carrying parent,
  Stage 1 must reapply page-local source filtering. A cross-page/background
  fragment may keep only visible source ids that belong to the planned page or
  intersect that page's bounds. Adjacent-page clip parents may remain as
  ancestry in broader source diagnostics, but they are not page-local visible
  owners for that fragment.
- The same rule applies to textless decorative shells made from vector/group
  material. If a visual child is inside a clip-carrying `Oval`, `Polygon`, or
  clipping `Rectangle`, the child-only render must not own the visible
  `SHELL_SLOT`. Stage 1 selects the clip-carrying parent composite as the
  textless shell owner and drops the child-only `deco_*`/`shape_*` channels.
  Editable text remains outside that shell and is owned by HWPX text. The
  extractor must emit the clip-carrying shape parent as an executable candidate
  before rendering child groups, so Stage 1 can choose the source owner without
  relying on post-render duplicate cleanup.
  Once the extraction plan marks that non-Group clip-carrying parent as a
  composite `SHELL_CANDIDATE`, the extractor executes that candidate from plan
  fields only. It must not re-filter the candidate with `contentType`,
  child-shape scans, output pixels, or visible-result heuristics.
  If the clip-carrying parent is spread/group-local (`pageIndex=-1`) while a
  descendant child resolves to a concrete page, the parent candidate is emitted
  on the child's resolved page. A child-only page-local candidate under that
  parent is invalid unless the parent candidate is not visible on that page.
- Stage 3 must not alpha-crop or trim a rendered PNG to repair source bounds,
  reduce transparent canvas, or realign a shell. If a rendered shell contains
  unwanted transparent padding, Stage 1/extractor metadata is incomplete: the
  planner must choose a narrower source cluster or the extractor must emit an
  explicit page-local fragment bound before rendering. Pixel transparency is
  not ownership or placement evidence.
- For `PLACE_TEXT_SHELL` roles, the rendered PNG payload and the displayed
  extent are exactly the Stage 1/extractor contract. The executor may clip only
  to an explicit page intersection or `cropSourceBounds` supplied by extraction;
  it must not derive a new shell rectangle from alpha pixels.
- If a rendered textless shell carrier has broad group bounds but its
  non-text visual source ids identify a concrete Rectangle/Oval/Polygon shell
  that contains the owned TextFrame(s), Stage 1 must use that concrete
  source object's page-local bounds as `ObjectPlan.bounds`. The executor must
  not recover this by moving the cropped PNG after placement.
- Stage 3 must not create adjacent-page overflow copies. If a source visual
  legitimately belongs on more than one page, Stage 1 must emit explicit
  `ObjectPlan`s for those visible slots. The executor may clip the already
  planned page-local material, but it cannot invent `previous page` or
  `next page` materialization from bounds overflow.
- A rendered file prefix is not ownership truth. A `deco_*` render that contains
  an Image/PDF/EPS source child is still a `CONTENT_VISUAL_SLOT` candidate, not
  decoration, unless the source bundle has an explicit text/shell-only role.
- If an image-backed content child has both `inline_object` and `page_object`
  render channels, the representative `rendered_floating_item:page_object`
  channel owns the visible slot. The paired story-flow inline render and
  scaled/derived `rendered_image_frame` channel are non-canonical duplicate
  channels and are dropped.
- If an image-backed inline child is marked as baked into a page-positioned
  composite carrier, but that carrier later executes only as a background/text
  shell rather than a visible content PNG, the inline child is an orphaned
  `CONTENT_VISUAL_SLOT`. Restore it in page coordinates instead of leaving it
  dropped behind a non-content carrier.
- If an inline source object has `AnchoredPosition="Anchored"`, the paired
  page-object channel owns the visual slot because the source is story-anchored
  but page-positioned.
- When a graphic-only atomic source bundle is available through both
  `inline_object` and `page_object` channels without a story inline anchor, the
  `page_object` channel owns the visible content slot because it preserves page
  coordinates. The paired inline channel is a non-canonical render channel and
  is dropped.
- Editable TextFrames inside a page-positioned shell do not make the shell
  inline. They are owned text of that floating shell.
