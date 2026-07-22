# Table Ownership Contract Rewrite Plan

Status: draft for `table-ownership-contract`.

This plan replaces legacy table recovery with an explicit Stage 0/1 table
contract. Existing table code may be read as historical context, but the new
implementation must not reuse its recovery, fallback, geometry guessing, or
late page reassignment logic.

## Investigation: Guide u0 p30-32

Fresh run:

- branch: `table-ownership-contract`
- base: `open-indd` at `17bdf50a`
- case: `지도서/u0`, pages 30-32
- output: `output/issues/지도서/u0/p030-032-20260722-110234`

Observed source facts:

- IDML contains table stories for the relevant pages:
  - `Story_u9151.xml` -> table `u9151i9181`
  - `Story_u91fe.xml` -> table `u91fei9215`
  - `Story_u9310.xml` -> table `u9310i938e`
  - `Story_u9408.xml` -> table `u9408i941f`
- Resolved table records currently exist only for p30 table carriers:
  - owner TextFrame `37198`, resolved story `37201`, table `37249`,
    IDML table `u9151i9181`, bounds `[73.0, -180, 115.6944, 203]`
  - owner TextFrame `37371`, resolved story `37374`, table `37397`,
    IDML table `u91fei9215`, bounds `[138.9193, -180, 254.9999, 203]`
- ObjectPlans exist for p31/p32 table-only carriers, but the corresponding
  resolved table/text-frame records are absent from `resolved.textFrames` /
  `resolved.stories`.
- One p31 table-only plan is invalid by source ancestry:
  - `37726` is under parent group `37670`, whose source visibility is
    `visible=false`; this table must not execute.
- Current AST output places p30 table fragments on later sections:
  - `u9151i9181` and `u91fei9215` are emitted on sections 1/2 with negative or
    zero x values instead of being owned as p30 -> p31 page-local fragments.

Conclusion:

The bug is not one table placement defect. It is a contract defect:

1. Stage 0 does not expose every executable table carrier/table pair as
   resolved source facts.
2. Stage 1 emits table-style ObjectPlans that are not fully executable because
   they lack a stable source table/story/fragment contract.
3. Stage 2/3 still rediscover and reposition tables from IDML stories, causing
   page shifts, duplicates, and missing tables.

## New Table Policy

### 1. A table is executable only through a table ObjectPlan

Every visible HWPX table must be backed by exactly one `ObjectPlan` with:

- `ownershipSlot=TABLE_STYLE_SLOT`
- `materialization=HWPX_TABLE_STYLE`
- `visualAction=PLACE_TABLE_STYLE`
- `sourceObjectIds` containing the carrier TextFrame id and the IDML table id
- `styleSourceObjectIds` containing only declared table appearance sources
- `ownedTextFrameIds` containing only the source carrier TextFrame ids
- `pageIndex`, `bounds`, and `coordinateSpace=PAGE` for one page-local fragment

If no such plan exists, the table is not emitted. The converter reports a
missing-table warning with source ids and story/table ids.

### 2. Stage 0 must provide immutable table source facts

Stage 0 must build a table source index from IDML and resolved metadata:

- `sourceStoryId`: IDML story self id, such as `u9151`
- `resolvedStoryId`: resolved story id, when available
- `sourceTableId`: IDML table self id, such as `u9151i9181`
- `decimalTableId`: decimal id parsed from the table self id
- `carrierTextFrameId`
- `carrierParentChainIds`
- `carrierVisibleByTree`
- `pageIndex`
- `carrierBounds`
- `tableContentBounds`
- `cell structure`, `rowHeights`, `columnWidths`, merged-cell map
- `styleSourceObjectIds`

This index is data, not ownership. It must not place a table.

### 3. Visibility ancestry is mandatory

A table carrier is executable only when the carrier and every source ancestor
needed for placement is visible. Child visibility alone is insufficient.

Hidden carrier plans must be emitted as diagnostics or `DROP_VISUAL`, but must
not become executable HWPX tables.

### 4. Cross-page tables are page-local fragments

A source table that spans pages is not one later-resplit table. Stage 1 must
emit one executable ObjectPlan per visible page fragment, all sharing the same
source bundle and table slot but differing by:

