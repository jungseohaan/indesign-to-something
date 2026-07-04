# Source Ownership Policy: Layers And Z-Depth

> This file is part of the canonical source ownership policy.
> The canonical index is `docs/specs/POLICY-source-ownership.md`.

## 9. Visual Layers

Policy uses only four layers:

`BACKGROUND < DECORATION < CONTENT < TEXT`

Mapping:

- Page and container backgrounds: `BACKGROUND`
- Label shells, label connectors, outlines, masks, separators, and visual
  chrome: `DECORATION`
- Photos, illustrations, charts, QR, complete visuals: `CONTENT`
- HWPX text/table text: `TEXT`

Original IDML layer and z-order are ownership depth truth for non-text visual
objects. The four policy layers describe slot role and HWPX plane behavior; they
do not replace IDML source stacking order for graphics. A visual `BACKGROUND`,
`DECORATION`, or `CONTENT` object may appear above or below another visual object
only according to its IDML source depth. If that order looks wrong in HWPX, Stage
1 must first verify source ancestry/layer/z-order and the chosen visible owner;
it must not repair the page by policy-band promotion or demotion.
When a rendered candidate has `sourceObjectIds`, Stage 1 must derive
`ObjectPlan.zOrder` from those source objects before falling back to extractor
render order. The canonical source depth is the normalized IDML/source-item rank
for page items. In this rank, smaller values are farther back within the same
page/spread stacking context; larger values are visually in front. A numeric
`zOrder` copied from an extractor or resolved cache is a fallback only when
source-item order is unavailable, because some extractors encode that number in
the opposite direction. Extractor render order is diagnostic/cache order, not
ownership depth truth. If a content surface's source depth is behind a local
HWPX-owned TextFrame that it overlaps, Stage 1 may use a behind-text content
plane (`CONTENT_BACKDROP`) while keeping its policy layer as `CONTENT`.
When the source collector receives a front-to-back `allPageItems` rank, it must
keep that raw rank only as diagnostic metadata (`rawZOrder` / `sourceOrder`) and
write `ObjectPlan.zOrder` from the normalized back-to-front rank. Later stages
must never reinterpret the raw rank.
Imported `planner_declared_object_plan` records are already Stage 1 ownership
decisions; Java bridge/planner code may execute or validate them, but must not
canonicalize their `zOrder` from resolved/raw source items again.
Independent placed content remains `CONTENT_VISUAL` only when source depth does
not put editable local text in front of it. Text overlap alone is not a reason
to demote it; source depth is required.
Planner-declared `ObjectPlan` records imported from extractor JSON are not
exempt from this rule. During Stage 1 import, their `zOrder` must be
canonicalized from the normalized IDML source depth for the same page-local
source id before execution sees the plan.

Stage 1 has exactly one final visual-depth contract gate. Earlier planner
passes may create, split, merge, or drop candidate ownership records while
legacy extraction candidates are migrated into `ObjectPlan`, but they are not
the final z-depth authority. Immediately before `ObjectPlan` is written,
Stage 1 must finalize every visible visual plan's:

Initial `ObjectPlan` creation may assign a role-based provisional
`visualLayer`, but it must not decide source-depth plane changes such as
`CONTENT_VISUAL` versus `CONTENT_BACKDROP`. Those changes belong only to this
final gate.
For `PLACE_TEXT_SHELL` plans, that provisional role layer must be assigned
through the Stage 1 text-shell layer helper. Individual restore/split passes
must not hard-code their own label/container layer choice; they may only provide
a source-role fallback to the helper while the final depth gate remains the
z-order authority.

- `zOrder`, from normalized IDML source depth of `visualSourceObjectIds`, then
  `sourceObjectIds`, then extractor z-order only as a last fallback.
- `visualLayer`, only when the HWPX plane must be chosen from source-depth
  metadata, such as a `CONTENT_VISUAL` source that is behind local editable
  HWPX text in the same page coordinate space.
- HWPX-owned TextFrame `zOrder` only for source-declared shell ownership: when
  a visible front-plane text shell owns an editable TextFrame, the editable text
  plan is placed one level above the finalized shell plan. This is part of the
  same final depth contract, not an executor repair.
