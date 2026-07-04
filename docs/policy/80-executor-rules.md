# Source Ownership Policy: Executor Rules

> This file is part of the canonical source ownership policy.
> The canonical index is `docs/specs/POLICY-source-ownership.md`.

## 10. Executor Rules

Executor emission order:

- Executor emission must preserve the finalized Stage 1 source-depth contract.
  When HWPX XML order affects overlapping objects in the same execution plane,
  emit planned objects back-to-front: smaller `ObjectPlan.zOrder` first, larger
  `ObjectPlan.zOrder` later. This is not a new ownership decision and must not
  recalculate, promote, demote, or drop any plan.
- Pixel preparation must not remove the fill of a visual whose finalized policy
  layer is `BACKGROUND`. Knockout/transparent-paper preparation is allowed only
  for planned shell material where the fill is not the owned visual slot.

Text Builder:

- Emits only planned HWPX text/table text.
- Builds source story/cell flow before container placement. The flow already
  contains source inline anchors and planned inline objects.
- A source tab is a TextFlow token and may be emitted only once. If Text Builder
  synthesizes a decorative leader/page-number separator tab, it must first prove
  from the already-built source flow that no tab token immediately precedes that
  target. Inline anchors and shell/materialization must not add their own
  spacing tab or margin to compensate for placement.
- Places a TextFrame from its source TextFrame bounds or from an explicit Stage
  1 placement plan only. It must not move, resize, split, or hide a TextFrame
  because another TextFrame overlaps it, because a sibling/source shell appears
  nearby, because alpha pixels suggest a tighter shell, or because a rendered
  backdrop seems to contain it. Those are shell/text ownership questions and
  must be represented in `ObjectPlan` before this stage.
- Text Builder must not split a single TextFrame into multiple HWPX text boxes
  from composed-line Y gaps, median line spacing, or other visual layout
  heuristics. Splitting is allowed only when the source says so through
  story/thread/table/anchor structure or when Stage 1 has an explicit plan for
  separate source carriers.
- A planned text shell may provide visual material and style for its owned text,
  but it is not a late geometry instruction. Text Builder must not align a
  TextFrame to shell pixel bounds, expand it to a sibling shape, or shift it
  away from a sibling fill/stroke object.
- A source story/cell flow builder returns an empty flow when there is no
  executable content; it does not return null. Container executors treat empty
  flow as empty source content, not as a signal to reinterpret ownership.
- Shell-only fallback is not allowed. If source cell/story flow has no
  executable content, that emptiness must be represented by Stage 1 ownership
  and reported by validation when required material is missing.
- Tables, TextFrames, and other containers consume the prepared flow; they do
  not recover missing inline objects by matching visible strings after layout.
- Structural bridges such as side-head table normalization may execute only
  when Stage 1 has already marked the source table with an explicit plan. If a
  bridge recognizes an unplanned structural candidate, it records a validation
  warning and leaves the AST unchanged.
- Executors must not synthesize a new inline text-shell `ObjectPlan` from
  resolved/page-item metadata. If Stage 1 did not plan an inline text shell, the
  executor leaves that anchor to the already planned text/visual owner.
- Table-cell flow executes planned inline text shells by source anchor and
  `ownedTextFrameIds`, not by the rendered channel type. If Stage 1 says a
  `page_object` render is the canonical extracted shell for an inline source,
  the table-cell executor attaches that shell material to the same inline shell
  carrier.
- Code must not rebuild cell text from a resolved story as a fallback. Missing
  cell text or missing inline anchors are Stage 1/Text Builder planning defects,
  not an invitation for executor recovery.
- Executes original inline anchors from either IDML story markers or resolved
  `inline_anchor` runs. Both are source anchor metadata; neither may be replaced
  by child TextFrame text merging.
- If an inline source-chain owns HWPX text but has no executable source story
  carrier, Stage 1 promotes that TextFrame to a page text carrier using resolved
  page bounds. Inline children inside that promoted story remain inline source
  anchors.
- An inline text shell with extracted shell material remains one ownership
  plan. The default materialization is a single inline shell object, but if the
  source children are already an inline flow composition of visual leaves before
  and after editable TextFrames, the executor materializes that same plan as
  source-ordered inline fragments: visual leaf, HWPX text, visual leaf. This is
  execution of source child order, not a new ownership or placement decision.
- If that inline shell has extracted PNG material, the executor uses the
  extracted image as the shell carrier fill before native vector
  reconstruction. The owned text uses the source TextFrame story paragraphs,
  paragraph alignment, and TextFrame inset/bounds relation; it is not centered
  or reflowed by a generic badge fallback.
