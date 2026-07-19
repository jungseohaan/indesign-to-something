package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership;

import java.util.Arrays;

/** Source ownership policy object plan. */
public final class ObjectPlan {
    public String objectPlanId;
    public final int domId;
    public final String kind;
    public final String candidateId;
    public final String planPassId;
    public final String slotRole;
    public final int pageIndex;
    public final TextAction textAction;
    public final VisualAction visualAction;
    public final VisualLayer visualLayer;
    public final Placement placement;
    public final Integer renderId;
    public final int[] sourceObjectIds;
    public final int[] sourceRootObjectIds;
    public final int[] clusterSourceObjectIds;
    public final int[] omittedClusterSourceObjectIds;
    public final int[] visualSourceObjectIds;
    public final int[] styleSourceObjectIds;
    public final int[] exportSourceObjectIds;
    public final int[] hiddenVisualSourceObjectIds;
    public final int[] ownedTextFrameIds;
    public String[] ownedTextFrameIdKeys;
    public final int[] descendantVisualObjectIds;
    public final String sourceBundleKey;
    public final Materialization materialization;
    public final CoordinateSpace coordinateSpace;
    public final String anchorOwner;
    public final int zOrder;
    public final String reason;
    public final String file;
    public final double[] bounds;
    public final double[] renderSourceBounds;
    public final double[] cropSourceBounds;
    public final String sourceLayerId;
    public final String sourceLayerName;
    public final int sourceLayerIndex;
    public final boolean inlineSourceTreeClosed;
    public final int[] inlineFlowSourceObjectIds;
    public TextLayoutContract textLayoutContract;

    public static ObjectPlan legacyDefaulted(
            int domId,
            String kind,
            int pageIndex,
            TextAction textAction,
            VisualAction visualAction,
            VisualLayer visualLayer,
            Placement placement,
            Integer renderId,
            int[] sourceObjectIds,
            int zOrder,
            String reason,
            String file,
            double[] bounds) {
        return legacyDefaulted(domId, kind, pageIndex, textAction, visualAction,
                visualLayer, placement, renderId, sourceObjectIds, null, null,
                null, null, zOrder, reason, file, bounds, null, null, -1);
    }

    public static ObjectPlan legacyDefaulted(
            int domId,
            String kind,
            int pageIndex,
            TextAction textAction,
            VisualAction visualAction,
            VisualLayer visualLayer,
            Placement placement,
            Integer renderId,
            int[] sourceObjectIds,
            int[] visualSourceObjectIds,
            int[] ownedTextFrameIds,
            int[] descendantVisualObjectIds,
            String sourceBundleKey,
            int zOrder,
            String reason,
            String file,
            double[] bounds,
            String sourceLayerId,
            String sourceLayerName,
            int sourceLayerIndex) {
        return new ObjectPlan(domId, kind, pageIndex, textAction, visualAction,
                legacyDefaultVisualLayer(visualLayer), placement,
                renderId, sourceObjectIds, visualSourceObjectIds, null, ownedTextFrameIds,
                descendantVisualObjectIds, sourceBundleKey,
                legacyDefaultMaterialization(textAction, visualAction),
                legacyDefaultCoordinateSpace(placement),
                null, zOrder, reason, file, bounds, null, sourceLayerId, sourceLayerName,
                sourceLayerIndex);
    }

    public ObjectPlan(
            int domId,
            String kind,
            int pageIndex,
            TextAction textAction,
            VisualAction visualAction,
            VisualLayer visualLayer,
            Placement placement,
            Integer renderId,
            int[] sourceObjectIds,
            int[] visualSourceObjectIds,
            int[] styleSourceObjectIds,
            int[] ownedTextFrameIds,
            int[] descendantVisualObjectIds,
            String sourceBundleKey,
            Materialization materialization,
            CoordinateSpace coordinateSpace,
            String anchorOwner,
            int zOrder,
            String reason,
            String file,
            double[] bounds,
            String sourceLayerId,
            String sourceLayerName,
            int sourceLayerIndex) {
        this(domId, kind, pageIndex, textAction, visualAction, visualLayer, placement,
                renderId, sourceObjectIds, visualSourceObjectIds, styleSourceObjectIds,
                ownedTextFrameIds, descendantVisualObjectIds, sourceBundleKey,
                materialization, coordinateSpace, anchorOwner, zOrder, reason, file,
                bounds, null, sourceLayerId, sourceLayerName, sourceLayerIndex);
    }

    public ObjectPlan(
            int domId,
            String kind,
            int pageIndex,
            TextAction textAction,
            VisualAction visualAction,
            VisualLayer visualLayer,
            Placement placement,
            Integer renderId,
            int[] sourceObjectIds,
            int[] visualSourceObjectIds,
            int[] styleSourceObjectIds,
            int[] ownedTextFrameIds,
            int[] descendantVisualObjectIds,
            String sourceBundleKey,
            Materialization materialization,
            CoordinateSpace coordinateSpace,
            String anchorOwner,
            int zOrder,
            String reason,
            String file,
            double[] bounds,
            double[] renderSourceBounds,
            String sourceLayerId,
            String sourceLayerName,
            int sourceLayerIndex) {
        this(domId, kind, pageIndex, textAction, visualAction, visualLayer, placement,
                renderId, sourceObjectIds, visualSourceObjectIds, styleSourceObjectIds,
                null, null, null, null, null,
                ownedTextFrameIds, descendantVisualObjectIds, sourceBundleKey,
                materialization, coordinateSpace, anchorOwner, zOrder, reason, file,
                bounds, renderSourceBounds, null, sourceLayerId, sourceLayerName,
                sourceLayerIndex);
    }