- HWPX text-only plans do not receive a new `visualLayer` while being restored
  from a composite carrier. The owning shell/visual plan carries the visual
  layer; editable text remains in the `TEXT` policy layer and is ordered by the
  final depth contract.
- Container outline `zOrder` only for source sibling contracts: when a source
  outline and its materialized sibling shell/fill/text carriers share the same
  source parent, the outline may be placed one level above those finalized
  siblings. This preserves an IDML outline/mask relation and must run inside the
  final visual-depth gate.
- Layered container face/shadow duplicate resolution may choose the visible
  owner and provisional role layer, but it must not synthesize a new z-order by
  min/max offsets. The final gate re-derives the executable z-order from IDML
  source depth.
- Large layered image-export pairs may be marked as provisional container
  backdrop role only through one Stage 1 helper. This is a slot-role
  normalization, not a depth repair; it must not drop or reorder either visual
  unless the same source slot is otherwise proven duplicate-owned.

No pass after this final gate may change `visualLayer`, `zOrder`,
`placement`, `materialization`, or visible source ids. `writePlans`,
executors, and validators consume the finalized contract only. If a later
stage appears to need a layer promotion/demotion, the Stage 1 finalizer or its
source metadata inputs are wrong.

The HWPX behind-text/in-front-of-text plane mapping for finalized
`visualLayer` values is a single Stage 1 policy table implemented by
`VisualPlanePolicy`. Stage 3/4 adapters and Java HWPX builders may call that
table to translate a finalized layer name into an HWPX plane, but they must not
carry their own divergent lists of foreground/background layers.

The final gate may use only IDML/resolved source metadata: source id, parentage,
group membership, page/spread, bounds, layer, visibility, and normalized source
z-order. It must not use rendered pixel color, user-visible symptoms, output
occlusion, page numbers, literal text, or coordinate-specific exceptions.

If the source metadata marks a visual-only page/spread object as belonging to a
background-role layer, such as `배경`, `바탕`, `background`, `bg`, or `backdrop`, Stage 1
must treat that layer name as a background hint, not as ownership truth. The
visual becomes `PAGE_BACKGROUND` only when its source geometry also behaves like
a page/spread backdrop: it covers a substantial page area, touches/bleeds from a
page edge while spanning a major axis, or is explicitly emitted as a page
background. Smaller circles, badges, label plates, masks, and card shells on a
background-named layer remain `DECORATION`/shell material according to their
source bundle role. Image/PDF/EPS descendants do not by themselves promote that
background-layer carrier to `CONTENT_VISUAL`.
This geometry check is performed in the same page-local coordinate space as the
rendered candidate bounds. If page metadata has been normalized to another unit,
Stage 1 must compare against the normalized page width/height converted back to
the candidate's page-local unit; otherwise a full-page visual can be misread as
small content and placed above text.

Default HWPX plane:

- `BACKGROUND`: behind text
- `LABEL_BACKDROP`, `LABEL_CONNECTOR_BACKDROP`, and
  `LABEL_OVERLAY_BACKDROP`: front object plane with the finalized
  `ObjectPlan.zOrder`. These roles are still logical `DECORATION` and must stay
  below their owned HWPX text by the Stage 1 text/shell z-order contract. They
  must not be mapped to the global HWPX `BEHIND_TEXT` plane, because that plane
  can hide local speech bubbles, labels, and connector shells behind unrelated
  HWPX table/text carriers.
- `LABEL_CONNECTOR_BACKDROP` is for textless vector
  connector graphics inside a source composite layout that visually connect
  labels/badges. It is not selected from output occlusion or line shape alone;
  it must be backed by source parentage, textless-vector material, and the
  inline/composite source role. It must still preserve the finalized source
  `ObjectPlan.zOrder`; if an editor intentionally places a connector above a
  badge, execution must not demote it by role.
- Stage 1 must preserve IDML source z-depth as `ObjectPlan.zOrder` for all
  visible non-text visual objects, including `BACKGROUND`, `DECORATION`,
  `CONTENT_BACKDROP`, `CONTENT_VISUAL`, foreground decoration, and masks. Stage
  3/4 must insert visible visual figures by this planned source-depth contract
  and must not re-band, promote, or demote objects from screenshot symptoms.
  Text shells must not use a separate insertion path from ordinary PNG/vector
  visuals, because that lets execution order override the Stage 1 plan.
  Background material is still ordered internally; broad page/paper backdrops
  and local background illustrations keep their source order instead of
  collapsing to one identical z-order.
