package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.stage3;

import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ResolvedBuildContext;
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

    public static boolean isBadgeShellGraphicBehind(RenderedGroup rg) {
        return rg != null && "inline_badge".equals(rg.reason());
    }

    public static boolean isTextOwnedVisualShell(RenderedGroup rg) {
        if (rg == null) return false;
        if (isTextOwnedRenderedContent(rg)) return false;
        if (!"hwpx_tf".equals(rg.textOwner())) return false;
        if (!"indesign_png".equals(rg.visualOwner())) return false;
        if (Boolean.TRUE.equals(rg.containsText()) || Boolean.TRUE.equals(rg.containsEditableText())) return false;
        return isTextFrameVisualShellReason(rg.reason());
    }

    public static boolean isTextOwnedRenderedContent(RenderedGroup rg) {
        if (rg == null) return false;
        if (!"hwpx_tf".equals(rg.textOwner())) return false;
        if (!"indesign_png".equals(rg.visualOwner())) return false;
        if (Boolean.TRUE.equals(rg.containsText()) || Boolean.TRUE.equals(rg.containsEditableText())) return false;
        String reason = rg.reason();
        if (reason == null) return false;
        return reason.contains("mixed_group_text_hidden")
                || reason.contains("image_group_text_hidden")
                || reason.contains("complex_graphic_text_hidden");
    }

    public static boolean isEditableLabelShellCandidate(RenderedGroup rg) {
        if (rg == null || !isPageObject(rg)) return false;
        if (Boolean.TRUE.equals(rg.containsText()) || Boolean.TRUE.equals(rg.containsEditableText())) return false;
        if ("indesign_png".equals(rg.textOwner())) return false;
        if (Boolean.FALSE.equals(rg.placementAllowed())) return false;
        if (!"indesign_png".equals(rg.visualOwner())) return false;
        if (!isTextFrameVisualShellReason(rg.reason())) return false;
        return true;
    }

    public static boolean isTextFrameVisualShellReason(String reason) {
        if (reason == null) return false;
        return reason.contains("decoration")
                || reason.contains("text_hidden")
                || reason.contains("visual_shell")
                || reason.contains("textframe_visual_shell")
                || reason.contains("complex_graphic");
    }

    public static boolean isPageObject(RenderedGroup rg) {
        if (rg == null) return false;
        String t = rg.itemType();
        if ("page_object".equals(t)) return true;
        if (t != null) return false;
        String f = rg.file();
        return f != null && (f.contains("img_") || f.contains("deco_")
                || f.contains("shape_") || f.contains("graphic_") || f.contains("master_")
                || f.contains("haseera_"));
    }
}
