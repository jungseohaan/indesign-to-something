package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.textflow;

import java.util.HashSet;
import java.util.Set;

/** Builds the reusable TextFlow placement model from the Stage 1 snapshot. */
public final class TextFlowDocumentBuilder {
    private TextFlowDocumentBuilder() {}

    public static TextFlowDocument build(TextFlowDiagnostics diagnostics, TextFlowIndex index) {
        TextFlowDocument document = new TextFlowDocument();
        if (diagnostics == null || diagnostics.flows == null) return document;
        TextFlowIndex lookup = index != null ? index : TextFlowIndex.from(diagnostics);
        for (TextFlowDiagnostics.TextFlow flow : diagnostics.flows) {
            TextFlowDocument.TextFlowUnit unit = buildUnit(flow, lookup, new HashSet<String>());
            if (unit != null) document.units.add(unit);
        }
        return document;
    }

    private static TextFlowDocument.TextFlowUnit buildUnit(
            TextFlowDiagnostics.TextFlow flow,
            TextFlowIndex index,
            Set<String> stack) {
        if (flow == null) return null;
        String storyId = flow.storyId;
        if (storyId != null && !stack.add(storyId)) {
            return shallowUnit(flow);
        }

        TextFlowDocument.TextFlowUnit unit = shallowUnit(flow);
        for (TextFlowDiagnostics.TextFlowParagraph paragraph : flow.paragraphs) {
            if (paragraph == null) continue;
            TextFlowDocument.TextFlowParagraph out = new TextFlowDocument.TextFlowParagraph();
            out.sourceParagraph = paragraph.sourceParagraph;
            out.index = paragraph.index;
            out.styleName = paragraph.styleName;
            out.justification = paragraph.justification;
            out.generatedPrefixText = paragraph.generatedPrefixText;
            for (TextFlowDiagnostics.TextFlowRun run : paragraph.runs) {
                TextFlowDocument.TextFlowAtom atom = buildAtom(run, index, stack);
                if (atom != null) out.atoms.add(atom);
            }
            unit.paragraphs.add(out);
        }

        if (storyId != null) stack.remove(storyId);
        return unit;
    }

    private static TextFlowDocument.TextFlowUnit shallowUnit(TextFlowDiagnostics.TextFlow flow) {
        TextFlowDocument.TextFlowUnit unit = new TextFlowDocument.TextFlowUnit();
        if (flow == null) return unit;
        unit.storyId = flow.storyId;
        unit.textOwner = flow.textOwner;
        unit.ownerTextFrameIds.addAll(flow.ownerTextFrameIds);
        unit.ownerPageIndexes.addAll(flow.ownerPageIndexes);
        return unit;
    }

    private static TextFlowDocument.TextFlowAtom buildAtom(
            TextFlowDiagnostics.TextFlowRun run,
            TextFlowIndex index,
            Set<String> stack) {
        if (run == null) return null;
        if ("INLINE_SLOT".equals(run.kind)) {
            TextFlowDocument.InlineSlotAtom atom = new TextFlowDocument.InlineSlotAtom();
            atom.sourceRun = run.sourceRun;
            atom.index = run.index;
            atom.anchoredObjectId = run.anchoredObjectId;
            atom.sourceStatus = run.sourceStatus;
            atom.sourceType = run.sourceType;
            atom.sourcePageIndex = run.sourcePageIndex;
            atom.sourceLayerName = run.sourceLayerName;
            atom.sourceInline = run.sourceInline;
            atom.sourceStoryAnchorPlacement = run.sourceStoryAnchorPlacement;
            atom.sourceHidden = run.sourceHidden;
            atom.sourceBounds = run.sourceBounds != null ? run.sourceBounds.clone() : null;
            atom.planTextAction = run.planTextAction;
            atom.planVisualAction = run.planVisualAction;
            atom.planPlacement = run.planPlacement;
            atom.planMaterialization = run.planMaterialization;
            atom.planReason = run.planReason;
            atom.nestedFlow = nestedFlow(run.anchoredObjectId, index, stack);
            return atom;
        }

        TextFlowDocument.TextAtom atom = new TextFlowDocument.TextAtom();
        atom.sourceRun = run.sourceRun;
        atom.index = run.index;
        atom.text = run.text;
        atom.fontFamily = run.fontFamily;
        atom.fontSize = run.fontSize;
        atom.fontStyle = run.fontStyle;
        atom.fillColor = run.fillColor;
        atom.charStyle = run.charStyle;
        atom.tracking = run.tracking;
        atom.horizontalScale = run.horizontalScale;
        atom.baselineShift = run.baselineShift;
        return atom;
    }

    private static TextFlowDocument.TextFlowUnit nestedFlow(
            Integer anchoredObjectId,
            TextFlowIndex index,
            Set<String> stack) {
        if (anchoredObjectId == null || index == null) return null;
        String ownerId = String.valueOf(anchoredObjectId);
        java.util.List<TextFlowDiagnostics.TextFlow> owners = index.byOwnerTextFrameId(ownerId);
        if (owners.isEmpty()) return null;
        return buildUnit(owners.get(0), index, stack);
    }
}