- Inline text-shell vertical alignment is source geometry, not text content.
  When a textless inline shell atom owns one editable child TextFrame, the
  executor may center the HWPX text only when the child TextFrame bounds are
  vertically centered inside the shell/source bounds. It must not infer this
  from label text length, wording, page, or visual appearance. Margin/inset
  calculations must compare bounds in the same source coordinate space; rendered
  page-local plan bounds are an execution placement target and must not be mixed
  with source TextFrame bounds.
- A TextFrame listed in a `PLACE_TEXT_SHELL` plan's `ownedTextFrameIds` is
  a source relation, not a merge instruction. The shell visual is emitted by the
  shell plan. If the same shell plan has `textAction=OWNED_BY_HWPX_TEXT`, it also
  emits the editable text for those ids; otherwise the editable text must have a
  separate planned TextFrame/table owner unless the source explicitly declares
  complete-PNG text ownership.
- A native parent source shape (`NATIVE_SOURCE_SHAPE`) may supply the shell,
  fill, stroke, corner, and inset style for a child editable TextFrame, but it
  should not take the `TEXT_SLOT` away from a direct TextFrame owner. When a
  direct TextFrame plan exists, the direct TextFrame remains
  `OWNED_BY_HWPX_TEXT`; the native shell plan keeps `PLACE_TEXT_SHELL` with
  `textAction=DROP_TEXT` and retains `ownedTextFrameIds` only as a shell/style
  relation. This keeps Rectangle/Oval-backed inline text boxes consistent with
  extracted textless shells without merging text into the shell owner.
- Execution must keep that separation. A `NATIVE_SOURCE_SHAPE` shell that
  references `ownedTextFrameIds` is emitted as a textless visual shell by the
  visual executor; the child TextFrame is emitted by the text executor using its
  own TextFrame bounds. For an inline badge/text-shell atom, however, Stage 1
  may explicitly choose a single inline carrier whose visual material is the
  native/extracted shell and whose text remains editable HWPX text. That carrier
  may be an HWPX `drawText` object when its fill comes from the planned source
  shell image/vector. Later stages must not invent the shell by Java style,
  bounds, stroke, fill, or corner fallback when source shell material is absent.
- If one source inline anchor contains multiple Stage 1 inline text-shell child
  plans, the executor materializes every planned child atom in source reading
  order. It must not stop after the first descendant shell, and it must not fall
  through to a synthetic/simple-button carrier while an executable
  native/extracted shell plan exists.
- `pass.vector_shape_frames` is not a native fallback executor. It emits the
  planned IDML source vector as extracted visual material and the later stages
  only place that result. A no-op export marker or a Java redraw of the source
  bounds/style is not an executable visual owner for this pass.
- A TextFrame whose source story contains an object-replacement character is an
  inline-object carrier. When such a frame is owned by a text shell, its source
  anchors remain source anchors; its visible text is not flattened into the
  shell body as a late duplicate-removal workaround.
- A graphic-only `inline_object` in story flow whose bounds are a degenerate or
  very thin line is a text-style decoration such as an underline. It must not
  own or displace the surrounding `TEXT_SLOT`. If HWPX cannot materialize that
  decoration faithfully, Stage 1 may drop the visual, but the carrier TextFrame
  text remains `OWNED_BY_HWPX_TEXT`.
- A source inline `GraphicLine` used as a text-style marker is never a standalone
  inline PNG owner. Stage 1 must either absorb it into an explicit HWPX text
  style/underline-tab representation or assign `DROP_VISUAL`; the executor must
  not preserve it as `PLACE_INLINE_PNG` merely because it is anchored in story
  flow.
- Text Builder must not split, shrink, or horizontally shift a TextFrame from
  `composedLines`, `wrapIndentLeft`, `wrapIndentRight`, paragraph Y offsets, or
  line-gap measurements. Those are extractor diagnostics for validation and
  planning only. If source text must avoid an obstacle, Stage 1 must express the
  carrier structure through source story/thread/table/anchor metadata or an
  explicit `ObjectPlan`; executors do not invent `_g`/wrap-split carriers.
- Text Builder must not suppress paragraph left indent or skip paragraph content
  from ORC/inline-object detection, partial-left-wrap detection, or rendered
  overlap heuristics. If a paragraph style must change because of a source
  carrier relation, Stage 1 must write that style action into `ObjectPlan`;
  otherwise executors record a validation warning and keep the source paragraph
  properties/content.
- Validation warnings for paragraph-style rewrites must be source-property
  based. A warning is emitted only when the source paragraph actually has a
  property that would change, such as a positive non-neutral left indent. The
  mere presence of an inline object marker, overlap, or rendered control glyph
  is not enough.
