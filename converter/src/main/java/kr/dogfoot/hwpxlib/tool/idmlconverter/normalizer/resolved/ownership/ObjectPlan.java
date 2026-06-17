package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership;

import java.util.Arrays;

/** SPEC-035 관찰 모드용 단일 객체 ownership 계획. */
public final class ObjectPlan {
    public final int domId;
    public final String kind;
    public final int pageIndex;
    public final TextAction textAction;
    public final VisualAction visualAction;
    public final VisualLayer visualLayer;
    public final Placement placement;
    public final Integer renderId;
    public final int[] sourceObjectIds;
    public final int zOrder;
    public final String reason;
    public final String file;
    public final double[] bounds;
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
        this.domId = domId;
        this.kind = kind;
        this.pageIndex = pageIndex;
        this.textAction = textAction;
        this.visualAction = visualAction;
        this.visualLayer = visualLayer != null ? visualLayer : VisualLayer.CONTENT_VISUAL;
        this.placement = placement;
        this.renderId = renderId;
        this.sourceObjectIds = sourceObjectIds != null ? Arrays.copyOf(sourceObjectIds, sourceObjectIds.length) : new int[0];
        this.zOrder = zOrder;
        this.reason = reason;
        this.file = file;
        this.bounds = bounds != null ? Arrays.copyOf(bounds, bounds.length) : null;
        this.sourceLayerId = sourceLayerId;
        this.sourceLayerName = sourceLayerName;
        this.sourceLayerIndex = sourceLayerIndex;
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
                || visualLayer == VisualLayer.LABEL_BACKDROP
                || visualLayer == VisualLayer.LABEL_OVERLAY_BACKDROP
                || visualLayer == VisualLayer.CONTAINER_OUTLINE
                || visualLayer == VisualLayer.FOREGROUND_MASK) {
            return PolicyLayer.DECORATION;
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
                zOrder,
                newReason != null ? newReason : reason,
                file,
                bounds,
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
                zOrder,
                reason,
                file,
                bounds,
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
                zOrder,
                reason,
                file,
                bounds,
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
                newZOrder,
                reason,
                file,
                bounds,
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
                zOrder,
                reason,
                file,
                bounds,
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
                newZOrder,
                newReason != null ? newReason : reason,
                newFile != null ? newFile : file,
                newBounds != null ? newBounds : bounds,
                sourceLayerId,
                sourceLayerName,
                sourceLayerIndex);
    }

    public String toJson() {
        StringBuilder sb = new StringBuilder(320);
        sb.append('{')
                .append("\"domId\":").append(domId).append(',')
                .append("\"kind\":\"").append(escape(kind)).append("\",")
                .append("\"pageIndex\":").append(pageIndex).append(',')
                .append("\"textAction\":\"").append(textAction).append("\",")
                .append("\"visualAction\":\"").append(visualAction).append("\",")
                .append("\"visualLayer\":\"").append(visualLayer).append("\",")
                .append("\"policyLayer\":\"").append(visualPolicyLayer()).append("\",")
                .append("\"placement\":\"").append(placement).append("\",")
                .append("\"renderId\":").append(renderId != null ? renderId : -1).append(',')
                .append("\"sourceObjectIds\":").append(intArrayJson(sourceObjectIds)).append(',')
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
        sb.append('}');
        return sb.toString();
    }

    static String intArrayJson(int[] values) {
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
