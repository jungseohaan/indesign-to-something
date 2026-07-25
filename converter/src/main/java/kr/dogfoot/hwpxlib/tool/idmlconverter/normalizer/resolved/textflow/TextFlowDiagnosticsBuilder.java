package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.textflow;

import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLCharacterRun;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLParagraph;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLStory;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTextFrame;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ResolvedBuildContext;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.ObjectPlan;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.Placement;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.VisualAction;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.shared.SourceTextStyleResolver;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.RenderedGroup;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedData;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedParagraph;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedPageItem;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedRun;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedStory;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedTextFrame;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Builds TextFlow diagnostics without changing the AST conversion path. */
public final class TextFlowDiagnosticsBuilder {
    private static final char OBJECT_REPLACEMENT = '\uFFFC';

    private TextFlowDiagnosticsBuilder() {}

    public static TextFlowDiagnostics build(ResolvedBuildContext ctx) {
        TextFlowDiagnostics diagnostics = new TextFlowDiagnostics();
        if (ctx == null || ctx.resolvedData == null) {
            diagnostics.warnings.add(warning("MISSING_RESOLVED_DATA", null, null,
                    "TextFlow diagnostics skipped because resolvedData is missing"));
            return diagnostics;
        }

        ResolvedData data = ctx.resolvedData;
        List<ResolvedStory> stories = new ArrayList<>(data.stories());
        stories.sort(Comparator.comparing(s -> safe(s.id()), TextFlowDiagnosticsBuilder::compareStoryIds));

        Set<String> claimedTextFrames = new HashSet<>();
        Set<String> warnedInlineSlots = new HashSet<>();
        for (ResolvedStory story : stories) {
            if (story == null) continue;
            TextFlowDiagnostics.TextFlow flow = new TextFlowDiagnostics.TextFlow();
            flow.storyId = story.id();

            List<ResolvedTextFrame> owners = data.getTextFramesForStory(story.id());
            owners.sort(Comparator.comparing(tf -> safe(tf.id()), TextFlowDiagnosticsBuilder::compareStoryIds));
            if (shouldSkipPlanlessSyntheticTextFlow(ctx, owners)) {
                diagnostics.warnings.add(warning("PLANLESS_SYNTHETIC_MASTER_TEXT_FLOW_SKIPPED",
                        story.id(), firstOwnerId(owners),
                        "Synthetic master/off-canvas TextFrame story has no Stage 1 ObjectPlan"));
                continue;
            }
            boolean hasHwpxTextOwner = false;
            boolean hasPngTextOwner = false;
            for (ResolvedTextFrame tf : owners) {
                if (tf == null) continue;
                if (tf.id() != null) {
                    flow.ownerTextFrameIds.add(tf.id());
                    if (!claimedTextFrames.add(tf.id())) {
                        diagnostics.warnings.add(warning("TEXT_FRAME_REUSED", story.id(), tf.id(),
                                "TextFrame is claimed by more than one TextFlow"));
                    }
                }
                if (tf.pageIndex() >= 0 && !flow.ownerPageIndexes.contains(tf.pageIndex())) {
                    flow.ownerPageIndexes.add(tf.pageIndex());
                }
                if (data.isTextOwnedByIndesignPng(tf.id())
                        || ctx.isTextFrameOwnedByPngPlan(tf.id())) {
                    hasPngTextOwner = true;
                } else {
                    hasHwpxTextOwner = true;
                }
            }
            if (owners.isEmpty() && shouldWarnStoryWithoutTextFrame(data, story)) {
                diagnostics.warnings.add(warning("STORY_WITHOUT_TEXT_FRAME", story.id(), null,
                        "Story has no owning TextFrame in resolved metadata"));
            }
            flow.textOwner = hasHwpxTextOwner ? "HWPX_TEXT" : (hasPngTextOwner ? "PNG_TEXT" : "UNKNOWN");

            int paragraphIndex = 0;
            IDMLStory idmlStory = loadIdmlStory(ctx, story.id());
            for (ResolvedParagraph paragraph : story.paragraphs()) {
                TextFlowDiagnostics.TextFlowParagraph outPara =
                        new TextFlowDiagnostics.TextFlowParagraph();
                outPara.index = paragraphIndex++;
                if (paragraph != null) {
                    outPara.sourceParagraph = paragraph;
                    outPara.styleName = paragraph.styleName();
                    outPara.justification = paragraph.justification();
                    outPara.generatedPrefixText = paragraph.generatedPrefixText();
                    IDMLParagraph idmlParagraph = idmlParagraphAt(idmlStory, outPara.index);
                    applyGeneratedPrefixStyle(ctx, outPara, paragraph, idmlParagraph);
                    int runIndex = 0;
                    for (ResolvedRun run : paragraph.runs()) {
                        TextFlowDiagnostics.TextFlowRun outRun = buildRun(ctx, run, runIndex++);
                        if (outRun != null) outPara.runs.add(outRun);
                    }
                    overlayIdmlInlineSlots(ctx, story.id(), outPara);
                    applyEffectiveIdmlTextStyles(ctx, outPara, idmlParagraph);
                }
                flow.paragraphs.add(outPara);
            }
            recomputeFlowCounters(flow);
            collectInlineWarnings(ctx, flow, diagnostics, warnedInlineSlots);
            diagnostics.flows.add(flow);
        }
        return diagnostics;
    }

