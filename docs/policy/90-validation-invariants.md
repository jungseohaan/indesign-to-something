# Source Ownership Policy: Validation, Forbidden Patterns, Cleanup

> This file is part of the canonical source ownership policy.
> The canonical index is `docs/specs/POLICY-source-ownership.md`.

## 11. Invariants

- Stage 4 ObjectPlan validation is a fatal pre-render gate. If
  `object-plans.json.validation.issueCount > 0`, extraction must write
  diagnostics and stop before PNG/vector render execution or HWPX conversion.
  Later stages may not treat ObjectPlan issues as advisory warnings.
- Every IDML/resolved `SourceObject` in the selected page range has exactly one
  coverage status before ownership planning completes. `UNRESOLVED` coverage is
  a blocking Stage 1 failure.
- Every visible/style/text source object is reachable from exactly one
  `SourceBundle` + `OwnershipSlot` + `SlotOwner` path. Objects that are
  ancestry only must be marked `PROVENANCE_ONLY`; objects that are intentionally
  dropped must be marked `DROPPED_INTENTIONAL`.
- Every extracted PNG/vector result must correspond to a prior Stage 1
  `RenderUnit`. A rendered file without a prior RenderUnit is not ownership
  evidence and must fail validation.
- Java legacy bridge code must not add or mutate ownership plans. A non-zero
  bridge-added or bridge-mutated ownership count is a Stage 0/Stage 1 planning
  defect, not a successful recovery.
- One source bundle slot has one visible owner.
- One TextFrame cannot be both `OWNED_BY_PNG` and `OWNED_BY_HWPX_TEXT`.
- The same source bundle slot cannot be emitted both inline and floating.
- The same source bundle slot cannot be emitted by multiple extraction passes.
  Pass names, rendered ids, file prefixes, crop sizes, or fallback channels do
  not create a second visible owner.
- A `STORY_FLOW` inline plan cannot carry raw spread/page coordinates that place
  it outside the owning story/table/page flow. Stage 1 must normalize the bounds
  from source anchor geometry or fail validation.
- `PLACE_TEXT_SHELL` must not contain pixels for its owned HWPX text.
- A planned text-owning `SHELL_SLOT` with explicit `exportSourceObjectIds`
  must produce one extraction result for that candidate. Silent export failure,
  missing result registration, or replacement by a later fallback channel is an
  invariant violation; later stages must not recreate or reinterpret the shell.
- A complete `inline_graphic_only` PNG is valid only when the source object tree
  is truly textless. If the source group has descendant TextFrames, Stage 1 must
  decompose it into the appropriate shell/text/content slot owners and drop the
  complete inline PNG unless the text slot is explicitly `OWNED_BY_PNG`.
- An inline source tree that contains editable TextFrame descendants must declare
  its text contract in the extraction candidate. The candidate either sets
  `textOwner=hwpx_tf` with `requiresTextHidden=true` and lists all descendant
  editable TextFrame ids, or sets `textOwner=indesign_png` only for an explicit
  simple-marker complete PNG. The extractor must render the first case with
  hidden editable text pixels and report `inline_text_hidden`; it must not emit
  `inline_graphic_only` for a source tree that still has editable text ownership.
- Simple-marker complete PNG ownership is limited to non-editing position
  markers such as one Korean marker letter, one consonant marker, or one/two
  digit labels. Labels, titles, body text, questions, and activity names remain
  HWPX text even when they are visually packaged inside an inline shell.
- If an `inline_graphic_only` render has no text pixels but its source subtree
  contains visible shell material plus editable TextFrame descendants, it is an
  inline textless `SHELL_SLOT`, not a complete PNG. Stage 1 keeps
  `placement=INLINE`, owns the descendant TextFrame ids through the shell plan,
  and removes those child TextFrame direct plans to prevent duplicate text.
