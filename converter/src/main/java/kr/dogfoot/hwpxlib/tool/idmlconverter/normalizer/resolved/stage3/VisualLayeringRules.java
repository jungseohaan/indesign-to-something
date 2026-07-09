package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.stage3;

import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ResolvedBuildContext;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.VisualLayer;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.VisualPlanePolicy;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.RenderedGroup;

/**
 * Stage 3 visual-layer predicates shared by placement planning bridges.
 */
public final class VisualLayeringRules {
    private VisualLayeringRules() {
    }

    public static boolean isCompletePngSimpleButtonLabel(ResolvedBuildContext ctx, RenderedGroup rg) {
        return ctx != null
                && ctx.resolvedData != null
                && ctx.resolvedData.shouldUseCompletePngForSimpleButtonLabel(rg);
    }

    public static boolean isInFrontLayer(VisualLayer layer) {
        return VisualPlanePolicy.isInFrontLayer(layer);
    }

    public static boolean isEditableLabelShellCandidate(RenderedGroup rg) {
        if (rg == null || !isPageObject(rg)) return false;
        if (Boolean.TRUE.equals(rg.containsText()) || Boolean.TRUE.equals(rg.containsEditableText())) return false;
        if ("indesign_png".equals(rg.textOwner())) return false;
        if (Boolean.FALSE.equals(rg.placementAllowed())) return false;
        if (!"indesign_png".equals(rg.visualOwner())) return false;
        return isStructuredTextShellCandidate(rg);
    }

    public static boolean isStructuredTextShellCandidate(RenderedGroup rg) {
        if (rg == null) return false;
        if ("TEXTLESS_SHELL_WITH_TF".equals(rg.atomicObjectKind())) return true;
        if ("pass.editable_textframe_visual_shells".equals(rg.planPassId())) return true;
        if (isStructuredShellRole(rg.slotRole())) return true;
        if (isStructuredShellRole(rg.compositeRole())) return true;
        return rg.hasEditableTextHiddenFromPng() && hasAnyEditableTextFrameId(rg);
    }

    private static boolean hasAnyEditableTextFrameId(RenderedGroup rg) {
        return rg != null
                && rg.editableTextFrameIds() != null
                && rg.editableTextFrameIds().length > 0;
    }

    private static boolean isStructuredShellRole(String value) {
        if (value == null || value.isEmpty()) return false;
        return "SHELL_SLOT".equals(value)
                || "TEXTLESS_SHELL_SLOT".equals(value)
                || "shell_slot_only".equals(value)
                || "direct_child_shell_slot".equals(value)
                || "background_shell_slot".equals(value)
                || "inline_textless_sibling_decoration_slot".equals(value)
                || "textframe_style_shell_slot".equals(value)
                || "native_parent_text_shell_slot".equals(value)
                || "source_declared_closed_text_shell".equals(value)
                || "table_carrier_textless_shell".equals(value)
                || "table_carrier_sibling_decoration".equals(value)
                || "clipped_placed_carrier_sibling_shell".equals(value)
                || "leaf_vector_shell_source".equals(value);
    }

    public static boolean isPageObject(RenderedGroup rg) {
        if (rg == null) return false;
        String t = rg.itemType();
        if (t == null || t.isEmpty()) {
            t = rg.type();
        }
        return "page_object".equals(t);
    }
}
