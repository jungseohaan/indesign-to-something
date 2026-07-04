package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership;

import java.util.Arrays;

/** Source ownership policy object plan. */
public final class ObjectPlan {
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
    public final String sourceLayerId;
    public final String sourceLayerName;
    public final int sourceLayerIndex;

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
            int zOrder,
            String reason,
            String file,
            double[] bounds) {
        this(domId, kind, pageIndex, textAction, visualAction, visualLayer, placement,
                renderId, sourceObjectIds, zOrder, reason, file, bounds, null, null, -1);
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
            int zOrder,
            String reason,
            String file,
            double[] bounds,
            String sourceLayerId,
            String sourceLayerName,
            int sourceLayerIndex) {
        this(domId, kind, pageIndex, textAction, visualAction, visualLayer, placement,
                renderId, sourceObjectIds, null, null, null, null, zOrder, reason, file, bounds,
                sourceLayerId, sourceLayerName, sourceLayerIndex);
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
        this(domId, kind, pageIndex, textAction, visualAction, visualLayer, placement,
                renderId, sourceObjectIds, visualSourceObjectIds, null, ownedTextFrameIds,
                descendantVisualObjectIds, sourceBundleKey, null, null, null, zOrder, reason,
                file, bounds, null, sourceLayerId, sourceLayerName, sourceLayerIndex);
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
                bounds, renderSourceBounds, sourceLayerId, sourceLayerName,
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
            String sourceLayerId,
            String sourceLayerName,
            int sourceLayerIndex) {
        this(domId, kind, null, null, null, pageIndex, textAction, visualAction,
                visualLayer, placement, renderId, sourceObjectIds, visualSourceObjectIds,
                styleSourceObjectIds, exportSourceObjectIds, hiddenVisualSourceObjectIds,
                sourceRootObjectIds, clusterSourceObjectIds, omittedClusterSourceObjectIds,
                ownedTextFrameIds, descendantVisualObjectIds, sourceBundleKey,
                materialization, coordinateSpace, anchorOwner, zOrder, reason, file,
                bounds, renderSourceBounds, sourceLayerId, sourceLayerName,
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
            String sourceLayerId,
            String sourceLayerName,
            int sourceLayerIndex) {
        this.domId = domId;
        this.kind = kind;
        this.candidateId = candidateId;
        this.planPassId = planPassId;
        this.slotRole = slotRole;
        this.pageIndex = pageIndex;
        this.textAction = textAction;
        this.visualAction = visualAction;
        this.visualLayer = visualLayer != null ? visualLayer : VisualLayer.CONTENT_VISUAL;
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
        this.descendantVisualObjectIds = descendantVisualObjectIds != null
                ? Arrays.copyOf(descendantVisualObjectIds, descendantVisualObjectIds.length)
                : new int[0];
        this.sourceBundleKey = sourceBundleKey;
        this.materialization = materialization != null
                ? materialization
                : defaultMaterialization(textAction, visualAction);
        this.coordinateSpace = coordinateSpace != null
                ? coordinateSpace
                : defaultCoordinateSpace(placement);
        this.anchorOwner = anchorOwner;
        this.zOrder = zOrder;
        this.reason = reason;
        this.file = file;
        this.bounds = bounds != null ? Arrays.copyOf(bounds, bounds.length) : null;
        this.renderSourceBounds = renderSourceBounds != null
                ? Arrays.copyOf(renderSourceBounds, renderSourceBounds.length)
                : null;
        this.sourceLayerId = sourceLayerId;
        this.sourceLayerName = sourceLayerName;
        this.sourceLayerIndex = sourceLayerIndex;
    }

    private static Materialization defaultMaterialization(TextAction textAction, VisualAction visualAction) {
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

    private static CoordinateSpace defaultCoordinateSpace(Placement placement) {
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
        if (visualLayer == VisualLayer.PAGE_BACKGROUND
                || visualLayer == VisualLayer.CONTAINER_BACKDROP) {
            return PolicyLayer.BACKGROUND;
        }
        if (visualLayer == VisualLayer.TEXT_CARD_BACKDROP
                || visualLayer == VisualLayer.CONTAINER_FACE
                || visualLayer == VisualLayer.LABEL_CONNECTOR_BACKDROP
                || visualLayer == VisualLayer.LABEL_BACKDROP
                || visualLayer == VisualLayer.LABEL_OVERLAY_BACKDROP
                || visualLayer == VisualLayer.CONTAINER_OUTLINE
                || visualLayer == VisualLayer.FOREGROUND_MASK) {
            return PolicyLayer.DECORATION;
        }
        if (visualLayer == VisualLayer.CONTENT_BACKDROP) {
            return PolicyLayer.CONTENT;
        }
        return PolicyLayer.CONTENT;
    }

    public ObjectPlan withVisualAction(VisualAction newVisualAction, String newReason) {
        return new ObjectPlan(
                domId,
                kind,
                pageIndex,
                textAction,
                newVisualAction,
                visualLayer,
                placement,
                renderId,
                sourceObjectIds,
                visualSourceObjectIds,
                styleSourceObjectIds,
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
                sourceLayerId,
                sourceLayerName,
                sourceLayerIndex);
    }

    public ObjectPlan withExtractionCandidate(
            String newCandidateId,
            String newPlanPassId,
            String newSlotRole) {
        return new ObjectPlan(
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
                sourceLayerId,
                sourceLayerName,
                sourceLayerIndex);
    }

    public ObjectPlan withTextAction(TextAction newTextAction) {
        return new ObjectPlan(
                domId,
                kind,
                pageIndex,
                newTextAction,
                visualAction,
                visualLayer,
                placement,
                renderId,
                sourceObjectIds,
                visualSourceObjectIds,
                styleSourceObjectIds,
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
                sourceLayerId,
                sourceLayerName,
                sourceLayerIndex);
    }

    public ObjectPlan withVisualLayer(VisualLayer newVisualLayer) {
        return new ObjectPlan(
                domId,
                kind,
                pageIndex,
                textAction,
                visualAction,
                newVisualLayer,
                placement,
                renderId,
                sourceObjectIds,
                visualSourceObjectIds,
                styleSourceObjectIds,
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
                sourceLayerId,
                sourceLayerName,
                sourceLayerIndex);
    }

    public ObjectPlan withPlacement(Placement newPlacement) {
        return new ObjectPlan(
                domId,
                kind,
                pageIndex,
                textAction,
                visualAction,
                visualLayer,
                newPlacement,
                renderId,
                sourceObjectIds,
                visualSourceObjectIds,
                styleSourceObjectIds,
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
                sourceLayerId,
                sourceLayerName,
                sourceLayerIndex);
    }

    public ObjectPlan withPlacementAndCoordinateSpace(
            Placement newPlacement,
            CoordinateSpace newCoordinateSpace) {
        return new ObjectPlan(
                domId,
                kind,
                pageIndex,
                textAction,
                visualAction,
                visualLayer,
                newPlacement,
                renderId,
                sourceObjectIds,
                visualSourceObjectIds,
                styleSourceObjectIds,
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
                sourceLayerId,
                sourceLayerName,
                sourceLayerIndex);
    }

    public ObjectPlan withZOrder(int newZOrder) {
        return new ObjectPlan(
                domId,
                kind,
                pageIndex,
                textAction,
                visualAction,
                visualLayer,
                placement,
                renderId,
                sourceObjectIds,
                visualSourceObjectIds,
                styleSourceObjectIds,
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
                sourceLayerId,
                sourceLayerName,
                sourceLayerIndex);
    }

    public ObjectPlan withSourceObjectIds(int[] newSourceObjectIds) {
        return new ObjectPlan(
                domId,
                kind,
                pageIndex,
                textAction,
                visualAction,
                visualLayer,
                placement,
                renderId,
                newSourceObjectIds,
                visualSourceObjectIds,
                styleSourceObjectIds,
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
                sourceLayerId,
                sourceLayerName,
                sourceLayerIndex);
    }

    public ObjectPlan withVisualSourceObjectIds(int[] newVisualSourceObjectIds) {
        return new ObjectPlan(
                domId,
                kind,
                pageIndex,
                textAction,
                visualAction,
                visualLayer,
                placement,
                renderId,
                sourceObjectIds,
                newVisualSourceObjectIds,
                styleSourceObjectIds,
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
                sourceLayerId,
                sourceLayerName,
                sourceLayerIndex);
    }

    public ObjectPlan withStyleSourceObjectIds(int[] newStyleSourceObjectIds) {
        return new ObjectPlan(
                domId,
                kind,
                pageIndex,
                textAction,
                visualAction,
                visualLayer,
                placement,
                renderId,
                sourceObjectIds,
                visualSourceObjectIds,
                newStyleSourceObjectIds,
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
                sourceLayerId,
                sourceLayerName,
                sourceLayerIndex);
    }

    public ObjectPlan withOwnedTextFrameIds(int[] newOwnedTextFrameIds) {
        return new ObjectPlan(
                domId,
                kind,
                pageIndex,
                textAction,
                visualAction,
                visualLayer,
                placement,
                renderId,
                sourceObjectIds,
                visualSourceObjectIds,
                styleSourceObjectIds,
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
                sourceLayerId,
                sourceLayerName,
                sourceLayerIndex);
    }

    public ObjectPlan withDescendantVisualObjectIds(int[] newDescendantVisualObjectIds) {
        return new ObjectPlan(
                domId,
                kind,
                pageIndex,
                textAction,
                visualAction,
                visualLayer,
                placement,
                renderId,
                sourceObjectIds,
                visualSourceObjectIds,
                styleSourceObjectIds,
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
                sourceLayerId,
                sourceLayerName,
                sourceLayerIndex);
    }

    public ObjectPlan withSourceBundleKey(String newSourceBundleKey) {
        return new ObjectPlan(
                domId,
                kind,
                pageIndex,
                textAction,
                visualAction,
                visualLayer,
                placement,
                renderId,
                sourceObjectIds,
                visualSourceObjectIds,
                styleSourceObjectIds,
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
                sourceLayerId,
                sourceLayerName,
                sourceLayerIndex);
    }

    public ObjectPlan withMaterialization(Materialization newMaterialization) {
        return new ObjectPlan(
                domId,
                kind,
                pageIndex,
                textAction,
                visualAction,
                visualLayer,
                placement,
                renderId,
                sourceObjectIds,
                visualSourceObjectIds,
                styleSourceObjectIds,
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
                sourceLayerId,
                sourceLayerName,
                sourceLayerIndex);
    }

    public ObjectPlan withBounds(double[] newBounds) {
        return new ObjectPlan(
                domId,
                kind,
                pageIndex,
                textAction,
                visualAction,
                visualLayer,
                placement,
                renderId,
                sourceObjectIds,
                visualSourceObjectIds,
                styleSourceObjectIds,
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
                sourceLayerId,
                sourceLayerName,
                sourceLayerIndex);
    }

    public ObjectPlan withRenderSourceBounds(double[] newRenderSourceBounds) {
        return new ObjectPlan(
                domId,
                kind,
                pageIndex,
                textAction,
                visualAction,
                visualLayer,
                placement,
                renderId,
                sourceObjectIds,
                visualSourceObjectIds,
                styleSourceObjectIds,
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
                sourceLayerId,
                sourceLayerName,
                sourceLayerIndex);
    }

    public ObjectPlan withExtractionSourceObjectIds(
            int[] newExportSourceObjectIds,
            int[] newHiddenVisualSourceObjectIds) {
        return new ObjectPlan(
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
                sourceLayerId,
                sourceLayerName,
                sourceLayerIndex);
    }

    public ObjectPlan withSourceTreeDiagnostics(
            int[] newSourceRootObjectIds,
            int[] newClusterSourceObjectIds,
            int[] newOmittedClusterSourceObjectIds) {
        return new ObjectPlan(
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
                sourceLayerId,
                sourceLayerName,
                sourceLayerIndex);
    }

    public ObjectPlan withPageIndexAndBounds(int newPageIndex, double[] newBounds, String newReason) {
        return new ObjectPlan(
                domId,
                kind,
                newPageIndex,
                textAction,
                visualAction,
                visualLayer,
                placement,
                renderId,
                sourceObjectIds,
                visualSourceObjectIds,
                styleSourceObjectIds,
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
                sourceLayerId,
                sourceLayerName,
                sourceLayerIndex);
    }

    public ObjectPlan withRenderedVisual(
            VisualLayer newVisualLayer,
            int[] newSourceObjectIds,
            int newZOrder,
            String newReason,
            String newFile,
            double[] newBounds) {
        return new ObjectPlan(
                domId,
                kind,
                pageIndex,
                textAction,
                visualAction,
                newVisualLayer,
                placement,
                renderId,
                newSourceObjectIds,
                newSourceObjectIds,
                styleSourceObjectIds,
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
                sourceLayerId,
                sourceLayerName,
                sourceLayerIndex);
    }

    public ObjectPlan withVisibleMaterialFrom(ObjectPlan materialPlan, String newReason) {
        if (materialPlan == null) return this;
        int[] newVisualSourceObjectIds = materialPlan.visualSourceObjectIds != null
                && materialPlan.visualSourceObjectIds.length > 0
                ? materialPlan.visualSourceObjectIds
                : materialPlan.sourceObjectIds;
        return new ObjectPlan(
                domId,
                kind,
                pageIndex,
                textAction,
                visualAction,
                visualLayer,
                placement,
                renderId,
                sourceObjectIds,
                newVisualSourceObjectIds,
                styleSourceObjectIds,
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
                sourceLayerId,
                sourceLayerName,
                sourceLayerIndex);
    }

    public String toJson() {
        StringBuilder sb = new StringBuilder(320);
        sb.append('{')
                .append("\"domId\":").append(domId).append(',')
                .append("\"kind\":\"").append(escape(kind)).append("\",")
                .append("\"candidateId\":\"").append(escape(candidateId)).append("\",")
                .append("\"planPassId\":\"").append(escape(planPassId)).append("\",")
                .append("\"slotRole\":\"").append(escape(slotRole)).append("\",")
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
                .append("\"ownedTextFrameIds\":").append(intArrayJson(ownedTextFrameIds)).append(',')
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

    static String escape(String value) {
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