    private ObjectPlan(
            int domId,
            String kind,
            int pageIndex,
            TextAction textAction,
            VisualAction visualAction,
            VisualLayer visualLayer,
            Placement placement,
            Integer renderId,
            int[] sourceObjectIds,
            int[] visualSourceObjectIds,
            int[] styleSourceObjectIds,
            int[] exportSourceObjectIds,
            int[] hiddenVisualSourceObjectIds,
            int[] sourceRootObjectIds,
            int[] clusterSourceObjectIds,
            int[] omittedClusterSourceObjectIds,
            int[] ownedTextFrameIds,
            int[] descendantVisualObjectIds,
            String sourceBundleKey,
            Materialization materialization,
            CoordinateSpace coordinateSpace,
            String anchorOwner,
            int zOrder,
            String reason,
            String file,
            double[] bounds,
            double[] renderSourceBounds,
            double[] cropSourceBounds,
            String sourceLayerId,
            String sourceLayerName,
            int sourceLayerIndex) {
        this(domId, kind, null, null, null, pageIndex, textAction, visualAction,
                visualLayer, placement, renderId, sourceObjectIds, visualSourceObjectIds,
                styleSourceObjectIds, exportSourceObjectIds, hiddenVisualSourceObjectIds,
                sourceRootObjectIds, clusterSourceObjectIds, omittedClusterSourceObjectIds,
                ownedTextFrameIds, descendantVisualObjectIds, sourceBundleKey,
                materialization, coordinateSpace, anchorOwner, zOrder, reason, file,
                bounds, renderSourceBounds, cropSourceBounds, sourceLayerId, sourceLayerName,
                sourceLayerIndex);
    }

    private ObjectPlan(
            int domId,
            String kind,
            String candidateId,
            String planPassId,
            String slotRole,
            int pageIndex,
            TextAction textAction,
            VisualAction visualAction,
            VisualLayer visualLayer,
            Placement placement,
            Integer renderId,
            int[] sourceObjectIds,
            int[] visualSourceObjectIds,
            int[] styleSourceObjectIds,
            int[] exportSourceObjectIds,
            int[] hiddenVisualSourceObjectIds,
            int[] sourceRootObjectIds,
            int[] clusterSourceObjectIds,
            int[] omittedClusterSourceObjectIds,
            int[] ownedTextFrameIds,
            int[] descendantVisualObjectIds,
            String sourceBundleKey,
            Materialization materialization,
            CoordinateSpace coordinateSpace,
            String anchorOwner,
            int zOrder,
            String reason,
            String file,
            double[] bounds,
            double[] renderSourceBounds,
            double[] cropSourceBounds,
            String sourceLayerId,
            String sourceLayerName,
            int sourceLayerIndex) {
        this.domId = domId;
        this.objectPlanId = null;
        this.kind = kind;
        this.candidateId = candidateId;
        this.planPassId = planPassId;
        this.slotRole = slotRole;
        this.pageIndex = pageIndex;
        this.textAction = textAction;
        this.visualAction = visualAction;
        this.visualLayer = visualLayer;
        this.placement = placement;
        this.renderId = renderId;
        this.sourceObjectIds = sourceObjectIds != null ? Arrays.copyOf(sourceObjectIds, sourceObjectIds.length) : new int[0];
        this.sourceRootObjectIds = sourceRootObjectIds != null
                ? Arrays.copyOf(sourceRootObjectIds, sourceRootObjectIds.length)
                : new int[0];
        this.clusterSourceObjectIds = clusterSourceObjectIds != null
                ? Arrays.copyOf(clusterSourceObjectIds, clusterSourceObjectIds.length)
                : new int[0];
        this.omittedClusterSourceObjectIds = omittedClusterSourceObjectIds != null
                ? Arrays.copyOf(omittedClusterSourceObjectIds, omittedClusterSourceObjectIds.length)
                : new int[0];
        this.visualSourceObjectIds = visualSourceObjectIds != null
                ? Arrays.copyOf(visualSourceObjectIds, visualSourceObjectIds.length)
                : Arrays.copyOf(this.sourceObjectIds, this.sourceObjectIds.length);
        this.styleSourceObjectIds = styleSourceObjectIds != null
                ? Arrays.copyOf(styleSourceObjectIds, styleSourceObjectIds.length)
                : new int[0];
        this.exportSourceObjectIds = exportSourceObjectIds != null
                ? Arrays.copyOf(exportSourceObjectIds, exportSourceObjectIds.length)
                : new int[0];
        this.hiddenVisualSourceObjectIds = hiddenVisualSourceObjectIds != null
                ? Arrays.copyOf(hiddenVisualSourceObjectIds, hiddenVisualSourceObjectIds.length)
                : new int[0];
        this.ownedTextFrameIds = ownedTextFrameIds != null
                ? Arrays.copyOf(ownedTextFrameIds, ownedTextFrameIds.length)
                : new int[0];
        this.ownedTextFrameIdKeys = new String[0];
        this.descendantVisualObjectIds = descendantVisualObjectIds != null
                ? Arrays.copyOf(descendantVisualObjectIds, descendantVisualObjectIds.length)
                : new int[0];
        this.sourceBundleKey = sourceBundleKey;
        this.materialization = materialization;
        this.coordinateSpace = coordinateSpace;
        this.anchorOwner = anchorOwner;
        this.zOrder = zOrder;
        this.reason = reason;
        this.file = file;
        this.bounds = bounds != null ? Arrays.copyOf(bounds, bounds.length) : null;
        this.renderSourceBounds = renderSourceBounds != null
                ? Arrays.copyOf(renderSourceBounds, renderSourceBounds.length)
                : null;
        this.cropSourceBounds = cropSourceBounds != null
                ? Arrays.copyOf(cropSourceBounds, cropSourceBounds.length)
                : null;
        this.sourceLayerId = sourceLayerId;
        this.sourceLayerName = sourceLayerName;
        this.sourceLayerIndex = sourceLayerIndex;
        this.inlineSourceTreeClosed = false;
        this.inlineFlowSourceObjectIds = new int[0];
        this.textLayoutContract = null;
    }

