# Source Ownership Policy: Overview

> This file is part of the canonical source ownership policy.
> The canonical index is `docs/specs/POLICY-source-ownership.md`.

> Status: Active / Canonical.
> Goal: Decide IDML source ownership once, then execute that plan without
> case-specific reinterpretation.

This is the canonical policy for source ownership. Removed ownership SPECs are
intentionally not linked from this policy; their usable rules have been
consolidated here.

## 1. Core Principles

1. Stage 1 is the only owner of ownership decisions.
2. Later stages execute `ObjectPlan`; they do not reinterpret ownership,
   placement, layer, or materialization.
3. Editable/searchable text is HWPX text or HWPX table text.
4. Visual material must come from IDML source material or extractor metadata.
5. One source bundle slot has exactly one visible owner.
6. Original IDML source metadata is the source of truth: source ids, parentage,
   group membership, story/thread, anchor, table/cell structure, visibility,
   layer, z-order, bounds, and style.
7. Page number, literal text, object size, character count, color sampling,
   pixel analysis, and occlusion heuristics are not ownership reasons.
8. IDML, resolved metadata, and this policy are the only ownership truth. A
   user-observed visual symptom may start investigation, but it is not itself a
   conversion rule.
9. If a requested fix would add a page/text/coordinate/color/symptom condition,
   first inspect the source metadata and either strengthen the common
   `ObjectPlan` model or report that the request needs more confirmation.
10. Bypass code is not allowed. Code that moves a legacy heuristic into
    `OwnershipPlanner` without making it a source-slot decision is still a
    bypass and must be removed, not preserved as migration glue.
11. After Stage 1 writes `ObjectPlan`, no later code may mutate ownership
    fields. This includes `textAction`, `visualAction`, `placement`,
    `coordinateSpace`, `visualLayer`, `zOrder`, `materialization`,
    `ownedTextFrameIds`, `visualSourceObjectIds`, and `styleSourceObjectIds`.
12. DOM-id set suppression, rendered-child coverage suppression, inline
    coverage suppression, and post-text/table visual refinement are forbidden.
    They hide missing source-slot decisions and must be replaced by Stage 1
    slot ownership.
13. Text layout style is source truth too. Later stages must not infer a new
    TextFrame vertical alignment from composed-line offset, visible overlap, or
    HWPX rendering behavior. If source `verticalJustification` is `TOP_ALIGN`,
    it remains top-aligned unless Stage 1 has an explicit source-style owner
    that says otherwise. `composedLines` may be used to preserve source line
    geometry or to validate overlap, but not to rewrite alignment by heuristic.

## 1.1 Refactoring Direction

The extractor and converter must move toward a closed source-coverage pipeline.
Stage 1 does not begin from rendered candidates or from visually missing
objects. It begins by registering every IDML/resolved `SourceObject` in the page
range, assigning a coverage status to each one, grouping related source objects
into `SourceBundle`s, splitting each bundle into ownership slots, assigning one
owner to every slot, and creating `RenderUnit`s only for the slots that need
extracted PNG/vector material.

InDesign source trees are recursive, so conversion first normalizes source
objects into closed source coverage and source bundles, then decides text,
shell, content, and table-style ownership, and only then places those atoms into
page, inline, table-cell, master, or spread-fragment contexts.

Placement context must not define the identity of a source cluster. A
`TEXT_OWNING_SHELL` is the same ownership class whether it is page-floating,
inline/anchored, inside a table cell, or applied from a master page. Executors
may vary only the placement mechanics.

The legacy monolithic extractor is split by responsibility with the rule
`move -> wire -> verify -> delete old path`. New helper modules must not become
parallel fallback implementations. Once a function is moved to `scripts/indd/`,
the old definition in `scripts/extract_indd.jsx` is removed in the same change.

During migration, `source-clusters.json` may be emitted as a diagnostic artifact.
It describes recursive source-tree clusters before ownership execution, but it
does not by itself create, drop, merge, or reposition any visible output. A
later stage may consume source clusters only after the corresponding
ObjectPlan/slot ownership rule is explicit in this policy.

The planner may use a source-cluster index to answer source-tree queries such
as descendant source ids, page-local source ids, and editable TextFrame
membership. These queries are planner inputs only. They must not become a
second ownership decision path, and they must not reinterpret placement after
ObjectPlan creation.

During migration, a Java legacy bridge may report missing or mutated ownership
plans, but those reports are defects in Stage 0/Stage 1 source coverage,
bundle construction, slot decomposition, slot ownership, RenderUnit creation, or
ObjectPlan serialization. They are not work queues for reason-by-reason recovery
inside Java.

