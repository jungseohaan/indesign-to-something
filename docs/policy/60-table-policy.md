# Source Ownership Policy: Table Structure

> This file is part of the canonical source ownership policy.
> The canonical index is `docs/specs/POLICY-source-ownership.md`.

## 8. Table Structure And Decoration

V2 keeps the table itself editable, but stops translating decorative cell
appearance into HWPX table style.

- IDML table structure belongs to the table structure slot: table object,
  rows, columns, merged cells, fixed outer bounds, row/column geometry, and
  editable cell text.
- If an IDML table cell contains another IDML table source, the outer table and
  the nested table remain one editable table-structure tree. Stage 1 may decide
  whether that tree is anchored inline or page-local, but it must not split the
  nested table into a second visible page-level table owner or replace it with a
  whole-table PNG fallback merely because it is nested.
- A nested table that lives inside the same source Story as its parent table is
  owned through the parent cell relationship, not as an independent top-level
  table placement. Executors may materialize it only inside the owning HWPX
  cell `SubList`.
- A nested table that lives in a different source Story but is referenced by a
  parent table cell is still table structure, not a textless visual recovery
  case. The wrapper/anchor decision belongs to Stage 1, and later stages may
  flatten only the wrapper flow around that nested table. They must not emit the
  nested table again as a separate floating/page-level AST table.
- A merged/covered table grid coordinate is not a second editable cell owner.
  If an IDML table exposes both the spanning source cell and placeholder cells
  whose start grid coordinate is already covered by that span, Stage 2/3 must
  materialize only the spanning cell. Covered placeholders may be kept only as
  provenance; they must not receive a duplicate HWPX cell, duplicate text
  paragraphs, or a second table-structure owner.
- Resolved table-cell data may be used to rescue inline anchors that are lost
  from IDML story XML, but it must not create editable text in an IDML cell
  that has no direct visible source text. The source cell that owns the text is
  the IDML cell containing that text; empty spacer/placeholder cells are not
  text owners merely because resolved row/column lookup returns nearby text.
- When a clean `ASTTable` already owns an IDML table source, a table-only
  carrier TextFrame plan for the same table is the same table-structure slot.
  Executors must not emit another HWPX table/text owner for that carrier. The
  carrier may remain as provenance for placement, but not as a second visible
  `TEXT_SLOT` or table-structure owner.
- During migration the implementation value `TABLE_STYLE_SLOT` /
  `PLACE_TABLE_STYLE` / `HWPX_TABLE_STYLE` may still name this slot. In this
  policy, that legacy name means table structure only.
- IDML cell/table decoration belongs to textless graphic ownership, not HWPX
  table style: cell fill, border, rounded plates, row bands, shadows, patterns,
  masks, decorative separators, and table chrome are materialized from source
  PNG/vector graphic bundles.
- HWPX table carriers must be visually neutral except for the minimum structure
  needed to keep rows, columns, merged cells, text anchoring, and editing
  behavior. They must not create default white faces, synthetic borders, or
  replacement decoration that can cover the source-owned graphic material.
- If a visible table/cell decoration is missing, Stage 1 must look for the
  source graphic bundle or extraction record that should own that textless
  material. It must not repair the issue by inventing HWPX cell fill/border.
- Table outer borders are source edges, not inferred decoration. If IDML cell
  edge metadata says an outer edge has no stroke, the HWPX table carrier must
  keep that edge empty. Visible outer edges come from planned textless graphic
  material, not from a representative visible inner border.
- Missing table edge weight is not a license to invent a default stroke. A table
  border fallback must not borrow a weight from another edge. If source
  decoration is visible, it must come from planned native line/shell/textless
  graphic sources.
- When a table-only carrier cell contains an inline anchored composite object,
  source objects already planned as table structure are not emitted again as
  separate shell PNGs. Distinct visual shells inside the same composite remain
  separate textless graphic slots.
- A table-only carrier bundle includes the marker TextFrame and the IDML Table
  source. Source page-items in the same rendered carrier do not become table
  structure owners. They remain textless graphic material unless they are the
  actual IDML table structure source. All visual page-items remain `SHELL_SLOT`
  or `CONTENT_VISUAL_SLOT`.
- In table-like carrier groups, sibling TextFrames that visually sit on the
  table grid remain independent HWPX TextFrames unless the source explicitly
  anchors them inside an IDML table cell. They are not absorbed into cell text
  merely because their bounds align with the grid.
- Table-like carrier groups must not create cell overlays from later geometry
  matching. The HWPX table owns only table structure and editable cell text.
  TextFrames that are source siblings of the table carrier remain floating HWPX
  text plans unless the source explicitly anchors them inside an IDML table
  cell.
- Non-text parent/sibling page-items near a table-only TextFrame default to
  `SHELL_SLOT` or `CONTENT_VISUAL_SLOT`, not table structure. Stage 1 must not
  attach a source item to table structure merely because it looks like a cell
  fill, row band, edge, or grid primitive.
- Composite groups, group roots, child-bearing shapes, tabs, rounded outlines,
  badges, callout containers, and any shape protruding outside the table bounds
  remain separate shell/content visual owners. They must not be subtracted from
  shell PNGs merely because they are siblings of a table-only TextFrame.
- Stage 1 extraction planning must emit `SHELL_SLOT` candidates for non-text
  visual siblings of a table-only TextFrame carrier when those visual sources
  are not the actual IDML table structure source. This includes composite/
  child-bearing visual siblings, leaf source shapes that carry visible fill, and
  stroke-only lines used as visible table/grid decoration. The executor must not
  recover these shells later by bounds matching or by drawing replacement
  shapes.
- A broad direct-child shell candidate that contains a table-only TextFrame
  carrier and non-text visual sibling shells is still a carrier for those
  siblings, not the final owner of their visual channel. Stage 1 must split the
  sibling shell slots out first, then remove those sibling visual sources from
  the broad parent export. This is a source-tree ownership split, not a later
  HWPX layer or bounds correction.
- Hidden visual source ids from a rendered table carrier are still filtered by
  the same table-structure eligibility rule before they can close a visible
  shell channel. A broad carrier source bundle is provenance, not automatic
  ownership of every descendant visual as table structure.
- A rendered slot-only parent shell that contains a table-only TextFrame source
  is not executable shell material unless its visual source channel explicitly
  excludes the table structure sources.
- A separate source shape must not be absorbed into table style. If it is
  visible, it owns textless graphic material.
- A `PLACE_TABLE_STYLE` plan may describe table structure and geometry only. It
  must not project page shapes, row bands, cell plates, borders, or fills into
  HWPX cell style.
- Header bands, first-column bands, and table outer outlines that are source
  fill/stroke material are textless graphic owners. They are not emitted as
  HWPX table properties.
- Native or extracted header/table imagery owns a distinct
  `SHELL_SLOT`/`CONTENT_VISUAL_SLOT` and must not duplicate table structure.
- A source owned as table structure is not also emitted as shell/content visual;
  a source owned as table decoration is not also emitted as HWPX table style.
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
- When a table has fixed source bounds, row and column geometry is part of the
  table structure slot. Executors must preserve the planned row heights and
  disable row auto-grow; content-fit expansion is allowed only for tables
  without fixed source bounds.
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