    private ObjectPlan(
            ObjectPlan base,
            boolean inlineSourceTreeClosed,
            int[] inlineFlowSourceObjectIds) {
        this.domId = base.domId;
        this.objectPlanId = base.objectPlanId;
        this.kind = base.kind;
        this.candidateId = base.candidateId;
        this.planPassId = base.planPassId;
        this.slotRole = base.slotRole;
        this.pageIndex = base.pageIndex;
        this.textAction = base.textAction;
        this.visualAction = base.visualAction;
        this.visualLayer = base.visualLayer;
        this.placement = base.placement;
        this.renderId = base.renderId;
        this.sourceObjectIds = Arrays.copyOf(base.sourceObjectIds, base.sourceObjectIds.length);
        this.sourceRootObjectIds = Arrays.copyOf(base.sourceRootObjectIds, base.sourceRootObjectIds.length);
        this.clusterSourceObjectIds = Arrays.copyOf(base.clusterSourceObjectIds, base.clusterSourceObjectIds.length);
        this.omittedClusterSourceObjectIds = Arrays.copyOf(
                base.omittedClusterSourceObjectIds,
                base.omittedClusterSourceObjectIds.length);
        this.visualSourceObjectIds = Arrays.copyOf(base.visualSourceObjectIds, base.visualSourceObjectIds.length);
        this.styleSourceObjectIds = Arrays.copyOf(base.styleSourceObjectIds, base.styleSourceObjectIds.length);
        this.exportSourceObjectIds = Arrays.copyOf(base.exportSourceObjectIds, base.exportSourceObjectIds.length);
        this.hiddenVisualSourceObjectIds = Arrays.copyOf(
                base.hiddenVisualSourceObjectIds,
                base.hiddenVisualSourceObjectIds.length);
        this.ownedTextFrameIds = Arrays.copyOf(base.ownedTextFrameIds, base.ownedTextFrameIds.length);
        this.ownedTextFrameIdKeys = copyStringArray(base.ownedTextFrameIdKeys);
        this.descendantVisualObjectIds = Arrays.copyOf(
                base.descendantVisualObjectIds,
                base.descendantVisualObjectIds.length);
        this.sourceBundleKey = base.sourceBundleKey;
        this.materialization = base.materialization;
        this.coordinateSpace = base.coordinateSpace;
        this.anchorOwner = base.anchorOwner;
        this.zOrder = base.zOrder;
        this.reason = base.reason;
        this.file = base.file;
        this.bounds = base.bounds != null ? Arrays.copyOf(base.bounds, base.bounds.length) : null;
        this.renderSourceBounds = base.renderSourceBounds != null
                ? Arrays.copyOf(base.renderSourceBounds, base.renderSourceBounds.length)
                : null;
        this.cropSourceBounds = base.cropSourceBounds != null
                ? Arrays.copyOf(base.cropSourceBounds, base.cropSourceBounds.length)
                : null;
        this.sourceLayerId = base.sourceLayerId;
        this.sourceLayerName = base.sourceLayerName;
        this.sourceLayerIndex = base.sourceLayerIndex;
        this.inlineSourceTreeClosed = inlineSourceTreeClosed;
        this.inlineFlowSourceObjectIds = inlineFlowSourceObjectIds != null
                ? Arrays.copyOf(inlineFlowSourceObjectIds, inlineFlowSourceObjectIds.length)
                : new int[0];
        this.textLayoutContract = base.textLayoutContract != null
                ? base.textLayoutContract.copy()
                : null;
    }

    private static VisualLayer legacyDefaultVisualLayer(VisualLayer visualLayer) {
        return visualLayer != null ? visualLayer : VisualLayer.CONTENT_VISUAL;
    }

    private static Materialization legacyDefaultMaterialization(TextAction textAction, VisualAction visualAction) {
        if (visualAction == VisualAction.PLACE_TABLE_STYLE) {
            return Materialization.HWPX_TABLE_STYLE;
        }
        if (visualAction == VisualAction.ABSORB_TEXT_STYLE || visualAction == VisualAction.DROP_VISUAL) {
            return Materialization.HWPX_TEXT;
        }
        if (visualAction == VisualAction.PLACE_TEXT_SHELL) {
            return Materialization.EXTRACTED_PNG_VECTOR;
        }
        if (textAction == TextAction.OWNED_BY_PNG) {
            return Materialization.COMPLETE_PNG;
        }
        return Materialization.EXTRACTED_PNG_VECTOR;
    }

    private static CoordinateSpace legacyDefaultCoordinateSpace(Placement placement) {
        return placement == Placement.INLINE ? CoordinateSpace.STORY_FLOW : CoordinateSpace.PAGE;
    }

    public boolean hasVisibleVisual() {
        return visualAction != VisualAction.DROP_VISUAL
                && visualAction != VisualAction.ABSORB_TEXT_STYLE
                && visualAction != VisualAction.PLACE_TABLE_STYLE;
    }

    public boolean hasVisibleText() {
        return textAction == TextAction.OWNED_BY_HWPX_TEXT
                || textAction == TextAction.HIDDEN_SEMANTIC;
    }

    public ShellRole shellRole() {
        return ShellRole.from(this);
    }

    public PolicyLayer visualPolicyLayer() {
        if (!hasVisibleVisual()) {
            return hasVisibleText() ? PolicyLayer.TEXT : PolicyLayer.CONTENT;
        }
        if (visualLayer == VisualLayer.PAGE_BACKGROUND) {
            return PolicyLayer.BACKGROUND;
        }
        if (visualLayer == VisualLayer.TEXT_CARD_BACKDROP
                || visualLayer == VisualLayer.CONTAINER_BACKDROP
                || visualLayer == VisualLayer.CONTAINER_FACE
                || visualLayer == VisualLayer.LABEL_CONNECTOR_BACKDROP
                || visualLayer == VisualLayer.LABEL_BACKDROP
                || visualLayer == VisualLayer.LABEL_OVERLAY_BACKDROP
                || visualLayer == VisualLayer.CONTAINER_OUTLINE
                || visualLayer == VisualLayer.FOREGROUND_MASK) {
            return PolicyLayer.DECORATION;
        }
        return PolicyLayer.CONTENT;
    }

    public String ownershipSlot() {
        String role = slotRole != null ? slotRole : "";
        if (visualAction == VisualAction.PLACE_TABLE_STYLE
                || materialization == Materialization.HWPX_TABLE_STYLE
                || role.contains("table_style")) {
            return "TABLE_STYLE_SLOT";
        }
        if (visualAction == VisualAction.PLACE_TEXT_SHELL
                || role.contains("shell")) {
            return "SHELL_SLOT";
        }
        if (!hasVisibleVisual()
                && (textAction == TextAction.OWNED_BY_HWPX_TEXT
                    || textAction == TextAction.HIDDEN_SEMANTIC
                    || materialization == Materialization.HWPX_TEXT)) {
            return "TEXT_SLOT";
        }
        return "CONTENT_VISUAL_SLOT";
    }

