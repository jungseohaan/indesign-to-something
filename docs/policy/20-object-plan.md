# Source Ownership Policy: ObjectPlan Contract And Decision Order

> This file is part of the canonical source ownership policy.
> The canonical index is `docs/specs/POLICY-source-ownership.md`.

## 3. ObjectPlan Contract

Every visible candidate must have one `ObjectPlan` before AST generation.

Required identity fields:

- `sourceObjectIds`: source bundle identity.
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

For a `PLACE_TEXT_SHELL` plan, executor-visible source is the union of
`visualSourceObjectIds`, `styleSourceObjectIds`, and `ownedTextFrameIds`, after
Stage 1 has removed child slots owned by inline/table/content plans. The hidden
visual set for export is `executionSourceObjectIds - exportSourceObjectIds`.
This keeps broad source ancestry available for diagnostics without letting a
parent shell hide or export child slots that another ObjectPlan already owns.

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
