# Source Ownership Policy: Text

> This file is part of the canonical source ownership policy.
> The canonical index is `docs/specs/POLICY-source-ownership.md`.

## 6. Text

- HWPX owns source text that is editable/searchable in IDML.
- TextFrames are merged only when IDML story/thread/table structure says they
  are the same text flow.
- Bounds containment is not a merge reason.
- TextFrame layout properties such as `verticalJustification`, inset, leading,
  paragraph spacing, and composed-line geometry are source layout facts. Stage 2
  may copy them or materialize them according to `ObjectPlan`; it must not
  replace them with a guessed alignment or page-specific overlap workaround.
- Source TextWrap is governed by
  [`45-text-wrap-policy.md`](45-text-wrap-policy.md). When Stage 1 declares a
  TextWrap contract from `resolved.composedLines`, Stage 2 may approximate the
  source composed line geometry while keeping the text editable/searchable.
- When a source TextFrame's authored bounds overlap another frame but their
  `composedLines` do not overlap, the source composed-line geometry is the
  diagnostic truth for visible text overlap. The fix is to preserve that source
  geometry or report a validation gap, not to merge the TextFrames or alter
  their ownership after planning.
- A complete visual may own text only when the source bundle itself is declared
  a complete visual owner.
- Placed external visuals are not decomposed into HWPX text unless the extractor
  exposes source text objects and ownership metadata for them.
