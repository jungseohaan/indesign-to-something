package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.textflow;

import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedParagraph;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedRun;

import java.util.ArrayList;
import java.util.List;

/**
 * Diagnostics-only representation of story text before it is bound to a
 * TextFrame, table cell, or shell container.
 */
public final class TextFlowDiagnostics {
    public final List<TextFlow> flows = new ArrayList<>();
    public final List<String> warnings = new ArrayList<>();

    public static final class TextFlow {
        public String storyId;
        public String textOwner;
        public final List<String> ownerTextFrameIds = new ArrayList<>();
        public final List<Integer> ownerPageIndexes = new ArrayList<>();
        public final List<TextFlowParagraph> paragraphs = new ArrayList<>();
        public int textLength;
        public int inlineSlotCount;
    }

    public static final class TextFlowParagraph {
        public transient ResolvedParagraph sourceParagraph;
        public int index;
        public String styleName;
        public String justification;
        public String generatedPrefixText;
        public final List<TextFlowRun> runs = new ArrayList<>();
    }

    public static final class TextFlowRun {
        public transient ResolvedRun sourceRun;
        public int index;
        public String kind;
        public String text;
        public Integer anchoredObjectId;
        public String fontFamily;
        public Double fontSize;
        public String fontStyle;
        public String fillColor;
        public String charStyle;
        public Double tracking;
        public Double horizontalScale;
        public Double baselineShift;
        public String planTextAction;
        public String planVisualAction;
        public String planPlacement;
        public String planMaterialization;
        public String planReason;
        public String sourceStatus;
        public String sourceType;
        public Integer sourcePageIndex;
        public String sourceLayerName;
        public Boolean sourceInline;
        public String sourceStoryAnchorPlacement;
        public Boolean sourceHidden;
        public double[] sourceBounds;
    }
}
