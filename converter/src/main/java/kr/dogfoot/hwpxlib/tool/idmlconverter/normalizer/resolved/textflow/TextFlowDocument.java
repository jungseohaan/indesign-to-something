package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.textflow;

import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedParagraph;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedRun;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Planner-declared TextFlow model before placement into frames, cells, or shells. */
public final class TextFlowDocument {
    public final List<TextFlowUnit> units = new ArrayList<>();

    public static final class TextFlowUnit {
        public String storyId;
        public String textOwner;
        public final List<String> ownerTextFrameIds = new ArrayList<>();
        public final List<Integer> ownerPageIndexes = new ArrayList<>();
        public final List<TextFlowParagraph> paragraphs = new ArrayList<>();
    }

    public static final class TextFlowParagraph {
        public transient ResolvedParagraph sourceParagraph;
        public int index;
        public String styleName;
        public String justification;
        public final List<TextFlowAtom> atoms = new ArrayList<>();
    }

    public abstract static class TextFlowAtom {
        public transient ResolvedRun sourceRun;
        public int index;
    }

    public static final class TextAtom extends TextFlowAtom {
        public String text;
        public String fontFamily;
        public Double fontSize;
        public String fontStyle;
        public String fillColor;
        public String charStyle;
        public Double tracking;
        public Double horizontalScale;
        public Double baselineShift;
    }

    public static final class InlineSlotAtom extends TextFlowAtom {
        public Integer anchoredObjectId;
        public String sourceStatus;
        public String sourceType;
        public Integer sourcePageIndex;
        public String sourceLayerName;
        public Boolean sourceInline;
        public String sourceStoryAnchorPlacement;
        public Boolean sourceHidden;
        public double[] sourceBounds;
        public String planTextAction;
        public String planVisualAction;
        public String planPlacement;
        public String planMaterialization;
        public String planReason;
        public TextFlowUnit nestedFlow;
    }

    public List<TextFlowUnit> units() {
        return Collections.unmodifiableList(units);
    }

    public TextFlowUnit byStoryId(String storyId) {
        if (storyId == null) return null;
        for (TextFlowUnit unit : units) {
            if (storyId.equals(unit.storyId)) return unit;
        }
        return null;
    }

    public List<TextFlowUnit> byOwnerTextFrameId(String textFrameId) {
        if (textFrameId == null) return Collections.emptyList();
        List<TextFlowUnit> out = new ArrayList<>();
        for (TextFlowUnit unit : units) {
            if (unit != null && unit.ownerTextFrameIds.contains(textFrameId)) {
                out.add(unit);
            }
        }
        return Collections.unmodifiableList(out);
    }

    public ResolvedRun firstVisibleTextRunByStoryId(String storyId) {
        TextFlowUnit unit = byStoryId(storyId);
        if (unit == null) return null;
        return firstVisibleTextRun(unit);
    }

    public boolean hasInlineSlot(int anchoredObjectId) {
        return inlineSlotCarrier(anchoredObjectId) != null;
    }

    public InlineSlotCarrier inlineSlotCarrier(int anchoredObjectId) {
        for (TextFlowUnit unit : units) {
            InlineSlotCarrier carrier = unitInlineSlotCarrier(unit, anchoredObjectId);
            if (carrier != null) return carrier;
        }
        return null;
    }

    private static InlineSlotCarrier unitInlineSlotCarrier(TextFlowUnit unit, int anchoredObjectId) {
        if (unit == null) return null;
        for (TextFlowParagraph paragraph : unit.paragraphs) {
            if (paragraph == null) continue;
            for (TextFlowAtom atom : paragraph.atoms) {
                if (!(atom instanceof InlineSlotAtom)) continue;
                InlineSlotAtom slot = (InlineSlotAtom) atom;
                if (slot.anchoredObjectId != null && slot.anchoredObjectId == anchoredObjectId) {
                    return new InlineSlotCarrier(anchoredObjectId, unit, paragraph, slot);
                }
                InlineSlotCarrier nested = unitInlineSlotCarrier(slot.nestedFlow, anchoredObjectId);
                if (nested != null) return nested;
            }
        }
        return null;
    }

    private static ResolvedRun firstVisibleTextRun(TextFlowUnit unit) {
        if (unit == null) return null;
        for (TextFlowParagraph paragraph : unit.paragraphs) {
            if (paragraph == null) continue;
            for (TextFlowAtom atom : paragraph.atoms) {
                if (atom instanceof TextAtom) {
                    TextAtom textAtom = (TextAtom) atom;
                    String text = textAtom.text;
                    if (text == null) continue;
                    String visible = text
                            .replace("\uFFFC", "")
                            .replace("\r", "")
                            .replace("\n", "")
                            .trim();
                    if (!visible.isEmpty() && textAtom.sourceRun != null) {
                        return textAtom.sourceRun;
                    }
                } else if (atom instanceof InlineSlotAtom) {
                    ResolvedRun nested = firstVisibleTextRun(((InlineSlotAtom) atom).nestedFlow);
                    if (nested != null) return nested;
                }
            }
        }
        return null;
    }

    public static final class InlineSlotCarrier {
        public final int anchorId;
        public final TextFlowUnit unit;
        public final TextFlowParagraph paragraph;
        public final InlineSlotAtom slot;

        private InlineSlotCarrier(
                int anchorId,
                TextFlowUnit unit,
                TextFlowParagraph paragraph,
                InlineSlotAtom slot) {
            this.anchorId = anchorId;
            this.unit = unit;
            this.paragraph = paragraph;
            this.slot = slot;
        }
    }
}