- A composite carrier may own a combined visual shell slot, but it must not own
  descendant editable TextFrame text slots. Descendant text slots remain direct
  `HWPX_TEXT` owners unless they are explicitly `OWNED_BY_PNG`.
- A planned composite source-set is executable only as that same closed
  source-set. Its extraction candidate must keep `sourceObjectIds`,
  `visualSourceObjectIds`, and `exportSourceObjectIds` aligned to the same
  closed set, and `exportTargetObjectId` must be the source-set root. If the
  executor exports a descendant/leaf instead, or the result row reports a
  narrower source set, validation must fail before HWPX conversion.
- `PLACE_TEXT_SHELL` must be behind the text it owns unless the source explicitly
  defines a front mask/outline slot.
- When an IDML source group is a closed text-owning shell and its direct visual
  child branches overlap inside that same source group, Stage 1 must keep the
  group as one textless `SHELL_SLOT` owner. The child branches must not be split
  into independent shell owners, because their relative z-depth is meaningful
  only inside the source group render. Editable child TextFrames still remain
  HWPX text; only the shell visual channel is grouped.
- A textless composite that contains image descendants may still be a
  `PLACE_TEXT_SHELL` when Stage 1 can prove the source role from metadata:
  the rendered group is textless, has shell material sources, is associated
  with a nearby editable TextFrame whose paragraph/source style is a callout
  or speech-bubble style, and owns only the shell channel while the TextFrame
  remains `HWPX_TEXT`. The executor must not rediscover this from occlusion.
- A textless composite whose root source is a visible shape shell/container
  and whose descendants include placed image/PDF/EPS content is not promoted
  to `CONTENT_VISUAL` merely because of those descendants. Stage 1 must classify
  the root composite by the root source role (`CONTAINER_BACKDROP` or
  `PLACE_TEXT_SHELL`) and keep child content as its own slot when a separate
  content owner exists. This prevents a shell/container parent from covering
  HWPX-owned callout text as if it were an independent photo.
- Hidden source trees have no visible output.
- `TABLE_STYLE_SLOT` sources cannot also appear as shell/content material.
- HWPX table cell fill, border, inset, and source-bounds ownership survive to
  the writer.
- Source layer/z/anchor/page ownership must be traceable in output diagnostics.
- A visible `BACKGROUND` plan whose source bundle crosses a page/spread boundary
  must satisfy both page-local background invariants: no visible source material
  may come from a non-intersecting adjacent page, and every source child included
  in `visualSourceObjectIds` must either belong to the plan page or intersect the
  plan page bounds.
- Every background/spread source child whose bounds intersect an applied page
  must have exactly one visible page-local owner for that page, unless a visible
  parent plan's rendered file is explicitly the clipped/textless owner for that
  same page intersection. Missing page-local owners are validation failures, not
  executor fallback opportunities.
- A visible parent composite whose `sourceObjectIds` contain a descendant
  visible text/shell/table/content slot owned by another plan must either prove
  that the descendant is absent from its rendered file or be `DROP_VISUAL`.
  Source ancestry narrowing is not enough.
- Conversely, an `editable_textframe_visual_shell` fallback may be dropped only
  when a visible composite proves executable ownership of that TextFrame's shell
  style source through `exportSourceObjectIds`, `styleSourceObjectIds`, or an
  equivalent slot-owner field. Broad `sourceObjectIds`/`visualSourceObjectIds`
  ancestry is not proof that the rendered PNG contains the shell.
- A `BACKGROUND` or `CONTAINER_BACKDROP` plan must not bake HWPX-owned text,
  label shell glyphs, complete markers, or child content pixels into its PNG.
  Those are separate visible slots unless explicitly `OWNED_BY_PNG`.
- Executor-visible `RenderedGroup.bounds` and resolved page-relative
  TextFrame/page-item bounds are resolved-coordinate values. The executor must
  convert them through the shared resolved coordinate scale (`value *
  scaleFactor`) before writing HWPUNIT geometry; it must not pass those values
  directly to a point-to-HWPUNIT converter. This applies equally to inline
  text-shell carriers and floating shell/table/image material.
