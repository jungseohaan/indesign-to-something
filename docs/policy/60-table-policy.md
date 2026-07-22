# Source Ownership Policy: Table Structure

> This file is part of the canonical source ownership policy.
> The canonical index is `docs/specs/POLICY-source-ownership.md`.

## 8. Table Structure And Decoration

V2 keeps the table itself editable, and table-authored fill/stroke/outline
appearance is absorbed into HWPX table style only when Stage 1 declares the
source objects as the table's `TABLE_STYLE_SLOT`.

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
- The implementation values `TABLE_STYLE_SLOT` / `PLACE_TABLE_STYLE` /
  `HWPX_TABLE_STYLE` name the table style channel. This channel may include the
  IDML table structure source and source-authored table appearance such as cell
  fill, cell border, grid stroke, and table outer outline.
- IDML cell/table decoration belongs to HWPX table style when the source
  relationship identifies it as table appearance: a table-only TextFrame's
  direct fill/stroke wrapper, table cell plate, or simple grid/outline stroke
  declared in `ObjectPlan.styleSourceObjectIds`.
- HWPX table carriers must remain visually neutral only for appearance that is
  not declared in `TABLE_STYLE_SLOT`. They must not create default white faces,
  synthetic borders, or replacement decoration.
- If a visible table/cell decoration is missing, Stage 1 must look for the
  source object that should be listed in `styleSourceObjectIds`. Executors must
  not repair the issue by bounds-searching arbitrary page shapes.
- Table outer borders are source edges, not inferred decoration. If IDML cell
  edge metadata and declared style sources say an outer edge has no stroke, the
  HWPX table carrier must keep that edge empty.
- Missing table edge weight is not a license to invent a default stroke. A table
  border fallback must not borrow a weight from another edge. If source
  decoration is visible, it must come from a declared table style source.
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
  `SHELL_SLOT` or `CONTENT_VISUAL_SLOT` unless source relationship and simple
  table-appearance role qualify them for `TABLE_STYLE_SLOT`. Stage 1 must not
  attach a source item to table style merely because it looks nearby.
- Composite groups, group roots, child-bearing shapes unrelated to the table
  cell wrapper, tabs, rounded outlines, badges, callout containers, and any
  shape protruding outside the table role remain separate shell/content visual
  owners. They must not be subtracted from shell PNGs merely because they are
  siblings of a table-only TextFrame.
- Stage 1 extraction planning must emit `TABLE_STYLE_SLOT` candidates for
  source-authored non-text visual table attributes and `SHELL_SLOT` candidates
  for other non-text visual siblings. The executor must not recover either class
  later by bounds matching or by drawing replacement shapes.
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
- A separate source shape must not be absorbed into table style unless Stage 1
  declares it in a table plan's `styleSourceObjectIds`.
- If an editable table cell contains a planned inline text shell
  (`placement=INLINE`, `PLACE_TEXT_SHELL`, `OWNED_BY_HWPX_TEXT`) whose shell
  visual is declared as table/cell style material, the containing HWPX cell may
  absorb that shell's fill/stroke as `TABLE_STYLE_SLOT`. The inline carrier is
  then removed from cell flow, and the shell's `ownedTextFrameIds` are emitted
  as editable HWPX cell text in the same cell. The owned TextFrame's source
  inset becomes the HWPX cell margin, and its resolved run color remains the
  HWPX run text color. This is execution of a Stage 1 source relationship, not
  a bounds, text, page, or color heuristic.
- A `PLACE_TABLE_STYLE` plan describes table structure, geometry, and declared
  source-authored table appearance. It must not project undeclared page shapes,
  row bands, cell plates, borders, or fills into HWPX cell style.
- Header bands, first-column bands, and table outer outlines that are declared
  table style sources are emitted as HWPX table properties. Otherwise they
  remain textless graphic owners.
- Native or extracted header/table imagery owns a distinct
  `SHELL_SLOT`/`CONTENT_VISUAL_SLOT` and must not duplicate table structure.
- A source owned as table structure is not also emitted as shell/content visual;
  a source owned as table style is not also emitted as shell/content visual.
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
- Table execution is plan-only. A visible HWPX table may be emitted only from a
  Stage 1 `ObjectPlan` with `TABLE_STYLE_SLOT`, `PLACE_TABLE_STYLE`, and
  `HWPX_TABLE_STYLE`. Later executors must not rediscover, reposition, or rescue
  tables by scanning IDML stories, bounds, page overlap, text contents, or
  nearby graphics. If the plan is missing or not executable, the table is
  omitted with a table ownership warning.
- Stage 0 must expose table source facts before ownership planning: IDML story
  id, IDML table id, decimal table id, carrier TextFrame id, carrier parent
  chain, source visibility through that chain, page index, carrier bounds, table
  content bounds, cell grid, row/column geometry, and declared table appearance
  sources. These facts are not ownership decisions.
- A table carrier is executable only when the carrier and its source parent
  chain are visible. Child visibility is not enough. A table-only TextFrame under
  a hidden group must not become a visible HWPX table; it may only produce a
  diagnostic or `DROP_VISUAL` plan.
- Cross-page tables are represented by explicit page-local table fragment
  ObjectPlans. Executors must not infer previous/next page fragments from
  negative x, page width, carrier width, table width, or row/column geometry.
  Each fragment must carry its own `pageIndex`, `bounds`, and fragment source
  range from Stage 1.
- Missing table facts are not repaired. The allowed outcome for missing story,
  table, carrier, style source, or fragment facts is a warning and omission, not
  a synthetic table, fallback placement, borrowed border/fill, or PNG
  replacement.
- IDML table/cell layout metadata includes `TextTopInset`,
  `FirstBaselineOffset`, `MinimumFirstBaselineOffset`, and
  `VerticalJustification`. These are source table layout properties and must
  survive IDML -> AST -> Flat -> HWPX. If HWPX cannot express
  `FirstBaselineOffset=EmboxHeight` directly in a table cell, the executor may
  project the source top inset to the first paragraph's source spacing and keep
  the HWPX cell top margin at zero. This is a source-metadata translation, not a
  late visual offset; it must not be triggered by page number, text content,
  bounds overlap, or screenshot symptoms.