- Master-page direct fragments must preserve the master source-item depth, not
  the temporary export/result array order. Exporting individual master items or
  clusters is only pixel preparation; it must carry the source depth forward so
  a local master panel can remain above a broader master backdrop when the IDML
  source order says so.
- A single pure `BACKGROUND` visual slot may materialize as page-local visual
  fragments on multiple pages when the source bounds physically cross a
  page/spread boundary. This is not a new ownership decision: every fragment
  belongs to the same source slot, keeps the same visual layer and z-order, and
  differs only by page-local visible bounds and extractor-rendered appearance.
- Page-local fragments must have page-local asset identity. If the same source
  root/source bundle is materialized on multiple pages, the extracted file path
  and `ObjectPlan.file` must be unique for each `(source bundle, pageIndex,
  visible slot)` record. Reusing a `deco_*`, `graphic_*`, or equivalent filename
  for multiple page-local records is invalid because later extraction can
  overwrite a different page's pixels.
- Page-local copies are required whenever a source background/container slot
  visibly intersects an applied page in the same applied spread, even if the
  source item's own `pageIndex` points at the other page of that facing spread.
  `pageIndex` identifies source anchoring; it is not by itself the visible-page
  ownership boundary for spread-spanning background material. Stage 1 and
  extraction planning must create a source-backed page-local fragment for each
  same-spread intersection; they must not use a source-less page background
  render, adjacent-page geometry fallback, or a later recovery pass.
- A spread/background parent group may contain children whose source
  `pageIndex` values differ from the current applied page. Stage 1 must decide
  visibility from source bounds intersecting the applied page bounds and from
  source bundle role. It must not discard a background child only because its
  source `pageIndex` belongs to the opposite page of the spread.
- Conversely, a non-background child from an adjacent text-shell bundle does not
  become executable merely because its bounds cross the current page. It remains
  source ancestry of the owning text-shell bundle unless it owns a distinct
  source slot on the current page.
- Page-local background materialization has two required sides. It must not
  leak adjacent-page pixels into the current page, and it must not drop source
  children whose bounds intersect the current page. The extractor may include
  cross-page or pasteboard children only when their source bounds intersect the
  current applied page bounds. Intersecting children are part of the same
  `BACKGROUND` slot; non-intersecting children remain source ancestry only and
  must be hidden from the current page-local fragment render.
- A background/decoration object's placement bounds may extend outside the page
  because the source artwork intentionally bleeds or lives partly on the
  pasteboard. Out-of-page bounds alone do not prove that a source fragment is
  missing. Stage 4 must require a page-local fragment only when source metadata
  shows that a visual source anchored to another applied page intersects the
  current page, or when the extractor already declared explicit crop/source
  fragment metadata. Bounds/occlusion symptoms are not ownership evidence.
- If a page-local background/content/decoration slot needs clipping, masking,
  pasted-inside geometry, spread slicing, or source effects, Stage 1 must use
  `TEXTLESS_VISUAL_FRAGMENT`. The fragment asset is the InDesign-rendered
  page-local visible appearance for that slot, with HWPX-owned text hidden. HWPX
  placement code only places the fragment at `ObjectPlan.bounds`; it must not
  synthesize adjacent-page overflow copies or infer crop geometry from raw
  `RenderedGroup` bounds.
- `ObjectPlan.bounds` are the page-local visible bounds of a visual fragment.
  `ObjectPlan.renderSourceBounds`, when present, is diagnostic/extractor
  provenance for the broader source render. It is not a second ownership
  decision and must not be used by later stages to invent new visible output.
- Extractor-level `cropSourceBounds` is different from `renderSourceBounds`.
  `cropSourceBounds` is an explicit pixel-preparation contract emitted with a
  rendered asset when the PNG still contains the broader source render but
  `bounds` already describes the page-local visible output. Stage 3 may use
  `cropSourceBounds` only to prepare that already-planned asset. It must not
  derive an equivalent crop from `ObjectPlan.renderSourceBounds`, page overflow,
  or neighboring-page geometry.