    private static boolean shouldSkipPlanlessSyntheticTextFlow(
            ResolvedBuildContext ctx,
            List<ResolvedTextFrame> owners) {
        if (ctx == null || owners == null || owners.isEmpty()) return false;
        boolean sawSyntheticOwner = false;
        for (ResolvedTextFrame tf : owners) {
            if (tf == null) continue;
            if (!ctx.shouldSkipPlanlessSyntheticCloneTextFrame(tf)) {
                return false;
            }
            sawSyntheticOwner = true;
        }
        return sawSyntheticOwner;
    }

    private static String firstOwnerId(List<ResolvedTextFrame> owners) {
        if (owners == null) return null;
        for (ResolvedTextFrame tf : owners) {
            if (tf != null && tf.id() != null) return tf.id();
        }
        return null;
    }

    private static TextFlowDiagnostics.TextFlowRun buildRun(
            ResolvedBuildContext ctx,
            ResolvedRun run,
            int runIndex) {
        TextFlowDiagnostics.TextFlowRun out = new TextFlowDiagnostics.TextFlowRun();
        out.index = runIndex;
        out.sourceRun = run;
        if (run == null) {
            out.kind = "TEXT";
            return out;
        }
        if (run.isInlineAnchor()) {
            int anchoredObjectId = run.anchoredObjectId();
            if (isAbsorbedTextStyleAnchor(ctx, anchoredObjectId)) {
                return null;
            }
            if (!isTextFlowInlineSlot(ctx, anchoredObjectId)) {
                return null;
            }
            out.kind = "INLINE_SLOT";
            out.anchoredObjectId = anchoredObjectId;
            applySourceMetadata(ctx, out, anchoredObjectId);
            ObjectPlan plan = findPlanForAnchor(ctx, anchoredObjectId);
            if (plan != null) {
                out.planTextAction = plan.textAction != null ? plan.textAction.name() : null;
                out.planVisualAction = plan.visualAction != null ? plan.visualAction.name() : null;
                out.planPlacement = plan.placement != null ? plan.placement.name() : null;
                out.planMaterialization = plan.materialization != null ? plan.materialization.name() : null;
                out.planReason = plan.reason;
            }
            return out;
        }
        out.kind = "TEXT";
        out.text = run.text();
        out.fontFamily = run.fontFamily();
        out.fontSize = run.fontSize();
        out.fontStyle = run.fontStyle();
        out.fillColor = run.fillColor();
        out.charStyle = run.charStyle();
        out.tracking = run.tracking();
        out.horizontalScale = run.horizontalScale();
        out.baselineShift = run.baselineShift();
        return out;
    }

    private static IDMLStory loadIdmlStory(ResolvedBuildContext ctx, String storyId) {
        if (ctx == null || ctx.loadIDMLStory == null || storyId == null || storyId.isEmpty()) return null;
        return ctx.loadIDMLStory.apply(storyId);
    }

    private static IDMLParagraph idmlParagraphAt(IDMLStory story, int index) {
        if (story == null || story.paragraphs() == null || index < 0
                || index >= story.paragraphs().size()) {
            return null;
        }
        return story.paragraphs().get(index);
    }

