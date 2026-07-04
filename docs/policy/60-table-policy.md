# Source Ownership Policy: Table Style

> This file is part of the canonical source ownership policy.
> The canonical index is `docs/specs/POLICY-source-ownership.md`.

## 8. Table Style

Table/cell visual properties belong to `TABLE_STYLE_SLOT`.

- IDML cell fill, border, inset, and row/column structure are emitted as HWPX
  table properties.
- Table outer borders are source edges, not inferred decoration. If IDML cell
  edge metadata says an outer edge has no stroke, HWPX must keep that edge empty.
  Executors may add an outer border only from explicit IDML cell edge metadata,
  an explicit TextFrame/parent outline source, or a real source page-item line
  absorbed by a `PLACE_TABLE_STYLE` plan. They must not synthesize missing outer
  edges from a representative visible inner border.
- Missing table edge weight is not a license to invent a default stroke. A table
  border fallback may borrow a weight only from another explicit visible edge in
  the same source table. If no source edge carries visible weight, HWPX table
  borders stay empty and any visible separators must come from planned native
  line/shell sources.
- When a table-only carrier cell contains an inline anchored composite object,
  source shapes already planned into the table/style carrier slot are not
  emitted again as separate shell PNGs. Distinct label shells inside the same
  composite remain separate shell slots.
- A table-only carrier bundle includes the marker TextFrame and the IDML Table
  source. Source page-items in the same rendered carrier do not automatically
  become `TABLE_STYLE_SLOT` owners. They may enter `TABLE_STYLE_SLOT` only when
  Stage 1 maps them one-to-one to IDML cell fill, cell edge, inset, row/column
  geometry, or a simple contained table grid primitive. All other visual
  page-items remain `SHELL_SLOT` or `CONTENT_VISUAL_SLOT`.
- In table-like carrier groups, sibling TextFrames that visually sit on the
  table grid remain independent HWPX TextFrames unless the source explicitly
  anchors them inside an IDML table cell. They are not absorbed into cell text
  merely because their bounds align with the grid.
- Table-like carrier groups must not create cell overlays. The HWPX table owns
  only `TABLE_STYLE_SLOT` (fill, stroke, inset, row/column geometry). TextFrames
  that are source siblings of the table/style carrier remain floating HWPX text
  plans and are not moved into table cells by a later executor.
- Non-text parent/sibling page-items near a table-only TextFrame default to
  `SHELL_SLOT`, not `TABLE_STYLE_SLOT`. Stage 1 may attach a source item to
  `TABLE_STYLE_SLOT` only when it is a leaf source shape that represents the
  IDML Table/Cell edge/fill itself or a simple rectangular table grid line/fill
  that is contained by the table owner bounds and can map one-to-one to cells,
  rows, columns, or edges.
- Composite groups, group roots, child-bearing shapes, tabs, rounded outlines,
  badges, callout containers, and any shape protruding outside the table bounds
  remain separate shell/content visual owners. They must not be subtracted from
  shell PNGs merely because they are siblings of a table-only TextFrame.
- Stage 1 extraction planning must emit `SHELL_SLOT` candidates for non-text
  visual siblings of a table-only TextFrame carrier when those visual sources
  are not IDML Table/Cell edge/fill material. This includes composite/child-
  bearing visual siblings and leaf source shapes that carry visible fill. A leaf
  stroke-only source remains table/grid/connector material unless the source
  tree declares it as part of a larger shell bundle. The executor must not
  recover these shells later by bounds matching or by drawing replacement
  shapes.
- A broad direct-child shell candidate that contains a table-only TextFrame
  carrier and non-text visual sibling shells is still a carrier for those
  siblings, not the final owner of their visual channel. Stage 1 must split the
  sibling shell slots out first, then remove those sibling visual sources from
  the broad parent export. This is a source-tree ownership split, not a later
  HWPX layer or bounds correction.
- Hidden visual source ids from a rendered table carrier are still filtered by
  the same table-style eligibility rule before they can close a visible shell
  channel. A broad carrier source bundle is provenance, not automatic ownership
  of every descendant visual as `TABLE_STYLE_SLOT`.
- A rendered slot-only parent shell that contains a table-only TextFrame source
  is not executable shell material unless its visual source channel explicitly
  excludes the table/style sources.
