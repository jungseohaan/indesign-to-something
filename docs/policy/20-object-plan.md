# Source Ownership Policy: ObjectPlan Contract And Decision Order

> This file is part of the canonical source ownership policy.
> The canonical index is `docs/specs/POLICY-source-ownership.md`.

## 3. ObjectPlan Contract

Every visible candidate must have one `ObjectPlan` before AST generation.

Required executor identity fields:

- `sourceObjectIds`: source bundle identity.
- `visualSourceObjectIds`: source ids used as visible visual material.
- `styleSourceObjectIds`: source ids absorbed as HWPX style.
- `ownedTextFrameIds`: TextFrame ids whose text is owned by HWPX.
- extractor candidates must also distinguish `sourceObjectIds` from
  `exportSourceObjectIds` when a parent bundle is wider than the visible slot.
  `sourceObjectIds` preserves ownership ancestry; `exportSourceObjectIds` is the
  exact source subset InDesign must render to PNG.
- execution bridges may derive `executionSourceObjectIds` from the explicit
  slot source fields. Executors use that executor-visible source set for
  candidate id, hide/export, and material lookup. They must not use broad
  ancestry `sourceObjectIds` as a substitute for the visible slot source set.
- `renderSourceBounds`: optional extractor/source provenance bounds for a
  page-local fragment. It explains what broader source area produced the
  fragment, but `bounds` remains the only visible placement bounds.
- `cropSourceBounds`: optional extractor-authored pixel-preparation contract
  for a page-local visual fragment when the rendered PNG still contains a
  broader physical page/spread source. It is executable crop metadata, not
  ownership provenance, and must never be derived from `renderSourceBounds`.
  Stage 1 may compute both fields from the same source-extent analysis, but it
  must write the executable crop extent explicitly to `cropSourceBounds`.
- `inlineSourceTreeClosed` and `inlineFlowSourceObjectIds`: optional Stage 1
  execution contract for closed inline shell/carrier plans whose source children
  must be emitted as source-ordered inline fragments. When hidden visual children
  contain HWPX-owned TextFrames, Stage 1 must provide the complete source order
  here. Executors must not reconstruct the order from TextFrame bounds,
  rendered bounds, overlap, or DOM probing.

Normal executor-facing ObjectPlans must stay small. They may carry explicit
slot source ids and stable proof references, but they must not carry expanded
recursive cluster evidence unless that evidence is itself part of the executable
slot. The normal execution contract is limited to:

- explicit source ids needed for ancestry, visible material, style absorption,
  text ownership, export, and hidden material;
- placement, coordinate space, layer/plane, z-order, bounds, materialization,
  action, and reason fields;
- compact proof refs such as `coverageClaimRef`, `slotClosureRef`,
  `exportClosureRef`, `hiddenChildrenRef`, and source-set refs that can be
  expanded from Stage 0/1 diagnostics when needed.

Diagnostic evidence fields are separate from the executor contract:

- `sourceRootObjectIds`: diagnostic top-level roots inside `sourceObjectIds`.
  Multi-root composite diagnostics use this to compare the union of closed
  source trees with the executable bundle.
- `clusterSourceObjectIds`: diagnostic recursive descendants of the primary
  source root or source-root union. This may prove a closed source tree during
  migration, but final executors use explicit slot source fields instead.
- `clusterKindCounts`, `omittedClusterSourceObjectIds`, and
  `omittedClusterKindCounts`: diagnostic evidence for narrower-than-cluster
  blockers. They are not execution inputs; they only show what source kinds
  Stage 1 has not yet assigned to explicit slots.

Large recursive source sets may be represented by stable source-set references,
such as `sourceSetId`, `clusterSourceSetId`, `exportSourceSetId`, and
`hiddenSourceSetId`, when the expanded arrays are available from Stage 0/1
diagnostics or trace mode. Validators may expand those references for failing
records, but executors must not require expanded cluster or omitted-cluster
arrays to place, hide, export, or drop material.

In normal conversion output, diagnostic recursive fields are omitted unless a
plan is failing, not import-ready, or trace/dev diagnostics are enabled. A
validator on the normal path validates the compact proof refs and indexed
membership checks; it does not force every ObjectPlan to repeat
`clusterSourceObjectIds`, `omittedClusterSourceObjectIds`, or other expanded
evidence arrays.

For `PLACE_TEXT_SHELL`, visible source evidence may come from
`styleSourceObjectIds` instead of `visualSourceObjectIds` when the shell is a
TextFrame's own fill/stroke/backdrop style. This is still a visible
`SHELL_SLOT`; it is not a missing visual source and it does not grant PNG
ownership of the TextFrame's editable text.

Visible shell slot identity is based on `pageIndex + placement +
SHELL_SLOT + visible slot source ids`, not on the extraction pass that found it.
If `pass.decoration_groups` and `pass.editable_textframe_visual_shells` both
claim the same TextFrame fill/stroke shell source, Stage 1 must keep one
canonical shell owner before export. Different pass names or file prefixes do
not make separate visible owners.