While the legacy candidate planner remains active, cluster query diagnostics
may compare cluster-derived source sets with legacy candidate source sets. A
mismatch is a migration signal, not permission for a later executor to widen or
narrow ownership after planning.

Source clusters and planner-declared bundles are different model layers:

- A source cluster is the recursive IDML ancestry rooted at a source object.
- A planner-declared bundle is the executable source set for one visible slot on
  one page/placement context.

The planner-declared bundle may be narrower than the source cluster for
page-local fragments, textless shell slots, inline child removal, table-style
absorption, or visible-slot ownership. It may be broader than one source
cluster when Stage 1 intentionally declares a composite shell/master/inline
bundle. Such differences must be explicit in Stage 1 diagnostics and policy;
executors must not infer them after the fact.

Planner bundle diagnostics may expose provisional `visualSourceObjectIds`,
`styleSourceObjectIds`, and `ownedTextFrameIds` derived from legacy candidates.
These fields are used to audit the migration toward ObjectPlan. They are not
executor authority until the same fields are written by Stage 1 ObjectPlan.

During migration, `object-plans.json` may be emitted as an ObjectPlan-shaped
diagnostic artifact derived from planner-declared bundles. It exists to show
which legacy candidates already satisfy the ObjectPlan contract and which ones
need explicit source-slot policy before they can become Stage 1 output. It must
not be consumed by later executors as a fallback planner until legacy candidate
execution is removed and Stage 1 writes the same fields as authoritative
ObjectPlan records.

ObjectPlan diagnostics must include contract validation, not just a record dump.
Each diagnostic plan is marked as either `READY_FOR_STAGE1_IMPORT`,
`NEEDS_MIGRATION_POLICY`, or `NEEDS_POLICY_OR_METADATA`. The ready state requires
all required ObjectPlan decision fields, source-slot invariant checks, and a
source-cluster relation that is already exact enough for Stage 1 import.
`READY_EXACT_CLUSTER` is import-ready. `READY_SLOT_ONLY_CLUSTER_FRAGMENT` is also
import-ready when `sourceObjectIds` keep broad ancestry, `exportSourceObjectIds`
declare the actual PNG export source set, and `hiddenVisualSourceObjectIds`
declare the child source ids intentionally absent from that export.
`READY_CLOSED_PLACED_CONTENT_FRAME` is import-ready when a frame owns a placed
content source such as PDF/Image/EPS and `visualSourceObjectIds` match the closed
frame descendant source tree. Diagnostic failures are migration work items;
later executors must not compensate for them.

During migration, a pure decoration fragment may be normalized into an explicit
slot-only shell contract when all of these source facts hold: it is a
`SHELL_SLOT`, it is narrower than its recursive source cluster, it has no
page-local editable TextFrame, it has no page-local TextFrame carrier, and it
has no placed content descendant. In that case `exportSourceObjectIds` must be
the visible decoration fragment itself and `hiddenVisualSourceObjectIds` must
record the same cluster descendants intentionally omitted from that fragment.
This is the planner bundle declaring a source-slot contract, not a rendered
overlap cleanup.

The visible placement bounds for a slot-only shell are the extracted/export
slot bounds, not the broader recursive source cluster bounds. Broad ancestry is
kept in `sourceObjectIds` only for ownership provenance. Recomputing placement
bounds from the broad source tree scales the extracted PNG and misaligns the
separate HWPX text channel, so Stage 1 must preserve the extractor's visible
slot bounds for `slot_only_textless_shell` plans.

The same rule applies to rendered text-owning shell PNGs. Once the extractor
has emitted a concrete textless shell image, its rendered/export bounds are the
visible `SHELL_SLOT` bounds. Stage 1 must not shrink or stretch that shell from
child shape bounds, child TextFrame bounds, or a subset of visual descendants.
Root source bounds may be used only for non-rendered native source shapes whose
own source object is the visible shell.
Render-source-bound normalization is allowed for floating content fragments
whose visible owner is `PLACE_FLOATING_PNG`; it is not allowed for
`PLACE_TEXT_SHELL`, because the shell image already carries the visible
decoration slot geometry.
The same source-slot contract also owns the text relationship metadata:
`ownedTextFrameIds` for a rendered textless shell must not be reduced to only
TextFrames under the visual export subtree. The export subtree is the PNG
payload; the broad source bundle is the provenance for separate editable text.