    public ObjectPlan withVisualAction(VisualAction newVisualAction, String newReason) {
        return withCurrentInlineFlow(new ObjectPlan(
                domId,
                kind,
                candidateId,
                planPassId,
                slotRole,
                pageIndex,
                textAction,
                newVisualAction,
                visualLayer,
                placement,
                renderId,
                sourceObjectIds,
                visualSourceObjectIds,
                styleSourceObjectIds,
                exportSourceObjectIds,
                hiddenVisualSourceObjectIds,
                sourceRootObjectIds,
                clusterSourceObjectIds,
                omittedClusterSourceObjectIds,
                ownedTextFrameIds,
                descendantVisualObjectIds,
                sourceBundleKey,
                materialization,
                coordinateSpace,
                anchorOwner,
                zOrder,
                newReason != null ? newReason : reason,
                file,
                bounds,
                renderSourceBounds,
                cropSourceBounds,
                sourceLayerId,
                sourceLayerName,
                sourceLayerIndex));
    }

    public ObjectPlan withExtractionCandidate(
            String newCandidateId,
            String newPlanPassId,
            String newSlotRole) {
        return withCurrentInlineFlow(new ObjectPlan(
                domId,
                kind,
                newCandidateId,
                newPlanPassId,
                newSlotRole,
                pageIndex,
                textAction,
                visualAction,
                visualLayer,
                placement,
                renderId,
                sourceObjectIds,
                visualSourceObjectIds,
                styleSourceObjectIds,
                exportSourceObjectIds,
                hiddenVisualSourceObjectIds,
                sourceRootObjectIds,
                clusterSourceObjectIds,
                omittedClusterSourceObjectIds,
                ownedTextFrameIds,
                descendantVisualObjectIds,
                sourceBundleKey,
                materialization,
                coordinateSpace,
                anchorOwner,
                zOrder,
                reason,
                file,
                bounds,
                renderSourceBounds,
                cropSourceBounds,
                sourceLayerId,
                sourceLayerName,
                sourceLayerIndex));
    }

    public ObjectPlan withTextAction(TextAction newTextAction) {
        return withCurrentInlineFlow(new ObjectPlan(
                domId,
                kind,
                candidateId,
                planPassId,
                slotRole,
                pageIndex,
                newTextAction,
                visualAction,
                visualLayer,
                placement,
                renderId,
                sourceObjectIds,
                visualSourceObjectIds,
                styleSourceObjectIds,
                exportSourceObjectIds,
                hiddenVisualSourceObjectIds,
                sourceRootObjectIds,
                clusterSourceObjectIds,
                omittedClusterSourceObjectIds,
                ownedTextFrameIds,
                descendantVisualObjectIds,
                sourceBundleKey,
                materialization,
                coordinateSpace,
                anchorOwner,
                zOrder,
                reason,
                file,
                bounds,
                renderSourceBounds,
                cropSourceBounds,
                sourceLayerId,
                sourceLayerName,
                sourceLayerIndex));
    }

    public ObjectPlan withVisualLayer(VisualLayer newVisualLayer) {
        return withCurrentInlineFlow(new ObjectPlan(
                domId,
                kind,
                candidateId,
                planPassId,
                slotRole,
                pageIndex,
                textAction,
                visualAction,
                newVisualLayer,
                placement,
                renderId,
                sourceObjectIds,
                visualSourceObjectIds,
                styleSourceObjectIds,
                exportSourceObjectIds,
                hiddenVisualSourceObjectIds,
                sourceRootObjectIds,
                clusterSourceObjectIds,
                omittedClusterSourceObjectIds,
                ownedTextFrameIds,
                descendantVisualObjectIds,
                sourceBundleKey,
                materialization,
                coordinateSpace,
                anchorOwner,
                zOrder,
                reason,
                file,
                bounds,
                renderSourceBounds,
                cropSourceBounds,
                sourceLayerId,
                sourceLayerName,
                sourceLayerIndex));
    }

    public ObjectPlan withPlacement(Placement newPlacement) {
        return withCurrentInlineFlow(new ObjectPlan(
                domId,
                kind,
                candidateId,
                planPassId,
                slotRole,
                pageIndex,
                textAction,
                visualAction,
                visualLayer,
                newPlacement,
                renderId,
                sourceObjectIds,
                visualSourceObjectIds,
                styleSourceObjectIds,
                exportSourceObjectIds,
                hiddenVisualSourceObjectIds,
                sourceRootObjectIds,
                clusterSourceObjectIds,
                omittedClusterSourceObjectIds,
                ownedTextFrameIds,
                descendantVisualObjectIds,
                sourceBundleKey,
                materialization,
                coordinateSpace,
                anchorOwner,
                zOrder,
                reason,
                file,
                bounds,
                renderSourceBounds,
                cropSourceBounds,
                sourceLayerId,
                sourceLayerName,
                sourceLayerIndex));
    }

    public ObjectPlan withPlacementAndCoordinateSpace(
            Placement newPlacement,
            CoordinateSpace newCoordinateSpace) {
        return withCurrentInlineFlow(new ObjectPlan(
                domId,
                kind,
                candidateId,
                planPassId,
                slotRole,
                pageIndex,
                textAction,
                visualAction,
                visualLayer,
                newPlacement,
                renderId,
                sourceObjectIds,
                visualSourceObjectIds,
                styleSourceObjectIds,
                exportSourceObjectIds,
                hiddenVisualSourceObjectIds,
                sourceRootObjectIds,
                clusterSourceObjectIds,
                omittedClusterSourceObjectIds,
                ownedTextFrameIds,
                descendantVisualObjectIds,
                sourceBundleKey,
                materialization,
                newCoordinateSpace,
                anchorOwner,
                zOrder,
                reason,
                file,
                bounds,
                renderSourceBounds,
                cropSourceBounds,
                sourceLayerId,
                sourceLayerName,
                sourceLayerIndex));
    }

