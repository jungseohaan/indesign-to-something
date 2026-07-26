package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership;

/**
 * Canonical HWPX plane mapping for visual material.
 *
 * <p>The source ownership policy now treats the historical visualLayer values
 * as diagnostic roles. HWPX execution uses three policy strata:
 * BACKGROUND_GRAPHIC below TEXTLESS_IMAGE_GROUP below TEXT_TABLE_STRUCTURE.
 * Later stages may ask this helper while adapting an already decided
 * {@link ObjectPlan}, but they must not promote diagnostic roles back into a
 * foreground graphic plane.</p>
 */
public final class VisualPlanePolicy {
    private static final int PLANE_STRIDE = 500_000_000;
    private static final int LAYER_STRIDE = 100_000;
    private static final int MAX_SOURCE_LAYER_BUCKET = 4095;
    private static final int MAX_SOURCE_Z_ORDER = 99_999;

    private VisualPlanePolicy() {
    }

    public static boolean isInFrontLayer(VisualLayer layer) {
        return layer == VisualLayer.CONTENT_VISUAL
                || layer == VisualLayer.CONTAINER_OUTLINE
                || layer == VisualLayer.FOREGROUND_MASK;
    }

    public static boolean isInFrontLayerName(String layer) {
        return "CONTENT_VISUAL".equals(layer)
                || "CONTAINER_OUTLINE".equals(layer)
                || "FOREGROUND_MASK".equals(layer);
    }

    public static boolean isBehindTextLayer(VisualLayer layer) {
        return layer != null && !isInFrontLayer(layer);
    }

    public static boolean isBehindTextLayerName(String layer) {
        return layer != null && !layer.isEmpty() && !isInFrontLayerName(layer);
    }

    public static boolean isBackgroundMaterialLayer(VisualLayer layer) {
        return layer == VisualLayer.PAGE_BACKGROUND;
    }

    public static boolean isBackgroundMaterialLayerName(String layer) {
        return "PAGE_BACKGROUND".equals(layer);
    }

    public static int textlessGraphicZOrder(VisualLayer layer, int sourceZOrder) {
        return textlessGraphicZOrder(layer, -1, sourceZOrder);
    }

    public static int textlessGraphicZOrder(
            VisualLayer layer,
            int sourceLayerIndex,
            int sourceZOrder) {
        return planeBandForLayer(layer)
                + samePlaneSourceLayerBand(sourceLayerIndex)
                + normalizedSourceZOrder(sourceZOrder);
    }

    public static int textlessGraphicZOrderName(String layer, int sourceZOrder) {
        return textlessGraphicZOrderName(layer, -1, sourceZOrder);
    }

    public static int textlessGraphicZOrderName(
            String layer,
            int sourceLayerIndex,
            int sourceZOrder) {
        return planeBandForLayerName(layer)
                + samePlaneSourceLayerBand(sourceLayerIndex)
                + normalizedSourceZOrder(sourceZOrder);
    }

    public static boolean isBackgroundZOrder(int zOrder) {
        return zOrder >= planeBandForPolicyPlane(0)
                && zOrder < planeBandForPolicyPlane(1);
    }

    public static boolean isDecorationZOrder(int zOrder) {
        return zOrder >= planeBandForPolicyPlane(1)
                && zOrder < planeBandForPolicyPlane(2);
    }

    public static boolean isContentZOrder(int zOrder) {
        return zOrder >= planeBandForPolicyPlane(1)
                && zOrder < planeBandForPolicyPlane(2);
    }

    public static int sourceZOrderComponent(int zOrder) {
        return normalizedSourceZOrder(zOrder % LAYER_STRIDE);
    }

    private static int planeBandForLayer(VisualLayer layer) {
        return planeBandForPolicyPlane(policyPlaneIndex(layer));
    }

    private static int planeBandForLayerName(String layer) {
        return planeBandForPolicyPlane(policyPlaneIndexName(layer));
    }

    private static int planeBandForPolicyPlane(int planeIndex) {
        return Math.max(0, planeIndex) * PLANE_STRIDE;
    }

    private static int policyPlaneIndex(VisualLayer layer) {
        if (isBackgroundMaterialLayer(layer)) {
            return 0;
        }
        return 1;
    }

    private static int policyPlaneIndexName(String layer) {
        if (isBackgroundMaterialLayerName(layer)) {
            return 0;
        }
        return 1;
    }

    private static int samePlaneSourceLayerBand(int sourceLayerIndex) {
        if (sourceLayerIndex < 0) return 0;
        int normalized = Math.min(sourceLayerIndex, MAX_SOURCE_LAYER_BUCKET);
        return (MAX_SOURCE_LAYER_BUCKET - normalized) * LAYER_STRIDE;
    }

    private static int normalizedSourceZOrder(int sourceZOrder) {
        return Math.max(0, Math.min(sourceZOrder, MAX_SOURCE_Z_ORDER));
    }
}