During migration, an already-declared `SLOT_ONLY` shell whose hidden children
are recorded but whose `exportSourceObjectIds` are missing must be completed
from the declared visible shell source set in the planner bundle diagnostics.
That completion is allowed only when the visible source set is already known
from source metadata; it is not a pixel/bounds based cleanup and executors must
not infer a different export set later.

The same visible-fragment contract applies to `CONTENT_VISUAL_SLOT` candidates
that are intentionally narrower than their recursive source cluster. If the
source metadata already identifies the executable content visual source set and
the omitted descendants, Stage 1 may mark the plan `SLOT_ONLY` with
`exportSourceObjectIds` and `hiddenVisualSourceObjectIds`. This is allowed only
when no editable TextFrame/text carrier is omitted from the content fragment.
Text-owning shell fragments use the same fields, but keep the editable
TextFrame ids in `ownedTextFrameIds`.

A large textless vector group may be finalized early as an atomic
`CONTENT_VISUAL_SLOT` when source metadata proves that one closed Group root, or
a small closed set of sibling Group roots, contains the executable visual source
set. Editable TextFrames inside those roots remain HWPX-owned text when they
are explicitly listed in the hidden/omitted source set; they are not absorbed
into the visual slot. Stage 1 records broad provenance in `sourceObjectIds`, the
executable visual closure in `exportSourceObjectIds`, any hidden text/style
descendants in `hiddenVisualSourceObjectIds`, and the closed render root or
root set in `exportTargetObjectId`/`atomicExportTargetObjectIds` or an
equivalent atomic target reference. Later stages must not re-own or re-export
internal polygon/vector children as independent visible candidates. They may
use the atomic root set for PNG export while keeping the expanded source set as
proof metadata.

When a page item source tree contains placed content, Stage 1 must not also
emit a partial `NATIVE_SOURCE_SHAPE`/vector owner for the ancestor shape. The
placed-content frame is the closed `CONTENT_VISUAL_SLOT` owner for that source
tree, including the frame's visible clipping, stroke, and fill as rendered by
InDesign. This is an input-planning rule, not a later duplicate-removal rule.
Source-depth plane normalization must not downgrade a visible placed-content
owner into `BACKGROUND` or `DECORATION`; only explicit source background-layer
policy may keep placed content behind normal content.

When final Stage 1 `ObjectPlan`s show that a floating non-placed
carrier/composite visual overlaps a visible `HWPX_TEXT` plan on the same page,
Stage 1 uses a behind-text implementation layer (`CONTENT_BACKDROP`) while
keeping its policy layer as `CONTENT`. The z-order of a placed-image leaf inside
that composite is not allowed to pull the whole composite above editable text.
This rule must not apply to independent placed content such as photos,
illustrations, charts, or page-local image exports whose closed source tree is
the content visual itself, unless the independent content is source-stacked
behind the overlapping editable TextFrame. Independent content that is source
behind editable text uses a behind-text implementation plane so `TEXT` remains
the top semantic layer. This is a final Stage 1 invariant after layer
normalization; rendered occlusion, page number, literal text, or a user-visible
symptom must not create this rule in later phases.

For clipped placed content, the visible `CONTENT_VISUAL_SLOT` owner must be
chosen from the IDML parent chain of the clipping frame. A broad composite that
merely lists the image and clipping frame in `sourceObjectIds` is not a valid
owner unless its own source root is the clipping frame or one of that frame's
ancestors. Among valid source-tree owners, the planner prefers the nearest
ancestor that still owns the closed clipped content. This prevents unrelated
composite shells from absorbing clipped image content and later competing with
the exact clip-carrying source owner.

A clipped placed visual may be a `SHELL_SLOT` instead of a `CONTENT_VISUAL_SLOT`
only when the IDML source tree declares it as a table/carrier sibling shell:
the clipping source is a `Rectangle`/`Oval`/`Polygon`, it has placed visual
descendants but no placed visual directly on itself, it has no editable
TextFrame descendants, and either that source or one of its local source
ancestors has a direct empty TextFrame carrier sibling on the same page. In
that case the extractor must export the clipping parent source as the shell
payload, including the placed visual children clipped by that parent, and must
not also emit the internal placed-image child group as an independent content
image. The child group remains part of the shell payload; it is not a hidden
export child and not a second visible owner. This decision is made from IDML
source parentage and table/carrier metadata before export; later stages must
not promote, crop, restore, or drop images to simulate this relationship.

