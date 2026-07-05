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
    private VisualPlanePolicy() {
    }

    public static boolean isInFrontLayer(VisualLayer layer) {
        return false;
    }

    public static boolean isInFrontLayerName(String layer) {
        return false;
    }

    public static boolean isBehindTextLayer(VisualLayer layer) {
        return layer != null;
    }

    public static boolean isBehindTextLayerName(String layer) {
        return layer != null && !layer.isEmpty();
    }

    public static boolean isBackgroundMaterialLayer(VisualLayer layer) {
        return layer == VisualLayer.PAGE_BACKGROUND;
    }

    public static boolean isBackgroundMaterialLayerName(String layer) {
        return "PAGE_BACKGROUND".equals(layer);
    }

    public static int textlessGraphicZOrder(VisualLayer layer, int sourceZOrder) {
        int z = Math.max(0, sourceZOrder);
        if (isBackgroundMaterialLayer(layer)) {
            return z;
        }
        return 100_000 + z;
    }

    public static int textlessGraphicZOrderName(String layer, int sourceZOrder) {
        int z = Math.max(0, sourceZOrder);
        if (isBackgroundMaterialLayerName(layer)) {
            return z;
        }
        return 100_000 + z;
    }
}