    public ObjectPlan withZOrder(int newZOrder) {
        return withCurrentInlineFlow(new ObjectPlan(
                domId,
                kind,
                candidateId,
                planPassId,
                slotRole,
                pageIndex,
                textAction,
                visualAction,
                visualLayer,
                placement,
                renderId,
                sourceObjectIds,
                visualSourceObjectIds,
                styleSourceObjectIds,
                exportSourceObjectIds,
                hiddenVisualSourceObjectIds,
                sourceRootObjectIds,
                clusterSourceObjectIds,
                omittedClusterSourceObjectIds,
                ownedTextFrameIds,
                descendantVisualObjectIds,
                sourceBundleKey,
                materialization,
                coordinateSpace,
                anchorOwner,
                newZOrder,
                reason,
                file,
                bounds,
                renderSourceBounds,
                cropSourceBounds,
                sourceLayerId,
                sourceLayerName,
                sourceLayerIndex));
    }

    public ObjectPlan withSourceObjectIds(int[] newSourceObjectIds) {
        return withCurrentInlineFlow(new ObjectPlan(
                domId,
                kind,
                candidateId,
                planPassId,
                slotRole,
                pageIndex,
                textAction,
                visualAction,
                visualLayer,
                placement,
                renderId,
                newSourceObjectIds,
                visualSourceObjectIds,
                styleSourceObjectIds,
                exportSourceObjectIds,
                hiddenVisualSourceObjectIds,
                sourceRootObjectIds,
                clusterSourceObjectIds,
                omittedClusterSourceObjectIds,
                ownedTextFrameIds,
                descendantVisualObjectIds,
                sourceBundleKey,
                materialization,
                coordinateSpace,
                anchorOwner,
                zOrder,
                reason,
                file,
                bounds,
                renderSourceBounds,
                cropSourceBounds,
                sourceLayerId,
                sourceLayerName,
                sourceLayerIndex));
    }

    public ObjectPlan withVisualSourceObjectIds(int[] newVisualSourceObjectIds) {
        return withCurrentInlineFlow(new ObjectPlan(
                domId,
                kind,
                candidateId,
                planPassId,
                slotRole,
                pageIndex,
                textAction,
                visualAction,
                visualLayer,
                placement,
                renderId,
                sourceObjectIds,
                newVisualSourceObjectIds,
                styleSourceObjectIds,
                exportSourceObjectIds,
                hiddenVisualSourceObjectIds,
                sourceRootObjectIds,
                clusterSourceObjectIds,
                omittedClusterSourceObjectIds,
                ownedTextFrameIds,
                descendantVisualObjectIds,
                sourceBundleKey,
                materialization,
                coordinateSpace,
                anchorOwner,
                zOrder,
                reason,
                file,
                bounds,
                renderSourceBounds,
                cropSourceBounds,
                sourceLayerId,
                sourceLayerName,
                sourceLayerIndex));
    }

    public ObjectPlan withStyleSourceObjectIds(int[] newStyleSourceObjectIds) {
        return withCurrentInlineFlow(new ObjectPlan(
                domId,
                kind,
                candidateId,
                planPassId,
                slotRole,
                pageIndex,
                textAction,
                visualAction,
                visualLayer,
                placement,
                renderId,
                sourceObjectIds,
                visualSourceObjectIds,
                newStyleSourceObjectIds,
                exportSourceObjectIds,
                hiddenVisualSourceObjectIds,
                sourceRootObjectIds,
                clusterSourceObjectIds,
                omittedClusterSourceObjectIds,
                ownedTextFrameIds,
                descendantVisualObjectIds,
                sourceBundleKey,
                materialization,
                coordinateSpace,
                anchorOwner,
                zOrder,
                reason,
                file,
                bounds,
                renderSourceBounds,
                cropSourceBounds,
                sourceLayerId,
                sourceLayerName,
                sourceLayerIndex));
    }

    public ObjectPlan withOwnedTextFrameIds(int[] newOwnedTextFrameIds) {
        return withCurrentInlineFlow(new ObjectPlan(
                domId,
                kind,
                candidateId,
                planPassId,
                slotRole,
                pageIndex,
                textAction,
                visualAction,
                visualLayer,
                placement,
                renderId,
                sourceObjectIds,
                visualSourceObjectIds,
                styleSourceObjectIds,
                exportSourceObjectIds,
                hiddenVisualSourceObjectIds,
                sourceRootObjectIds,
                clusterSourceObjectIds,
                omittedClusterSourceObjectIds,
                newOwnedTextFrameIds,
                descendantVisualObjectIds,
                sourceBundleKey,
                materialization,
                coordinateSpace,
                anchorOwner,
                zOrder,
                reason,
                file,
                bounds,
                renderSourceBounds,
                cropSourceBounds,
                sourceLayerId,
                sourceLayerName,
                sourceLayerIndex));
    }

    public ObjectPlan withDescendantVisualObjectIds(int[] newDescendantVisualObjectIds) {
        return withCurrentInlineFlow(new ObjectPlan(
                domId,
                kind,
                candidateId,
                planPassId,
                slotRole,
                pageIndex,
                textAction,
                visualAction,
                visualLayer,
                placement,
                renderId,
                sourceObjectIds,
                visualSourceObjectIds,
                styleSourceObjectIds,
                exportSourceObjectIds,
                hiddenVisualSourceObjectIds,
                sourceRootObjectIds,
                clusterSourceObjectIds,
                omittedClusterSourceObjectIds,
                ownedTextFrameIds,
                newDescendantVisualObjectIds,
                sourceBundleKey,
                materialization,
                coordinateSpace,
                anchorOwner,
                zOrder,
                reason,
                file,
                bounds,
                renderSourceBounds,
                cropSourceBounds,
                sourceLayerId,
                sourceLayerName,
                sourceLayerIndex));
    }

    public ObjectPlan withSourceBundleKey(String newSourceBundleKey) {
        return withCurrentInlineFlow(new ObjectPlan(
                domId,
                kind,
                candidateId,
                planPassId,
                slotRole,
                pageIndex,
                textAction,
                visualAction,
                visualLayer,
                placement,
                renderId,
                sourceObjectIds,
                visualSourceObjectIds,
                styleSourceObjectIds,
                exportSourceObjectIds,
                hiddenVisualSourceObjectIds,
                sourceRootObjectIds,
                clusterSourceObjectIds,
                omittedClusterSourceObjectIds,
                ownedTextFrameIds,
                descendantVisualObjectIds,
                newSourceBundleKey,
                materialization,
                coordinateSpace,
                anchorOwner,
                zOrder,
                reason,
                file,
                bounds,
                renderSourceBounds,
                cropSourceBounds,
                sourceLayerId,
                sourceLayerName,
                sourceLayerIndex));
    }