- A source single-line TextFrame whose story contains inline anchors remains a
  single HWPX text carrier when Stage 1 owns its text. Stage 2 may enlarge the
  carrier's display height from source font/line metadata so inline shell/line
  material cannot force a wrapped or clipped second line. This is source
  geometry preservation, not a new ownership or placement decision.
- If that single-line story begins with inline anchors, the anchor cluster and
  following editable text inherit the same source single-line/no-wrap decision.
  The executor must not let the HWPX carrier rewrap the text merely because the
  inline shell/line consumes leading flow width.
- Source inline `GraphicLine` material is decoration, not a text glyph. It may
  be anchored to the source story for ordering, but it must not consume the
  editable text flow width when materialized in HWPX.

## 12. Forbidden Patterns

- Page-specific, literal-text-specific, or coordinate-specific exceptions.
- Character-count or box-size rules for semantic ownership.
- Bounds/occlusion-only duplicate suppression.
- Pixel/color/alpha analysis to decide text vs shell ownership.
- Java-created lookalike shell PNGs or drawText boxes when source shell material
  is missing.
- Executor-local `already handled` sets as the primary duplicate policy.
- Late conversion from inline to floating or floating to inline.
- Executor-local promotion of a `page_object` shell into an inline companion
  shell. If a page-object render is the canonical material for an inline source
  chain, Stage 1 must say so explicitly with `placement=INLINE`.
- Executor-local restoration of a `rendered_graphic_frame`, fallback PNG, or
  alternate pass candidate for a source slot already owned by a canonical
  page/inline plan.
- Passing raw spread/page-object bounds into a `STORY_FLOW` inline plan.
- Dropping a TextFrame merely because a `PLACE_TEXT_SHELL` plan references it.
- Treating `ownedTextFrameIds` on a `DROP_TEXT` shell as text ownership. Those
  ids are relation metadata unless `textAction=OWNED_BY_HWPX_TEXT`.
- TextFrame merge that does not come from IDML story/thread/table structure.
- Treating a `textOwner=hwpx_tf` render as a complete PNG.

## 13. Cleanup Direction

Code that still contains scenario names or visual symptom names should be folded
into one of the source-driven concepts above:

- source bundle root detection
- slot assignment
- materialization selection
- placement from anchor/page ownership
- layer from source layer/z and policy layer
- validator invariant

If a new regression seems to need another condition, first check which source
metadata is missing or which `ObjectPlan` field is too weak. Add metadata or
strengthen the plan model before adding a new branch.

Current cleanup priorities for regression-free ownership:

1. Replace parent/composite PNG retention with slot-closed plans. A retained
   parent may trace child ids, but its visible material must exclude every child
   slot owned elsewhere. If no childless render exists, drop the parent instead
   of relying on z-order.
2. Introduce explicit page-local materialization records for spread/background
   slots. Bounds intersection with an applied page creates a page-local
   execution requirement even when the source item is anchored to the neighboring
   page.
3. Remove validator rules that equate a background-named source layer with
   `BACKGROUND` policy layer. Layer name is a hint; final policy layer comes
   from source bundle role plus geometry. Validators should check the chosen
   role's invariants, not force every `배경` source into one policy layer.
4. Promote ownership warnings that indicate duplicated/missing visible slots to
   blocking failures in dev/test mode:
   `BACKGROUND_SHELL_OWNS_TEXT`, parent composite with child visible slot,
   missing page-local background owner, same slot emitted by multiple render
   channels, and visible PNG file containing HWPX-owned text pixels.
5. Keep executor code boring. If a later builder needs to inspect literal text,
   bounds overlap, rendered color, alpha pixels, or page number to decide
   whether to place/drop/move an object, the required source-slot fact is
   missing from Stage 1 and must be added there instead.