- A separate source shape may be absorbed into table style only when Stage 1
  explicitly plans `PLACE_TABLE_STYLE`.
- A `PLACE_TABLE_STYLE` source shape may describe either a full-cell fill or a
  row/column band attached to cell edges. Executors may project such source
  bands to HWPX cell fill only for ids listed in the table plan's
  `styleSourceObjectIds`; unplanned page shapes are not reclassified as table
  style by geometry alone.
- Header bands, first-column bands, and table outer outlines that are simple
  source fill/stroke material remain `TABLE_STYLE_SLOT` owners. They are not
  emitted as native/header images merely because the IDML table cell metadata
  itself lacks the fill or stroke; the planned source page-item is the style
  source for the HWPX table property.
- Native or extracted header imagery is allowed only for non-table-style visual
  material that cannot be represented as HWPX cell fill, border, inset, or row/
  column geometry. It must own a distinct `SHELL_SLOT`/`CONTENT_VISUAL_SLOT`
  and must not duplicate a `TABLE_STYLE_SLOT` source.
- A source absorbed as table style is not also emitted as shell/content visual.
- Resolved table bounds are the canonical table content placement bounds when
  present. IDML tables live inside Stories/TextFrames, but the table grid/content
  can start below or inside its carrier frame; using the carrier as the primary
  table anchor loses that source offset.
- For marker-only/table-only owner TextFrames without resolved table bounds, the
  owner TextFrame content box is the fallback table placement. The owner
  TextFrame's `InsetSpacing` is part of the table content origin, so fallback
  placement uses the owner content box, not the raw frame box.
- Resolved table records must preserve their owner Story id. Executors may ask
  for table placement by IDML table self id or by resolved decimal table id; both
  identifiers refer to the same source table and must resolve to the same
  resolved table bounds before falling back to the table-only owner TextFrame
  content box.
- A table whose source bounds are page-local and closed keeps fixed outer bounds
  unless the original source is linked, overflowed, or multi-page.
- Writer/content-fit code must not enlarge such a table and push rows to another
  page as a side effect.
- When a table has fixed source bounds, row and column geometry is part of
  `TABLE_STYLE_SLOT`. Executors must preserve the planned row heights and disable
  row auto-grow; content-fit expansion is allowed only for tables without fixed
  source bounds.
- Table placement bounds and table row geometry are separate sources of truth.
  Placement bounds may set the table anchor and projected width, but they must
  not rescale IDML `rowHeights` to fill a larger carrier TextFrame. A table row
  may become taller only when Stage 1 declares that row geometry, not because a
  later writer sees a taller owner frame.
- For a table carried by a table-only TextFrame, resolved table bounds define
  the table anchor and page projection when available. Executors must not later
  rewrite that top/left from owner frame bounds, glyph bounds, or content-fit
  measurements. Owner content box placement is only the declared fallback when
  resolved table bounds are missing.
- Extraction must derive table-only carrier table bounds from the carrier
  TextFrame content box. A table-only carrier is the source object that owns the
  table placement, so first-cell baseline, glyph bounds, and row-height
  back-calculation are not authoritative for that table's top/left. Baseline
  back-calculation is allowed only as a fallback for non-carrier story tables
  where no source carrier/content bounds exist.
- When the table cell object does not expose the owning TextFrame directly,
  extraction must resolve the carrier through the source Story's text container
  before falling back to baseline geometry. The Story container is still source
  ownership metadata; it is not a layout-time repositioning rule.
- A table-only carrier is identified by a source TextFrame whose parent Story
  has one or more IDML tables and whose own contents are marker-only after
  removing InDesign control characters by character code. Story-level contents
  may include cell text and must not be used as the sole emptiness test.
- IDML table/cell layout metadata includes `TextTopInset`,
  `FirstBaselineOffset`, `MinimumFirstBaselineOffset`, and
  `VerticalJustification`. These are source table layout properties and must
  survive IDML -> AST -> Flat -> HWPX. If HWPX cannot express
  `FirstBaselineOffset=EmboxHeight` directly in a table cell, the executor may
  project the source top inset to the first paragraph's source spacing and keep
  the HWPX cell top margin at zero. This is a source-metadata translation, not a
  late visual offset; it must not be triggered by page number, text content,
  bounds overlap, or screenshot symptoms.