    public ObjectPlan withMaterialization(Materialization newMaterialization) {
        return withCurrentInlineFlow(new ObjectPlan(
                domId,
                kind,
                candidateId,
                planPassId,
                slotRole,
                pageIndex,
                textAction,
                visualAction,
                visualLayer,
                placement,
                renderId,
                sourceObjectIds,
                visualSourceObjectIds,
                styleSourceObjectIds,
                exportSourceObjectIds,
                hiddenVisualSourceObjectIds,
                sourceRootObjectIds,
                clusterSourceObjectIds,
                omittedClusterSourceObjectIds,
                ownedTextFrameIds,
                descendantVisualObjectIds,
                sourceBundleKey,
                newMaterialization,
                coordinateSpace,
                anchorOwner,
                zOrder,
                reason,
                file,
                bounds,
                renderSourceBounds,
                cropSourceBounds,
                sourceLayerId,
                sourceLayerName,
                sourceLayerIndex));
    }

    public ObjectPlan withBounds(double[] newBounds) {
        return withCurrentInlineFlow(new ObjectPlan(
                domId,
                kind,
                candidateId,
                planPassId,
                slotRole,
                pageIndex,
                textAction,
                visualAction,
                visualLayer,
                placement,
                renderId,
                sourceObjectIds,
                visualSourceObjectIds,
                styleSourceObjectIds,
                exportSourceObjectIds,
                hiddenVisualSourceObjectIds,
                sourceRootObjectIds,
                clusterSourceObjectIds,
                omittedClusterSourceObjectIds,
                ownedTextFrameIds,
                descendantVisualObjectIds,
                sourceBundleKey,
                materialization,
                coordinateSpace,
                anchorOwner,
                zOrder,
                reason,
                file,
                newBounds,
                renderSourceBounds,
                cropSourceBounds,
                sourceLayerId,
                sourceLayerName,
                sourceLayerIndex));
    }

    public ObjectPlan withRenderSourceBounds(double[] newRenderSourceBounds) {
        return withCurrentInlineFlow(new ObjectPlan(
                domId,
                kind,
                candidateId,
                planPassId,
                slotRole,
                pageIndex,
                textAction,
                visualAction,
                visualLayer,
                placement,
                renderId,
                sourceObjectIds,
                visualSourceObjectIds,
                styleSourceObjectIds,
                exportSourceObjectIds,
                hiddenVisualSourceObjectIds,
                sourceRootObjectIds,
                clusterSourceObjectIds,
                omittedClusterSourceObjectIds,
                ownedTextFrameIds,
                descendantVisualObjectIds,
                sourceBundleKey,
                materialization,
                coordinateSpace,
                anchorOwner,
                zOrder,
                reason,
                file,
                bounds,
                newRenderSourceBounds,
                cropSourceBounds,
                sourceLayerId,
                sourceLayerName,
                sourceLayerIndex));
    }

    public ObjectPlan withCropSourceBounds(double[] newCropSourceBounds) {
        return withCurrentInlineFlow(new ObjectPlan(
                domId,
                kind,
                candidateId,
                planPassId,
                slotRole,
                pageIndex,
                textAction,
                visualAction,
                visualLayer,
                placement,
                renderId,
                sourceObjectIds,
                visualSourceObjectIds,
                styleSourceObjectIds,
                exportSourceObjectIds,
                hiddenVisualSourceObjectIds,
                sourceRootObjectIds,
                clusterSourceObjectIds,
                omittedClusterSourceObjectIds,
                ownedTextFrameIds,
                descendantVisualObjectIds,
                sourceBundleKey,
                materialization,
                coordinateSpace,
                anchorOwner,
                zOrder,
                reason,
                file,
                bounds,
                renderSourceBounds,
                newCropSourceBounds,
                sourceLayerId,
                sourceLayerName,
                sourceLayerIndex));
    }

    public ObjectPlan withExtractionSourceObjectIds(
            int[] newExportSourceObjectIds,
            int[] newHiddenVisualSourceObjectIds) {
        return withCurrentInlineFlow(new ObjectPlan(
                domId,
                kind,
                candidateId,
                planPassId,
                slotRole,
                pageIndex,
                textAction,
                visualAction,
                visualLayer,
                placement,
                renderId,
                sourceObjectIds,
                visualSourceObjectIds,
                styleSourceObjectIds,
                newExportSourceObjectIds,
                newHiddenVisualSourceObjectIds,
                sourceRootObjectIds,
                clusterSourceObjectIds,
                omittedClusterSourceObjectIds,
                ownedTextFrameIds,
                descendantVisualObjectIds,
                sourceBundleKey,
                materialization,
                coordinateSpace,
                anchorOwner,
                zOrder,
                reason,
                file,
                bounds,
                renderSourceBounds,
                cropSourceBounds,
                sourceLayerId,
                sourceLayerName,
                sourceLayerIndex));
    }

    public ObjectPlan withSourceLayerMetadata(
            String newSourceLayerId,
            String newSourceLayerName,
            int newSourceLayerIndex) {
        return withCurrentInlineFlow(new ObjectPlan(
                domId,
                kind,
                candidateId,
                planPassId,
                slotRole,
                pageIndex,
                textAction,
                visualAction,
                visualLayer,
                placement,
                renderId,
                sourceObjectIds,
                visualSourceObjectIds,
                styleSourceObjectIds,
                exportSourceObjectIds,
                hiddenVisualSourceObjectIds,
                sourceRootObjectIds,
                clusterSourceObjectIds,
                omittedClusterSourceObjectIds,
                ownedTextFrameIds,
                descendantVisualObjectIds,
                sourceBundleKey,
                materialization,
                coordinateSpace,
                anchorOwner,
                zOrder,
                reason,
                file,
                bounds,
                renderSourceBounds,
                cropSourceBounds,
                newSourceLayerId,
                newSourceLayerName,
                newSourceLayerIndex));
    }