    private static void applyGeneratedPrefixStyle(
            ResolvedBuildContext ctx,
            TextFlowDiagnostics.TextFlowParagraph outPara,
            ResolvedParagraph resolvedParagraph,
            IDMLParagraph idmlParagraph) {
        if (ctx == null || outPara == null || resolvedParagraph == null) return;
        String prefix = resolvedParagraph.generatedPrefixText();
        if (prefix == null || prefix.trim().isEmpty()) return;
        ResolvedRun base = firstVisibleResolvedRun(resolvedParagraph);
        if (base == null) return;

        ResolvedRun markerRun = SourceTextStyleResolver.copyRun(base);
        ResolvedRun separatorRun = SourceTextStyleResolver.copyRun(base);
        String paraStyleRef = idmlParagraph != null && idmlParagraph.appliedParagraphStyle() != null
                ? idmlParagraph.appliedParagraphStyle()
                : resolvedParagraph.styleName();
        SourceTextStyleResolver.applyGeneratedBulletStyle(ctx, markerRun, paraStyleRef);
        outPara.generatedPrefixMarkerRun = markerRun;
        outPara.generatedPrefixSeparatorRun = separatorRun;
        outPara.generatedPrefixMarkerCharStyle = markerRun.charStyle();
        outPara.generatedPrefixMarkerFillColor = markerRun.fillColor();
        outPara.generatedPrefixMarkerHorizontalScale = markerRun.horizontalScale();
        outPara.generatedPrefixMarkerVerticalScale = markerRun.verticalScale();
    }

    private static void applyEffectiveIdmlTextStyles(
            ResolvedBuildContext ctx,
            TextFlowDiagnostics.TextFlowParagraph paragraph,
            IDMLParagraph idmlParagraph) {
        if (ctx == null || paragraph == null || idmlParagraph == null
                || paragraph.runs == null || paragraph.runs.isEmpty()) {
            return;
        }
        List<IdmlTextSpan> spans = idmlTextSpans(idmlParagraph);
        if (spans.isEmpty()) return;

        List<TextFlowDiagnostics.TextFlowRun> rebuilt = new ArrayList<>();
        int spanIndex = 0;
        int spanOffset = 0;
        boolean changed = false;

        for (TextFlowDiagnostics.TextFlowRun run : paragraph.runs) {
            if (run == null || !"TEXT".equals(run.kind) || run.text == null || run.text.isEmpty()) {
                if (run != null && "INLINE_SLOT".equals(run.kind)) {
                    SpanCursor advanced = skipObjectReplacementSpans(spans, spanIndex, spanOffset);
                    if (advanced == null) {
                        return;
                    }
                    spanIndex = advanced.index;
                    spanOffset = advanced.offset;
                }
                rebuilt.add(run);
                continue;
            }
            int runOffset = 0;
            while (runOffset < run.text.length()) {
                SpanCursor advanced = skipObjectReplacementSpans(spans, spanIndex, spanOffset);
                if (advanced == null) {
                    return;
                }
                spanIndex = advanced.index;
                spanOffset = advanced.offset;
                while (spanIndex < spans.size()
                        && spanOffset >= spans.get(spanIndex).text.length()) {
                    spanIndex++;
                    spanOffset = 0;
                }
                if (spanIndex >= spans.size()) {
                    String remaining = run.text.substring(runOffset);
                    if (isOnlyParagraphTerminators(remaining)) {
                        TextFlowDiagnostics.TextFlowRun piece = copyTextRun(run);
                        piece.text = remaining;
                        piece.sourceRun = SourceTextStyleResolver.copyRun(run.sourceRun);
                        if (piece.sourceRun != null) {
                            piece.sourceRun.text(remaining);
                        }
                        rebuilt.add(piece);
                        changed = true;
                        runOffset = run.text.length();
                        break;
                    }
                    return;
                }
                IdmlTextSpan span = spans.get(spanIndex);
                int take = Math.min(
                        run.text.length() - runOffset,
                        span.text.length() - spanOffset);
                if (take <= 0) return;
                String resolvedPart = run.text.substring(runOffset, runOffset + take);
                String idmlPart = span.text.substring(spanOffset, spanOffset + take);
                if (!resolvedPart.equals(idmlPart)) {
                    return;
                }
                TextFlowDiagnostics.TextFlowRun piece = copyTextRun(run);
                piece.text = resolvedPart;
                piece.sourceRun = SourceTextStyleResolver.copyRun(run.sourceRun);
                if (piece.sourceRun != null) {
                    piece.sourceRun.text(resolvedPart);
                    SourceTextStyleResolver.applyCharacterRun(ctx, piece.sourceRun, span.run);
                    copyResolvedFields(piece, piece.sourceRun);
                }
                rebuilt.add(piece);
                if (take != run.text.length() || spanOffset != 0
                        || take != span.text.length()) {
                    changed = true;
                }
                if (span.run != null && SourceTextStyleResolver.hasEffectiveStyle(span.run)) {
                    changed = true;
                }
                runOffset += take;
                spanOffset += take;
            }
        }
        if (!changed) return;
        paragraph.runs.clear();
        paragraph.runs.addAll(rebuilt);
        reindexRuns(paragraph);
    }