When a shell source cluster contains editable TextFrame descendants, Stage 1
must evaluate that relationship in the page-local source fragment. Stage 1 must
keep the closed page-local source cluster as `sourceObjectIds`, assign the
same-page editable TextFrames to `ownedTextFrameIds`, and render only the visual
shell subset via `exportSourceObjectIds`. The omitted descendants must be recorded as
`hiddenVisualSourceObjectIds` or equivalent diagnostic provenance. The extractor
must not shrink the bundle to the visual subset after discovering editable
text; doing so loses the text/visual slot relationship and forces later phases
to guess.

A source group that directly contains one or more editable TextFrames and one
or more direct visual shell children is a closed text-owning shell slot when
all editable TextFrame descendants of that group are those direct children and
the visual children do not contain placed content. This rule is independent of
placement context: the same source relationship is used for floating labels,
inline labels, table-cell labels, and nested section labels. Stage 1 must emit
one `SHELL_SLOT` owner for that group, hide the direct editable TextFrames
before PNG export, and let the TextFrames remain HWPX text. Later stages must
not rebuild the same relationship as separate native sibling shapes.

If a broad source group contains two or more descendant closed text-owning
shell slots and those descendant slots cover all editable TextFrames of the
broad group, the broad group is provenance or a non-text carrier, not an
executable `SHELL_SLOT`. Stage 1 must emit the descendant shell slots instead
of exporting the broad group as one text-owning shell. Conversely, when
descendant shell slots cover only part of the parent's editable TextFrames, the
parent remains the owner for that source slot and the partial descendant must
not be emitted as a second visible shell for the same visual/text relationship.
This partition rule is source-tree based; rendered area, pixel coverage,
occlusion, page text, and later HWPX placement must not choose the winner.

A visual-only source sibling may also be a text-owning shell slot when Stage 1
can prove the relationship from source metadata before execution: the shell is
a small label/callout decoration with no editable-text descendants, it is on
the same page and source layer as exactly one visible editable TextFrame, and
its source bounds contain or mostly cover that TextFrame's source bounds. The
shell material may be drawable vector material or an image/PDF-backed textless
shell export. Placed image/PDF descendants do not by themselves make the source
`CONTENT_VISUAL`; the role is decided by the source sibling relationship and
the TextFrame's source style. In that case Stage 1 emits one `SHELL_SLOT` owner
with the visual sibling in `visualSourceObjectIds` and the editable TextFrame
in `ownedTextFrameIds`; the shell visual is `LABEL_BACKDROP` or
`LABEL_OVERLAY_BACKDROP` according to the source style, and the text remains
HWPX text. This is a source-slot declaration. Later stages must not create the
relationship from rendered overlap, occlusion, page-specific text, or visual
symptoms.

A closed source group that contains editable text and placed image/PDF-backed
visual material may still be a `SHELL_SLOT` when the group source is the local
label/shell root. The placed descendant is then shell material, not independent
`CONTENT_VISUAL`, because the source group owns the textless decoration and the
editable text remains HWPX text. Stage 1 must not reject such a group solely
because `sourceHasPlacedVisualSource` is true; it must decide from the closed
source group relationship and slot ownership.

An editable TextFrame may itself own visible fill/stroke/corner styling. That
styling is a distinct `SHELL_SLOT` carried by the TextFrame source id as
`styleSourceObjectIds`; the editable characters remain `TEXT_SLOT` and are
owned by HWPX text. If a parent textless shell candidate already owns that
TextFrame in `ownedTextFrameIds`, the TextFrame's visible fill/stroke/corner
source belongs to the same parent shell export, not to an additional independent
visible PNG. Stage 1 must include the styled TextFrame source id in that
candidate's `exportSourceObjectIds` while hiding only its editable characters.
An independent TextFrame-style shell candidate is allowed only when no closed
parent/sibling shell candidate already owns the same TextFrame and exports that
style source. This decision is based only on source TextFrame fill/stroke/corner
metadata and source ancestry. Later stages must not recreate the shell with
drawText, alpha-crop a parent PNG to recover it, or reclassify it from visual
symptoms.
If a fallback `editable_textframe_visual_shell` render channel and a closed
parent/sibling `SHELL_SLOT` both claim the same TextFrame fill/stroke/corner
style source, the closed parent/sibling shell is the visible owner and the
fallback channel is diagnostic only. Stage 1 must drop the fallback channel as
part of slot ownership normalization; executors must not place both and must
not choose between them from bounds or rendered occlusion.
When a closed parent/sibling `SHELL_SLOT` owns that TextFrame style source, the
parent export must retain the TextFrame source id in `exportSourceObjectIds` and
hide only the editable characters. Direct-child shell-slot exclusion may remove
independent child visual shells from the parent export, but it must not remove
TextFrame fill/stroke/corner style sources that are part of the parent shell
contract. Otherwise answer-field shells disappear from the composite while the
diagnostic child fallback is also dropped.
If a visible `PLACE_TEXT_SHELL` plan lists a TextFrame id in
`styleSourceObjectIds`, that plan owns the TextFrame's fill/stroke/corner
`SHELL_SLOT` even when the same TextFrame text remains editable HWPX text.
Text builders must therefore emit only the text content for that TextFrame and
must not recreate its visual shell as an HWPX table/rectangle/drawText box.

