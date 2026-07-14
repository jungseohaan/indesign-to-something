package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTextFrameBlock;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ResolvedBuildContext;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.shared.ParagraphTextHelpers;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedParagraph;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedRun;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedStory;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedTextFrame;

import java.util.ArrayList;
import java.util.List;

/**
 * Source-ownership planner for linked TextFrame story ranges.
 *
 * <p>This class decides which character range of a resolved Story belongs to
 * each linked source TextFrame. Stage 2/Phase 3 paragraph distribution should
 * execute this plan only; it must not infer ownership from page symptoms,
 * coordinates, or document-specific text.</p>
 */
public final class TextFrameRangeOwnershipPlanner {

    private static final int FRAME_RANGE_REWIND_TOLERANCE = 32;

    private TextFrameRangeOwnershipPlanner() {}

    public static FrameRangePlan plan(
            ResolvedBuildContext ctx,
            List<ASTTextFrameBlock> orderedFrames,
            String storyText) {
        int frameCount = orderedFrames != null ? orderedFrames.size() : 0;
        int[][] ranges = new int[frameCount][2];
        boolean[] startsWithInlineObject = new boolean[frameCount];
        if (frameCount == 0 || storyText == null) {
            return new FrameRangePlan(ranges);
        }

        int searchFrom = 0;
        for (int fi = 0; fi < frameCount; fi++) {
            ASTTextFrameBlock block = orderedFrames.get(fi);
            ResolvedTextFrame rtf = resolvedTextFrame(ctx, block);

            String visibleText = block != null ? block.frameVisibleText() : null;
            if (visibleText == null) {
                visibleText = rtf != null ? rtf.frameVisibleText() : null;
            }
            String rawVisibleText = visibleText;
            startsWithInlineObject[fi] = startsWithInlineObjectOrBoundary(rawVisibleText);
            if (visibleText != null) {
                visibleText = normalizeFrameTextForRangeMatching(ctx, rtf, visibleText);
            }

            if (visibleText == null || visibleText.isEmpty()) {
                List<String> frameTexts = rtf != null ? rtf.frameParaTexts() : null;
                if (frameTexts != null && !frameTexts.isEmpty()) {
                    StringBuilder sb = new StringBuilder();
                    for (String ft : frameTexts) {
                        if (ft != null) sb.append(normalizeFrameTextForRangeMatching(ctx, rtf, ft));
                    }
                    visibleText = sb.toString();
                }
            }

            if (visibleText == null || visibleText.isEmpty()) {
                ranges[fi][0] = searchFrom;
                ranges[fi][1] = fi == frameCount - 1 ? storyText.length() : searchFrom;
                continue;
            }

            if (hasResolvedFrameCharacterRange(rtf) && startsWithInlineObjectOrBoundary(rawVisibleText)) {
                int resolvedStart = clamp(rtf.paragraphStart(), 0, storyText.length());
                int resolvedEnd = clamp(rtf.paragraphEnd(), resolvedStart, storyText.length());
                int visibleStoryStart = fi > 0
                        ? findFirstVisibleStoryStart(storyText, visibleText, searchFrom, resolvedStart)
                        : -1;
                ranges[fi][0] = visibleStoryStart >= 0 ? visibleStoryStart : resolvedStart;
                ranges[fi][1] = Math.max(ranges[fi][0], resolvedEnd);
                searchFrom = ranges[fi][1];
                continue;
            }

            int foundStart = findFrameStart(storyText, visibleText, searchFrom);
            if (foundStart < 0) {
                foundStart = resolvedFrameStartOffset(rtf, searchFrom, storyText.length());
            }

            String endKey = visibleText.length() > 20
                    ? visibleText.substring(visibleText.length() - 20)
                    : visibleText;
            int foundEnd = storyText.indexOf(endKey, foundStart);
            if (foundEnd >= 0) {
                foundEnd += endKey.length();
            } else {
                foundEnd = foundStart + visibleText.length();
            }

            ranges[fi][0] = foundStart;
            ranges[fi][1] = Math.min(foundEnd, storyText.length());
            searchFrom = ranges[fi][1];
        }

        closeThreadedStoryRangeGaps(ranges, storyText.length());
        includeLeadingInlineObjectAtFrameStarts(ranges, startsWithInlineObject, storyText);
        alignThreadedFrameRangesToTokenBoundaries(ranges, storyText);
        return new FrameRangePlan(ranges);
    }

    public static final class FrameRangePlan {
        private final int[][] ranges;

        private FrameRangePlan(int[][] ranges) {
            this.ranges = ranges != null ? ranges : new int[0][2];
        }

