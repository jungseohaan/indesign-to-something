package kr.dogfoot.hwpxlib.tool.idmlconverter.converter;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTInlineObject;

/**
 * Shared execution policy for objects anchored inside a text story.
 *
 * <p>If an InDesign object remains in the text flow, HWPX must treat it as a
 * character-sized participant in the same line box.  The same predicate drives
 * both shape position flags and paragraph line-spacing compensation so inline
 * shells do not visually collide with adjacent text lines.</p>
 */
final class InlineFlowPolicy {
    private InlineFlowPolicy() {
    }

    static boolean usesNonFlowWrapping(ASTInlineObject obj) {
        if (obj == null) return false;
        if (isLineNeutralInlineMarker(obj)) return false;
        if (!obj.affectsLineSpacing()) return true;
        boolean isAnchored = "Anchored".equals(obj.anchoredPosition());
        boolean isAboveLine = obj.anchoredPosition() != null
                && obj.anchoredPosition().startsWith("AboveLine");
        String wrapMode = obj.textWrapMode();
        boolean hasExplicitWrap = wrapMode != null && !"None".equals(wrapMode);
        return isAboveLine || (isAnchored && hasExplicitWrap);
    }

    static boolean participatesInLineSpacing(ASTInlineObject obj) {
        if (obj == null) return false;
        if (obj.isOverlay()) return false;
        if (isLineNeutralInlineMarker(obj)) return false;
        if (!obj.affectsLineSpacing()) return false;
        return !usesNonFlowWrapping(obj);
    }

    static boolean isLineNeutralInlineMarker(ASTInlineObject obj) {
        if (obj == null || !obj.keepInline()) return false;
        if (obj.layoutOnlyInlineSlot()) return false;
        ASTInlineObject.ObjectKind kind = obj.kind();
        if (kind != ASTInlineObject.ObjectKind.IMAGE
                && kind != ASTInlineObject.ObjectKind.INLINE_BADGE_GROUP
                && kind != ASTInlineObject.ObjectKind.SPACER_RECT) {
            return false;
        }
        if (hasExplicitTextWrap(obj)) return false;
        long w = Math.max(obj.width(), obj.resolvedWidth());
        long h = Math.max(obj.height(), obj.resolvedHeight());
        if (w <= 0 || h <= 0) return false;
        return w <= 3000 && h <= 1800;
    }

    private static boolean hasExplicitTextWrap(ASTInlineObject obj) {
        String wrapMode = obj.textWrapMode();
        return wrapMode != null && !"None".equals(wrapMode);
    }
}