- Text Builder must not expand a TextFrame after paragraph/style conversion
  using estimated HWPX font metrics, rendered collision checks, or a
  "single-line guarantee" heuristic. Source single-line intent is executed
  through source-derived no-wrap/SQUEEZE flags and source bounds, not through a
  Stage 4 geometry rewrite.
- Text Builder must not narrow or offset a TextFrame because a floating image
  appears beside or over it. HWPX text-wrap limitations are handled by Stage 1
  source placement/materialization planning or validation, not by
  `narrowedWidth`, `narrowedXOffset`, spacer insertion, or legacy
  `FloatingImageMerger` recovery.
- HWPX drawText carriers use the same positive source-derived height for
  `orgSz`, `curSz`, corner points, and `sz`. `curSz height=0` is not an
  auto-height policy; some renderers treat it as an invisible zero-height
  carrier and hide otherwise owned text.
- An object that Stage 1 leaves in source story flow and the executor emits as
  `treatAsChar=true` is part of the line box. Its HWPX shape position must use
  `affectLSpacing=true`, and paragraph line-spacing compensation must consider
  its source-derived height. This applies uniformly to inline images, native or
  extracted inline text shells, and inline table carriers. Objects that Stage 1
  plans as floating/wrapping carriers do not participate in the story line box.
- Does not merge unrelated TextFrames.
- Does not create shell/table fallback visuals.

Visual Builder:

- Emits only planned visual actions.
- Consumes `executionSourceObjectIds` and the explicit slot source fields
  (`visualSourceObjectIds`, `styleSourceObjectIds`, `exportSourceObjectIds`,
  `hiddenVisualSourceObjectIds`) as Stage 1 output. It must not reinterpret
  broad ancestry `sourceObjectIds` as the visible material set.
- Does not change inline/floating placement.
- Uses `ObjectPlan.zOrder`, `ObjectPlan.visualLayer`, and the policy-layer
  plane mapping as execution inputs; it does not recompute z-order or foreground
  plane from overlap, bounds, or rendered pixels.
- Stage 3 placement helpers may convert planned bounds into HWPX units and copy
  planned `visualLayer`, `zOrder`, and source layer metadata into executable
  records. They are adapters, not planners. A method/class in Stage 3 must not
  decide whether a visual is background, decoration, content, behind text, or in
  front of text.
- When execution needs the HWPX plane, it translates the finalized
  `ObjectPlan.visualLayer` through the canonical `VisualPlanePolicy` table.
  Executor-local foreground/background layer lists are not allowed.
- Does not search alternate inline/floating placement, source-only matches, or
  overlap-scored fallback plans when no exact render/placement plan is found.
- Does not lower shell/background visuals because they overlap TextFrames with
  semantic text. If a shell must sit behind text, Stage 1 must express that in
  the plan's layer and z-order.
- Does not replace missing extracted material with synthetic bounds-based
  graphics.
- Does not treat an existing cached/rendered file path as ownership evidence.
  Fallback PNGs are visible only when Stage 1 already planned that source bundle.
- When an extracted visual records `textHiddenBeforeExport=true`, the extractor
  must hide the render target TextFrame itself as well as nested TextFrames.
  Metadata must not claim a textless `SHELL_SLOT` render while the PNG still
  contains editable text pixels.
- May crop or prepare an already planned image only as image preparation, never
  as ownership refinement.
- Image preparation may use PNG sanity metadata such as exported byte size and
  source/page bounds to diagnose extraction failures. That metadata is not a
  text/shell ownership classifier.
- If a planned extracted inline visual has degenerate bounds on one axis,
  execution may restore the missing display dimension from source stroke width
  or the extracted PNG aspect ratio. This is size reconstruction for an already
  owned material, not a new ownership or placement decision.
- Textless shell extraction must not synthesize transparent guard canvases or
  expanded transparent bounds. The extracted shell material comes from the
  original source object; padding/cropping fixes belong to image preparation only.

Validator:

- Reports invariant failures.
- Does not create, suppress, or move output objects.
- Treats ownership invariant failures as blocking defects for development
  builds. A conversion may still write diagnostics for inspection, but a
  regression-fix branch is not considered complete while any blocking ownership
  invariant remains.
- Must validate visible slot closure, not only direct object ids. If a visible
  parent plan's ancestry contains a descendant TextFrame/shell/table/content
  source that is visible through another plan, the validator checks whether the
  parent `visualSourceObjectIds` and rendered-file metadata prove that the
  descendant pixels are excluded. If not, the parent composite is invalid even
  when its `visualSourceObjectIds` were narrowed after planning.
- Must validate page-local background coverage in both directions: no
  non-intersecting adjacent-page pixels are claimed by the current page, and no
  intersecting background/spread source child is left without a current-page
  executable materialization.