Callout/speech-bubble shells are overlay decorations. If Stage 1 can prove from
source metadata that a text-owning shell owns TextFrames whose paragraph style is
a callout/speech-bubble role and the shell source is drawable vector material
without placed image content, the shell visual is `LABEL_OVERLAY_BACKDROP`, not
ordinary `LABEL_BACKDROP`. The owned text remains HWPX text. This decision must
come from style/source structure, not page number, literal speech text, rendered
occlusion, or a later visual symptom.

A callout/speech-bubble shell must own the closed set of same-page, same-layer
editable callout TextFrames contained by that source shell. Stage 1 must not
pick only the best-fitting TextFrame when the same shell bounds contain multiple
callout TextFrames. A partial `ownedTextFrameIds` set leaves the remaining HWPX
text outside the shell ownership channel and lets the shell PNG cover it.

A text-owning `SHELL_SLOT` is not executable unless the visual export subset is
declared. This is true even when the candidate source set is an exact recursive
source cluster. Exact ancestry proves provenance, but it does not tell the
executor which descendants are hidden before PNG export. Therefore editable
text-owning shell candidates must carry `exportSourceObjectIds` for the
textless shell and `hiddenVisualSourceObjectIds` for the descendants omitted
from that export before later stages may execute them.
That export subset must contain executable visual material: visible fill/stroke
or a placed visual source, or a TextFrame source whose own fill/stroke is the
shell style. A transparent carrier group/rectangle with no visible material is
provenance only and must not become a visible `SHELL_SLOT`.
`exportSourceObjectIds` alone is incomplete: if editable descendants remain in
the same bundle, Stage 1 must also mark the plan as a shell-slot-only/textless
export contract and record the hidden editable descendants. Otherwise the
candidate is still a migration blocker, not an executable rendered shell.

A broad source cluster is provenance, not ownership. A composite shell carrier
may suppress a child visual only when the child's visible source ids are inside
the carrier's declared `sourceObjectIds` or visible/export source ids for that
slot. Descendant ancestry alone is not enough, because a planner bundle can be
narrower than the recursive IDML cluster. Any child visual outside the declared
slot remains independently visible or must be assigned to another explicit
owner.

If Stage 1/extractor metadata declares a child as `direct_child_shell_slot`,
that child is already an executable visible `SHELL_SLOT`. A composite carrier
may keep the child id in broad provenance fields, but it must not later absorb,
drop, or bake the child's shell merely because the child belongs to the carrier
ancestry. The carrier must subtract that child shell's visible source ids from
its own visible slot, or remain provenance-only. Java planner/import bridges
and legacy executors must treat `direct_child_shell_slot` as stronger than
composite-carrier coverage rules.

If the child slot source set contains an editable TextFrame, that TextFrame's
fill/stroke/corner styling is closed with the child `SHELL_SLOT`. A residual
parent `shell_slot_only` carrier must not re-add that hidden TextFrame source as
a fallback style export. Re-adding it creates a broad parent PNG that can cover
the native child shell. The parent may export only residual visual sources that
are not in any child slot; otherwise it is provenance-only.
Group/root ids that remain only as export-target provenance are not residual
visual sources. A residual parent may keep them in diagnostic provenance fields,
but it must not emit a PNG unless at least one remaining source id is itself a
drawable shell/style source.

When a diagnostic plan is not import-ready, it must also expose a
`migrationBlocker` and `migrationBlockerDetail`. The blocker is not a fallback
decision and must not be consumed by executors. It exists to show which Stage 1
policy/model gap remains before the legacy candidate can become an authoritative
ObjectPlan:

- `SYNTHETIC_PAGE_BACKGROUND_NEEDS_SOURCE_MODEL`: a page background render still
  has no source-root/page-fragment model.
- `NO_CLUSTER_REFERENCE_NEEDS_SOURCE_MODEL`: the candidate cannot be tied back
  to a source cluster.
- `SLOT_ONLY_HIDDEN_CHILDREN_NEEDS_OBJECTPLAN`: the candidate is intentionally
  narrower than its source cluster because child slots are hidden from the
  export; Stage 1 must write that slot-only export contract explicitly.