    public ObjectPlan withSourceTreeDiagnostics(
            int[] newSourceRootObjectIds,
            int[] newClusterSourceObjectIds,
            int[] newOmittedClusterSourceObjectIds) {
        return withCurrentInlineFlow(new ObjectPlan(
                domId,
                kind,
                candidateId,
                planPassId,
                slotRole,
                pageIndex,
                textAction,
                visualAction,
                visualLayer,
                placement,
                renderId,
                sourceObjectIds,
                visualSourceObjectIds,
                styleSourceObjectIds,
                exportSourceObjectIds,
                hiddenVisualSourceObjectIds,
                newSourceRootObjectIds,
                newClusterSourceObjectIds,
                newOmittedClusterSourceObjectIds,
                ownedTextFrameIds,
                descendantVisualObjectIds,
                sourceBundleKey,
                materialization,
                coordinateSpace,
                anchorOwner,
                zOrder,
                reason,
                file,
                bounds,
                renderSourceBounds,
                cropSourceBounds,
                sourceLayerId,
                sourceLayerName,
                sourceLayerIndex));
    }

    public ObjectPlan withInlineFlowContract(
            boolean newInlineSourceTreeClosed,
            int[] newInlineFlowSourceObjectIds) {
        return new ObjectPlan(this, newInlineSourceTreeClosed, newInlineFlowSourceObjectIds);
    }

    public ObjectPlan withTextLayoutContract(TextLayoutContract contract) {
        this.textLayoutContract = contract != null ? contract.copy() : null;
        return this;
    }

    private ObjectPlan withCurrentInlineFlow(ObjectPlan plan) {
        return plan.withObjectPlanId(objectPlanId)
                .withOwnedTextFrameIdKeys(ownedTextFrameIdKeys)
                .withInlineFlowContract(inlineSourceTreeClosed, inlineFlowSourceObjectIds)
                .withTextLayoutContract(textLayoutContract);
    }

    public ObjectPlan withObjectPlanId(String id) {
        this.objectPlanId = id;
        return this;
    }

    public ObjectPlan withOwnedTextFrameIdKeys(String[] keys) {
        this.ownedTextFrameIdKeys = copyStringArray(keys);
        return this;
    }

    private static String[] copyStringArray(String[] values) {
        return values != null ? Arrays.copyOf(values, values.length) : new String[0];
    }

    public ObjectPlan withPageIndexAndBounds(int newPageIndex, double[] newBounds, String newReason) {
        return withCurrentInlineFlow(new ObjectPlan(
                domId,
                kind,
                candidateId,
                planPassId,
                slotRole,
                newPageIndex,
                textAction,
                visualAction,
                visualLayer,
                placement,
                renderId,
                sourceObjectIds,
                visualSourceObjectIds,
                styleSourceObjectIds,
                exportSourceObjectIds,
                hiddenVisualSourceObjectIds,
                sourceRootObjectIds,
                clusterSourceObjectIds,
                omittedClusterSourceObjectIds,
                ownedTextFrameIds,
                descendantVisualObjectIds,
                sourceBundleKey,
                materialization,
                coordinateSpace,
                anchorOwner,
                zOrder,
                newReason != null ? newReason : reason,
                file,
                newBounds != null ? newBounds : bounds,
                renderSourceBounds,
                cropSourceBounds,
                sourceLayerId,
                sourceLayerName,
                sourceLayerIndex));
    }

    public ObjectPlan withRenderedVisual(
            VisualLayer newVisualLayer,
            int[] newSourceObjectIds,
            int newZOrder,
            String newReason,
            String newFile,
            double[] newBounds) {
        return withCurrentInlineFlow(new ObjectPlan(
                domId,
                kind,
                candidateId,
                planPassId,
                slotRole,
                pageIndex,
                textAction,
                visualAction,
                newVisualLayer,
                placement,
                renderId,
                newSourceObjectIds,
                newSourceObjectIds,
                styleSourceObjectIds,
                exportSourceObjectIds,
                hiddenVisualSourceObjectIds,
                sourceRootObjectIds,
                clusterSourceObjectIds,
                omittedClusterSourceObjectIds,
                ownedTextFrameIds,
                descendantVisualObjectIds,
                sourceBundleKey,
                Materialization.EXTRACTED_PNG_VECTOR,
                coordinateSpace,
                anchorOwner,
                newZOrder,
                newReason != null ? newReason : reason,
                newFile != null ? newFile : file,
                newBounds != null ? newBounds : bounds,
                renderSourceBounds,
                cropSourceBounds,
                sourceLayerId,
                sourceLayerName,
                sourceLayerIndex));
    }

    public ObjectPlan withVisibleMaterialFrom(ObjectPlan materialPlan, String newReason) {
        if (materialPlan == null) return this;
        int[] newVisualSourceObjectIds = materialPlan.visualSourceObjectIds != null
                && materialPlan.visualSourceObjectIds.length > 0
                ? materialPlan.visualSourceObjectIds
                : materialPlan.sourceObjectIds;
        return withCurrentInlineFlow(new ObjectPlan(
                domId,
                kind,
                candidateId,
                planPassId,
                slotRole,
                pageIndex,
                textAction,
                visualAction,
                visualLayer,
                placement,
                renderId,
                sourceObjectIds,
                newVisualSourceObjectIds,
                styleSourceObjectIds,
                materialPlan.exportSourceObjectIds != null && materialPlan.exportSourceObjectIds.length > 0
                        ? materialPlan.exportSourceObjectIds
                        : exportSourceObjectIds,
                materialPlan.hiddenVisualSourceObjectIds != null && materialPlan.hiddenVisualSourceObjectIds.length > 0
                        ? materialPlan.hiddenVisualSourceObjectIds
                        : hiddenVisualSourceObjectIds,
                materialPlan.sourceRootObjectIds != null && materialPlan.sourceRootObjectIds.length > 0
                        ? materialPlan.sourceRootObjectIds
                        : sourceRootObjectIds,
                materialPlan.clusterSourceObjectIds != null && materialPlan.clusterSourceObjectIds.length > 0
                        ? materialPlan.clusterSourceObjectIds
                        : clusterSourceObjectIds,
                materialPlan.omittedClusterSourceObjectIds != null && materialPlan.omittedClusterSourceObjectIds.length > 0
                        ? materialPlan.omittedClusterSourceObjectIds
                        : omittedClusterSourceObjectIds,
                ownedTextFrameIds,
                descendantVisualObjectIds,
                sourceBundleKey,
                materialPlan.materialization != null
                        ? materialPlan.materialization
                        : materialization,
                coordinateSpace,
                anchorOwner,
                zOrder,
                newReason != null ? newReason : reason,
                materialPlan.file != null ? materialPlan.file : file,
                bounds,
                materialPlan.renderSourceBounds != null
                        ? materialPlan.renderSourceBounds
                        : renderSourceBounds,
                materialPlan.cropSourceBounds != null
                        ? materialPlan.cropSourceBounds
                        : cropSourceBounds,
                sourceLayerId,
                sourceLayerName,
                sourceLayerIndex));
    }