- `cropSourceBounds` applies to every page-local visual fragment, including
  text-owning shell fragments. `PLACE_TEXT_SHELL` changes text ownership; it
  does not exempt the shell pixels from page-local clipping. A shell PNG that
  contains adjacent-page pixels for the same source bundle violates the visible
  slot uniqueness rule even when the editable text itself is HWPX-owned.
- When a `PLACE_TEXT_SHELL` plan is materialized by an extracted shell image
  (`EXTRACTED_PNG_VECTOR` or `TEXTLESS_VISUAL_FRAGMENT`), the extracted PNG owns
  the shell geometry: asymmetric corners, masks, strokes, and source effects.
  The HWPX text carrier must be a neutral rectangular carrier for that image and
  must not reapply anchor/child TextFrame fill, stroke, corner radius, or shape
  style. `NATIVE_SOURCE_SHAPE` is the only shell materialization that may derive
  the carrier geometry from source shape/style metadata.
- A visible leaf vector visual is not promoted or demoted by deciding whether
  it "looks like a background". Stage 1 preserves the IDML source page/spread,
  source z-order, and declared policy layer; extraction must emit the declared
  vector visual so execution can place that source material in z-depth order.
  Missing extracted material is an extraction defect, not a reason for Java
  native fallback drawing.
- A cross-page text shell whose owned editable text belongs to another applied
  page must not be dropped wholesale when the same source bundle contains
  non-text visual children on the current page. Stage 1 must split ownership:
  the other page keeps the `TEXT_SLOT`, while the current page materializes only
  the page-local visual slot as a background/decoration fragment. This split is
  source-metadata driven by child source page/intersection, not by page number,
  text content, or visible symptoms.
- If the source children of an extracted page-local
  `TEXTLESS_VISUAL_FRAGMENT` are master-page source ids that do not appear as
  normal document `pageItems`, the extractor result row is still valid source
  metadata for the current applied page. Stage 1 must use that row's page-local
  `bounds` plus non-text `visualSourceObjectIds` to preserve the visual slot,
  while dropping only the cross-page editable text ownership.
- Stage 1 must not create page-local clipping by editing `ObjectPlan.bounds`
  from a broader source render. It may accept extractor-emitted page-local
  records, classify their ownership, and validate that `bounds` and optional
  `cropSourceBounds` form an executable contract. Missing page-local records are
  extraction defects, not planner/executor fallback opportunities.
- A page-local background copy may reuse one source bundle id across pages only
  when extraction/resolved data exposes an explicit per-page materialization
  record. The planner must not invent a neighboring-page copy by shifting bounds
  from another page. A visible HWPX page must not depend on a plan whose
  `pageIndex` is only the neighboring page when the source slot visibly
  intersects the current page.
- A cross-page parent shell may trace adjacent-page children as ancestry, but it
  must not consume a child plan that has page-local visible source material on
  that child's own page. The child page owns that visible slot. This prevents a
  broad spread shell from dropping local bars, stars, dotted rules, masks, or
  other background fragments that are physically authored for the adjacent page.
- The page-local child rule does not make non-background chrome from an adjacent
  text-shell bundle independently executable. When a visual-only child is fully
  contained in an adjacent-page atomic/text-owning shell bundle, and the child's
  own source layer is not a background-role layer, the child remains ancestry of
  that text-shell bundle instead of becoming a standalone `CONTENT_VISUAL`.
  Background-layer fragments are excluded from this suppression so page-local
  bars, stars, rules, and page backdrops are preserved.
- A spread-spanning `BACKGROUND_SHELL` or `PAGE_BACKGROUND` render is not allowed
  to use the source root bounds as the visible output bounds. Its visible file
  and `ObjectPlan` must describe the current page intersection. Source ids may
  trace the original spread bundle, but `visualSourceObjectIds` may claim only
  source material that contributes to that page intersection.
- Spread-spanning background renders must not be implemented by Stage 3
  overflow-copy synthesis or by Stage 1 bounds-shift synthesis. If the source
  slot is visible on two pages, extraction/resolved data must expose two
  executable page-local records, and Stage 1 may normalize those records into
  two `TEXTLESS_VISUAL_FRAGMENT` plans, one per applied page. Inline shells,
  label shells, clipped content composites, text-owning shells, and content
  visuals also use explicit fragment plans when they need source clipping; they
  do not use an overflow-copy path.