- `NATIVE_SHAPE_WITH_PLACED_CONTENT_NEEDS_CONTENT_OWNER_POLICY`: a native/vector
  source is only part of a cluster that also contains placed content
  descendants. Stage 1 must choose the placed-content owner or declare a closed
  native owner; later phases must not decide from rendered overlap.
- `TEXT_OWNING_SHELL_FRAGMENT_NEEDS_TEXT_SLOT_SPLIT`: a shell candidate omits
  editable text descendants from the same source cluster. Stage 1 must keep the
  broad source ancestry and split visual/text slots explicitly.
- `DECORATION_FRAGMENT_NEEDS_CLOSED_SHELL_CONTRACT`: a decoration candidate is
  narrower than its source cluster without an explicit slot-only export
  contract. Stage 1 must declare whether it is a closed textless shell or a
  slot-only fragment with named omitted descendants.
- `BUNDLE_NARROWER_THAN_CLUSTER_NEEDS_SLOT_POLICY`: the candidate owns a
  descendant slot, but the slot policy is not explicit enough for import.
- `COMPOSITE_MASTER_BUNDLE_NEEDS_PAGE_APPLIED_POLICY`: an applied master graphic
  needs a separate master-source and page-local visible-fragment identity.
- `COMPOSITE_BUNDLE_NEEDS_EXPLICIT_SLOT_SPLIT`: a broad composite must either
  be split into visible slot owners or declared as one closed composite owner in
  Stage 1.
- `DIVERGENT_BUNDLE_NEEDS_SOURCE_BUNDLE_POLICY`: the executable source set
  neither contains nor is contained by the recursive source cluster.

These blocker names are allowed only in diagnostics. A final Stage 1 ObjectPlan
must replace them with explicit `sourceObjectIds`, slot source fields,
placement, layer, and materialization.

The legacy `pass.page_backgrounds` full-page PNG path is a no-op compatibility
pass only. It must not emit source-less visible candidates or ObjectPlan
diagnostics. Page backgrounds that should appear in output must come from
source-bearing page items, master-applied source material, or explicit
page-local background fragments with source ids.

Story-flow inline visual sources are planned only through the inline/story-flow
channel. If source metadata shows a visual source is anchored under a
`Character`, `InsertionPoint`, `Cell`, `Story`, or a descendant inline group, the
planner must not also create a page/floating visual candidate for the same
visible slot.

If extraction exposes both a story-flow inline visual owner and a page/spread
render channel for the same visual-only marker, the story-flow owner is
canonical only when Stage 1 can prove all source facts: inline/story-flow
placement, visual-only source tree, no placed-content descendants, no editable
TextFrame descendants, same page, same known source layer, matching source
geometry, and matching source style/material fingerprint. A Paper/knockout
inline source and a colored page source are distinct visual slots even when
their geometry matches. The page/spread channel is then `DROP_VISUAL` in Stage
1 only when the source fingerprint also matches. Geometry is only a clone
tie-breaker after those source facts are true; it is not an ownership decision
by itself.

If the story-flow inline source is Paper/knockout-only and the matching
page/spread source carries the non-Paper visible material for the same marker,
the inline source is the anchor/advance owner but not the visible material
owner. Stage 1 binds the inline `SHELL_SLOT`/marker slot to the page/spread
material file and drops the page/spread owner for that same slot. The resulting
ObjectPlan keeps `placement=INLINE` and `coordinateSpace=STORY_FLOW`; later
builders must execute that plan file as-is and must not fall back to the
Paper-only inline render.

Table-cell anchored external label shells are a separate placement case. If an
inline source is anchored from a table cell, the cell already has visible text
runs, and the shell source bounds are outside the table bounds, Stage 1 may keep
that `SHELL_SLOT` as a page/floating label while the editable label text remains
HWPX text. Validators must not report this as an inline-source violation. This
decision must be made from table/cell source metadata and source bounds in
ObjectPlan; executors must not infer it from rendered overlap or page-specific
symptoms.

