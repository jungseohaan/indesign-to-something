# Source Ownership Policy: TextWrap

> This file is part of the canonical source ownership policy.
> The canonical index is `docs/specs/POLICY-source-ownership.md`.

## 6.5 TextWrap

`TextWrap` is a source-layout execution feature for `TEXT_SLOT`. It is not a
new visual owner, not a duplicate text channel, and not permission for executors
to infer layout from visible symptoms.

TextWrap is one of the primary reasons the pipeline treats `resolved.json` as
canonical source metadata. IDML source structure can identify a floating group
and a body TextFrame, but it does not by itself provide the final composed line
geometry after InDesign applies text wrap, font metrics, inset, leading, and
composition. `resolved.composedLines` is therefore required source truth for
wrapped text reproduction.

### Source Truth

Stage 1 may declare `TextWrap` only from source layout metadata:

- the `HWPX_TEXT` TextFrame has `resolved.composedLines`;
- one or more composed lines have non-neutral `wrapIndentLeft` or
  `wrapIndentRight`, or their line bounds are narrower than the source
  TextFrame bounds;
- when the obstacle source can be identified, the narrowed side corresponds to
  a source page-level obstacle such as a floating group, anchored object, or
  placed visual whose source bounds overlap the TextFrame's source bounds in
  the affected vertical band;
- the obstacle relationship, when recorded, is proven from IDML/resolved source
  ids, bounds, z-order, page index, and placement metadata, not from rendered
  pixels or a page-specific phrase.

When those facts exist, the source `composedLines` are authoritative evidence
that InDesign already composed the text around the obstacle. The HWPX converter
does not need to rediscover the wrap. It needs to approximate the source
composed line geometry without turning automatic composition boundaries into
authored paragraph structure.

`resolved.composedLines` may be enough to declare the text layout contract even
when the obstacle source id cannot yet be named. In that case Stage 1 writes an
empty obstacle list on the `SOURCE_TEXT_WRAP` contract and keeps the obstacle's
visual ownership unchanged. Missing obstacle ids are diagnostics/proof gaps, not
permission for Stage 2 or Stage 3 to rediscover TextWrap from pixels or overlap.

### Ownership

TextWrap does not change ownership:

- the wrapped paragraph text remains `OWNED_BY_HWPX_TEXT`;
- the obstacle visual keeps its own planned owner, usually page-plane PNG,
  floating content PNG, or shell/content visual;
- the obstacle must not be converted to an inline spacer merely to push text;
- no text may be moved into the obstacle PNG unless Stage 1 separately assigns
  that text to `OWNED_BY_PNG` under the normal text policy.

The `TextWrap` execution contract belongs to the wrapped `HWPX_TEXT` plan as a
layout hint. It may reference the obstacle source ids/render unit ids, but it
does not make the obstacle part of the text bundle.

### Stage Responsibilities

Stage 1:

- detects source-composed wrap from `composedLines` and overlapping source
  obstacles when they can be proven;
- records the wrap contract on the `HWPX_TEXT` ObjectPlan or an equivalent
  Stage 1 text layout plan;
- records source proof: wrapped TextFrame id, affected composed line indices,
  original frame bounds, line bounds, wrap indents, and obstacle source ids when
  available;
- chooses no new visual ownership while doing this.

Stage 2:

- emits the original text as HWPX text;
- keeps the source paragraph as editable HWPX text. When Stage 1 declares a
  `SOURCE_TEXT_WRAP` contract, Stage 2 may insert hard HWPX `lineBreak`
  elements at source composed-line boundaries inside that same source TextFrame.
  This is a source-layout execution strategy, not a page/text/coordinate
  exception;
- executes the recorded wrap side without rediscovery. When the source contract
  says `wrapSide=LEFT`, affected paragraphs may use right alignment so the
  hard-broken source lines occupy the right side of the original editable
  TextFrame. `wrapSide=RIGHT` keeps the normal left-flow approximation unless
  Stage 1 records a stronger source alignment contract;
- must not split the source TextFrame into per-composed-line floating text
  boxes. That preserves visual line geometry at the cost of editability and is
  therefore not an allowed main-path implementation;
- may use HWPX paragraph/table/layout primitives only when they preserve the
  original source TextFrame as a coherent editable text flow;
- must preserve source reading order and searchable text;
- must not create page/text/coordinate/literal-string exceptions.

Stage 3:

- places the obstacle visual exactly as planned by its own ObjectPlan;
- must not change the obstacle from floating/page placement to inline placement
  just to simulate wrapping;
- must not add a second visual spacer for the same obstacle.

Stage 4:

- validates that the wrapped text remains single-owned as `HWPX_TEXT`;
- validates that the obstacle visual keeps its own planned slot owner;
- checks approximate line geometry against source `composedLines` within a
  documented tolerance;
- reports missing TextWrap contracts when a source TextFrame has composed wrap
  evidence but no Stage 1 layout plan.

### Forbidden Implementations

- Do not infer TextWrap from screenshots, occlusion, color, page number, literal
  text, or a one-off coordinate patch.
- Do not narrow or shift a TextFrame in Stage 2/3 unless Stage 1 has declared
  TextWrap.
- Do not insert spacer images/tables as an unplanned workaround.
- Do not materialize `resolved.composedLines` as hard line breaks unless Stage 1
  has declared a `SOURCE_TEXT_WRAP` contract for that source TextFrame.
- Do not materialize `resolved.composedLines` as individual floating TextFrames
  or drawText carriers. TextWrap approximation must not destroy ordinary text
  editing.
- Do not apply `lineWrap=SQUEEZE` from screenshots, visible overflow, narrow
  columns, or literal text symptoms. SQUEEZE is allowed only when Stage 1 has
  declared a source layout contract that needs it.
- For `SOURCE_TEXT_WRAP`, Stage 2 executes the contract by inserting
  source-composed hard line breaks inside the original editable TextFrame and
  applying paragraph-local alignment and `lineWrap=SQUEEZE` to those affected
  paragraphs. Alignment may only come from the Stage 1 `wrapSide` contract, not
  from screenshots or page-specific symptoms. This is the canonical HWPX
  approximation for source wrap because HWPX has no equivalent floating-object
  text wrap.
- `SOURCE_TEXT_WRAP` SQUEEZE must remain paragraph-local. It must not turn the
  whole TextFrame, drawText carrier, or unrelated paragraphs into a single-line
  pressure box.
- Bounded single-line labels/carriers may also use no-wrap/SQUEEZE only when
  resolved metadata proves source single-line intent, no overflow, and source
  ownership keeps the carrier as one editable object.
- Do not rely on HWPX floating image wrap to reproduce InDesign wrap unless
  Stage 1 explicitly selects that as the TextWrap implementation strategy and
  validation proves it is stable for the source layout.
- Do not merge the wrapped body TextFrame with the floating figure, and do not
  convert a page-level floating figure into story-flow inline material.

### Example Pattern

If a page-level floating group overlaps the right side of a body TextFrame and
the TextFrame's `composedLines` show the first seven lines ending at the
floating group's left edge, Stage 1 declares TextWrap on the body TextFrame.
The floating group remains page-level visual material. Stage 2 approximates the
wrapped layout by inserting source-composed hard line breaks inside the original
editable TextFrame. If HWPX cannot represent the wrap stably, validation reports
the layout approximation gap rather than splitting the paragraph into separate
floating text boxes.
