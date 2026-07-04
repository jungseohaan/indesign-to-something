package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.textflow;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Lookup index for the Stage 2 TextFlow snapshot. */
public final class TextFlowIndex {
    private static final TextFlowIndex EMPTY = new TextFlowIndex(
            Collections.emptyMap(),
            Collections.emptyMap(),
            Collections.emptyMap());

    private final Map<String, TextFlowDiagnostics.TextFlow> byStoryId;
    private final Map<String, List<TextFlowDiagnostics.TextFlow>> byOwnerTextFrameId;
    private final Map<Integer, List<InlineSlotRef>> inlineSlotsByAnchorId;

    private TextFlowIndex(
            Map<String, TextFlowDiagnostics.TextFlow> byStoryId,
            Map<String, List<TextFlowDiagnostics.TextFlow>> byOwnerTextFrameId,
            Map<Integer, List<InlineSlotRef>> inlineSlotsByAnchorId) {
        this.byStoryId = byStoryId;
        this.byOwnerTextFrameId = byOwnerTextFrameId;
        this.inlineSlotsByAnchorId = inlineSlotsByAnchorId;
    }

    public static TextFlowIndex empty() {
        return EMPTY;
    }

    public static TextFlowIndex from(TextFlowDiagnostics diagnostics) {
        if (diagnostics == null || diagnostics.flows == null || diagnostics.flows.isEmpty()) {
            return empty();
        }
        Map<String, TextFlowDiagnostics.TextFlow> byStory = new HashMap<>();
        Map<String, List<TextFlowDiagnostics.TextFlow>> byOwner = new HashMap<>();
        Map<Integer, List<InlineSlotRef>> byAnchor = new HashMap<>();

        for (TextFlowDiagnostics.TextFlow flow : diagnostics.flows) {
            if (flow == null) continue;
            if (flow.storyId != null) {
                byStory.put(flow.storyId, flow);
            }
            for (String ownerId : flow.ownerTextFrameIds) {
                if (ownerId == null) continue;
                byOwner.computeIfAbsent(ownerId, k -> new ArrayList<>()).add(flow);
            }
            indexInlineSlots(flow, byAnchor);
        }

        return new TextFlowIndex(
                Collections.unmodifiableMap(byStory),
                freezeFlowListMap(byOwner),
                freezeSlotListMap(byAnchor));
    }

    public TextFlowDiagnostics.TextFlow byStoryId(String storyId) {
        return storyId != null ? byStoryId.get(storyId) : null;
    }

    public List<TextFlowDiagnostics.TextFlow> byOwnerTextFrameId(String textFrameId) {
        List<TextFlowDiagnostics.TextFlow> flows =
                textFrameId != null ? byOwnerTextFrameId.get(textFrameId) : null;
        return flows != null ? flows : Collections.emptyList();
    }

    public List<InlineSlotRef> inlineSlotsByAnchorId(int anchorId) {
        List<InlineSlotRef> slots = inlineSlotsByAnchorId.get(anchorId);
        return slots != null ? slots : Collections.emptyList();
    }

    public int flowCount() {
        return byStoryId.size();
    }

    public int indexedInlineAnchorCount() {
        return inlineSlotsByAnchorId.size();
    }

    private static void indexInlineSlots(
            TextFlowDiagnostics.TextFlow flow,
            Map<Integer, List<InlineSlotRef>> byAnchor) {
        for (TextFlowDiagnostics.TextFlowParagraph paragraph : flow.paragraphs) {
            if (paragraph == null || paragraph.runs == null) continue;
            for (TextFlowDiagnostics.TextFlowRun run : paragraph.runs) {
                if (run == null || run.anchoredObjectId == null) continue;
                if (!"INLINE_SLOT".equals(run.kind)) continue;
                InlineSlotRef ref = new InlineSlotRef(
                        flow.storyId,
                        paragraph.index,
                        run.index,
                        run);
                byAnchor.computeIfAbsent(run.anchoredObjectId, k -> new ArrayList<>()).add(ref);
            }
        }
    }

    private static Map<String, List<TextFlowDiagnostics.TextFlow>> freezeFlowListMap(
            Map<String, List<TextFlowDiagnostics.TextFlow>> source) {
        Map<String, List<TextFlowDiagnostics.TextFlow>> out = new HashMap<>();
        for (Map.Entry<String, List<TextFlowDiagnostics.TextFlow>> entry : source.entrySet()) {
            out.put(entry.getKey(), Collections.unmodifiableList(new ArrayList<>(entry.getValue())));
        }
        return Collections.unmodifiableMap(out);
    }

    private static Map<Integer, List<InlineSlotRef>> freezeSlotListMap(
            Map<Integer, List<InlineSlotRef>> source) {
        Map<Integer, List<InlineSlotRef>> out = new HashMap<>();
        for (Map.Entry<Integer, List<InlineSlotRef>> entry : source.entrySet()) {
            out.put(entry.getKey(), Collections.unmodifiableList(new ArrayList<>(entry.getValue())));
        }
        return Collections.unmodifiableMap(out);
    }

    public static final class InlineSlotRef {
        public final String storyId;
        public final int paragraphIndex;
        public final int runIndex;
        public final TextFlowDiagnostics.TextFlowRun run;

        private InlineSlotRef(
                String storyId,
                int paragraphIndex,
                int runIndex,
                TextFlowDiagnostics.TextFlowRun run) {
            this.storyId = storyId;
            this.paragraphIndex = paragraphIndex;
            this.runIndex = runIndex;
            this.run = run;
        }
    }
}