- Stage 3 must not inject a planned `TEXTLESS_VISUAL_FRAGMENT` that lacks a
  matching rendered/resolved item for the same page-local identity. Matching by
  raw source id or render id alone is not sufficient; the executor must match
  page, placement, source slot, and file. Missing fragment rows are extraction
  defects and validation failures, not executor synthesis opportunities.
- HWPX text-frame carriers that are implemented as 1x1 tables are execution
  containers, not semantic tables. They must not use table-level pagination that
  reserves or pushes an entire page (`pageBreak=TABLE`). Background and shell
  carriers are fixed-position visual/text containers and must not create an
  extra page before the section break. Real semantic tables keep their own
  table pagination policy in the table builder.
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
- An editable TextFrame owned by a `LABEL_BACKDROP` shell uses a transparent
  HWPX drawText carrier when it has no inline table. Do not gate this by visible
  text length, page text, or label wording. The carrier choice follows the Stage
  1 shell/text ownership relation so the shell remains a separate decoration and
  the editable text remains above nearby content visuals.
- A textless shell whose source role is an independent label slot belongs to
  `LABEL_BACKDROP`. A textless shell whose source role is a text/table/inline
  carrier belongs to `CONTAINER_BACKDROP`, even when the carrier has colored fill
  or stroke. Visual color is style, not a layer classifier.
- A textless, floating, page-coordinate shell that spans from a page/spread edge
  is a carrier/backdrop source, not a local label. It belongs to
  `CONTAINER_BACKDROP` unless it owns editable TextFrame ids. This applies to
  planner-declared rendered plans as well; Stage 1 must not exempt imported
  planner plans from the final visual-depth/layer contract. Source role and
  coordinate-space decide this layer, not screenshot occlusion.
- A textless, floating, page-coordinate shell that overlaps editable TextFrames
  and is source-depth behind those TextFrames is also a carrier/backdrop source.
  It belongs to `CONTAINER_BACKDROP`, not front-plane `LABEL_BACKDROP`. If the
  source depth places the shell above the TextFrames, Stage 1 must preserve that
  authoring order instead of demoting it by shape or color.
- A `direct_child_shell_slot` that owns editable TextFrame text is a closed local
  label/shell slot. It maps to `LABEL_BACKDROP` unless the source metadata
  explicitly declares a page/container background role. Its physical width,
  height, or transparent canvas size must not demote it to `CONTAINER_BACKDROP`;
  the slot declaration is stronger than large-bounds heuristics.
- A `slot_only_textless_shell` with editable TextFrame ownership is also a closed
  local shell slot. It maps to `LABEL_BACKDROP` unless the source itself declares
  a page/background/container carrier role. Width, height, transparent canvas
  size, or table adjacency must not demote it to `CONTAINER_BACKDROP`; otherwise
  answer-field shells and long rounded label backdrops disappear behind HWPX
  table/text carriers.
- For an exact `shell_slot_only` source cluster, the extractor renders the
  declared source root/primary source object when that root contains the
  exported source set. A common parent may be used only when no declared root
  contains the set. Rendering from a broader parent changes the placement bounds
  and can make sibling answer-field shells share the same canvas/origin.
- A multi-TextFrame composite carrier whose extractor reason is `*text_hidden`
  and whose text is owned by HWPX is a text/table carrier layer. Image/PDF/EPS
  children inside that carrier keep their own content slot identity, but their
  presence must not promote the whole carrier PNG to `CONTENT_VISUAL` where it can
  cover HWPX-owned text.
- If such a carrier's non-text visual-only source leaves are a small local
  label/icon chrome area rather than the carrier surface, that chrome belongs to
  `LABEL_BACKDROP`. It is a decoration slot that must remain above table/body
  fills and below editable HWPX text.
- When a composite carrier is reclassified this way, it may own only the
  decoration/shell visual slot. Its editable TextFrames keep separate HWPX text
  owners unless a direct leaf/native shell owns that same text slot. The
  composite label chrome must not remain the text owner if its execution path
  only places the shell visual.