- `pageIndex`
- `bounds`
- `fragmentColumnRange` or equivalent source-table fragment field
- optional `continuedFrom` / `continuedTo` diagnostic ids

The executor must not infer previous/next page placement from negative x,
carrier width, column width, or page overlap. It executes the fragment written
by Stage 1.

### 5. Table structure and style are one slot

The editable table grid, editable cell text, row/column geometry, and declared
table appearance sources are one `TABLE_STYLE_SLOT` owner. The same source table
must not also be emitted through:

- page-background PNG
- shell PNG
- another floating table
- another table-only carrier
- an inline/floating rewritten copy

### 6. Missing beats invented

When required table facts are missing, do not synthesize from nearby objects,
text contents, visual overlap, page number, coordinate patterns, or bounds
search. Emit a warning and omit the table/attribute.

Allowed warning categories:

- `TABLE_SOURCE_FACT_MISSING`
- `TABLE_CARRIER_HIDDEN_BY_ANCESTRY`
- `TABLE_PLAN_MISSING`
- `TABLE_PLAN_NOT_EXECUTABLE`
- `TABLE_FRAGMENT_MISSING`
- `TABLE_STYLE_SOURCE_MISSING`

## New Implementation Strategy

### Stage 0: TableSourceIndex

Create a new Stage 0 table source collector that scans:

- IDML `Stories/Story_*.xml`
- source page items / source index
- resolved text frames
- resolved stories and resolved tables

It emits `table-source-index.jsonl` and in-memory `TableSourceRecord` objects.

Rules:

- Parse IDML story/table ids directly.
- Map decimal ids and `u...i...` ids once.
- Resolve the carrier TextFrame from source story containers.
- Record hidden ancestry, but do not decide ownership.
- Do not use page/text/coordinate exceptions.
- Do not synthesize a table from graphics.

### Stage 1: TableOwnershipPlanner

Create a new planner pass that consumes `TableSourceRecord` and writes
executable `ObjectPlan` records.

Planner responsibilities:

- choose exactly one table owner per `(source bundle, TABLE_STYLE_SLOT)`
- reject hidden-by-ancestry carriers
- split cross-page tables into explicit page-local fragment plans
- assign table structure/style sources
- write warnings for missing non-optional facts

The planner must produce no AST and no HWPX structures.

### Stage 2/3: TableStructureExecutor

Create a new executor that consumes only table ObjectPlans.

Executor responsibilities:

- load the exact IDML story/table referenced by the plan
- materialize the requested row/column fragment
- apply row/column geometry and merged cells
- apply declared `styleSourceObjectIds`
- place at `ObjectPlan.bounds`

Executor prohibitions:

- no table discovery
- no page reassignment
- no fallback to owner frame, baseline, nearby bounds, or geometry search
- no hidden parent repair
- no default fills/strokes
- no PNG/table substitution

### Stage 4: TableContractValidator

Add validation before HWPX write:

- no executable table without `PLACE_TABLE_STYLE`
- no visible table source emitted by more than one plan
- no hidden-by-ancestry table executed
- every table plan source id resolves to one IDML table
- every table plan fragment has explicit `pageIndex` and `bounds`
- every table style source id belongs to the declared table slot
- every skipped table has a warning

## Migration Plan

1. Add `TableSourceRecord` / `TableSourceIndex` data model.
2. Add source collector and diagnostic writer.
3. Add `TableOwnershipPlanner` and emit table ObjectPlans from it.
4. Add `TableContractValidator` in warning-only mode.
5. Add `TableStructureExecutor` behind a config flag for table plans.
6. Switch table execution to plan-only mode.
7. Delete or disable legacy table discovery paths.
8. Promote validator failures from warning to hard stop for duplicate visible
   table slots.

## Acceptance Criteria

- p30 upper/lower tables are emitted as p30 -> p31 page-local fragments.
- p31 hidden carrier `37726` is omitted with
  `TABLE_CARRIER_HIDDEN_BY_ANCESTRY`.
- p31/p32 visible carriers `37645` and `37893` are executable only if Stage 0
  has their source story/table records.
- No table appears on a page solely because a later executor inferred placement
  from negative x or page bounds.
- Missing records produce warnings, not invented tables.
