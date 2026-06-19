package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.phase3;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTParagraph;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTableCell;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLStory;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.FrameDisposition;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ResolvedBuildContext;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedParagraph;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedRun;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedStory;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedTextFrame;

import java.util.List;

/**
 * Builds source story flow before any container placement decision.
 *
 * <p>The container layer (table cell, text box, page frame) should receive a
 * ready paragraph flow and only decide where to place it. It must not recover
 * missing inline objects by text matching after placement.</p>
 */
public final class StoryFlowAssembler {
    private StoryFlowAssembler() {
    }

    public static List<ASTParagraph> buildCellFlow(
            ResolvedBuildContext ctx,
            IDMLTableCell idmlCell) {
        List<ASTParagraph> nestedTextFrameFlow = buildNestedTextFrameStoryFlow(ctx, idmlCell);
        if (nestedTextFrameFlow != null && !nestedTextFrameFlow.isEmpty()) {
            return nestedTextFrameFlow;
        }
        return StoryLoader.astParagraphsForCell(ctx, idmlCell);
    }

    private static List<ASTParagraph> buildNestedTextFrameStoryFlow(
            ResolvedBuildContext ctx,
            IDMLTableCell idmlCell) {
        if (ctx == null || ctx.resolvedData == null || idmlCell == null
                || idmlCell.textFrameStoryRefs() == null
                || idmlCell.textFrameStoryRefs().isEmpty()) {
            return null;
        }
        for (String storyRef : idmlCell.textFrameStoryRefs()) {
            if (storyRef == null || isStoryOwnedByPlacedTextFrame(ctx, storyRef)) continue;
            IDMLStory idmlStory = ctx.loadIDMLStory != null ? ctx.loadIDMLStory.apply(storyRef) : null;
            if (idmlStory != null && idmlStory.hasTables()) continue;
            ResolvedStory story = ctx.resolvedData.getStory(toDecimalStoryId(storyRef));
            if (story == null) {
                story = ctx.resolvedData.getStory(storyRef);
            }
            if (!hasAuthoritativeResolvedStructure(story)) continue;
            List<ASTParagraph> paragraphs = StoryConverter.convertStoryParagraphs(ctx, story, false);
            if (paragraphs != null && !paragraphs.isEmpty()) return paragraphs;
        }
        return null;
    }

    public static boolean isStoryOwnedByPlacedTextFrame(ResolvedBuildContext ctx, String storyRef) {
        if (ctx == null || ctx.resolvedData == null || storyRef == null) return false;
        String storyId = toDecimalStoryId(storyRef);
        if (storyId == null) return false;
        List<ResolvedTextFrame> frames = ctx.resolvedData.getTextFramesForStory(storyId);
        if (frames == null || frames.isEmpty()) return false;
        for (ResolvedTextFrame tf : frames) {
            if (tf == null || tf.id() == null) continue;
            try {
                int domId = Integer.parseInt(tf.id());
                if (ctx.isTextDisposed(domId, FrameDisposition.TEXT_BLOCK_PLACED)) return true;
                if (ctx.isTextFrameOwnedByTextShellPlan(domId)) return true;
            } catch (NumberFormatException ignored) {
                // Non-DOM ids cannot be checked against text-frame ownership disposition.
            }
        }
        return false;
    }

    public static boolean hasAuthoritativeResolvedStructure(ResolvedStory story) {
        if (story == null || story.paragraphs() == null || story.paragraphs().isEmpty()) return false;
        int nonEmptyParagraphs = 0;
        for (ResolvedParagraph para : story.paragraphs()) {
            if (para == null || para.runs() == null) continue;
            int visibleRuns = 0;
            for (ResolvedRun run : para.runs()) {
                if (run == null || run.isInlineAnchor()) continue;
                String text = run.text();
                if (text != null && !text.trim().isEmpty()) visibleRuns++;
            }
            if (visibleRuns > 0) nonEmptyParagraphs++;
            if (visibleRuns > 1) return true;
        }
        return nonEmptyParagraphs > 1;
    }

    static String toDecimalStoryId(String storyRef) {
        if (storyRef == null || storyRef.isEmpty()) return null;
        String s = storyRef;
        if (s.startsWith("child_")) s = s.substring("child_".length());
        if (s.startsWith("Story_")) s = s.substring("Story_".length());
        if (s.startsWith("u") && s.length() > 1) {
            try {
                return String.valueOf(Integer.parseInt(s.substring(1), 16));
            } catch (NumberFormatException ignored) {
                return storyRef;
            }
        }
        return storyRef;
    }
}