- A graphic-only sibling contained by a text-shell cluster is not automatically
  front-plane label chrome. Only small, local label/marker chrome belongs to
  `LABEL_OVERLAY_BACKDROP` so it stays above the cluster's carrier while the
  editable text remains HWPX text. Broad siblings, edge-spanning faces, masks,
  and carrier/container surfaces belong to `CONTAINER_BACKDROP` and must stay
  behind owned text/content.
- A direct native label shell plus its direct TextFrame may be split out of a
  broader composite text-shell carrier even when the carrier still owns other
  TextFrames. The carrier keeps its remaining body/container ownership, while
  the direct label shell owns its own `SHELL_SLOT` and `TEXT_SLOT` as
  `LABEL_OVERLAY_BACKDROP` plus editable HWPX text. This is a source slot split,
  not a page/text-specific exception.
- If a visible parent `PLACE_TEXT_SHELL` already materializes the same visual
  child source in its shell PNG, that child source is baked into the parent
  shell slot and must not be placed again as `LABEL_OVERLAY_BACKDROP`. The child
  may be front-plane label chrome only when its visual slot is not already
  owned by the parent shell render.
- A story-flow `inline_object` marker inside a text-shell group is not baked
  into the parent shell merely because the parent `sourceObjectIds` contain it.
  It may be dropped only when the parent shell's visual source channel explicitly
  owns that marker source. Otherwise the inline marker keeps its own
  `CONTENT_VISUAL_SLOT` so the ORC position can materialize the icon beside
  editable HWPX text.
- When a direct story-flow inline text shell is anchored inside a story that is
  itself owned by a floating `PLACE_TEXT_SHELL`, the nested inline shell cannot
  execute inside the carrier's HWPX drawText flow. Stage 1 materializes that
  direct shell as `FLOATING`/`PAGE` using the source page bounds and z-order.
  Because the shell is local chrome above the carrier surface, it maps to
  `LABEL_OVERLAY_BACKDROP`, while its owned TextFrame remains editable HWPX
  text in the higher text layer.
- A `PLACE_TEXT_SHELL` may own only smaller descendant visuals. It must not own
  a broader ancestor/composite render that contains the text shell source or
  encloses the text shell bounds. In that direction the broader render remains a
  `CONTAINER_BACKDROP` owner, and only its smaller local children may be absorbed
  into the shell channel.
- A `LABEL_BACKDROP` text shell is `DECORATION`, not `CONTENT`. If it overlaps a
  visible `CONTENT_VISUAL` on the same page, Stage 1 `ObjectPlan.zOrder` is the
  relative visual-order contract. The executor must not create a fixed
  `LABEL_*` or `CONTENT_*` z-band that can invert that source order. The shell
  and the editable HWPX text remain separate channels; only the text channel is
  allowed to sit in the semantic text layer above both visuals.
- A callout/speech-bubble `PLACE_TEXT_SHELL` must be classified in Stage 1 as
  `LABEL_OVERLAY_BACKDROP` from source metadata: owned TextFrame story styles
  such as speech-bubble/callout styles plus drawable textless shell sources. The
  same classification rule applies to rendered, native parent, native sibling,
  and split direct shell paths. Later stages must not rediscover this from
  page symptoms or overlap.
- `CONTENT_BACKDROP` is still policy `CONTENT`; it only uses the behind-text
  HWPX plane so editable `TEXT` remains on top. The executor must not drop it
  into the `BACKGROUND` z-band. Local label shells do not share this HWPX plane:
  they execute in the front object plane with finalized source z-order, while
  their owned editable text plans remain above them. This prevents the global
  HWPX behind-text plane from hiding speech bubble shells and label backdrops
  behind unrelated carriers.
- `LABEL_BACKDROP` / `LABEL_OVERLAY_BACKDROP` materialization must not use a
  hard-coded decoration execution z-band in Stage 3/4. `visualLayer` and the
  Stage 1 source-depth `ObjectPlan.zOrder` are the full execution contract for
  visual objects. Only Stage 1 may decide that a label shell is a foreground
  overlay; later stages may not infer it from the layer name alone.