`visualSourceObjectIds` must not contain ids from the same plan's
`ownedTextFrameIds` except for a TextFrame-owned `SHELL_SLOT` whose material is
the TextFrame's own fill/stroke shell. In that case the same source id is
allowed to appear in both fields because the id carries two distinct slots:
HWPX owns the editable text while the textless PNG/vector shell owns the
backdrop. This exception does not allow complete PNG text; the rendered shell
must hide the editable text.

For an extracted PNG/vector plan, `visualSourceObjectIds` is also a contract
that the rendered file's visible pixels correspond only to those visual ids.
If the file visibly contains any descendant source whose slot is owned by
another visible HWPX text/table/shell/content plan, the rendered file is invalid
for that ObjectPlan. Stage 1 must not keep the plan and expect layer ordering,
cropping, alpha preparation, or duplicate-removal code to hide the extra pixels.

The extractor must therefore choose the PNG export target before export. Broad
parent group export followed by Java-side crop/alpha trimming is allowed only
for physical page/spread clipping that the extractor explicitly records as
`cropSourceBounds`; it is not allowed as an ownership-slot correction. If a
text shell slot is a concrete rectangle/oval/polygon inside a broader group, the
candidate must export that concrete source object or a source-only temporary
group made from `exportSourceObjectIds`.

Parent and child shell plans may coexist only when their visible source sets are
disjoint. If a concrete descendant shell is emitted as its own visible plan,
the broader parent shell export must exclude that descendant shell source while
preserving the parent bundle's text ownership and non-overlapping visuals.

The visible placement `bounds` for that concrete text shell slot must also come
from the concrete shell source root, not from the broader rendered/composite
group bounds or descendant decoration union. If a resolved artifact contains a
subset of document pages, `pageIndex` is still the original document page index;
Stage 1 must resolve it against `ResolvedPage.index`, not the local `pages[]`
list position. Source coordinates normalized to points are converted back
through the resolved scale factor before writing ObjectPlan bounds, while
author-provided page-relative bounds are already page-local source units and are
not divided again.

When a non-text visual source intersects more than one page on the same spread,
Stage 1/extraction planning must emit one page-local candidate per intersecting
page. This decision is based on source geometry and spread membership, not layer
name, color, text, or observed output. Each page candidate keeps the same
ownership source bundle, but has its own `pageIndex`, visible `bounds`, and
extractor-authored `cropSourceBounds` when the source is physically clipped by
the page.

For text shell carriers, `sourceObjectIds` may preserve the broad ancestry, but
the PNG export target must be the smallest non-text visual ancestor that contains
the owned editable TextFrame sources. A broad carrier group is not a valid export
target when a narrower shell ancestor exists.

Floating text/container shells must not export story-flow inline anchor
subtrees as part of their `SHELL_SLOT`. An inline anchor group has its own
story-flow ownership slot even when the source ancestry is contained by the
floating container group. The floating shell may keep the inline source ids as
ancestry/provenance, but `exportSourceObjectIds` must exclude the inline
subtree; the inline object plan owns its own shell/content visual.

When an inline object is a text-owning shell, its native shell sources remain
valid `visualSourceObjectIds` even though they are inline-anchor descendants.
The inline plan owns the shell PNG and the child TextFrame ids become
`ownedTextFrameIds`; later stages must not replace that shell with a generated
shape or drop it as layout-only text.

For a closed inline text shell that is materialized as source-ordered fragments,
`inlineFlowSourceObjectIds` is the only legal order source. It may include both
exported visual leaf ids and hidden child TextFrame ids. If this array is
missing while `hiddenVisualSourceObjectIds` names HWPX-owned child TextFrames,
the plan is incomplete and execution must report a planning defect instead of
sorting child frames by geometry.

For a `PLACE_TEXT_SHELL` plan, executor-visible source is the union of
`visualSourceObjectIds`, `styleSourceObjectIds`, and `ownedTextFrameIds`, after
Stage 1 has removed child slots owned by inline/table/content plans. The hidden
visual set for export is `executionSourceObjectIds - exportSourceObjectIds`.
This keeps broad source ancestry available for diagnostics without letting a
parent shell hide or export child slots that another ObjectPlan already owns.

This removal happens while building the slot owner and `RenderUnit`, not as a
post-plan normalization repair. If Stage 1 cannot name the child slots that are
excluded from the parent export before the parent `RenderUnit` is created, the
parent plan is not executable and must be reported as a planning defect.

When a parent source bundle loses a child visible slot to a direct child plan,
the parent plan has only two valid forms:

- a separate extracted/materialized file that excludes the child slot pixels;
- `DROP_VISUAL` for the parent visual channel.

Keeping the original parent PNG while narrowing `visualSourceObjectIds` is not
valid unless the extractor confirms that the PNG itself was rendered with those
child sources hidden.

Extractor candidate closure:

- When a visible Rectangle/Oval/Polygon/GraphicLine has its own fill/stroke
  material, the extractor must emit a direct single-source visual candidate for
  that source even if an ancestor composite/decoration candidate also contains
  it as ancestry.