    private static boolean isOnlyParagraphTerminators(String text) {
        if (text == null || text.isEmpty()) return false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c != '\r' && c != '\n') return false;
        }
        return true;
    }

    private static SpanCursor skipObjectReplacementSpans(
            List<IdmlTextSpan> spans,
            int spanIndex,
            int spanOffset) {
        if (spans == null) return null;
        while (spanIndex < spans.size()) {
            IdmlTextSpan span = spans.get(spanIndex);
            String text = span != null ? span.text : null;
            if (text == null || text.isEmpty() || spanOffset >= text.length()) {
                spanIndex++;
                spanOffset = 0;
                continue;
            }
            if (text.charAt(spanOffset) != OBJECT_REPLACEMENT) {
                return new SpanCursor(spanIndex, spanOffset);
            }
            spanOffset++;
            if (spanOffset >= text.length()) {
                spanIndex++;
                spanOffset = 0;
            }
        }
        return new SpanCursor(spanIndex, spanOffset);
    }

    private static List<IdmlTextSpan> idmlTextSpans(IDMLParagraph paragraph) {
        List<IdmlTextSpan> spans = new ArrayList<>();
        if (paragraph == null || paragraph.characterRuns() == null) return spans;
        for (IDMLCharacterRun run : paragraph.characterRuns()) {
            if (run == null || run.content() == null || run.content().isEmpty()) continue;
            spans.add(new IdmlTextSpan(run.content(), run));
        }
        return spans;
    }

    private static ResolvedRun firstVisibleResolvedRun(ResolvedParagraph paragraph) {
        if (paragraph == null || paragraph.runs() == null) return null;
        for (ResolvedRun run : paragraph.runs()) {
            if (run == null || run.isInlineAnchor() || run.text() == null) continue;
            String text = run.text().replace(OBJECT_REPLACEMENT, ' ').trim();
            if (!text.isEmpty()) return run;
        }
        return null;
    }

    private static TextFlowDiagnostics.TextFlowRun copyTextRun(TextFlowDiagnostics.TextFlowRun source) {
        TextFlowDiagnostics.TextFlowRun copy = new TextFlowDiagnostics.TextFlowRun();
        copy.index = source.index;
        copy.kind = source.kind;
        copy.text = source.text;
        copy.sourceRun = source.sourceRun;
        copy.fontFamily = source.fontFamily;
        copy.fontSize = source.fontSize;
        copy.fontStyle = source.fontStyle;
        copy.fillColor = source.fillColor;
        copy.charStyle = source.charStyle;
        copy.tracking = source.tracking;
        copy.horizontalScale = source.horizontalScale;
        copy.baselineShift = source.baselineShift;
        return copy;
    }

    private static void copyResolvedFields(TextFlowDiagnostics.TextFlowRun target, ResolvedRun source) {
        if (target == null || source == null) return;
        target.fontFamily = source.fontFamily();
        target.fontSize = source.fontSize();
        target.fontStyle = source.fontStyle();
        target.fillColor = source.fillColor();
        target.charStyle = source.charStyle();
        target.tracking = source.tracking();
        target.horizontalScale = source.horizontalScale();
        target.baselineShift = source.baselineShift();
    }

    private static final class IdmlTextSpan {
        final String text;
        final IDMLCharacterRun run;

        IdmlTextSpan(String text, IDMLCharacterRun run) {
            this.text = text;
            this.run = run;
        }
    }

    private static final class SpanCursor {
        final int index;
        final int offset;

        SpanCursor(int index, int offset) {
            this.index = index;
            this.offset = offset;
        }
    }

    private static void overlayIdmlInlineSlots(
            ResolvedBuildContext ctx,
            String storyId,
            TextFlowDiagnostics.TextFlowParagraph paragraph) {
        if (ctx == null || ctx.loadIDMLStory == null || storyId == null || paragraph == null) return;
        IDMLStory idmlStory = ctx.loadIDMLStory.apply(storyId);
        if (idmlStory == null || idmlStory.paragraphs() == null) return;
        if (paragraph.index < 0 || paragraph.index >= idmlStory.paragraphs().size()) return;
        IDMLParagraph idmlParagraph = idmlStory.paragraphs().get(paragraph.index);
        List<IdmlInlineSlot> slots = idmlInlineSlots(idmlParagraph);
        if (slots.isEmpty()) return;

        List<IdmlInlineSlot> missing = new ArrayList<>();
        for (IdmlInlineSlot slot : slots) {
            if (slot.anchorObjectId == null) continue;
            if (isAbsorbedTextStyleAnchor(ctx, slot.anchorObjectId)) continue;
            if (!paragraphHasInlineAnchor(paragraph, slot.anchorObjectId)) {
                missing.add(slot);
            }
        }
        if (missing.isEmpty()) return;

        missing.sort(Comparator.comparingInt(slot -> slot.textOffset));
        int inserted = 0;
        for (IdmlInlineSlot slot : missing) {
            int index = insertIndexForTextOffset(paragraph.runs, slot.textOffset);
            TextFlowDiagnostics.TextFlowRun run = buildIdmlInlineRun(ctx, slot.anchorObjectId);
            if (run == null) continue;
            paragraph.runs.add(Math.max(0, Math.min(index + inserted, paragraph.runs.size())), run);
            inserted++;
        }
        reindexRuns(paragraph);
    }

    private static List<IdmlInlineSlot> idmlInlineSlots(IDMLParagraph paragraph) {
        List<IdmlInlineSlot> slots = new ArrayList<>();
        if (paragraph == null || paragraph.characterRuns() == null) return slots;
        int textOffset = 0;
        for (IDMLCharacterRun run : paragraph.characterRuns()) {
            if (run == null) continue;
            String content = run.content() != null ? run.content() : "";
            int anchorIndex = 0;
            for (int i = 0; i < content.length(); i++) {
                char c = content.charAt(i);
                if (c == OBJECT_REPLACEMENT) {
                    slots.add(new IdmlInlineSlot(textOffset, anchorObjectId(run, anchorIndex++)));
                } else {
                    textOffset++;
                }
            }
        }
        return slots;
    }

    private static Integer anchorObjectId(IDMLCharacterRun run, int anchorIndex) {
        if (run == null || run.inlineAnchors() == null
                || anchorIndex < 0 || anchorIndex >= run.inlineAnchors().size()) {
            return null;
        }
        IDMLCharacterRun.InlineAnchor anchor = run.inlineAnchors().get(anchorIndex);
        if (anchor == null || anchor.type() == null) return null;
        String id = null;
        if (anchor.type() == IDMLCharacterRun.InlineAnchorType.FRAME) {
            if (run.inlineFrames() != null && anchor.index() >= 0 && anchor.index() < run.inlineFrames().size()) {
                IDMLTextFrame frame = run.inlineFrames().get(anchor.index());
                id = frame != null ? frame.selfId() : null;
            }
        } else if (anchor.type() == IDMLCharacterRun.InlineAnchorType.GRAPHIC) {
            if (run.inlineGraphics() != null && anchor.index() >= 0 && anchor.index() < run.inlineGraphics().size()) {
                IDMLCharacterRun.InlineGraphic graphic = run.inlineGraphics().get(anchor.index());
                id = graphic != null ? graphic.selfId() : null;
            }
        }
        return parseIdmlNumericId(id);
    }

    private static TextFlowDiagnostics.TextFlowRun buildIdmlInlineRun(ResolvedBuildContext ctx, int anchorObjectId) {
        if (isAbsorbedTextStyleAnchor(ctx, anchorObjectId)) return null;
        if (!isTextFlowInlineSlot(ctx, anchorObjectId)) return null;
        TextFlowDiagnostics.TextFlowRun out = new TextFlowDiagnostics.TextFlowRun();
        out.kind = "INLINE_SLOT";
        out.anchoredObjectId = anchorObjectId;
        applySourceMetadata(ctx, out, anchorObjectId);
        ObjectPlan plan = findPlanForAnchor(ctx, anchorObjectId);
        if (plan != null) {
            out.planTextAction = plan.textAction != null ? plan.textAction.name() : null;
            out.planVisualAction = plan.visualAction != null ? plan.visualAction.name() : null;
            out.planPlacement = plan.placement != null ? plan.placement.name() : null;
            out.planMaterialization = plan.materialization != null ? plan.materialization.name() : null;
            out.planReason = plan.reason;
        }
        return out;
    }

    private static boolean isAbsorbedTextStyleAnchor(ResolvedBuildContext ctx, int anchorObjectId) {
        ObjectPlan plan = findPlanForAnchor(ctx, anchorObjectId);
        return plan != null
                && plan.placement == Placement.INLINE
                && plan.visualAction == VisualAction.ABSORB_TEXT_STYLE;
    }

    private static boolean isTextFlowInlineSlot(ResolvedBuildContext ctx, int anchorObjectId) {
        ObjectPlan plan = findPlanForAnchor(ctx, anchorObjectId);
        if (plan != null) {
            return plan.placement == Placement.INLINE;
        }
        if (ctx == null || ctx.resolvedData == null) {
            return true;
        }
        ResolvedPageItem item = ctx.resolvedData.getPageItem(String.valueOf(anchorObjectId));
        if (item == null) {
            return true;
        }
        String placement = safe(item.storyAnchorPlacement()).toUpperCase(java.util.Locale.ROOT);
        String anchoredPosition = safe(item.anchoredPosition()).toUpperCase(java.util.Locale.ROOT);
        if (item.storyTextInlineSlot()) {
            return true;
        }
        if ("FLOATING_ANCHORED".equals(placement) || "ANCHORED".equals(anchoredPosition)) {
            return false;
        }
        return "INLINE".equals(placement)
                || "INLINE_POSITION".equals(anchoredPosition)
                || "INLINEPOSITION".equals(anchoredPosition);
    }

    private static boolean paragraphHasInlineAnchor(TextFlowDiagnostics.TextFlowParagraph paragraph, int anchorObjectId) {
        if (paragraph == null || paragraph.runs == null) return false;
        for (TextFlowDiagnostics.TextFlowRun run : paragraph.runs) {
            if (run != null && "INLINE_SLOT".equals(run.kind)
                    && run.anchoredObjectId != null && run.anchoredObjectId.equals(anchorObjectId)) {
                return true;
            }
        }
        return false;
    }

    private static int insertIndexForTextOffset(List<TextFlowDiagnostics.TextFlowRun> runs, int textOffset) {
        if (runs == null || runs.isEmpty() || textOffset <= 0) return 0;
        int cursor = 0;
        for (int i = 0; i < runs.size(); i++) {
            TextFlowDiagnostics.TextFlowRun run = runs.get(i);
            if (run == null || !"TEXT".equals(run.kind) || run.text == null) continue;
            cursor += run.text.length();
            if (cursor >= textOffset) {
                return i + 1;
            }
        }
        return runs.size();
    }

    private static void reindexRuns(TextFlowDiagnostics.TextFlowParagraph paragraph) {
        if (paragraph == null || paragraph.runs == null) return;
        for (int i = 0; i < paragraph.runs.size(); i++) {
            TextFlowDiagnostics.TextFlowRun run = paragraph.runs.get(i);
            if (run != null) run.index = i;
        }
    }

    private static void recomputeFlowCounters(TextFlowDiagnostics.TextFlow flow) {
        if (flow == null) return;
        flow.textLength = 0;
        flow.inlineSlotCount = 0;
        for (TextFlowDiagnostics.TextFlowParagraph paragraph : flow.paragraphs) {
            if (paragraph == null || paragraph.runs == null) continue;
            if (paragraph.generatedPrefixText != null) {
                flow.textLength += paragraph.generatedPrefixText.length();
            }
            for (TextFlowDiagnostics.TextFlowRun run : paragraph.runs) {
                if (run == null) continue;
                if ("INLINE_SLOT".equals(run.kind)) {
                    flow.inlineSlotCount++;
                } else if (run.text != null) {
                    flow.textLength += run.text.length();
                }
            }
        }
    }

    private static void collectInlineWarnings(
            ResolvedBuildContext ctx,
            TextFlowDiagnostics.TextFlow flow,
            TextFlowDiagnostics diagnostics,
            Set<String> warnedInlineSlots) {
        if (flow == null || diagnostics == null || warnedInlineSlots == null) return;
        for (TextFlowDiagnostics.TextFlowParagraph paragraph : flow.paragraphs) {
            if (paragraph == null || paragraph.runs == null) continue;
            for (TextFlowDiagnostics.TextFlowRun run : paragraph.runs) {
                if (run == null || !"INLINE_SLOT".equals(run.kind) || run.planVisualAction != null) continue;
                if (!inlineSlotHasPotentialVisibleMaterial(ctx, run)) continue;
                String code = unplannedInlineWarningCode(run);
                String key = code + ":" + run.anchoredObjectId + ":" + safe(run.sourceStatus);
                if (warnedInlineSlots.add(key)) {
                    diagnostics.warnings.add(warning(code,
                            flow.storyId, String.valueOf(run.anchoredObjectId),
                            unplannedInlineWarningMessage(run)));
                }
            }
        }
    }

    private static boolean inlineSlotHasPotentialVisibleMaterial(
            ResolvedBuildContext ctx,
            TextFlowDiagnostics.TextFlowRun run) {
        if (ctx == null || ctx.resolvedData == null || run == null || run.anchoredObjectId == null) {
            return true;
        }
        if (!"NATIVE_INLINE_SOURCE".equals(run.sourceStatus)
                && !"NATIVE_PAGE_SOURCE".equals(run.sourceStatus)) {
            return true;
        }
        ResolvedPageItem item = ctx.resolvedData.getPageItem(String.valueOf(run.anchoredObjectId));
        if (item == null) return true;
        if (item.sourceHidden()) return false;
        String type = safe(item.type());
        if ("Image".equals(type) || "PDF".equals(type) || "EPS".equals(type)) return true;
        if (item.childIds() != null && item.childIds().length > 0) return true;
        String fill = item.fillColorName();
        if (fill != null && !"None".equals(fill) && !"[None]".equals(fill)) return true;
        String stroke = item.strokeColorName();
        return stroke != null && !"None".equals(stroke) && !"[None]".equals(stroke)
                && item.strokeWeight() > 0;
    }

    private static void applySourceMetadata(
            ResolvedBuildContext ctx,
            TextFlowDiagnostics.TextFlowRun out,
            Integer anchorId) {
        if (ctx == null || ctx.resolvedData == null || out == null || anchorId == null) {
            if (out != null) out.sourceStatus = "MISSING_SOURCE";
            return;
        }
        ResolvedPageItem item = ctx.resolvedData.getPageItem(String.valueOf(anchorId));
        if (item != null) {
            out.sourceType = item.type();
            out.sourcePageIndex = item.pageIndex();
            out.sourceLayerName = item.layerName();
            out.sourceInline = item.isInline();
            out.sourceStoryAnchorPlacement = item.storyAnchorPlacement();
            out.sourceHidden = item.sourceHidden();
            out.sourceBounds = item.pageRelativeBounds() != null
                    ? item.pageRelativeBounds()
                    : item.geometricBounds();
            if (item.sourceHidden()) {
                out.sourceStatus = "HIDDEN_NATIVE_SOURCE";
            } else if (item.isInline()) {
                out.sourceStatus = "NATIVE_INLINE_SOURCE";
            } else {
                out.sourceStatus = "NATIVE_PAGE_SOURCE";
            }
            return;
        }
        RenderedGroup rendered = ctx.inlineObjectById(anchorId);
        if (rendered != null) {
            out.sourceType = safe(rendered.type());
            out.sourcePageIndex = rendered.pageIndex();
            out.sourceInline = true;
            out.sourceHidden = Boolean.FALSE;
            out.sourceBounds = rendered.bounds();
            out.sourceStatus = "RENDERED_INLINE_SOURCE";
            return;
        }
        out.sourceStatus = "MISSING_SOURCE";
    }

    private static String unplannedInlineWarningCode(TextFlowDiagnostics.TextFlowRun run) {
        if (run == null) return "INLINE_SLOT_WITHOUT_SOURCE_OR_OBJECT_PLAN";
        if ("NATIVE_INLINE_SOURCE".equals(run.sourceStatus)) {
            return "INLINE_NATIVE_SOURCE_WITHOUT_OBJECT_PLAN";
        }
        if ("HIDDEN_NATIVE_SOURCE".equals(run.sourceStatus)) {
            return "INLINE_HIDDEN_SOURCE_WITHOUT_OBJECT_PLAN";
        }
        if ("RENDERED_INLINE_SOURCE".equals(run.sourceStatus)) {
            return "INLINE_RENDERED_SOURCE_WITHOUT_OBJECT_PLAN";
        }
        if ("NATIVE_PAGE_SOURCE".equals(run.sourceStatus)) {
            return "INLINE_PAGE_SOURCE_WITHOUT_OBJECT_PLAN";
        }
        return "INLINE_SLOT_WITHOUT_SOURCE_OR_OBJECT_PLAN";
    }

    private static String unplannedInlineWarningMessage(TextFlowDiagnostics.TextFlowRun run) {
        String status = run != null ? run.sourceStatus : null;
        if ("NATIVE_INLINE_SOURCE".equals(status)) {
            return "Inline anchor has native source metadata but no Stage 1 ObjectPlan";
        }
        if ("HIDDEN_NATIVE_SOURCE".equals(status)) {
            return "Inline anchor source is hidden and has no Stage 1 ObjectPlan";
        }
        if ("RENDERED_INLINE_SOURCE".equals(status)) {
            return "Inline anchor has rendered source metadata but no Stage 1 ObjectPlan";
        }
        if ("NATIVE_PAGE_SOURCE".equals(status)) {
            return "Inline anchor points to a non-inline native source and has no Stage 1 ObjectPlan";
        }
        return "Inline anchor has neither source metadata nor matching Stage 1 ObjectPlan";
    }

    private static boolean shouldWarnStoryWithoutTextFrame(ResolvedData data, ResolvedStory story) {
        if (data == null || story == null) return false;
        if (!storyHasVisibleTextOrInlineSlot(story)) return false;
        return !hasOverrideTextFrameOwner(data, story.id());
    }

    private static boolean storyHasVisibleTextOrInlineSlot(ResolvedStory story) {
        if (story == null || story.paragraphs() == null) return false;
        for (ResolvedParagraph paragraph : story.paragraphs()) {
            if (paragraph == null || paragraph.runs() == null) continue;
            for (ResolvedRun run : paragraph.runs()) {
                if (run == null) continue;
                if (run.isInlineAnchor()) return true;
                if (hasVisibleText(run.text())) return true;
            }
        }
        return false;
    }

    private static boolean hasVisibleText(String text) {
        if (text == null || text.isEmpty()) return false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (!Character.isISOControl(c) && !Character.isWhitespace(c)) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasOverrideTextFrameOwner(ResolvedData data, String storyId) {
        if (data == null || storyId == null || storyId.isBlank()) return false;
        String overridePrefix = storyId + "_oc";
        String pageInstancePrefix = storyId + "_pi";
        for (ResolvedTextFrame tf : data.textFrames()) {
            if (tf == null || tf.storyId() == null) continue;
            if (tf.storyId().startsWith(overridePrefix)
                    || tf.storyId().startsWith(pageInstancePrefix)) {
                return true;
            }
        }
        return false;
    }

    private static ObjectPlan findPlanForAnchor(ResolvedBuildContext ctx, Integer anchorId) {
        if (ctx == null || ctx.ownershipPlans == null || anchorId == null) return null;
        ObjectPlan direct = null;
        ObjectPlan inlinePng = null;
        ObjectPlan containing = null;
        for (ObjectPlan plan : ctx.ownershipPlans) {
            if (plan == null) continue;
            boolean directMatch = plan.domId == anchorId
                    || (plan.renderId != null && plan.renderId.equals(anchorId));
            boolean containsAnchor = directMatch
                    || contains(plan.sourceObjectIds, anchorId)
                    || contains(plan.visualSourceObjectIds, anchorId)
                    || contains(plan.ownedTextFrameIds, anchorId);
            if (!containsAnchor) continue;
            if (directMatch && direct == null) direct = plan;
            if (plan.placement == Placement.INLINE
                    && plan.visualAction == VisualAction.PLACE_INLINE_PNG
                    && inlinePng == null) {
                inlinePng = plan;
            }
            if (containing == null) containing = plan;
        }
        if (inlinePng != null) return inlinePng;
        if (direct != null) return direct;
        return containing;
    }

    private static boolean contains(int[] values, int needle) {
        if (values == null) return false;
        for (int value : values) {
            if (value == needle) return true;
        }
        return false;
    }

    private static String warning(String code, String storyId, String subjectId, String message) {
        StringBuilder sb = new StringBuilder();
        sb.append("{\"code\":\"").append(escape(code)).append("\"");
        if (storyId != null) sb.append(",\"storyId\":\"").append(escape(storyId)).append("\"");
        if (subjectId != null) sb.append(",\"subjectId\":\"").append(escape(subjectId)).append("\"");
        sb.append(",\"message\":\"").append(escape(message)).append("\"}");
        return sb.toString();
    }

    private static int compareStoryIds(String a, String b) {
        Long na = parseLong(a);
        Long nb = parseLong(b);
        if (na != null && nb != null) {
            int numeric = na.compareTo(nb);
            return numeric != 0 ? numeric : a.compareTo(b);
        }
        if (na != null) return -1;
        if (nb != null) return 1;
        return a.compareTo(b);
    }

    private static Long parseLong(String value) {
        if (value == null || value.isEmpty()) return null;
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static Integer parseIdmlNumericId(String id) {
        if (id == null || id.length() < 2 || id.charAt(0) != 'u') return null;
        try {
            return Integer.parseInt(id.substring(1), 16);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static final class IdmlInlineSlot {
        final int textOffset;
        final Integer anchorObjectId;

        IdmlInlineSlot(int textOffset, Integer anchorObjectId) {
            this.textOffset = textOffset;
            this.anchorObjectId = anchorObjectId;
        }
    }

    private static String safe(String value) {
        return value != null ? value : "";
    }

    private static String escape(String value) {
        if (value == null) return "";
        StringBuilder sb = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '\\': sb.append("\\\\"); break;
                case '"': sb.append("\\\""); break;
                case '\n': sb.append("\\n"); break;
                case '\r': sb.append("\\r"); break;
                case '\t': sb.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        return sb.toString();
    }
}