- HWPX-owned text blocks must use their Stage 1 text `ObjectPlan.zOrder`.
  Execution code must not push all HWPX text into an artificial high text band
  after planning. Stage 1 may adjust only HWPX text plans when needed to keep a
  TextFrame above its owning `PLACE_TEXT_SHELL`; it must not use that text rule
  to reorder unrelated visual objects. When a visible `PLACE_TEXT_SHELL` owns
  editable TextFrames, Stage 1 must keep those TextFrame `HWPX_TEXT` plans above
  the owning shell plan as a source-slot invariant. This relationship is
  declared in the `ObjectPlan` list; executors must not repair it by promotion,
  z-band
  injection, overlap checks, or late layer calculation.
- A TextFrame carrier's `cornerRadius` is geometry metadata, not visible shell
  material by itself. Executors may materialize a native carrier shell only when
  Stage 1 has a visible `SHELL_SLOT`/`TABLE_STYLE_SLOT` plan or the TextFrame
  source has visible fill/stroke style. A carrier with `fill=None` and
  `stroke=None` must remain transparent even if it has corner radius metadata.
- `ObjectPlan` is the ownership and placement authority, but the materialized
  PNG path may live on the matching `RenderedGroup`. Executors must not drop a
  planned visible visual only because `ObjectPlan.file` is empty; they may read
  the matched `RenderedGroup.file` when the render id/source ids prove it is the
  same planned source bundle.
- A rendered visual whose rendered-group z-order is `0`, unknown, or otherwise
  only a placeholder must derive `ObjectPlan.zOrder` from its tracked
  `sourceObjectIds`/`visualSourceObjectIds`. If source page-item z-order is
  available, it is the canonical order for decoration connectors, arrows,
  outlines, and extracted vector PNGs. Executors must not let a placeholder
  render order push those visuals behind sibling source shells.
- `CONTAINER_OUTLINE` is the visible edge/connector channel of a source group.
  When an outline/connector shares the same source parent group with
  `CONTAINER_BACKDROP`, `CONTAINER_FACE`, text shell, or table-style material,
  the outline connector must execute above those sibling faces and sibling
  HWPX text/table carriers that materialize as objects in the same parent
  group. A placeholder render order or same-z tie must not hide arrows,
  connector lines, or border strokes behind the boxes/tables they connect.
- A rendered parent visual that includes a table-only TextFrame source must not
  be treated as a textless shell solely from `deco_`/text-hidden naming. The
  table-only TextFrame owns `TEXT_SLOT` and `TABLE_STYLE_SLOT` through HWPX; a
  parent PNG containing that source would duplicate table text and must be
  dropped unless its visual source channel is proven to exclude the table/text
  source.
- When duplicate text-shell candidates own the same TextFrame ids, the smaller
  local shell may be the canonical `TEXT_SLOT` owner. A broader ancestor shell
  that loses text ownership must not be dropped if it carries independent
  container/background visual material; it is retained as `DROP_TEXT` plus a
  `CONTAINER_BACKDROP` visual owner.
  This retention is valid only for independent visual material. If the retained
  visual-only container shares any visible `visualSourceObjectIds` with another
  visible plan on the same page, the non-canonical container is dropped for that
  visual slot.
- A native source shell used as a local text container face must be emitted above
  broad page/PDF/background rasters and below label shells and editable text. It
  must not reuse the same lowest backdrop z-band as full-page backgrounds.
- The same visual layer must use the same execution z-band regardless of
  materialization. `NATIVE_SOURCE_SHAPE` and `EXTRACTED_PNG_VECTOR` container
  backdrops are both local backgrounds, not page backgrounds.
- An inline source whose IDML anchored object position is page-positioned keeps
  page-coordinate placement. `isInline` in resolved metadata is not sufficient
  to reflow a local label/shell through story text when the source anchor
  carries an anchored page position; otherwise the shell may be emitted both as
  a parent composite visual and as a shifted inline shell.
- A local label/shell source inside a larger composite source bundle keeps
  the parent's page-coordinate channel when the child has no direct executable
  story anchor, or when the child is visible only as a resolved story anchor in
  an anchor-only paragraph. The story anchor explains source attachment, but it
  must not override the composite carrier's page-positioned visual geometry.
- A child label shell may be absorbed into a floating composite parent only
  when the child is a simple atomic/visual-label shell. A mixed shell with its
  own visual-only children owns a distinct `SHELL_SLOT`; the larger composite
  carrier must not suppress it merely because their source ids or bounds are
  nested.
