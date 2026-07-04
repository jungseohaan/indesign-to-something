package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.stage3;

/**
 * Stage 3 placement execution plan.
 *
 * <p>The values in this object are already decided before visible output is
 * materialized. The executor must not reinterpret ownership, layer, or z-order.</p>
 */
public final class VisualPlacementPlan {
    public final long x;
    public final long y;
    public final long width;
    public final long height;
    public final int zOrder;
    public final String visualLayer;
    public final int sourceLayerIndex;
    public final boolean fromGroup;

    public VisualPlacementPlan(
            long x,
            long y,
            long width,
            long height,
            int zOrder,
            String visualLayer,
            int sourceLayerIndex,
            boolean fromGroup) {
        this.x = x;
        this.y = y;
        this.width = width;
        this.height = height;
        this.zOrder = zOrder;
        this.visualLayer = visualLayer;
        this.sourceLayerIndex = sourceLayerIndex;
        this.fromGroup = fromGroup;
    }

    public boolean hasPositiveSize() {
        return width > 0 && height > 0;
    }
}
