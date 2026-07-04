package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership;

/**
 * Canonical HWPX plane mapping for visual layers.
 *
 * <p>Stage 1 owns this decision. Later stages may only ask this helper while
 * adapting an already decided {@link ObjectPlan} to executable HWPX records.</p>
 */
public final class VisualPlanePolicy {
    private VisualPlanePolicy() {
    }

    public static boolean isInFrontLayer(VisualLayer layer) {
        return layer == VisualLayer.CONTAINER_FACE
                || layer == VisualLayer.TEXT_CARD_BACKDROP
                || layer == VisualLayer.LABEL_CONNECTOR_BACKDROP
                || layer == VisualLayer.LABEL_BACKDROP
                || layer == VisualLayer.LABEL_OVERLAY_BACKDROP
                || layer == VisualLayer.CONTENT_VISUAL
                || layer == VisualLayer.CONTAINER_OUTLINE
                || layer == VisualLayer.FOREGROUND_MASK;
    }

    public static boolean isInFrontLayerName(String layer) {
        return "CONTAINER_FACE".equals(layer)
                || "TEXT_CARD_BACKDROP".equals(layer)
                || "LABEL_CONNECTOR_BACKDROP".equals(layer)
                || "LABEL_BACKDROP".equals(layer)
                || "LABEL_OVERLAY_BACKDROP".equals(layer)
                || "CONTENT_VISUAL".equals(layer)
                || "CONTAINER_OUTLINE".equals(layer)
                || "FOREGROUND_MASK".equals(layer);
    }

    public static boolean isBehindTextLayer(VisualLayer layer) {
        return layer == VisualLayer.PAGE_BACKGROUND
                || layer == VisualLayer.CONTAINER_BACKDROP
                || layer == VisualLayer.CONTENT_BACKDROP;
    }

    public static boolean isBehindTextLayerName(String layer) {
        return "PAGE_BACKGROUND".equals(layer)
                || "CONTAINER_BACKDROP".equals(layer)
                || "CONTENT_BACKDROP".equals(layer);
    }
}