- A story-flow inline label shell keeps its own `SHELL_SLOT` even when a larger
  floating composite carrier lists the inline label source or its child visual
  sources. The carrier may suppress that shell only when the child is not a
  story-flow/inline shell and the carrier's direct executable plan is the sole
  visible shell owner for the same slot.
- A simple inline label whose source group appears as an ORC in a story is
  canonical in the story-flow channel. Stage 1 keeps an inline text-shell plan
  for that source bundle so the shell and editable label text move as one
  inline object. A paired page/deco render may provide the textless shell
  material, but it must not become a separate floating owner for the same
  `SHELL_SLOT` or `TEXT_SLOT`.
- If that story-flow inline label shell must execute in page/floating
  coordinates because the anchored object is page-positioned, the shell plan
  owns only the visual `SHELL_SLOT`. Its editable child TextFrames keep direct
  HWPX text ownership, and non-executable label helper plans must drop their
  duplicate text claims.
- HWPX execution plane and z-band must agree. A visual emitted in the page
  background z-band is emitted behind text; it must not remain on the front
  object plane because of a stale generic content layer.
- `PAGE_BACKGROUND` and `CONTAINER_BACKDROP` may be mapped to the HWPX visible
  bottom background z-band during emission. This is not a new ownership
  decision: it only adapts the finalized background policy layer so broad
  page/master backdrops cannot cover planned content/shell visuals in the same
  HWPX behind-text plane. The band must remain visible in HWPX; it must not use
  a negative/deep z-order that can place the image behind the paper itself.
  Source order is still preserved among visuals inside each execution band.
- HWPX placement is an execution mapping, not an ownership decision point. The
  converter may map a Stage 1 `visualLayer` to the required HWPX plane/z-band,
  but it must not solve overlap, missing, or duplicate symptoms by recalculating
  layer/z-order from bounds, pixels, or observed occlusion during HWPX emission.
  If a visual appears above or below the wrong object, fix the source-based
  `ObjectPlan` classification (`visualLayer`, `placement`, `zOrder`,
  ownership slot) before execution.
- Stage 1 itself may use source z-order, source ancestry, source layer names, and
  declared slot ownership to choose `visualLayer`, but it must not normalize a
  content visual to a different layer merely because its rendered bounds overlap
  an HWPX text plan. Overlap may be diagnostic evidence only, not the layer
  decision.
- HWPX emission must not infer background/front-plane placement from rendered
  image size, paper-like pixels, `fromGroup`, source id overlap, or fallback
  placement matching. Those are planning inputs only when they come from source
  metadata. A visible candidate without an exact executable `ObjectPlan` is a
  planner/extraction error, not an executor recovery case.
- Source extraction and Stage 1 planning are the only decision points. The
  extraction step records source structure, clipping/group membership, z-order,
  page ownership, and candidate slot evidence; the planner then commits the
  final `ObjectPlan` owner/action/layer/placement. Stage 2+ builders and HWPX
  emitters must only execute that committed plan. They must not reinterpret a
  source bundle as background, shell, content, inline, floating, outline, or
  mask after planning.
- A source shape with `Paper`/white fill is a container/background face even
  when it also has stroke. Stroke-only source shapes may become outlines.
- A `Paper`/white container face must not become `CONTAINER_OUTLINE` because a
  label or text shell overlaps its edge. The source role is decided once from
  source metadata: a filled face is `CONTAINER_BACKDROP`; a source-proven
  stroke-only edge or connector is `CONTAINER_OUTLINE`.
- A `Paper`/white face must not be promoted to `FOREGROUND_MASK` merely because
  it overlaps or clips a label/card. If the source is a filled face, it stays a
  `CONTAINER_BACKDROP`; only an explicit source mask/outline role may render in
  front of text.
- Image preparation must not knock out `Paper`/white pixels when that fill is
  the planned container/background face.
- outline/mask that must appear above content: in front of text only when source
  role and z-order require it
- `CONTENT`: follows source z-order against other content
- A content visual that keeps its editable labels as HWPX text is emitted below
  those owned TextFrames and above container/background shells from the same
  source cluster.
- `TEXT`: highest semantic layer
