package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership;

/**
 * Canonical Stage 1 role for {@link VisualAction#PLACE_TEXT_SHELL}.
 *
 * <p>Later stages must not infer what a text shell means from bounds, pixels,
 * page symptoms, or reason strings. They may only ask this role classifier and
 * then execute the already-decided {@link ObjectPlan}.</p>
 */
public enum ShellRole {
    NONE,
    BACKGROUND_SHELL,
    LABEL_SHELL,
    TEXT_OWNING_SHELL,
    CONTENT_SHELL;

    public static ShellRole from(ObjectPlan plan) {
        if (plan == null || plan.visualAction != VisualAction.PLACE_TEXT_SHELL) {
            return NONE;
        }
        if (isLabelLayer(plan.visualLayer)) {
            return LABEL_SHELL;
        }
        if (ownsEditableText(plan)) {
            return TEXT_OWNING_SHELL;
        }
        if (plan.visualPolicyLayer() == PolicyLayer.BACKGROUND) {
            return BACKGROUND_SHELL;
        }
        return CONTENT_SHELL;
    }

    public static boolean isTextShell(ObjectPlan plan) {
        return from(plan) != NONE;
    }

    public static boolean isBackgroundShell(ObjectPlan plan) {
        return from(plan) == BACKGROUND_SHELL;
    }

    public static boolean isLabelShell(ObjectPlan plan) {
        return from(plan) == LABEL_SHELL;
    }

    public static boolean isTextOwningShell(ObjectPlan plan) {
        return from(plan) == TEXT_OWNING_SHELL;
    }

    public static boolean isContentShell(ObjectPlan plan) {
        return from(plan) == CONTENT_SHELL;
    }

    public static boolean isCrossPageBackgroundCandidate(ObjectPlan plan) {
        return from(plan) == BACKGROUND_SHELL
                && plan.placement == Placement.FLOATING;
    }

    public static boolean ownsEditableText(ObjectPlan plan) {
        return plan != null
                && plan.textAction == TextAction.OWNED_BY_HWPX_TEXT
                && plan.ownedTextFrameIds != null
                && plan.ownedTextFrameIds.length > 0;
    }

    public static boolean isBackPlaneShell(ObjectPlan plan) {
        ShellRole role = from(plan);
        return role == BACKGROUND_SHELL
                || role == LABEL_SHELL
                || plan.visualLayer == VisualLayer.TEXT_CARD_BACKDROP
                || plan.visualLayer == VisualLayer.CONTAINER_BACKDROP;
    }

    private static boolean isLabelLayer(VisualLayer layer) {
        return layer == VisualLayer.LABEL_BACKDROP
                || layer == VisualLayer.LABEL_OVERLAY_BACKDROP;
    }
}