        public int start(int index) {
            return ranges[index][0];
        }

        public int end(int index) {
            return ranges[index][1];
        }
    }

    private static ResolvedTextFrame resolvedTextFrame(
            ResolvedBuildContext ctx,
            ASTTextFrameBlock block) {
        if (ctx == null || ctx.resolvedData == null || block == null) return null;
        String domId = ParagraphTextHelpers.domIdFromSourceId(block.sourceId());
        if (domId == null) domId = block.sourceId();
        return ctx.resolvedData.getTextFrame(domId);
    }

    private static void closeThreadedStoryRangeGaps(int[][] frameRanges, int storyLength) {
        if (frameRanges == null || frameRanges.length <= 1 || storyLength <= 0) return;
        for (int i = 0; i < frameRanges.length - 1; i++) {
            int currentEnd = clamp(frameRanges[i][1], 0, storyLength);
            int nextStart = clamp(frameRanges[i + 1][0], 0, storyLength);
            if (nextStart > currentEnd) {
                frameRanges[i][1] = nextStart;
            }
            if (nextStart < frameRanges[i][1]) {
                frameRanges[i][1] = nextStart;
            }
        }
    }

    private static void includeLeadingInlineObjectAtFrameStarts(
            int[][] frameRanges,
            boolean[] frameStartsWithInlineObject,
            String storyText) {
        if (frameRanges == null || frameStartsWithInlineObject == null || storyText == null) return;
        int count = Math.min(frameRanges.length, frameStartsWithInlineObject.length);
        for (int i = 0; i < count; i++) {
            if (!frameStartsWithInlineObject[i]) continue;
            int start = clamp(frameRanges[i][0], 0, storyText.length());
            if (start <= 0 || storyText.charAt(start - 1) != '\uFFFC') continue;
            int adjustedStart = start - 1;
            frameRanges[i][0] = adjustedStart;
            if (i > 0 && frameRanges[i - 1][1] >= start) {
                frameRanges[i - 1][1] = adjustedStart;
            }
        }
    }

    private static void alignThreadedFrameRangesToTokenBoundaries(
            int[][] frameRanges,
            String storyText) {
        if (frameRanges == null || frameRanges.length <= 1 || storyText == null
                || storyText.isEmpty()) {
            return;
        }
        int storyLength = storyText.length();
        for (int i = 0; i < frameRanges.length - 1; i++) {
            int boundary = clamp(frameRanges[i][1], 0, storyLength);
            if (!isInsideToken(storyText, boundary)) continue;
            int aligned = nextTokenBoundary(storyText, boundary);
            if (aligned <= boundary || aligned > storyLength) continue;
            frameRanges[i][1] = aligned;
            frameRanges[i + 1][0] = aligned;
        }
    }

    private static boolean isInsideToken(String text, int index) {
        if (text == null || index <= 0 || index >= text.length()) return false;
        char prev = text.charAt(index - 1);
        char curr = text.charAt(index);
        if (isOpeningAttachedTokenPunctuation(prev) && isTokenCoreChar(curr)) return true;
        return isTokenCoreChar(prev) && isTokenCoreChar(curr);
    }