    public String toJson() {
        StringBuilder sb = new StringBuilder(320);
        sb.append('{')
                .append("\"objectPlanId\":\"").append(escape(objectPlanId)).append("\",")
                .append("\"domId\":").append(domId).append(',')
                .append("\"kind\":\"").append(escape(kind)).append("\",")
                .append("\"candidateId\":\"").append(escape(candidateId)).append("\",")
                .append("\"planPassId\":\"").append(escape(planPassId)).append("\",")
                .append("\"slotRole\":\"").append(escape(slotRole)).append("\",")
                .append("\"ownershipSlot\":\"").append(escape(ownershipSlot())).append("\",")
                .append("\"pageIndex\":").append(pageIndex).append(',')
                .append("\"textAction\":\"").append(textAction).append("\",")
                .append("\"visualAction\":\"").append(visualAction).append("\",")
                .append("\"visualLayer\":\"").append(visualLayer).append("\",")
                .append("\"policyLayer\":\"").append(visualPolicyLayer()).append("\",")
                .append("\"placement\":\"").append(placement).append("\",")
                .append("\"renderId\":").append(renderId != null ? renderId : -1).append(',')
                .append("\"sourceObjectIds\":").append(intArrayJson(sourceObjectIds)).append(',')
                .append("\"sourceRootObjectIds\":").append(intArrayJson(sourceRootObjectIds)).append(',')
                .append("\"clusterSourceObjectIds\":").append(intArrayJson(clusterSourceObjectIds)).append(',')
                .append("\"omittedClusterSourceObjectIds\":").append(intArrayJson(omittedClusterSourceObjectIds)).append(',')
                .append("\"visualSourceObjectIds\":").append(intArrayJson(visualSourceObjectIds)).append(',')
                .append("\"styleSourceObjectIds\":").append(intArrayJson(styleSourceObjectIds)).append(',')
                .append("\"exportSourceObjectIds\":").append(intArrayJson(exportSourceObjectIds)).append(',')
                .append("\"hiddenVisualSourceObjectIds\":").append(intArrayJson(hiddenVisualSourceObjectIds)).append(',')
                .append("\"inlineSourceTreeClosed\":").append(inlineSourceTreeClosed).append(',')
                .append("\"inlineFlowSourceObjectIds\":").append(intArrayJson(inlineFlowSourceObjectIds)).append(',')
                .append("\"ownedTextFrameIds\":").append(intArrayJson(ownedTextFrameIds)).append(',')
                .append("\"ownedTextFrameIdKeys\":").append(stringArrayJson(ownedTextFrameIdKeys)).append(',')
                .append("\"descendantVisualObjectIds\":").append(intArrayJson(descendantVisualObjectIds)).append(',')
                .append("\"sourceBundleKey\":\"").append(escape(sourceBundleKey)).append("\",")
                .append("\"materialization\":\"").append(materialization).append("\",")
                .append("\"coordinateSpace\":\"").append(coordinateSpace).append("\",")
                .append("\"anchorOwner\":\"").append(escape(anchorOwner)).append("\",")
                .append("\"zOrder\":").append(zOrder).append(',')
                .append("\"sourceLayerId\":\"").append(escape(sourceLayerId)).append("\",")
                .append("\"sourceLayerName\":\"").append(escape(sourceLayerName)).append("\",")
                .append("\"sourceLayerIndex\":").append(sourceLayerIndex).append(',')
                .append("\"reason\":\"").append(escape(reason)).append("\",")
                .append("\"file\":\"").append(escape(file)).append("\"");
        if (bounds != null && bounds.length >= 4) {
            sb.append(",\"bounds\":[")
                    .append(bounds[0]).append(',')
                    .append(bounds[1]).append(',')
                    .append(bounds[2]).append(',')
                    .append(bounds[3]).append(']');
        }
        if (renderSourceBounds != null && renderSourceBounds.length >= 4) {
            sb.append(",\"renderSourceBounds\":[")
                    .append(renderSourceBounds[0]).append(',')
                    .append(renderSourceBounds[1]).append(',')
                    .append(renderSourceBounds[2]).append(',')
                    .append(renderSourceBounds[3]).append(']');
        }
        if (cropSourceBounds != null && cropSourceBounds.length >= 4) {
            sb.append(",\"cropSourceBounds\":[")
                    .append(cropSourceBounds[0]).append(',')
                    .append(cropSourceBounds[1]).append(',')
                    .append(cropSourceBounds[2]).append(',')
                    .append(cropSourceBounds[3]).append(']');
        }
        if (textLayoutContract != null) {
            sb.append(",\"textLayoutContract\":")
                    .append(textLayoutContract.toJson());
        }
        sb.append('}');
        return sb.toString();
    }

    public static String intArrayJson(int[] values) {
        if (values == null || values.length == 0) return "[]";
        StringBuilder sb = new StringBuilder(values.length * 8 + 2);
        sb.append('[');
        for (int i = 0; i < values.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(values[i]);
        }
        sb.append(']');
        return sb.toString();
    }

    public static String stringArrayJson(String[] values) {
        if (values == null || values.length == 0) return "[]";
        StringBuilder sb = new StringBuilder("[");
        for (int i = 0; i < values.length; i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(escape(values[i])).append('"');
        }
        sb.append(']');
        return sb.toString();
    }

    public static String escape(String value) {
        if (value == null) return "";
        StringBuilder out = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\b': out.append("\\b"); break;
                case '\f': out.append("\\f"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default:
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
            }
        }
        return out.toString();
    }
}