- A child source that is marked as part of an ancestor decoration render may not
  be skipped by a later extraction pass when that child has an explicit direct
  candidate. Stage 1 owns the decision to drop either the parent composite slot
  or the child slot; the extractor must not pre-drop the child based on ancestor
  containment.
- If the parent composite is later dropped because it bakes HWPX-owned text or
  child shell/content pixels, every still-visible child material slot must
  already have an executable direct candidate. Missing child candidates are
  extraction-plan defects, not Java fallback opportunities.
- A direct single-source `BACKGROUND` material candidate remains its own visual
  slot even when an ancestor text-shell composite lists it as ancestry. A parent
  text shell may not absorb that child unless the parent has an executable
  childless render that owns only the same background slot.

A TextFrame id may appear in the `ownedTextFrameIds` of only one visible
text-owning bundle. If a leaf atomic `PLACE_TEXT_SHELL` owns that TextFrame,
ancestor/composite groups must remove it from their text ownership. Ancestors may
keep only their own distinct visual/content slots.

When a `PLACE_TEXT_SHELL` plan has `textAction=OWNED_BY_HWPX_TEXT`, that plan is
the text owner for its `ownedTextFrameIds`. A separate `text_frame` plan for the
same ids may be `DROP_TEXT/DROP_VISUAL` to avoid duplicate carriers; validation
must not require a second HWPX text owner.

A `PLACE_TEXT_SHELL` plan may also have `textAction=DROP_TEXT` while retaining
`ownedTextFrameIds` as a source relation to the text it visually decorates. This
is valid only when every listed TextFrame has a separate Stage 1 HWPX text/table
owner. In that case the shell is visual-only and must not be reported as missing
HWPX text merely because it does not own the text slot itself.
That separate owner may be a direct `text_frame` plan or another composite/text
shell plan whose `ownedTextFrameIds` include the TextFrame.

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
- `PLACE_PAGE_BACKGROUND_PNG`
- `PLACE_TEXT_SHELL`
- `ABSORB_TEXT_STYLE`
- `PLACE_TABLE_STYLE`
- `DROP_VISUAL`

The `reason` must cite source metadata or extractor ownership metadata, not a
document-specific symptom.

## 4. Decision Order

Stage 1 decides in this order:

1. Register every IDML/resolved source object in the selected page range.
2. Build the source-slot registry and compact source-set/proof indexes:
   parent/child closure, page-local fragments, inline/table/master context,
   slot closure, source-set interning, and coverage claim lookup.
3. Assign a coverage status to every source object:
   `VISIBLE_OWNED`, `TEXT_OWNED`, `STYLE_OWNED`, `HIDDEN_BY_OWNER`,
   `DROPPED_INTENTIONAL`, `PROVENANCE_ONLY`, or `UNRESOLVED`.
4. Build `SourceBundle`s from source tree, story/thread, table/cell,
   inline/anchor, page/spread, and applied-master relationships.
5. Split every bundle into explicit ownership slots:
   `TEXT_SLOT`, `SHELL_SLOT`, `TABLE_STYLE_SLOT`, and
   `CONTENT_VISUAL_SLOT`.
6. Assign exactly one `SlotOwner` to every visible/style/text slot.
7. Create `RenderUnit`s for slots whose owner requires extracted PNG/vector
   material.
8. Derive `ObjectPlan`s from bundle, slot, owner, and RenderUnit records.
9. Resolve placement from original anchor/page/table/master context.
10. Resolve visual layer from source role, source layer/z, and the four policy
   layers.
11. Validate source coverage, slot uniqueness, RenderUnit/ObjectPlan closure,
    and executor readiness.

Steps 3-8 may query the registry, but they must not rebuild recursive source
clusters, rescan source subtrees, or create broad candidates that require later
normalization to become executable. If a slot cannot be closed from the registry
at creation time, the planner records a defect instead of creating a provisional
owner and repairing it in a later rebuild pass.

No later stage may create a new owner to compensate for a missing decision.

After Stage 1, executors may only do one of these:

- materialize the direct `ObjectPlan` for the current source object;
- skip the source object because the direct `ObjectPlan` says
  `DROP_VISUAL`, `DROP_TEXT`, `ABSORB_TEXT_STYLE`, or `PLACE_TABLE_STYLE`;
- record validation/diagnostic facts about an already executed plan.

If a source object has a direct executable `ObjectPlan`, later stages must not
try alternate materializations for that same story anchor or rendered item. In
particular, they must not fall through from a planned text shell to a badge,
box-list, simple-button, text-run, floating PNG, or rendered PNG fallback.
Fallback code is not allowed in the resolved pipeline. If an object is
unplanned, Stage 1 is incomplete and validation must report that gap.

No Stage 2.5 or post-text ownership refinement exists. Later stages may collect
diagnostics, but diagnostics must not change `ObjectPlan` or create/drop visible
material.

`SQUEEZE`/no-auto-line-wrap is only valid for source single-line labels. A
TextFrame whose resolved source text contains explicit line breaks, table
markers, or multiple composed lines must keep HWPX line wrapping enabled. Line
structure preservation should be represented by explicit break items or source
bounds, not by changing the whole container to single-line squeeze.