    private static int nextTokenBoundary(String text, int index) {
        int i = Math.max(0, Math.min(index, text.length()));
        while (i < text.length() && isOpeningAttachedTokenPunctuation(text.charAt(i))) {
            i++;
        }
        while (i < text.length() && isTokenCoreChar(text.charAt(i))) {
            i++;
        }
        int punctuationBudget = 4;
        while (i < text.length() && punctuationBudget-- > 0) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c) || Character.isISOControl(c)) break;
            if (!isAttachedTokenPunctuation(c)) break;
            i++;
        }
        return i;
    }

    private static boolean isTokenCoreChar(char c) {
        return Character.isLetterOrDigit(c)
                || c == '\''
                || c == '\u2019';
    }

    private static boolean isOpeningAttachedTokenPunctuation(char c) {
        switch (c) {
            case '(':
            case '[':
            case '{':
            case '"':
            case '\'':
            case '\u2018':
            case '\u201C':
                return true;
            default:
                return false;
        }
    }

    private static boolean isAttachedTokenPunctuation(char c) {
        switch (c) {
            case ')':
            case ']':
            case '}':
            case ':':
            case ';':
            case ',':
            case '.':
            case '?':
            case '!':
                return true;
            default:
                return false;
        }
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static boolean hasResolvedFrameCharacterRange(ResolvedTextFrame frame) {
        return frame != null && frame.paragraphStart() >= 0 && frame.paragraphEnd() >= frame.paragraphStart();
    }

    private static boolean startsWithInlineObjectOrBoundary(String text) {
        if (text == null || text.isEmpty()) return false;
        int i = 0;
        while (i < text.length()) {
            char c = text.charAt(i);
            if (c == '\uFEFF' || Character.isWhitespace(c) || Character.isISOControl(c)) {
                i++;
                continue;
            }
            return c == '\uFFFC';
        }
        return false;
    }

    private static int findFrameStart(String storyText, String visibleText, int searchFrom) {
        if (storyText == null || visibleText == null || visibleText.isEmpty()) return -1;
        int from = Math.max(0, searchFrom - FRAME_RANGE_REWIND_TOLERANCE);
        int maxLen = Math.min(20, visibleText.length());
        for (int len = maxLen; len >= 4; len--) {
            String key = trimTrailingFrameBoundaryChars(visibleText.substring(0, len));
            if (key.length() < 4) continue;
            int found = storyText.indexOf(key, from);
            if (found >= 0) return found;
        }
        return -1;
    }

    private static int findFirstVisibleStoryStart(
            String storyText,
            String visibleText,
            int searchFrom,
            int resolvedStart) {
        if (storyText == null || storyText.isEmpty()
                || visibleText == null || visibleText.isEmpty()) {
            return -1;
        }
        int windowBase = Math.min(
                clamp(searchFrom, 0, storyText.length()),
                clamp(resolvedStart, 0, storyText.length()));
        int windowStart = Math.max(0, windowBase - FRAME_RANGE_REWIND_TOLERANCE);
        int windowEnd = Math.min(
                storyText.length(),
                Math.max(clamp(searchFrom, 0, storyText.length()), clamp(resolvedStart, 0, storyText.length()))
                        + Math.max(visibleText.length(), FRAME_RANGE_REWIND_TOLERANCE));
        NormalizedFrameText normalizedStory = normalizeForFrameRangeSearch(storyText);
        NormalizedFrameText normalizedVisible = normalizeForFrameRangeSearch(visibleText);
        if (normalizedStory.text.isEmpty() || normalizedVisible.text.isEmpty()) {
            return -1;
        }
        for (int offset = 0; offset < normalizedVisible.text.length(); offset++) {
            char startChar = normalizedVisible.text.charAt(offset);
            if (Character.isWhitespace(startChar) || Character.isISOControl(startChar)) {
                continue;
            }
            int remaining = normalizedVisible.text.length() - offset;
            int maxLen = Math.min(40, remaining);
            for (int len = maxLen; len >= 8; len--) {
                String key = trimFrameBoundaryChars(normalizedVisible.text.substring(offset, offset + len));
                if (!isUsefulStoryMatchKey(key)) continue;
                int found = normalizedStory.text.indexOf(key);
                while (found >= 0) {
                    int originalStart = normalizedStory.originalOffset(found);
                    if (originalStart >= windowStart && originalStart <= windowEnd) {
                        return originalStart;
                    }
                    found = normalizedStory.text.indexOf(key, found + 1);
                }
            }
        }
        return -1;
    }

    private static NormalizedFrameText normalizeForFrameRangeSearch(String text) {
        if (text == null || text.isEmpty()) {
            return new NormalizedFrameText("", new int[0]);
        }
        StringBuilder normalized = new StringBuilder(text.length());
        List<Integer> offsets = new ArrayList<>();
        boolean pendingSpace = false;
        int pendingSpaceOffset = -1;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isISOControl(c) || Character.isWhitespace(c)
                    || c == '\u2003' || c == '\u2007' || c == '\u2009' || c == '\u00A0') {
                if (normalized.length() > 0) {
                    pendingSpace = true;
                    if (pendingSpaceOffset < 0) pendingSpaceOffset = i;
                }
                continue;
            }
            if (pendingSpace) {
                normalized.append(' ');
                offsets.add(pendingSpaceOffset >= 0 ? pendingSpaceOffset : i);
                pendingSpace = false;
                pendingSpaceOffset = -1;
            }
            normalized.append(c);
            offsets.add(i);
        }
        int[] map = new int[offsets.size()];
        for (int i = 0; i < offsets.size(); i++) {
            map[i] = offsets.get(i);
        }
        return new NormalizedFrameText(normalized.toString().trim(), map);
    }

    private static final class NormalizedFrameText {
        final String text;
        final int[] originalOffsets;

        NormalizedFrameText(String text, int[] originalOffsets) {
            this.text = text != null ? text : "";
            this.originalOffsets = originalOffsets != null ? originalOffsets : new int[0];
        }

        int originalOffset(int normalizedIndex) {
            if (normalizedIndex < 0 || normalizedIndex >= originalOffsets.length) return -1;
            return originalOffsets[normalizedIndex];
        }
    }

    private static boolean isUsefulStoryMatchKey(String key) {
        if (key == null || key.length() < 8) return false;
        for (int i = 0; i < key.length(); i++) {
            if (Character.isLetterOrDigit(key.charAt(i))) return true;
        }
        return false;
    }

    private static String trimFrameBoundaryChars(String text) {
        if (text == null || text.isEmpty()) return text;
        int start = 0;
        int end = text.length();
        while (start < end) {
            char c = text.charAt(start);
            if (Character.isWhitespace(c) || Character.isISOControl(c)
                    || c == '\u2003' || c == '\u2007' || c == '\u2009' || c == '\u00A0') {
                start++;
            } else {
                break;
            }
        }
        while (end > start) {
            char c = text.charAt(end - 1);
            if (Character.isWhitespace(c) || Character.isISOControl(c)
                    || c == '\u2003' || c == '\u2007' || c == '\u2009' || c == '\u00A0') {
                end--;
            } else {
                break;
            }
        }
        StringBuilder cleaned = new StringBuilder(end - start);
        boolean pendingSpace = false;
        for (int i = start; i < end; i++) {
            char c = text.charAt(i);
            if (Character.isISOControl(c) || Character.isWhitespace(c)
                    || c == '\u2003' || c == '\u2007' || c == '\u2009' || c == '\u00A0') {
                pendingSpace = cleaned.length() > 0;
                continue;
            }
            if (pendingSpace) {
                cleaned.append(' ');
                pendingSpace = false;
            }
            cleaned.append(c);
        }
        return cleaned.toString().trim();
    }

    private static String trimTrailingFrameBoundaryChars(String text) {
        if (text == null || text.isEmpty()) return text;
        int end = text.length();
        while (end > 0) {
            char c = text.charAt(end - 1);
            if (Character.isWhitespace(c) || Character.isISOControl(c)
                    || c == '\u2003' || c == '\u2007' || c == '\u2009' || c == '\u00A0') {
                end--;
            } else {
                break;
            }
        }
        return text.substring(0, end);
    }

    private static String normalizeFrameTextForRangeMatching(
            ResolvedBuildContext ctx,
            ResolvedTextFrame frame,
            String text) {
        if (text == null || text.isEmpty()) return text;
        List<String> inlineAnchorTexts = semanticInlineAnchorTexts(ctx, frame);
        int inlineAnchorIndex = 0;
        StringBuilder sb = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '\uFFFC') {
                String replacement = inlineAnchorIndex < inlineAnchorTexts.size()
                        ? inlineAnchorTexts.get(inlineAnchorIndex)
                        : null;
                inlineAnchorIndex++;
                if (replacement != null && !replacement.isEmpty()) {
                    sb.append(replacement);
                }
                continue;
            }
            if (c == '\n' || c == '\r') {
                continue;
            }
            if (Character.isISOControl(c) && c != '\t' && c != '\u0007' && c != '\u0008') {
                continue;
            }
            sb.append(c);
        }
        return sb.toString();
    }

    private static List<String> semanticInlineAnchorTexts(
            ResolvedBuildContext ctx,
            ResolvedTextFrame frame) {
        List<String> texts = new ArrayList<>();
        if (ctx == null || ctx.resolvedData == null || frame == null || frame.storyId() == null) {
            return texts;
        }
        ResolvedStory story = ctx.resolvedData.getStory(frame.storyId());
        if (story == null || story.paragraphs() == null) return texts;
        for (ResolvedParagraph paragraph : story.paragraphs()) {
            if (paragraph == null || paragraph.runs() == null) continue;
            for (ResolvedRun run : paragraph.runs()) {
                if (run == null || !run.isInlineAnchor() || run.anchoredObjectId() == null) continue;
                SimpleButtonLabelPlan plan = ctx.simpleButtonLabelPlan(run.anchoredObjectId());
                if (plan != null && plan.labelText != null && !plan.labelText.isEmpty()) {
                    texts.add(plan.labelText);
                } else {
                    texts.add("\uFFFC");
                }
            }
        }
        return texts;
    }

    private static int resolvedFrameStartOffset(ResolvedTextFrame frame, int fallback, int storyLength) {
        if (frame == null || frame.paragraphStart() < 0) return fallback;
        int resolvedStart = clamp(frame.paragraphStart(), 0, storyLength);
        return resolvedStart >= fallback ? resolvedStart : fallback;
    }
}