A TextFrame with visible fill/stroke may be a shell visual source. If the same
TextFrame also owns editable text, Stage 1 treats the source id as two slot
claims: the `TEXT_SLOT` is represented by `ownedTextFrameIds` and the
`SHELL_SLOT` is represented by `visualSourceObjectIds`/`styleSourceObjectIds`.
The PNG export must hide the editable text before rendering the shell, so the
text is not duplicated as complete PNG text.
Such a TextFrame shell style source is executable shell material: it must be
included in the shell plan's export source set and must not remain only in a
hidden visual list. Exporters may suppress glyph paint for that TextFrame, but
must preserve the frame fill/stroke/corner style while rendering the textless
shell.
If the executable shell material is the TextFrame source itself, broader
composite carrier provenance does not own that shell slot. The carrier may list
the TextFrame id for ancestry/diagnostics, but it must not drop the TextFrame
shell plan merely because the same id appears inside a recursive composite
source set. The `TEXT_SLOT` remains HWPX text and the TextFrame fill/stroke
remains the paired `SHELL_SLOT`.
If Stage 1 has already declared that hidden TextFrame as part of the same
`SHELL_SLOT`, inline/table ancestry does not remove the frame style from the
shell export. Inline ancestry controls placement, not whether the declared
shell style material exists.
However, a source already owned by an explicit `PLACE_INLINE_PNG`/inline-object
candidate is not also exported through an ancestor page/floating shell. The
inline candidate owns that visible slot, and the parent shell must keep it out
of its executable export source set.

## 1.2 Performance-Oriented Ownership Policy

Performance is an ownership property, not only an implementation concern. The
fast pipeline is the one that never creates non-executable visual candidates in
the first place. A later normalize/drop/deduplicate pass may be useful as a
diagnostic during migration, but it is not a permitted performance strategy.

Stage 0/1 must build a source-slot registry before any PNG/vector export. The
registry is the only place that maps recursive source clusters to executable
slots. Each registry entry is keyed by the visible execution identity:

- `pageIndex` or applied master/page fragment identity;
- placement context (`PAGE`, `STORY_FLOW`, table-cell flow, or applied master);
- source bundle ancestry;
- ownership slot (`TEXT_SLOT`, `SHELL_SLOT`, `TABLE_STYLE_SLOT`,
  `CONTENT_VISUAL_SLOT`);
- executable source ids for that slot (`exportSourceObjectIds` or style/text
  equivalent);
- hidden/omitted source ids that must not be painted into that slot;
- materialization, layer, z-order, bounds, and reason.

The registry may store large source-id collections as interned source-set
references. A source-set reference is a stable identity for a sorted set of
source ids, and it may be used for recursive clusters, executable export sets,
hidden/omitted sets, page-local fragments, and diagnostic ancestry. Registry
lookups compare the references or indexed membership, not freshly copied arrays.

Candidate generation must be registry-gated:

- If no registry entry exists for a source/slot/channel, no candidate is
  created and no PNG is exported.
- If a registry entry already owns a source bundle slot, other render channels
  for the same slot are diagnostics only. They must not export files and must
  not become later duplicate-removal work.
- A parent/composite candidate may be generated only when its executable source
  ids already exclude child slots owned by the registry. Rendering a broad
  parent and then cropping, alpha-trimming, dropping, hiding, or re-layering the
  child pixels later is not allowed.
- A text-owning shell candidate may be generated only when its textless export
  source ids and hidden editable descendants are already known. Exporting the
  complete group and then trying to recover the text/shell split later is not
  allowed.
- Table style absorption is decided before visual export. Sources owned by
  `TABLE_STYLE_SLOT` are not also exported as shell PNGs unless a separate
  explicit `SHELL_SLOT` remains after table-style sources are subtracted.
- Story-flow inline sources are registered in the story/table channel first.
  Page/floating render channels for the same visible slot are not generated
  unless Stage 1 has selected the page channel as the canonical material while
  preserving the original story-flow placement in `ObjectPlan`.

The policy therefore changes the cost model from "passes times page items" to
"executable source slots". The number of exported images must be bounded by the
number of visible visual slots, not by the number of extractor passes that can
describe the same source material.

Materialization selection is also part of the registry contract. Stage 1 must
ask whether a visible slot really requires PNG before registering an export:

- Simple source `Rectangle`, `Oval`, and `GraphicLine` shells with visible
  fill/stroke may be `NATIVE_SOURCE_SHAPE` when the source slot has no
  non-text child visual ownership to preserve.
- A painted `TextFrame` shell may be represented as HWPX text-box style when
  the editable text remains `OWNED_BY_HWPX_TEXT`.
- `Polygon` remains PNG-backed unless an explicit native polygon policy is
  introduced; HWPX native polygon fallback is not used implicitly.
- Multi-source shells, clipped composites, placed-content groups, and source
  trees with non-text child visuals remain PNG-backed because the source
  composition itself is the visible asset.
- These decisions are made from source metadata and ownership slots only.
  Pixel inspection, page-specific coordinates, later crop/drop behavior, or
  visual occlusion are not valid materialization inputs.

Source-tree reads must also be registry-backed:

- Recursive descendants, source roots, page-local fragments, editable
  TextFrame ids, inline-anchor ancestry, placed-content descendants, and table
  membership are indexed once in Stage 0/1.
- Stage 0/1 interns repeated source id sets once and exposes stable references
  for planner, validator, exporter, and diagnostics.
- Stage 1 diagnostic builders that need source clusters must reuse the
  prepared source-cluster index instead of rebuilding clusters for every
  bundle/object-plan refresh.
- Later planner code may query that index, but must not call live InDesign
  recursive APIs such as `allPageItems` inside loops over candidates, pages, or
  render passes.
- A candidate/planner loop must not rescan a source subtree to rediscover
  ownership facts that are already in the registry.
- Diagnostics may expand source-set references for human review, but execution
  and validation use compact refs, status maps, and indexed membership checks.
- The normal conversion path proves source coverage and slot closure by compact
  proof records (`coverageClaimRef`, `slotClosureRef`, `exportClosureRef`,
  `hiddenChildrenRef`, and interned source-set refs), not by copying descendant
  arrays into every candidate, bundle, ObjectPlan, coverage row, or validation
  row.
- Expanded recursive arrays are trace/explain data. They are emitted for
  failing records, not as the default representation of a successful plan.

Normalize becomes validation:

- It may report duplicate slot owners, missing hidden child declarations,
  missing export source ids, invalid broad parent PNGs, or missing direct child
  candidates.
- It must not create `exportSourceObjectIds`, invent
  `hiddenVisualSourceObjectIds`, promote a child, restore a dropped visual,
  convert inline/floating placement, or change layer/materialization to make the
  output look correct.
- If such a correction is needed, the registry/ObjectPlan policy is incomplete
  and candidate generation must be fixed before export.
- Candidate generation must therefore do slot partitioning before export. A
  parent composite that excludes child text/shell/table/content slots is born as
  `SLOT_ONLY` with explicit export/hidden refs, or it is born as `DROP_VISUAL`;
  it is not emitted as a broad visible candidate and then narrowed by normalize
  or rebuild passes.

Performance invariants:

- For each `(page/placement, source bundle, ownership slot)` there is at most
  one executable visible `ObjectPlan`.
- A non-executable alternate render channel does not write an image file.
- PNG/vector export count is less than or equal to the count of ObjectPlans
  whose `visualAction` is `PLACE_INLINE_PNG`, `PLACE_FLOATING_PNG`, or
  `PLACE_TEXT_SHELL`.
- The same source tree is traversed once to build indexes and then only queried
  by id/set lookups.
- Recursive source-set arrays are not copied into every ObjectPlan, bundle, or
  coverage row in normal conversion output.
- Validators on the normal path validate proof refs and indexed membership
  checks. They expand source trees only for trace/dev explanation or failing
  records.
- Full RenderUnit rows are retained in the in-memory Stage 1 model for
  validation/execution, but normal conversion output writes only summary and
  bounded preview rows unless trace/dev diagnostics are enabled.
- Full source item rows are retained in the in-memory extraction plan and
  diagnostics/debug output, but normal `extraction-plan.json` and
  `source-graph.json` write source item summaries and bounded previews instead
  of duplicating every source row.
- Full extraction candidates are retained in the in-memory extraction plan for
  renderer lookup and validation, but normal `extraction-plan.json` writes
  candidate summaries and bounded previews instead of duplicating every
  candidate instruction.
- Page-spanning source material emits explicit page-local slot plans before
  export, so no adjacent-page overflow copy or post-export ownership repair is
  needed.
- Chunked extraction may reuse immutable document-level Stage 0 facts such as
  IDML, z-order maps, resolved source metadata, and interned source-set indexes.
  Each chunk still runs Stage 1 with its own page/placement context, and chunk
  merge is a result merge only; it never reassigns source ownership.
- A performance optimization is policy-compatible only when it preserves the
  same source-slot registry and proof refs. Skipping repeated traversal,
  copying, serialization, or diagnostic expansion is encouraged; skipping
  ownership planning or validation is not.

Migration milestones for performance work:

1. Build the source-slot registry from source clusters and existing
   ObjectPlan-shaped diagnostics.
2. Gate legacy candidate emitters with the registry so non-owned channels do
   not export PNG files.
3. Replace ownership-normalization transformations with validation-only
   reports.
4. Remove pass-specific duplicate channels once their source slots are emitted
   directly by Stage 1.
