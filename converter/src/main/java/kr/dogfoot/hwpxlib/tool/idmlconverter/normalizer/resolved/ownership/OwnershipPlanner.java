package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership;

import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ResolvedBuildContext;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.RenderedGroup;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedData;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedPageItem;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedTextFrame;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;

/**
 * SPEC-035 Stage 1 관찰 모드.
 *
 * <p>현재 legacy Phase의 동작을 바꾸지 않고, resolved 데이터만 읽어
 * ObjectPlan과 invariant warning을 기록한다. 이 로그가 안정화되면
 * Phase 2/3/6/7의 분산 판단을 plan 실행으로 옮긴다.</p>
 */
public final class OwnershipPlanner {
    private final ResolvedBuildContext ctx;
    private final ResolvedData data;
    private final List<ObjectPlan> plans = new ArrayList<>();
    private final Map<String, Double> imageInkScoreCache = new HashMap<>();
    private final Map<String, Double> imageWhiteOpaqueScoreCache = new HashMap<>();

    private OwnershipPlanner(ResolvedBuildContext ctx) {
        this.ctx = ctx;
        this.data = ctx != null ? ctx.resolvedData : null;
    }

    public static void runObservation(ResolvedBuildContext ctx) {
        if (ctx == null || ctx.resolvedData == null) return;
        new OwnershipPlanner(ctx).run();
    }

    private void run() {
        planRenderedItems();
        planTextFrames();
        resolveInlineFloatingSameDom();
        resolveDuplicateRenderedChannels();
        resolveInlineCompositeHwpxTextParents();
        resolveVisualBackdropClusterSources();
        resolveTextShellSharedSources();
        resolveCoveredParentGroups();
        resolveParentGroupsWithMoreSpecificChildren();
        resolveOverlappingImageExportDuplicates();
        resolveLargeLayeredImageExportBackdrops();
        resolveClippedDecorationParents();
        resolveContainerMasksOverIntrudingLabelBackdrops();
        resolveNestedTextShellSources();
        resolveClusterOwnedTextFrameShells();
        resolveDroppedRenderedTextOwnership();
        resolveNonTextVisualEditableTextSources();
        writePlans();
        validate();
        System.err.println("[OwnershipPlanner] observation plans=" + plans.size()
                + " warnings=" + ctx.ownershipWarningLines.size());
    }

    private void planRenderedItems() {
        for (RenderedGroup rg : data.allRenderedFloatingItems()) {
            addPlanForRendered(rg, "rendered_floating_item");
        }
        for (RenderedGroup rg : data.allRenderedGraphicFrames()) {
            addPlanForRendered(rg, "rendered_graphic_frame");
        }
        for (RenderedGroup rg : data.allRenderedImageFrames()) {
            addPlanForRendered(rg, "rendered_image_frame");
        }
        for (RenderedGroup rg : data.allRenderedPdfFrames()) {
            addPlanForRendered(rg, "rendered_pdf_frame");
        }
    }

    private void addPlanForRendered(RenderedGroup rg, String channel) {
        if (rg == null) return;
        Placement placement = placementOf(rg);
        TextAction textAction = textActionOf(rg);
        VisualAction visualAction = visualActionOf(rg, placement, textAction);
        VisualLayer visualLayer = visualLayerOf(rg, visualAction, textAction);
        int[] sourceIds = sourceIdsOrSelf(rg);
        int zOrder = zOrderOf(rg, visualAction, sourceIds);
        plans.add(new ObjectPlan(
                rg.id(),
                channel + ":" + safe(rg.type()) + ":" + safe(rg.itemType()),
                rg.pageIndex(),
                textAction,
                visualAction,
                visualLayer,
                placement,
                rg.id(),
                sourceIds,
                zOrder,
                safe(rg.reason()),
                rg.file(),
                rg.bounds()));
    }

    private void planTextFrames() {
        for (ResolvedTextFrame tf : data.textFrames()) {
            if (tf == null || tf.id() == null) continue;
            int domId = parseInt(tf.id(), -1);
            if (domId < 0) continue;
            TextAction textAction;
            if (tf.onHiddenLayer() || tf.nonprinting()) {
                textAction = TextAction.DROP_TEXT;
            } else if (data.isTextOwnedByIndesignPng(tf.id())) {
                textAction = TextAction.OWNED_BY_PNG;
            } else {
                textAction = TextAction.OWNED_BY_HWPX_TEXT;
            }
            plans.add(new ObjectPlan(
                    domId,
                    "text_frame",
                    tf.pageIndex(),
                    textAction,
                    VisualAction.DROP_VISUAL,
                    VisualLayer.CONTENT_VISUAL,
                    tf.isInline() ? Placement.INLINE : Placement.FLOATING,
                    null,
                    new int[] { domId },
                    tf.zOrder(),
                    textFrameReason(tf, textAction),
                    null,
                    tf.pageRelativeBounds() != null ? tf.pageRelativeBounds() : tf.geometricBounds()));
        }
    }

    private TextAction textActionOf(RenderedGroup rg) {
        if (data.shouldUseCompletePngForSimpleButtonLabel(rg)) {
            return TextAction.OWNED_BY_PNG;
        }
        if ("indesign_png".equals(rg.textOwner())) {
            return TextAction.OWNED_BY_PNG;
        }
        if ("hidden_semantic".equals(rg.textOwner())) {
            return TextAction.HIDDEN_SEMANTIC;
        }
        if ("hwpx_tf".equals(rg.textOwner())
                || Boolean.TRUE.equals(rg.containsEditableText())
                || hasEditableTextFrameIds(rg)) {
            return TextAction.OWNED_BY_HWPX_TEXT;
        }
        return TextAction.DROP_TEXT;
    }

    private VisualAction visualActionOf(RenderedGroup rg, Placement placement, TextAction textAction) {
        if (isCompanionShellOfCompleteSimpleLabel(rg)) {
            return VisualAction.DROP_VISUAL;
        }
        if (Boolean.FALSE.equals(rg.placementAllowed())) {
            return VisualAction.DROP_VISUAL;
        }
        if (isUnabsorbedHwpxTextStyleInlineVisual(rg)) {
            return VisualAction.DROP_VISUAL;
        }
        if (textAction == TextAction.OWNED_BY_HWPX_TEXT
                && ("hwpx_tf".equals(rg.textOwner())
                || Boolean.TRUE.equals(rg.containsEditableText())
                || hasEditableTextFrameIds(rg))) {
            if ("label_backdrop_group".equals(rg.reason())) {
                return VisualAction.PLACE_TEXT_SHELL;
            }
            if (isCalloutOrOutlineTextShell(rg)) {
                return VisualAction.PLACE_TEXT_SHELL;
            }
            if (canAbsorbEditableLabelShellAsTextStyle(rg)) {
                return VisualAction.ABSORB_TEXT_STYLE;
            }
            return VisualAction.PLACE_TEXT_SHELL;
        }
        if (rg.shouldSkipByOwnership() && textAction != TextAction.OWNED_BY_PNG) {
            return VisualAction.DROP_VISUAL;
        }
        if (placement == Placement.INLINE) {
            return VisualAction.PLACE_INLINE_PNG;
        }
        if (placement == Placement.FLOATING) {
            return VisualAction.PLACE_FLOATING_PNG;
        }
        return VisualAction.DROP_VISUAL;
    }

    private VisualLayer visualLayerOf(RenderedGroup rg, VisualAction visualAction, TextAction textAction) {
        if (visualAction == VisualAction.DROP_VISUAL
                || visualAction == VisualAction.ABSORB_TEXT_STYLE
                || visualAction == VisualAction.PLACE_TABLE_STYLE) {
            return VisualLayer.CONTENT_VISUAL;
        }
        if (rg.isPageBackground()) {
            return VisualLayer.PAGE_BACKGROUND;
        }
        if (isPageOrSpreadBackdropImage(rg)) {
            return VisualLayer.PAGE_BACKGROUND;
        }
        if (visualAction == VisualAction.PLACE_INLINE_PNG) {
            return VisualLayer.CONTENT_VISUAL;
        }
        if (visualAction == VisualAction.PLACE_TEXT_SHELL) {
            if (isEditableLabelCardShell(rg)) {
                return VisualLayer.LABEL_BACKDROP;
            }
            if (isCalloutOrOutlineTextShell(rg)) {
                return VisualLayer.CONTAINER_OUTLINE;
            }
            if ("visual_label_text_hidden_shell".equals(rg.reason())) {
                // This is not a passive backdrop behind a text frame. The
                // extractor exported the complete label chrome with text hidden,
                // and the editable TextFrame is placed above it. These labels
                // often sit on a container edge, so placing them in the lower
                // LABEL_BACKDROP band lets the container outline/image clip them.
                return VisualLayer.CONTENT_VISUAL;
            }
            if (isImageBackedContentShell(rg)) {
                if (isBackdropDominantImageShell(rg)) {
                    return VisualLayer.CONTAINER_BACKDROP;
                }
                return VisualLayer.CONTENT_VISUAL;
            }
            if ("label_backdrop_group".equals(rg.reason())) {
                return VisualLayer.LABEL_BACKDROP;
            }
            return looksLikeShortLabel(rg) ? VisualLayer.LABEL_BACKDROP : VisualLayer.CONTAINER_BACKDROP;
        }
        if ("visual_backdrop_cluster".equals(rg.reason())) {
            return VisualLayer.CONTAINER_BACKDROP;
        }
        if (isPaperStrokeForegroundMask(rg)) {
            return VisualLayer.FOREGROUND_MASK;
        }
        if (isPaperMaskInsideContainerBackdrop(rg)) {
            return VisualLayer.CONTAINER_BACKDROP;
        }
        if (isFlatImageExportBackdrop(rg)) {
            return VisualLayer.CONTAINER_BACKDROP;
        }
        if (isPlacedContentImage(rg)) {
            return VisualLayer.CONTENT_VISUAL;
        }
        if (isPaperStrokeContainerVisual(rg)) {
            return VisualLayer.CONTAINER_OUTLINE;
        }
        if (isTextFrameBackdropVector(rg)) {
            return VisualLayer.CONTAINER_BACKDROP;
        }
        if (isOpaquePaperBackdrop(rg)) {
            return VisualLayer.CONTAINER_BACKDROP;
        }
        if (isPaperFillBackdropPatch(rg)) {
            return VisualLayer.CONTAINER_BACKDROP;
        }
        if (isPaperStrokeBoxBackdrop(rg)) {
            return VisualLayer.CONTAINER_FACE;
        }
        if (isFilledContainerBoxBackdrop(rg)) {
            return VisualLayer.CONTAINER_BACKDROP;
        }
        if (isRuleLineGroup(rg)) {
            return VisualLayer.CONTAINER_OUTLINE;
        }
        if (isLineLikeVisual(rg)) {
            return VisualLayer.CONTAINER_OUTLINE;
        }
        if (isMaskLikeVisual(rg)) {
            return VisualLayer.FOREGROUND_MASK;
        }
        if (isLabelBackdropLike(rg, textAction)) {
            return VisualLayer.LABEL_BACKDROP;
        }
        if (isContainerBackdropLike(rg)) {
            return VisualLayer.CONTAINER_BACKDROP;
        }
        return VisualLayer.CONTENT_VISUAL;
    }

    private boolean isEditableLabelCardShell(RenderedGroup rg) {
        if (rg == null) return false;
        if (!"mixed_group_text_hidden".equals(rg.reason())) return false;
        if (!"hwpx_tf".equals(rg.textOwner())) return false;
        String[] editableIds = rg.editableTextFrameIds();
        if (editableIds == null || editableIds.length < 2) return false;
        double[] b = rg.bounds();
        if (b == null || b.length < 4) return false;
        double h = Math.abs(b[2] - b[0]);
        double w = Math.abs(b[3] - b[1]);
        // 여러 editable TF가 한 장식 카드 묶음에 들어간 경우에는 shell의
        // 외곽선/박스도 텍스트보다 뒤에 있어야 한다. CONTAINER_OUTLINE으로
        // 올리면 HWPX의 in-front 평면에서 owned text를 덮는다.
        return h <= 80.0 && w <= 140.0;
    }

    private boolean isCompanionShellOfCompleteSimpleLabel(RenderedGroup rg) {
        if (rg == null) return false;
        if (!"visual_label_text_hidden_shell".equals(rg.reason())) return false;
        return data.shouldUseCompletePngForSimpleButtonLabel(rg);
    }

    private boolean canAbsorbEditableLabelShellAsTextStyle(RenderedGroup rg) {
        if (rg == null || data == null) return false;
        // visual_label_text_hidden_shell is an extractor-owned visual-only shell:
        // text pixels are already hidden and the editable TextFrame is expected
        // to be placed above it.  Absorbing it into drawText style drops the
        // shell, and small badge labels lose both their backdrop and alignment.
        if ("visual_label_text_hidden_shell".equals(rg.reason())) return false;
        if (isCalloutOrOutlineTextShell(rg)) return false;
        if (isLargeVisual(rg) || isLineLikeVisual(rg) || isMaskLikeVisual(rg)) return false;
        if (isImageBackedContentShell(rg) || isPlacedContentImage(rg)) return false;
        if (isPaperStrokeContainerVisual(rg) || isPaperStrokeForegroundMask(rg)) return false;
        if (!looksLikeAbsorbableEditableLabelShell(rg)) return false;
        if (!hasOnlyAbsorbableEditableLabelShellSources(rg)) return false;

        List<ResolvedTextFrame> ownedTextFrames = ownedTextFramesOf(rg);
        if (ownedTextFrames.isEmpty()) return false;
        for (ResolvedTextFrame tf : ownedTextFrames) {
            if (canTextFrameAbsorbVisualStyle(tf)) {
                return true;
            }
        }
        return false;
    }

    private boolean looksLikeAbsorbableEditableLabelShell(RenderedGroup rg) {
        if (rg == null) return false;
        double[] b = rg.bounds();
        if (b == null || b.length < 4) return false;
        double h = Math.abs(b[2] - b[0]);
        double w = Math.abs(b[3] - b[1]);
        if (w < 8.0 || h < 3.0 || h > 28.0 || w > 140.0) return false;
        if (w / Math.max(1.0, h) < 1.15) return false;

        String reason = safe(rg.reason());
        return reason.contains("label")
                || reason.contains("textframe_visual_shell")
                || reason.contains("visual_shell")
                || "hwpx_tf".equals(rg.textOwner())
                || hasEditableTextFrameIds(rg);
    }

    private boolean hasOnlyAbsorbableEditableLabelShellSources(RenderedGroup rg) {
        if (rg == null || data == null) return false;
        boolean hasTextFrame = false;
        boolean hasRect = false;
        for (int id : sourceIdsOrSelf(rg)) {
            ResolvedPageItem item = data.getPageItem(String.valueOf(id));
            if (item == null) continue;
            String type = safe(item.type());
            if ("Group".equals(type)) {
                continue;
            }
            if ("TextFrame".equals(type)) {
                hasTextFrame = true;
                continue;
            }
            if (!"Rectangle".equals(type)) {
                return false;
            }
            if (!isSimpleDrawableShape(item)) return false;
            if (Math.abs(item.absoluteRotationAngle()) > 0.5) return false;
            if (Math.abs(item.absoluteShearAngle()) > 0.5) return false;
            hasRect = true;
        }
        return hasTextFrame && hasRect;
    }

    private List<ResolvedTextFrame> ownedTextFramesOf(RenderedGroup rg) {
        List<ResolvedTextFrame> out = new ArrayList<>();
        if (rg == null) return out;
        LinkedHashSet<String> ids = new LinkedHashSet<>();
        if (rg.editableTextFrameIds() != null) {
            for (String id : rg.editableTextFrameIds()) {
                if (id != null && !id.isBlank()) ids.add(id);
            }
        }
        if (rg.sourceObjectIds() != null) {
            for (int id : rg.sourceObjectIds()) {
                ids.add(String.valueOf(id));
            }
        }
        ids.add(String.valueOf(rg.id()));
        for (String id : ids) {
            ResolvedTextFrame tf = data.getTextFrame(id);
            if (tf != null && !tf.onHiddenLayer() && !tf.nonprinting()) {
                out.add(tf);
            }
        }
        return out;
    }

    private boolean canTextFrameAbsorbVisualStyle(ResolvedTextFrame tf) {
        if (tf == null) return false;
        if (hasOwnTextFrameShapeStyle(tf)) return true;
        return hasAbsorbableSiblingShape(tf);
    }

    private static boolean hasOwnTextFrameShapeStyle(ResolvedTextFrame tf) {
        if (tf == null) return false;
        if (!isNoneColor(tf.fillColor()) && !isPaperColor(tf.fillColor())) return true;
        if (!isNoneColor(tf.strokeColor()) && tf.strokeWeight() > 0.01) return true;
        return false;
    }

    private boolean hasAbsorbableSiblingShape(ResolvedTextFrame tf) {
        ResolvedPageItem tfItem = data.getPageItem(tf.id());
        if (tfItem == null || tfItem.parentId() == null) return false;
        double[] tfBounds = tf.pageRelativeBounds() != null ? tf.pageRelativeBounds() : tf.geometricBounds();
        if (tfBounds == null || tfBounds.length < 4) return false;
        boolean foundAbsorbableRect = false;
        for (ResolvedPageItem item : data.pageItems()) {
            if (item == null || item.id() == null || item.id().equals(tf.id())) continue;
            if (!tfItem.parentId().equals(item.parentId())) continue;
            String type = safe(item.type());
            if ("TextFrame".equals(type)) continue;
            if (!isSimpleDrawableShape(item)) {
                if ("GraphicLine".equals(type)) return false;
                continue;
            }
            if (Math.abs(item.absoluteRotationAngle()) > 0.5) continue;
            if (Math.abs(item.absoluteShearAngle()) > 0.5) continue;
            if (!"Rectangle".equals(type)) return false;
            if (overlapRatio(tfBounds, boundsOf(item)) >= 0.70) {
                foundAbsorbableRect = true;
            } else {
                return false;
            }
        }
        return foundAbsorbableRect;
    }

    private static boolean isSimpleDrawableShape(ResolvedPageItem item) {
        if (item == null) return false;
        String type = safe(item.type());
        if (!("Rectangle".equals(type) || "Polygon".equals(type) || "Oval".equals(type))) {
            return false;
        }
        boolean hasFill = !isNoneColor(item.fillColorName());
        boolean hasStroke = !isNoneColor(item.strokeColorName()) && item.strokeWeight() > 0.01;
        return hasFill || hasStroke || item.cornerRadius() > 0.01;
    }

    private boolean isCalloutOrOutlineTextShell(RenderedGroup rg) {
        if (rg == null || data == null) return false;
        String reason = safe(rg.reason());
        if (!reason.contains("mixed_group_text_hidden")
                && !reason.contains("image_group_text_hidden")
                && !reason.contains("complex_graphic_text_hidden")) {
            return false;
        }
        int textFrames = 0;
        int drawableShapes = 0;
        int polygonShapes = 0;
        int roundedOrStrokedShapes = 0;
        if (rg.sourceObjectIds() != null) {
            for (int id : rg.sourceObjectIds()) {
                ResolvedPageItem item = data.getPageItem(String.valueOf(id));
                if (item == null) continue;
                String type = safe(item.type());
                if ("TextFrame".equals(type)) {
                    textFrames++;
                    continue;
                }
                if (!isSimpleDrawableShape(item)) continue;
                drawableShapes++;
                if ("Polygon".equals(type)) {
                    polygonShapes++;
                }
                boolean hasStroke = !isNoneColor(item.strokeColorName()) && item.strokeWeight() > 0.01;
                if (hasStroke || item.cornerRadius() > 0.01) {
                    roundedOrStrokedShapes++;
                }
            }
        }
        if (textFrames <= 0 || drawableShapes <= 0) return false;

        double[] b = rg.bounds();
        double h = b != null && b.length >= 4 ? Math.abs(b[2] - b[0]) : 0.0;
        double w = b != null && b.length >= 4 ? Math.abs(b[3] - b[1]) : 0.0;
        boolean calloutTail = polygonShapes > 0 && roundedOrStrokedShapes > 0;
        boolean outlineBox = roundedOrStrokedShapes > 0 && (h > 16.0 || w > 45.0);
        return calloutTail || outlineBox;
    }

    private Placement placementOf(RenderedGroup rg) {
        if ("inline_object".equals(rg.type()) || "inline_object".equals(rg.itemType())) {
            return Placement.INLINE;
        }
        if (rg.isPageBackground()) {
            return Placement.FLOATING;
        }
        return Placement.FLOATING;
    }

    private void writePlans() {
        for (ObjectPlan plan : plans) {
            ctx.addOwnershipPlan(plan);
            ctx.ownershipPlanLines.add(plan.toJson());
        }
    }

    private void validate() {
        warnDuplicateVisibleSourceIds();
        warnConflictingTextOwnership();
        warnVisibleVisualContainsHwpxTextSource();
        warnInlineFloatingSameDomId();
        warnDuplicateRenderedBounds();
        warnTextShellZOrder();
    }

    private void resolveInlineFloatingSameDom() {
        Map<String, Boolean> inlineVisibleByPageDom = new HashMap<>();
        for (ObjectPlan plan : plans) {
            if (!plan.hasVisibleVisual()) continue;
            if (plan.placement != Placement.INLINE) continue;
            inlineVisibleByPageDom.put(pageDomKey(plan), Boolean.TRUE);
        }
        for (int i = 0; i < plans.size(); i++) {
            ObjectPlan plan = plans.get(i);
            if (!plan.hasVisibleVisual()) continue;
            if (plan.placement != Placement.FLOATING) continue;
            if (!Boolean.TRUE.equals(inlineVisibleByPageDom.get(pageDomKey(plan)))) continue;
            plans.set(i, plan.withVisualAction(VisualAction.DROP_VISUAL, plan.reason));
        }
    }

    private void resolveDuplicateRenderedChannels() {
        Map<String, List<Integer>> byRenderedIdentity = new LinkedHashMap<>();
        for (int i = 0; i < plans.size(); i++) {
            ObjectPlan plan = plans.get(i);
            if (!plan.hasVisibleVisual()) continue;
            if ("text_frame".equals(plan.kind)) continue;
            if (plan.renderId == null) continue;
            String key = renderedIdentityKey(plan);
            byRenderedIdentity.computeIfAbsent(key, k -> new ArrayList<>()).add(i);
        }
        for (List<Integer> indexes : byRenderedIdentity.values()) {
            if (indexes.size() <= 1) continue;
            int winner = indexes.get(0);
            for (int idx : indexes) {
                if (renderedChannelPriority(plans.get(idx)) < renderedChannelPriority(plans.get(winner))) {
                    winner = idx;
                }
            }
            for (int idx : indexes) {
                if (idx == winner) continue;
                ObjectPlan loser = plans.get(idx);
                plans.set(idx, loser.withVisualAction(VisualAction.DROP_VISUAL, loser.reason));
            }
        }
    }

    private void resolveInlineCompositeHwpxTextParents() {
        for (int i = 0; i < plans.size(); i++) {
            ObjectPlan plan = plans.get(i);
            if (!isVisibleRenderedVisual(plan)) continue;
            if (plan.placement != Placement.INLINE) continue;
            if (plan.visualAction != VisualAction.PLACE_INLINE_PNG) continue;
            if (plan.textAction == TextAction.OWNED_BY_PNG) continue;
            if (plan.sourceObjectIds == null || plan.sourceObjectIds.length <= 1) continue;
            if (!containsHwpxOwnedTextFrameSource(plan)) continue;
            plans.set(i, plan.withVisualAction(VisualAction.DROP_VISUAL,
                    "inline_parent_contains_hwpx_text_sources"));
        }
    }

    private void resolveTextShellSharedSources() {
        Map<String, Boolean> visibleNonShellSources = new HashMap<>();
        for (ObjectPlan plan : plans) {
            if (!plan.hasVisibleVisual()) continue;
            if (plan.visualAction == VisualAction.PLACE_TEXT_SHELL) continue;
            for (int sourceId : plan.sourceObjectIds) {
                visibleNonShellSources.put(pageSourceKey(plan.pageIndex, sourceId), Boolean.TRUE);
            }
        }
        for (int i = 0; i < plans.size(); i++) {
            ObjectPlan plan = plans.get(i);
            if (plan.visualAction != VisualAction.PLACE_TEXT_SHELL) continue;
            List<Integer> retained = new ArrayList<>();
            boolean changed = false;
            for (int sourceId : plan.sourceObjectIds) {
                boolean sourceOwnedByNonShell = Boolean.TRUE.equals(
                        visibleNonShellSources.get(pageSourceKey(plan.pageIndex, sourceId)));
                if (sourceOwnedByNonShell && sourceId != plan.domId) {
                    changed = true;
                    continue;
                }
                retained.add(sourceId);
            }
            if (!changed) continue;
            plans.set(i, plan.withSourceObjectIds(toIntArray(retained)));
        }
    }

    private void resolveVisualBackdropClusterSources() {
        List<ObjectPlan> clusters = new ArrayList<>();
        for (ObjectPlan plan : plans) {
            if (!isVisualBackdropCluster(plan)) continue;
            clusters.add(plan);
        }
        if (clusters.isEmpty()) return;

        for (int i = 0; i < plans.size(); i++) {
            ObjectPlan child = plans.get(i);
            if (!isVisibleRenderedVisual(child)) continue;
            if (isVisualBackdropCluster(child)) continue;
            if ("text_frame".equals(child.kind)) continue;
            if (child.visualLayer == VisualLayer.CONTENT_VISUAL) continue;
            if (child.visualAction == VisualAction.PLACE_TEXT_SHELL) continue;
            for (ObjectPlan cluster : clusters) {
                if (child.pageIndex != cluster.pageIndex) continue;
                if (!sharesAnySource(cluster, child)) continue;
                if (!boundsMostlyOverlap(cluster.bounds, child.bounds, 0.20)
                        && !boundsContains(cluster.bounds, child.bounds, 3.0)) {
                    continue;
                }
                plans.set(i, child.withVisualAction(VisualAction.DROP_VISUAL,
                        "owned_by_visual_backdrop_cluster"));
                break;
            }
        }
    }

    private void resolveCoveredParentGroups() {
        boolean changed;
        do {
            changed = false;
            for (int i = 0; i < plans.size(); i++) {
                ObjectPlan parent = plans.get(i);
                if (!isDroppableParentGroup(parent)) continue;
                LinkedHashSet<Integer> childSources = new LinkedHashSet<>();
                List<ObjectPlan> children = new ArrayList<>();
                int childCount = 0;
                for (int j = 0; j < plans.size(); j++) {
                    if (i == j) continue;
                    ObjectPlan child = plans.get(j);
                    if (!isVisibleRenderedVisual(child)) continue;
                    if (child.pageIndex != parent.pageIndex) continue;
                    if (!isStrictChildPlan(parent, child)) continue;
                    childCount++;
                    children.add(child);
                    for (int sourceId : child.sourceObjectIds) {
                        childSources.add(sourceId);
                    }
                }
                if (childCount == 0) continue;
                if (!coversAllParentSources(parent, childSources)) continue;
                if (parentHasVisiblePixelsOutsideChildren(parent, children)) continue;
                plans.set(i, parent.withVisualAction(VisualAction.DROP_VISUAL, parent.reason));
                changed = true;
            }
        } while (changed);
    }

    private void resolveParentGroupsWithMoreSpecificChildren() {
        for (int i = 0; i < plans.size(); i++) {
            ObjectPlan parent = plans.get(i);
            if (!isDroppableParentGroup(parent)) continue;
            if (parent.visualAction != VisualAction.PLACE_FLOATING_PNG) continue;
            for (int j = 0; j < plans.size(); j++) {
                if (i == j) continue;
                ObjectPlan child = plans.get(j);
                if (!isVisibleRenderedVisual(child)) continue;
                if (child.visualAction == VisualAction.PLACE_TEXT_SHELL) continue;
                if (child.pageIndex != parent.pageIndex) continue;
                if (!isStrictChildPlan(parent, child)) continue;
                if (parentSelfContributesVisibleVisual(parent)
                        || parentHasVisiblePixelsOutsideChildren(parent, Arrays.asList(child))
                        || shouldPreferCompositeParent(parent, child)) {
                    ObjectPlan retainedParent = parent;
                    if (parent.visualLayer == VisualLayer.CONTAINER_OUTLINE
                            && parentHasPaperBackdrop(parent)) {
                        retainedParent = parent.withVisualLayer(VisualLayer.CONTAINER_BACKDROP);
                        plans.set(i, retainedParent);
                    }
                    plans.set(j, child.withVisualAction(VisualAction.DROP_VISUAL, child.reason));
                    parent = retainedParent;
                    continue;
                }
                plans.set(i, parent.withVisualAction(VisualAction.DROP_VISUAL, parent.reason));
                break;
            }
        }
    }

    private void resolveOverlappingImageExportDuplicates() {
        for (int i = 0; i < plans.size(); i++) {
            ObjectPlan a = plans.get(i);
            if (!isImageExportVisual(a)) continue;
            for (int j = i + 1; j < plans.size(); j++) {
                ObjectPlan b = plans.get(j);
                if (!isImageExportVisual(b)) continue;
                if (a.pageIndex != b.pageIndex) continue;
                if (!boundsMostlyOverlap(a.bounds, b.bounds, 0.78)) continue;
                if (isLargeLayeredImageExportPair(a, b)) {
                    ObjectPlan layerA = a.withVisualLayer(VisualLayer.CONTAINER_BACKDROP);
                    ObjectPlan layerB = b.withVisualLayer(VisualLayer.CONTAINER_BACKDROP);
                    plans.set(i, layerA);
                    plans.set(j, layerB);
                    a = layerA;
                    continue;
                }
                if (!isLikelyDuplicateImageExport(a, b)) {
                    if (isLargeLayeredImageExportPair(a, b)) {
                        ObjectPlan layerA = a.withVisualLayer(VisualLayer.CONTAINER_BACKDROP);
                        ObjectPlan layerB = b.withVisualLayer(VisualLayer.CONTAINER_BACKDROP);
                        plans.set(i, layerA);
                        plans.set(j, layerB);
                        a = layerA;
                    }
                    continue;
                }
                if (isBackdropAndContentImagePair(a, b)) continue;
                double aScore = visualInkScore(a);
                double bScore = visualInkScore(b);
                if (Math.abs(aScore - bScore) < 0.004) continue;
                if (aScore > bScore) {
                    plans.set(j, b.withVisualAction(VisualAction.DROP_VISUAL, "duplicate_image_export"));
                } else {
                    plans.set(i, a.withVisualAction(VisualAction.DROP_VISUAL, "duplicate_image_export"));
                    break;
                }
            }
        }
    }

    private void resolveLargeLayeredImageExportBackdrops() {
        for (int i = 0; i < plans.size(); i++) {
            ObjectPlan a = plans.get(i);
            if (!isImageExportVisual(a)) continue;
            for (int j = i + 1; j < plans.size(); j++) {
                ObjectPlan b = plans.get(j);
                if (!isImageExportVisual(b)) continue;
                if (!isLargeLayeredImageExportPair(a, b)) continue;
                ObjectPlan layerA = a.withVisualLayer(VisualLayer.CONTAINER_BACKDROP);
                ObjectPlan layerB = b.withVisualLayer(VisualLayer.CONTAINER_BACKDROP);
                plans.set(i, layerA);
                plans.set(j, layerB);
                a = layerA;
            }
        }
    }

    private void resolveClippedDecorationParents() {
        for (int i = 0; i < plans.size(); i++) {
            ObjectPlan parent = plans.get(i);
            if (!isRenderedClippingParentCandidate(parent)) continue;
            for (int j = 0; j < plans.size(); j++) {
                if (i == j) continue;
                ObjectPlan child = plans.get(j);
                if (!isVisibleRenderedVisual(child)) continue;
                if (child.pageIndex != parent.pageIndex) continue;
                if (!isUnclippedDecorationChild(parent, child)) continue;
                ObjectPlan retainedParent = parent
                        .withVisualAction(VisualAction.PLACE_FLOATING_PNG, parent.reason)
                        .withVisualLayer(VisualLayer.CONTAINER_BACKDROP);
                plans.set(i, retainedParent);
                plans.set(j, child.withVisualAction(VisualAction.DROP_VISUAL,
                        "owned_by_clipped_decoration_parent"));
                parent = retainedParent;
            }
        }
    }

    private boolean isRenderedClippingParentCandidate(ObjectPlan plan) {
        if (plan == null || plan.renderId == null || plan.file == null || plan.file.isBlank()) return false;
        if (plan.placement != Placement.FLOATING) return false;
        if (!plan.kind.startsWith("rendered_floating_item:")) return false;
        String reason = safe(plan.reason);
        if (!reason.contains("complex_graphic")) return false;
        ResolvedPageItem item = data.getPageItem(String.valueOf(plan.domId));
        if (item == null) return false;
        String type = safe(item.type());
        return "Polygon".equals(type) || "Rectangle".equals(type) || "Oval".equals(type);
    }

    private boolean isUnclippedDecorationChild(ObjectPlan parent, ObjectPlan child) {
        if (parent == null || child == null) return false;
        if (!safe(child.reason).contains("decoration")) return false;
        if (!isStrictChildPlan(parent, child)) return false;
        if (!hasSourceParentRelation(parent, child)) return false;
        double parentArea = area(parent.bounds);
        double childArea = area(child.bounds);
        if (parentArea <= 0.0 || childArea <= 0.0) return false;
        return childArea > parentArea * 1.35
                || !boundsContains(parent.bounds, child.bounds, 2.0);
    }

    private boolean hasSourceParentRelation(ObjectPlan parent, ObjectPlan child) {
        if (parent == null || child == null || data == null) return false;
        for (int sourceId : child.sourceObjectIds) {
            ResolvedPageItem item = data.getPageItem(String.valueOf(sourceId));
            if (item == null || item.parentId() == null) continue;
            if (item.parentId().equals(String.valueOf(parent.domId))) {
                return true;
            }
            ResolvedPageItem directParent = data.getPageItem(item.parentId());
            if (directParent != null && String.valueOf(parent.domId).equals(directParent.parentId())) {
                return true;
            }
        }
        return false;
    }

    private void resolveContainerMasksOverIntrudingLabelBackdrops() {
        List<ObjectPlan> labels = new ArrayList<>();
        for (ObjectPlan plan : plans) {
            if (!isVisibleRenderedVisual(plan)) continue;
            if (plan.visualLayer == VisualLayer.LABEL_BACKDROP) {
                labels.add(plan);
            }
        }
        if (labels.isEmpty()) return;

        for (int i = 0; i < plans.size(); i++) {
            ObjectPlan container = plans.get(i);
            if (!isVisibleRenderedVisual(container)) continue;
            if (container.visualAction == VisualAction.PLACE_TEXT_SHELL) continue;
            if (container.visualLayer != VisualLayer.CONTAINER_BACKDROP) continue;
            if (!isPaperLikeContainerFace(container)) continue;
            for (ObjectPlan label : labels) {
                if (label.pageIndex != container.pageIndex) continue;
                if (!labelIntrudesIntoContainerFace(label.bounds, container.bounds)) continue;
                plans.set(i, container.withVisualLayer(VisualLayer.CONTAINER_OUTLINE));
                break;
            }
        }
    }

    private void resolveNestedTextShellSources() {
        for (int i = 0; i < plans.size(); i++) {
            ObjectPlan parent = plans.get(i);
            if (parent.visualAction != VisualAction.PLACE_TEXT_SHELL) continue;
            LinkedHashSet<Integer> childSources = new LinkedHashSet<>();
            for (int j = 0; j < plans.size(); j++) {
                if (i == j) continue;
                ObjectPlan child = plans.get(j);
                if (child.visualAction != VisualAction.PLACE_TEXT_SHELL) continue;
                if (child.pageIndex != parent.pageIndex) continue;
                if (!isStrictChildPlan(parent, child)) continue;
                for (int sourceId : child.sourceObjectIds) {
                    childSources.add(sourceId);
                }
            }
            if (childSources.isEmpty()) continue;
            List<Integer> retained = new ArrayList<>();
            boolean changed = false;
            for (int sourceId : parent.sourceObjectIds) {
                if (sourceId != parent.domId && childSources.contains(sourceId)) {
                    changed = true;
                    continue;
                }
                retained.add(sourceId);
            }
            if (!changed) continue;
            plans.set(i, parent.withSourceObjectIds(toIntArray(retained)));
        }
    }

    private void resolveClusterOwnedTextFrameShells() {
        List<ObjectPlan> clusterShells = new ArrayList<>();
        for (ObjectPlan plan : plans) {
            if (isWideShortMultiTextShell(plan)) {
                clusterShells.add(plan);
            }
        }
        if (clusterShells.isEmpty()) return;

        for (int i = 0; i < plans.size(); i++) {
            ObjectPlan child = plans.get(i);
            if (!isVisibleRenderedVisual(child)) continue;
            if (!"editable_textframe_visual_shell".equals(child.reason)) continue;
            for (ObjectPlan cluster : clusterShells) {
                if (child.pageIndex != cluster.pageIndex) continue;
                if (!boundsMostlyOverlap(cluster.bounds, child.bounds, 0.92)) continue;
                if (!isComparableOrSmallerShell(child, cluster)) continue;
                plans.set(i, child.withVisualAction(VisualAction.DROP_VISUAL,
                        "covered_by_cluster_text_shell"));
                break;
            }
        }
    }

    private void resolveDroppedRenderedTextOwnership() {
        for (int i = 0; i < plans.size(); i++) {
            ObjectPlan plan = plans.get(i);
            if ("text_frame".equals(plan.kind)) continue;
            if (plan.visualAction != VisualAction.DROP_VISUAL) continue;
            if (plan.textAction != TextAction.OWNED_BY_PNG) continue;
            plans.set(i, plan.withTextAction(TextAction.DROP_TEXT));
        }
    }

    private void resolveNonTextVisualEditableTextSources() {
        Map<String, Boolean> hwpxTextSources = new HashMap<>();
        for (ObjectPlan plan : plans) {
            if (plan.textAction != TextAction.OWNED_BY_HWPX_TEXT) continue;
            for (int sourceId : textFrameIdsForPlan(plan)) {
                hwpxTextSources.put(pageSourceKey(plan.pageIndex, sourceId), Boolean.TRUE);
            }
        }
        if (hwpxTextSources.isEmpty()) return;
        for (int i = 0; i < plans.size(); i++) {
            ObjectPlan plan = plans.get(i);
            if (!isVisibleRenderedVisual(plan)) continue;
            if (plan.textAction == TextAction.OWNED_BY_HWPX_TEXT) continue;
            if (plan.textAction == TextAction.OWNED_BY_PNG) continue;
            List<Integer> retained = new ArrayList<>();
            boolean changed = false;
            for (int sourceId : plan.sourceObjectIds) {
                if (sourceId != plan.domId
                        && Boolean.TRUE.equals(hwpxTextSources.get(pageSourceKey(plan.pageIndex, sourceId)))) {
                    changed = true;
                    continue;
                }
                retained.add(sourceId);
            }
            if (!changed) continue;
            plans.set(i, plan.withSourceObjectIds(toIntArray(retained)));
        }
    }

    private void warnDuplicateVisibleSourceIds() {
        Map<String, List<ObjectPlan>> byPageSource = new LinkedHashMap<>();
        for (ObjectPlan plan : plans) {
            if (!plan.hasVisibleVisual()) continue;
            for (int sourceId : plan.sourceObjectIds) {
                String key = plan.pageIndex + ":" + sourceId;
                byPageSource.computeIfAbsent(key, k -> new ArrayList<>()).add(plan);
            }
        }
        Map<String, LinkedHashSet<String>> groupedSources = new LinkedHashMap<>();
        for (Map.Entry<String, List<ObjectPlan>> e : byPageSource.entrySet()) {
            if (e.getValue().size() <= 1) continue;
            String[] parts = e.getKey().split(":", 2);
            String page = parts.length > 0 ? parts[0] : "";
            String source = parts.length > 1 ? parts[1] : e.getKey();
            String planRefs = planRefs(e.getValue());
            String groupKey = "page=" + page + " plans=" + planRefs;
            groupedSources.computeIfAbsent(groupKey, k -> new LinkedHashSet<>()).add(source);
        }
        for (Map.Entry<String, LinkedHashSet<String>> e : groupedSources.entrySet()) {
            warn("DUPLICATE_VISIBLE_SOURCE",
                    e.getKey() + " sources=" + joinSources(e.getValue(), 24));
        }
    }

    private void warnConflictingTextOwnership() {
        Map<Integer, Boolean> pngOwned = new HashMap<>();
        Map<Integer, Boolean> hwpxOwned = new HashMap<>();
        for (ObjectPlan plan : plans) {
            int[] textIds = textFrameIdsForPlan(plan);
            if (textIds.length == 0) continue;
            for (int id : textIds) {
                if (plan.textAction == TextAction.OWNED_BY_PNG) {
                    pngOwned.put(id, Boolean.TRUE);
                } else if (plan.textAction == TextAction.OWNED_BY_HWPX_TEXT) {
                    hwpxOwned.put(id, Boolean.TRUE);
                }
            }
        }
        for (Integer id : pngOwned.keySet()) {
            if (Boolean.TRUE.equals(hwpxOwned.get(id))) {
                warn("CONFLICTING_TEXT_OWNER",
                        "textFrameId=" + id + " has OWNED_BY_PNG and OWNED_BY_HWPX_TEXT");
            }
        }
    }

    private void warnVisibleVisualContainsHwpxTextSource() {
        for (ObjectPlan plan : plans) {
            if (!plan.hasVisibleVisual()) continue;
            if (plan.visualAction == VisualAction.PLACE_TEXT_SHELL) continue;
            if (plan.visualAction == VisualAction.ABSORB_TEXT_STYLE) continue;
            if (plan.visualAction == VisualAction.PLACE_TABLE_STYLE) continue;
            if (plan.textAction != TextAction.OWNED_BY_HWPX_TEXT) continue;
            int[] textIds = textFrameIdsForPlan(plan);
            if (textIds.length == 0) continue;
            warn("VISIBLE_VISUAL_CONTAINS_HWPX_TEXT_SOURCE",
                    "plan=" + planRefs(java.util.Collections.singletonList(plan))
                            + " textFrameIds=" + ObjectPlan.intArrayJson(textIds));
        }
    }

    private void warnInlineFloatingSameDomId() {
        Map<Integer, Boolean> inlineVisible = new HashMap<>();
        Map<Integer, Boolean> floatingVisible = new HashMap<>();
        for (ObjectPlan plan : plans) {
            if (!plan.hasVisibleVisual()) continue;
            if (plan.placement == Placement.INLINE) {
                inlineVisible.put(plan.domId, Boolean.TRUE);
            } else if (plan.placement == Placement.FLOATING) {
                floatingVisible.put(plan.domId, Boolean.TRUE);
            }
        }
        for (Integer id : inlineVisible.keySet()) {
            if (Boolean.TRUE.equals(floatingVisible.get(id))) {
                warn("INLINE_FLOATING_SAME_DOM",
                        "domId=" + id + " has visible inline and floating plans");
            }
        }
    }

    private void warnDuplicateRenderedBounds() {
        Map<String, List<ObjectPlan>> byBounds = new LinkedHashMap<>();
        for (ObjectPlan plan : plans) {
            if (!plan.hasVisibleVisual()) continue;
            if (plan.file == null || plan.file.isEmpty()) continue;
            if (plan.bounds == null || plan.bounds.length < 4) continue;
            String key = plan.pageIndex + ":" + plan.file + ":" + roundedBounds(plan.bounds);
            byBounds.computeIfAbsent(key, k -> new ArrayList<>()).add(plan);
        }
        for (List<ObjectPlan> same : byBounds.values()) {
            if (same.size() <= 1) continue;
            warn("DUPLICATE_VISIBLE_FILE_BOUNDS", "plans=" + planRefs(same));
        }
    }

    private void warnTextShellZOrder() {
        Map<Integer, ResolvedTextFrame> textFrameById = new HashMap<>();
        for (ResolvedTextFrame tf : data.textFrames()) {
            if (tf == null || tf.id() == null) continue;
            int id = parseInt(tf.id(), -1);
            if (id >= 0) textFrameById.put(id, tf);
        }
        for (ObjectPlan plan : plans) {
            if (plan.visualAction != VisualAction.PLACE_TEXT_SHELL) continue;
            for (int id : plan.sourceObjectIds) {
                ResolvedTextFrame tf = textFrameById.get(id);
                if (tf == null) continue;
                if (plan.zOrder >= tf.zOrder()) {
                    warn("TEXT_SHELL_ZORDER_GE_TEXT",
                            "shell=" + plan.domId + " textFrame=" + id
                                    + " shellZ=" + plan.zOrder + " textZ=" + tf.zOrder());
                }
            }
        }
    }

    private int[] textFrameIdsForPlan(ObjectPlan plan) {
        List<Integer> ids = new ArrayList<>();
        for (int sourceId : plan.sourceObjectIds) {
            if (data.getTextFrame(String.valueOf(sourceId)) != null) {
                ids.add(sourceId);
            }
        }
        int[] out = new int[ids.size()];
        for (int i = 0; i < ids.size(); i++) out[i] = ids.get(i);
        return out;
    }

    private void warn(String code, String detail) {
        ctx.ownershipWarningLines.add("{\"code\":\"" + ObjectPlan.escape(code)
                + "\",\"detail\":\"" + ObjectPlan.escape(detail) + "\"}");
    }

    private static boolean hasEditableTextFrameIds(RenderedGroup rg) {
        return rg.editableTextFrameIds() != null && rg.editableTextFrameIds().length > 0;
    }

    private boolean isUnabsorbedHwpxTextStyleInlineVisual(RenderedGroup rg) {
        if (rg == null || data == null) return false;
        if (!"inline_object".equals(rg.itemType()) && !"inline_object".equals(rg.type())) return false;
        if (Boolean.TRUE.equals(rg.containsText()) || Boolean.TRUE.equals(rg.containsEditableText())) return false;
        double[] b = rg.bounds();
        if (b == null || b.length < 4) return false;
        double w = Math.abs(b[3] - b[1]);
        double h = Math.abs(b[2] - b[0]);
        if (w <= 0.0 || h <= 0.0) return false;
        boolean thinTextStyleStrip = h <= 3.0 && w >= 6.0 && w / Math.max(0.1, h) >= 4.0;
        if (!thinTextStyleStrip) return false;

        int id = rg.id();
        for (RenderedGroup owner : allRenderedGroups()) {
            if (owner == null) continue;
            if (!"hwpx_tf".equals(owner.textOwner()) && !hasEditableTextFrameIds(owner)) continue;
            if (!contains(owner.tfInlineVisualIds(), id)
                    && !contains(sourceIdsOrSelf(owner), id)) {
                continue;
            }
            if (hasHwpxOwnedTextFrame(owner)) return true;
        }
        return false;
    }

    private boolean containsHwpxOwnedTextFrameSource(ObjectPlan plan) {
        if (plan == null || plan.sourceObjectIds == null) return false;
        for (int sourceId : plan.sourceObjectIds) {
            ResolvedTextFrame tf = data.getTextFrame(String.valueOf(sourceId));
            if (tf != null && data.isHwpxOwnedTextFrame(tf.id())) {
                return true;
            }
        }
        return false;
    }

    private boolean hasHwpxOwnedTextFrame(RenderedGroup rg) {
        if (rg == null) return false;
        if (rg.editableTextFrameIds() != null) {
            for (String id : rg.editableTextFrameIds()) {
                if (id != null && data.isHwpxOwnedTextFrame(id)) return true;
            }
        }
        if (rg.sourceObjectIds() != null) {
            for (int sourceId : rg.sourceObjectIds()) {
                ResolvedTextFrame tf = data.getTextFrame(String.valueOf(sourceId));
                if (tf != null && data.isHwpxOwnedTextFrame(tf.id())) return true;
            }
        }
        return false;
    }

    private List<RenderedGroup> allRenderedGroups() {
        List<RenderedGroup> out = new ArrayList<>();
        out.addAll(data.allRenderedFloatingItems());
        out.addAll(data.allRenderedGraphicFrames());
        out.addAll(data.allRenderedImageFrames());
        out.addAll(data.allRenderedPdfFrames());
        return out;
    }

    private static int[] sourceIdsOrSelf(RenderedGroup rg) {
        int[] ids = rg.sourceObjectIds();
        if (ids == null || ids.length == 0) return new int[] { rg.id() };
        int[] copy = Arrays.copyOf(ids, ids.length);
        Arrays.sort(copy);
        return copy;
    }

    private int zOrderOf(RenderedGroup rg, VisualAction visualAction, int[] sourceIds) {
        if (visualAction == VisualAction.PLACE_TEXT_SHELL) {
            int textZ = minOwnedTextFrameZOrder(rg, sourceIds);
            if (textZ != Integer.MAX_VALUE) {
                return Math.max(0, textZ - 1);
            }
        }
        return rg.zOrder();
    }

    private int minOwnedTextFrameZOrder(RenderedGroup rg, int[] sourceIds) {
        int min = Integer.MAX_VALUE;
        if (rg.editableTextFrameIds() != null) {
            for (String id : rg.editableTextFrameIds()) {
                ResolvedTextFrame tf = data.getTextFrame(id);
                if (tf != null) min = Math.min(min, tf.zOrder());
            }
        }
        if (sourceIds != null) {
            for (int sourceId : sourceIds) {
                ResolvedTextFrame tf = data.getTextFrame(String.valueOf(sourceId));
                if (tf != null) min = Math.min(min, tf.zOrder());
            }
        }
        return min;
    }

    private static boolean isLineLikeVisual(RenderedGroup rg) {
        double[] b = rg.bounds();
        if (b == null || b.length < 4) return false;
        double h = Math.abs(b[2] - b[0]);
        double w = Math.abs(b[3] - b[1]);
        double min = Math.min(w, h);
        double max = Math.max(w, h);
        if (min <= 2.8 && max >= 12.0) return true;
        String reason = safe(rg.reason());
        return "vector_shape".equals(reason) && min <= 3.5 && max >= 8.0;
    }

    private static boolean isMaskLikeVisual(RenderedGroup rg) {
        if (rg == null) return false;
        if (isLargeVisual(rg)) return false;
        String reason = safe(rg.reason());
        String file = safe(rg.file());
        if (reason.contains("paper") || reason.contains("mask")) return true;
        if (file.contains("mask")) return true;
        return false;
    }

    private boolean isRuleLineGroup(RenderedGroup rg) {
        if (rg == null || data == null) return false;
        String reason = safe(rg.reason());
        if (!reason.contains("decoration") && !reason.contains("line")) return false;
        int graphicLines = 0;
        int filledShapes = 0;
        for (int id : sourceIdsOrSelf(rg)) {
            ResolvedPageItem item = data.getPageItem(String.valueOf(id));
            if (item == null) continue;
            String type = safe(item.type());
            if ("GraphicLine".equals(type)) {
                graphicLines++;
                continue;
            }
            if (isSimpleDrawableShape(item)
                    && !isPaperColor(item.fillColorName())
                    && !isNoneColor(item.fillColorName())) {
                filledShapes++;
            }
        }
        return graphicLines > 0 && filledShapes == 0;
    }

    private boolean isLabelBackdropLike(RenderedGroup rg, TextAction textAction) {
        if (rg == null || isLargeVisual(rg)) return false;
        if (textAction == TextAction.OWNED_BY_PNG) return false;
        if (looksLikeShortLabel(rg)) return true;
        if (isBackdropOfEditableShortLabel(rg)) return true;
        String reason = safe(rg.reason());
        return reason.contains("label") || reason.contains("visual_label");
    }

    private boolean isBackdropOfEditableShortLabel(RenderedGroup rg) {
        if (rg == null || data == null) return false;
        if (!isLabelShapeCandidate(rg)) return false;
        double[] rb = rg.bounds();
        if (rb == null || rb.length < 4) return false;
        for (ResolvedTextFrame tf : data.textFrames()) {
            if (tf == null || tf.onHiddenLayer() || tf.nonprinting()) continue;
            if (tf.pageIndex() != rg.pageIndex()) continue;
            if (!isShortEditableLabelTextFrame(tf)) continue;
            double[] tb = tf.pageRelativeBounds() != null ? tf.pageRelativeBounds() : tf.geometricBounds();
            if (overlapRatio(rb, tb) >= 0.60 || containsCenter(rb, tb)) {
                return true;
            }
        }
        return false;
    }

    private boolean isLabelShapeCandidate(RenderedGroup rg) {
        if (rg == null || isLargeVisual(rg)) return false;
        if (isLineLikeVisual(rg) || isMaskLikeVisual(rg)) return false;
        String reason = safe(rg.reason());
        if (!"vector_shape".equals(reason)
                && !"pure_decoration_group".equals(reason)
                && !"decoration_group".equals(reason)) {
            return false;
        }
        double[] b = rg.bounds();
        if (b == null || b.length < 4) return false;
        double h = Math.abs(b[2] - b[0]);
        double w = Math.abs(b[3] - b[1]);
        if (w < 8.0 || h < 3.0 || h > 40.0 || w > 140.0) return false;
        if (w / Math.max(1.0, h) < 1.15) return false;
        return true;
    }

    private static boolean isShortEditableLabelTextFrame(ResolvedTextFrame tf) {
        if (tf == null) return false;
        String text = safe(tf.frameVisibleText()).replace('\n', ' ').replace('\r', ' ').trim();
        if (text.isEmpty()) return false;
        if (text.length() > 24) return false;
        double[] b = tf.pageRelativeBounds() != null ? tf.pageRelativeBounds() : tf.geometricBounds();
        if (b == null || b.length < 4) return false;
        double h = Math.abs(b[2] - b[0]);
        double w = Math.abs(b[3] - b[1]);
        if (w < 4.0 || h < 2.0 || h > 18.0 || w > 90.0) return false;
        return true;
    }

    private static boolean isImageBackedContentShell(RenderedGroup rg) {
        if (rg == null) return false;
        String reason = safe(rg.reason());
        if (!reason.contains("image_group")) return false;
        String file = safe(rg.file());
        int slash = Math.max(file.lastIndexOf('/'), file.lastIndexOf('\\'));
        String basename = slash >= 0 ? file.substring(slash + 1) : file;
        return basename.startsWith("img_");
    }

    private boolean isBackdropDominantImageShell(RenderedGroup rg) {
        if (rg == null || !isLargeVisual(rg)) return false;
        return whiteOpaqueScore(rg.file()) >= 0.25;
    }

    private boolean isTextFrameBackdropVector(RenderedGroup rg) {
        if (rg == null || data == null) return false;
        if (!"vector_shape".equals(safe(rg.reason()))) return false;
        double[] rb = rg.bounds();
        if (rb == null || rb.length < 4) return false;
        if (!hasDrawableBackdropShapeSource(rg)) return false;

        double rArea = area(rb);
        if (rArea < 20.0) return false;
        for (ResolvedTextFrame tf : data.textFrames()) {
            if (tf == null || tf.id() == null) continue;
            if (tf.pageIndex() != rg.pageIndex()) continue;
            if (tf.onHiddenLayer() || tf.nonprinting()) continue;
            if (data.isTextOwnedByIndesignPng(tf.id())) continue;
            double[] tb = tf.pageRelativeBounds() != null ? tf.pageRelativeBounds() : tf.geometricBounds();
            if (tb == null || tb.length < 4) continue;
            double tArea = area(tb);
            if (tArea < 10.0) continue;
            if (overlapRatio(rb, tb) < 0.58) continue;
            if (rArea > tArea * 1.65 && !boundsContains(rb, tb, 2.0)) continue;
            return true;
        }
        return false;
    }

    private boolean hasDrawableBackdropShapeSource(RenderedGroup rg) {
        for (int id : sourceIdsOrSelf(rg)) {
            ResolvedPageItem item = data.getPageItem(String.valueOf(id));
            if (item == null) continue;
            String type = safe(item.type());
            if (!("Rectangle".equals(type) || "Polygon".equals(type) || "Oval".equals(type))) {
                continue;
            }
            boolean hasFill = !isNoneColor(item.fillColorName());
            boolean hasStroke = !isNoneColor(item.strokeColorName()) && item.strokeWeight() > 0.01;
            if (hasFill || hasStroke || item.cornerRadius() > 0.01) {
                return true;
            }
        }
        return false;
    }

    private static boolean isContainerBackdropLike(RenderedGroup rg) {
        if (rg == null) return false;
        if (isPlacedContentImage(rg)) return false;
        if (isLargeVisual(rg)) return true;
        String reason = safe(rg.reason());
        return reason.contains("container")
                || reason.contains("textframe_visual_shell")
                || reason.contains("visual_shell");
    }

    private boolean isPaperStrokeContainerVisual(RenderedGroup rg) {
        if (rg == null || !isLargeVisual(rg)) return false;
        ResolvedPageItem self = data.getPageItem(String.valueOf(rg.id()));
        if (selfContributesFilledVisual(self)) return false;
        int[] ids = sourceIdsOrSelf(rg);
        for (int id : ids) {
            ResolvedPageItem item = data.getPageItem(String.valueOf(id));
            if (item == null) continue;
            if (!isPaperColor(item.fillColorName())) continue;
            if (item.strokeWeight() <= 0.01) continue;
            if (isNoneColor(item.strokeColorName())) continue;
            String type = safe(item.type());
            if ("Polygon".equals(type) || "Rectangle".equals(type) || "Oval".equals(type)) {
                return true;
            }
        }
        return false;
    }

    private boolean isPaperStrokeForegroundMask(RenderedGroup rg) {
        if (!isPaperStrokeContainerVisual(rg)) return false;
        double[] rb = rg.bounds();
        if (rb == null || rb.length < 4) return false;

        for (RenderedGroup other : data.allRenderedFloatingItems()) {
            if (other == null || other.id() == rg.id()) continue;
            if (other.pageIndex() != rg.pageIndex()) continue;
            if (!looksLikeShortColoredLabel(other)) continue;
            if (partiallyClips(rb, other.bounds())) return true;
        }

        int sourceZ = maxSourceZOrder(rg);
        int sourceOrder = maxSourcePageItemOrder(rg);
        if (sourceZ < 0 && sourceOrder < 0) return false;

        List<ResolvedPageItem> pageItems = data.pageItems();
        for (int i = 0; i < pageItems.size(); i++) {
            ResolvedPageItem item = pageItems.get(i);
            if (item == null || item.id() == null) continue;
            if (item.pageIndex() != rg.pageIndex()) continue;
            if (contains(sourceIdsOrSelf(rg), parseInt(item.id(), -1))) continue;
            if (!looksLikeShortColoredLabel(item)) continue;
            boolean aboveByZ = sourceZ >= 0 && sourceZ > item.zOrder();
            boolean aboveBySourceOrder = sourceOrder >= 0 && sourceOrder > i;
            boolean clipsLabel = partiallyClips(rb, boundsOf(item));
            if (!aboveByZ && !aboveBySourceOrder && !clipsLabel) continue;
            if (intersectsOrContainsCenter(rb, boundsOf(item))) {
                return true;
            }
        }
        return false;
    }

    private boolean isPaperMaskInsideContainerBackdrop(RenderedGroup rg) {
        if (rg == null || !"vector_shape".equals(safe(rg.reason()))) return false;
        double[] rb = rg.bounds();
        if (rb == null || rb.length < 4) return false;
        double maskArea = area(rb);
        if (maskArea < 800.0) return false;
        double pageArea = pageArea(rg.pageIndex());
        if (pageArea > 0.0 && maskArea / pageArea > 0.45) return false;
        if (!isPaperMaskVisual(rg)) return false;

        for (RenderedGroup other : data.allRenderedFloatingItems()) {
            if (other == null || other.id() == rg.id()) continue;
            if (other.pageIndex() != rg.pageIndex()) continue;
            if (!isContainerBackdropCandidate(other)) continue;
            if (boundsMostlyOverlap(rb, other.bounds(), 0.70)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isPlacedContentImage(RenderedGroup rg) {
        if (rg == null) return false;
        String reason = safe(rg.reason());
        if (!"image_export".equals(reason)) return false;
        if (isPageOrSpreadBackdropImage(rg)) return false;
        double[] b = rg.bounds();
        if (b == null || b.length < 4) return false;
        double area = area(b);
        return area >= 400.0;
    }

    private static boolean isPageOrSpreadBackdropImage(RenderedGroup rg) {
        if (rg == null) return false;
        if (!"image_export".equals(safe(rg.reason()))) return false;
        if (Boolean.FALSE.equals(rg.placementAllowed())) return false;
        double[] b = rg.bounds();
        if (b == null || b.length < 4) return false;
        double h = Math.abs(b[2] - b[0]);
        double w = Math.abs(b[3] - b[1]);
        if (w < 180.0 || h < 90.0) return false;
        boolean touchesBleedOrPageEdge = b[0] <= 1.0 || b[1] <= 1.0;
        boolean spansPageOrSpread = w >= 260.0 || h >= 180.0;
        boolean backgroundOrder = rg.zOrder() <= 1 || !rg.zOrderKnown();
        return touchesBleedOrPageEdge && spansPageOrSpread && backgroundOrder;
    }

    private boolean isPaperMaskVisual(RenderedGroup rg) {
        if (whiteOpaqueScore(rg.file()) >= 0.70) return true;
        for (int id : sourceIdsOrSelf(rg)) {
            ResolvedPageItem item = data.getPageItem(String.valueOf(id));
            if (item == null) continue;
            String fill = safe(item.fillColorName());
            if (isPaperColor(fill)) return true;
        }
        return false;
    }

    private boolean isContainerBackdropCandidate(RenderedGroup rg) {
        if (rg == null || !isLargeVisual(rg)) return false;
        String reason = safe(rg.reason());
        if (reason.contains("image_group")
                || reason.contains("container")
                || reason.contains("textframe_visual_shell")
                || reason.contains("visual_shell")) {
            return true;
        }
        if (isFilledContainerBoxBackdrop(rg)) return true;
        if (isOpaquePaperBackdrop(rg)) return true;
        return false;
    }

    private boolean isPaperStrokeBoxBackdrop(RenderedGroup rg) {
        if (rg == null || isLineLikeVisual(rg)) return false;
        String reason = safe(rg.reason());
        if (!("vector_shape".equals(reason)
                || reason.contains("decoration")
                || reason.contains("visual_shell"))) {
            return false;
        }
        double[] rb = rg.bounds();
        if (rb == null || rb.length < 4) return false;
        double h = Math.abs(rb[2] - rb[0]);
        double w = Math.abs(rb[3] - rb[1]);
        if (w < 18.0 || h < 18.0 || (w * h) < 800.0) return false;
        for (int id : sourceIdsOrSelf(rg)) {
            ResolvedPageItem item = data.getPageItem(String.valueOf(id));
            if (isPaperStrokeBoxItem(item)) return true;
        }
        return false;
    }

    private static boolean isPaperStrokeBoxItem(ResolvedPageItem item) {
        if (item == null) return false;
        String type = safe(item.type());
        if (!("Rectangle".equals(type) || "Polygon".equals(type) || "Oval".equals(type))) {
            return false;
        }
        if (!isPaperColor(item.fillColorName())) return false;
        if (item.strokeWeight() <= 0.01) return false;
        if (isNoneColor(item.strokeColorName())) return false;
        double[] b = boundsOf(item);
        if (b == null || b.length < 4) return false;
        double h = Math.abs(b[2] - b[0]);
        double w = Math.abs(b[3] - b[1]);
        return w >= 18.0 && h >= 18.0 && (w * h) >= 800.0;
    }

    private boolean isPaperFillBackdropPatch(RenderedGroup rg) {
        if (rg == null || isLineLikeVisual(rg)) return false;
        if (!"vector_shape".equals(safe(rg.reason()))) return false;
        double[] rb = rg.bounds();
        if (rb == null || rb.length < 4) return false;
        double h = Math.abs(rb[2] - rb[0]);
        double w = Math.abs(rb[3] - rb[1]);
        if (w < 2.0 || h < 2.0 || (w * h) < 40.0) return false;
        for (int id : sourceIdsOrSelf(rg)) {
            ResolvedPageItem item = data.getPageItem(String.valueOf(id));
            if (isPaperFillBackdropPatchItem(item)) return true;
        }
        return false;
    }

    private static boolean isPaperFillBackdropPatchItem(ResolvedPageItem item) {
        if (item == null) return false;
        String type = safe(item.type());
        if (!("Rectangle".equals(type) || "Polygon".equals(type) || "Oval".equals(type))) {
            return false;
        }
        if (!isPaperColor(item.fillColorName())) return false;
        if (item.strokeWeight() > 0.01 && !isNoneColor(item.strokeColorName())) return false;
        double[] b = boundsOf(item);
        if (b == null || b.length < 4) return false;
        double h = Math.abs(b[2] - b[0]);
        double w = Math.abs(b[3] - b[1]);
        return w >= 2.0 && h >= 2.0 && (w * h) >= 40.0;
    }

    private boolean isFilledContainerBoxBackdrop(RenderedGroup rg) {
        if (rg == null || isLineLikeVisual(rg)) return false;
        if (!"vector_shape".equals(safe(rg.reason()))) return false;
        double[] rb = rg.bounds();
        if (rb == null || rb.length < 4) return false;
        double h = Math.abs(rb[2] - rb[0]);
        double w = Math.abs(rb[3] - rb[1]);
        if (w < 24.0 || h < 24.0 || (w * h) < 1800.0) return false;
        for (int id : sourceIdsOrSelf(rg)) {
            ResolvedPageItem item = data.getPageItem(String.valueOf(id));
            if (isFilledContainerBoxItem(item)) return true;
        }
        return false;
    }

    private static boolean isFilledContainerBoxItem(ResolvedPageItem item) {
        if (item == null) return false;
        String type = safe(item.type());
        if (!("Rectangle".equals(type) || "Polygon".equals(type) || "Oval".equals(type))) {
            return false;
        }
        String fill = safe(item.fillColorName());
        if (fill.isEmpty() || isNoneColor(fill) || isPaperColor(fill)) return false;
        if (looksLikeShortColoredLabel(item)) return false;
        double[] b = boundsOf(item);
        if (b == null || b.length < 4) return false;
        double h = Math.abs(b[2] - b[0]);
        double w = Math.abs(b[3] - b[1]);
        if (w < 24.0 || h < 24.0 || (w * h) < 1800.0) return false;
        return true;
    }

    private boolean looksLikeShortColoredLabel(RenderedGroup rg) {
        if (rg == null) return false;
        double[] b = rg.bounds();
        if (b == null || b.length < 4) return false;
        double h = Math.abs(b[2] - b[0]);
        double w = Math.abs(b[3] - b[1]);
        if (w < 8.0 || h < 3.0 || h > 32.0) return false;
        if (w / Math.max(1.0, h) < 1.2) return false;
        if (!"vector_shape".equals(safe(rg.reason()))) return false;
        for (int id : sourceIdsOrSelf(rg)) {
            ResolvedPageItem item = data.getPageItem(String.valueOf(id));
            if (looksLikeShortColoredLabel(item)) return true;
        }
        return false;
    }

    private int maxSourceZOrder(RenderedGroup rg) {
        int max = -1;
        for (int id : sourceIdsOrSelf(rg)) {
            ResolvedPageItem item = data.getPageItem(String.valueOf(id));
            if (item != null) max = Math.max(max, item.zOrder());
        }
        return max;
    }

    private int maxSourcePageItemOrder(RenderedGroup rg) {
        int max = -1;
        int[] ids = sourceIdsOrSelf(rg);
        List<ResolvedPageItem> pageItems = data.pageItems();
        for (int i = 0; i < pageItems.size(); i++) {
            ResolvedPageItem item = pageItems.get(i);
            if (item == null || item.id() == null) continue;
            if (contains(ids, parseInt(item.id(), -1))) {
                max = Math.max(max, i);
            }
        }
        return max;
    }

    private static boolean looksLikeShortColoredLabel(ResolvedPageItem item) {
        if (item == null) return false;
        double[] b = boundsOf(item);
        if (b == null || b.length < 4) return false;
        double h = Math.abs(b[2] - b[0]);
        double w = Math.abs(b[3] - b[1]);
        if (w < 8.0 || h < 3.0 || h > 32.0) return false;
        if (w / Math.max(1.0, h) < 1.2) return false;
        String fill = safe(item.fillColorName());
        if (fill.isEmpty() || isNoneColor(fill) || isPaperColor(fill)) return false;
        String type = safe(item.type());
        return "Rectangle".equals(type) || "Polygon".equals(type) || "Oval".equals(type);
    }

    private static double[] boundsOf(ResolvedPageItem item) {
        if (item == null) return null;
        return item.pageRelativeBounds() != null ? item.pageRelativeBounds() : item.geometricBounds();
    }

    private static boolean intersectsOrContainsCenter(double[] container, double[] item) {
        if (container == null || item == null || container.length < 4 || item.length < 4) return false;
        double overlapTop = Math.max(container[0], item[0]);
        double overlapLeft = Math.max(container[1], item[1]);
        double overlapBottom = Math.min(container[2], item[2]);
        double overlapRight = Math.min(container[3], item[3]);
        if (overlapRight > overlapLeft && overlapBottom > overlapTop) {
            return true;
        }
        double centerY = (item[0] + item[2]) / 2.0;
        double centerX = (item[1] + item[3]) / 2.0;
        return centerY >= container[0] && centerY <= container[2]
                && centerX >= container[1] && centerX <= container[3];
    }

    private static boolean containsCenter(double[] container, double[] item) {
        if (container == null || item == null || container.length < 4 || item.length < 4) return false;
        double centerY = (item[0] + item[2]) / 2.0;
        double centerX = (item[1] + item[3]) / 2.0;
        return centerY >= container[0] && centerY <= container[2]
                && centerX >= container[1] && centerX <= container[3];
    }

    private static boolean partiallyClips(double[] container, double[] item) {
        if (container == null || item == null || container.length < 4 || item.length < 4) return false;
        double overlapTop = Math.max(container[0], item[0]);
        double overlapLeft = Math.max(container[1], item[1]);
        double overlapBottom = Math.min(container[2], item[2]);
        double overlapRight = Math.min(container[3], item[3]);
        if (!(overlapRight > overlapLeft && overlapBottom > overlapTop)) return false;
        return item[0] < container[0] || item[1] < container[1]
                || item[2] > container[2] || item[3] > container[3];
    }

    private boolean isOpaquePaperBackdrop(RenderedGroup rg) {
        if (rg == null || !isLargeVisual(rg)) return false;
        return whiteOpaqueScore(rg.file()) >= 0.25;
    }

    private static boolean looksLikeShortLabel(RenderedGroup rg) {
        double[] b = rg.bounds();
        if (b == null || b.length < 4) return false;
        double h = Math.abs(b[2] - b[0]);
        double w = Math.abs(b[3] - b[1]);
        if (w < 8.0 || h < 3.0) return false;
        if (h > 16.0) return false;
        return w / Math.max(1.0, h) >= 2.0;
    }

    private static boolean isLargeVisual(RenderedGroup rg) {
        double[] b = rg.bounds();
        if (b == null || b.length < 4) return false;
        double h = Math.abs(b[2] - b[0]);
        double w = Math.abs(b[3] - b[1]);
        return w >= 120.0 || h >= 120.0 || (w * h) >= 6000.0;
    }

    private double pageArea(int pageIndex) {
        if (data == null || pageIndex < 0 || pageIndex >= data.pages().size()) return 0.0;
        double[] b = data.pages().get(pageIndex).bounds();
        return area(b);
    }

    private static boolean isPaperColor(String colorName) {
        return "Paper".equals(colorName) || "White".equals(colorName);
    }

    private static boolean isNoneColor(String colorName) {
        if (colorName == null || colorName.isBlank()) return true;
        return "None".equals(colorName) || "[None]".equals(colorName);
    }

    private static String textFrameReason(ResolvedTextFrame tf, TextAction action) {
        if (tf.onHiddenLayer()) return "hidden_layer";
        if (tf.nonprinting()) return "nonprinting";
        if (action == TextAction.OWNED_BY_PNG) return "text_owned_by_indesign_png";
        return "editable_text_frame";
    }

    private static String pageDomKey(ObjectPlan plan) {
        return plan.pageIndex + ":" + plan.domId;
    }

    private static String pageSourceKey(int pageIndex, int sourceId) {
        return pageIndex + ":" + sourceId;
    }

    private static String renderedIdentityKey(ObjectPlan plan) {
        return plan.pageIndex
                + ":" + plan.domId;
    }

    private static int renderedChannelPriority(ObjectPlan plan) {
        if (plan.kind.startsWith("rendered_floating_item:")) return 0;
        if (plan.kind.startsWith("rendered_graphic_frame:")) return 1;
        if (plan.kind.startsWith("rendered_image_frame:")) return 2;
        if (plan.kind.startsWith("rendered_pdf_frame:")) return 3;
        return 10;
    }

    private static boolean isVisibleRenderedVisual(ObjectPlan plan) {
        return plan != null
                && plan.hasVisibleVisual()
                && plan.renderId != null
                && !"text_frame".equals(plan.kind);
    }

    private static boolean isDroppableParentGroup(ObjectPlan plan) {
        if (!isVisibleRenderedVisual(plan)) return false;
        if (plan.visualAction == VisualAction.PLACE_TEXT_SHELL) return false;
        if (plan.placement == Placement.INLINE) return false;
        return plan.sourceObjectIds.length > 1;
    }

    private static boolean isStrictChildPlan(ObjectPlan parent, ObjectPlan child) {
        if (parent.domId == child.domId) return false;
        if (parent.sourceObjectIds.length <= child.sourceObjectIds.length) return false;
        if (containsAll(parent.sourceObjectIds, child.sourceObjectIds)) return true;
        return contains(parent.sourceObjectIds, child.domId);
    }

    private boolean shouldPreferCompositeParent(ObjectPlan parent, ObjectPlan child) {
        if (parent == null || child == null) return false;
        if (!boundsMostlyOverlap(parent.bounds, child.bounds, 0.90)) return false;
        if (parent.sourceObjectIds.length <= child.sourceObjectIds.length) return false;
        double parentScore = visualInkScore(parent);
        double childScore = visualInkScore(child);
        return parentScore > childScore + 0.006;
    }

    private boolean parentHasPaperBackdrop(ObjectPlan parent) {
        if (parent == null || parent.file == null || parent.file.isBlank()) return false;
        return whiteOpaqueScore(parent.file) >= 0.45;
    }

    private boolean parentHasVisiblePixelsOutsideChildren(ObjectPlan parent, List<ObjectPlan> children) {
        if (parent == null || children == null || children.isEmpty()) return false;
        if (parent.file == null || parent.file.isBlank()) return false;
        if (parent.bounds == null || parent.bounds.length < 4) return false;
        File imageFile = new File(parent.file);
        if (!imageFile.isAbsolute()) {
            if (data == null || data.basePath() == null) return false;
            imageFile = new File(data.basePath(), parent.file);
        }
        if (!imageFile.exists()) return false;
        try {
            BufferedImage image = ImageIO.read(imageFile);
            if (image == null) return false;
            int width = image.getWidth();
            int height = image.getHeight();
            if (width <= 0 || height <= 0) return false;
            double parentTop = parent.bounds[0];
            double parentLeft = parent.bounds[1];
            double parentBottom = parent.bounds[2];
            double parentRight = parent.bounds[3];
            double parentW = Math.max(0.0001, parentRight - parentLeft);
            double parentH = Math.max(0.0001, parentBottom - parentTop);
            int[][] childRects = childPixelRects(parent, children, width, height, parentTop, parentLeft, parentW, parentH);
            int step = Math.max(1, (int) Math.sqrt((width * (double) height) / 160_000.0));
            int outsideSamples = 0;
            int outsideVisible = 0;
            for (int y = 0; y < height; y += step) {
                for (int x = 0; x < width; x += step) {
                    if (insideAnyRect(x, y, childRects)) continue;
                    outsideSamples++;
                    if (isNonWhiteVisiblePixel(image.getRGB(x, y))) {
                        outsideVisible++;
                    }
                }
            }
            if (outsideSamples == 0) return false;
            double ratio = outsideVisible / (double) outsideSamples;
            return outsideVisible >= 8 && ratio >= 0.0012;
        } catch (IOException e) {
            return false;
        }
    }

    private static int[][] childPixelRects(ObjectPlan parent, List<ObjectPlan> children,
                                           int imageW, int imageH,
                                           double parentTop, double parentLeft,
                                           double parentW, double parentH) {
        List<int[]> rects = new ArrayList<>();
        for (ObjectPlan child : children) {
            if (child == null || child.bounds == null || child.bounds.length < 4) continue;
            double top = Math.max(parentTop, child.bounds[0]);
            double left = Math.max(parentLeft, child.bounds[1]);
            double bottom = Math.min(parentTop + parentH, child.bounds[2]);
            double right = Math.min(parentLeft + parentW, child.bounds[3]);
            if (bottom <= top || right <= left) continue;
            int x0 = clamp((int) Math.floor((left - parentLeft) / parentW * imageW), 0, imageW);
            int x1 = clamp((int) Math.ceil((right - parentLeft) / parentW * imageW), 0, imageW);
            int y0 = clamp((int) Math.floor((top - parentTop) / parentH * imageH), 0, imageH);
            int y1 = clamp((int) Math.ceil((bottom - parentTop) / parentH * imageH), 0, imageH);
            if (x1 <= x0 || y1 <= y0) continue;
            rects.add(new int[] { x0, y0, x1, y1 });
        }
        return rects.toArray(new int[0][]);
    }

    private static boolean insideAnyRect(int x, int y, int[][] rects) {
        if (rects == null) return false;
        for (int[] rect : rects) {
            if (rect == null || rect.length < 4) continue;
            if (x >= rect[0] && x < rect[2] && y >= rect[1] && y < rect[3]) {
                return true;
            }
        }
        return false;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private static boolean isNonWhiteVisiblePixel(int argb) {
        int alpha = (argb >>> 24) & 0xff;
        if (alpha <= 32) return false;
        int r = (argb >>> 16) & 0xff;
        int g = (argb >>> 8) & 0xff;
        int b = argb & 0xff;
        int max = Math.max(r, Math.max(g, b));
        int min = Math.min(r, Math.min(g, b));
        double brightness = (r + g + b) / 3.0;
        int saturation = max - min;
        return !(brightness >= 245.0 && saturation <= 12);
    }

    private boolean isWideShortMultiTextShell(ObjectPlan plan) {
        if (plan == null || plan.visualAction != VisualAction.PLACE_TEXT_SHELL) return false;
        if (!"mixed_group_text_hidden".equals(plan.reason)) return false;
        if (textFrameIdsForPlan(plan).length < 2) return false;
        if (plan.bounds == null || plan.bounds.length < 4) return false;
        double h = Math.abs(plan.bounds[2] - plan.bounds[0]);
        double w = Math.abs(plan.bounds[3] - plan.bounds[1]);
        return h >= 6.0
                && h <= 28.0
                && w >= 35.0
                && w / Math.max(1.0, h) >= 2.6;
    }

    private static boolean isComparableOrSmallerShell(ObjectPlan child, ObjectPlan cluster) {
        double childArea = area(child != null ? child.bounds : null);
        double clusterArea = area(cluster != null ? cluster.bounds : null);
        if (childArea <= 0.0 || clusterArea <= 0.0) return false;
        return childArea <= clusterArea * 1.35;
    }

    private static boolean isImageExportVisual(ObjectPlan plan) {
        return isVisibleRenderedVisual(plan)
                && plan.placement == Placement.FLOATING
                && "image_export".equals(plan.reason)
                && plan.bounds != null
                && plan.bounds.length >= 4;
    }

    private boolean isBackdropAndContentImagePair(ObjectPlan a, ObjectPlan b) {
        boolean aBackdrop = isFlatImageExportBackdrop(a);
        boolean bBackdrop = isFlatImageExportBackdrop(b);
        if (aBackdrop == bBackdrop) return false;
        ObjectPlan backdrop = aBackdrop ? a : b;
        ObjectPlan content = aBackdrop ? b : a;
        double backdropScore = visualInkScore(backdrop);
        double contentScore = visualInkScore(content);
        return contentScore > backdropScore + 0.035;
    }

    private boolean isFlatImageExportBackdrop(ObjectPlan plan) {
        if (!isImageExportVisual(plan)) return false;
        if (area(plan.bounds) < 1200.0) return false;
        return visualInkScore(plan) <= 0.035;
    }

    private boolean isLargeLayeredImageExportPair(ObjectPlan a, ObjectPlan b) {
        if (!isImageExportVisual(a) || !isImageExportVisual(b)) return false;
        if (a.pageIndex != b.pageIndex) return false;
        if (a.domId == b.domId) return false;
        if (!boundsMostlyOverlap(a.bounds, b.bounds, 0.55)) return false;
        double aArea = area(a.bounds);
        double bArea = area(b.bounds);
        if (Math.min(aArea, bArea) < 6000.0) return false;
        double pageArea = pageArea(a.pageIndex);
        if (pageArea > 0.0) {
            double aRatio = aArea / pageArea;
            double bRatio = bArea / pageArea;
            if (Math.min(aRatio, bRatio) < 0.12 && Math.min(aArea, bArea) < 10000.0) return false;
            if (Math.max(aRatio, bRatio) < 0.18 && Math.max(aArea, bArea) < 14000.0) return false;
        }

        double areaRatio = Math.min(aArea, bArea) / Math.max(1.0, Math.max(aArea, bArea));
        boolean nearSameGeometry = areaRatio >= 0.94 && boundsMostlyOverlap(a.bounds, b.bounds, 0.96);
        boolean nearSamePixels = Math.abs(visualInkScore(a) - visualInkScore(b)) < 0.04
                && Math.abs(whiteOpaqueScore(a.file) - whiteOpaqueScore(b.file)) < 0.04;
        if (sharesAnySource(a, b) && nearSameGeometry) return false;
        return !(nearSameGeometry && nearSamePixels);
    }

    private boolean isFlatImageExportBackdrop(RenderedGroup rg) {
        if (rg == null) return false;
        if (!"image_export".equals(safe(rg.reason()))) return false;
        if (!isLargeVisual(rg)) return false;
        String file = rg.file();
        if (file == null || file.isBlank()) return false;
        Double cached = imageInkScoreCache.get(file);
        double score = cached != null ? cached : readVisualInkScore(file);
        imageInkScoreCache.put(file, score);
        return score <= 0.035;
    }

    private boolean isLikelyDuplicateImageExport(ObjectPlan a, ObjectPlan b) {
        if (sharesAnySource(a, b)) return true;
        double aArea = area(a != null ? a.bounds : null);
        double bArea = area(b != null ? b.bounds : null);
        if (aArea <= 0.0 || bArea <= 0.0) return false;
        double areaRatio = Math.min(aArea, bArea) / Math.max(aArea, bArea);
        if (areaRatio < 0.70) return false;
        if (!boundsMostlyOverlap(a.bounds, b.bounds, 0.86)) return false;
        boolean nearSameGeometry = areaRatio >= 0.94 && boundsMostlyOverlap(a.bounds, b.bounds, 0.96);
        boolean nearSamePixels = Math.abs(visualInkScore(a) - visualInkScore(b)) < 0.04
                && Math.abs(whiteOpaqueScore(a.file) - whiteOpaqueScore(b.file)) < 0.04;
        return nearSameGeometry && nearSamePixels;
    }

    private static boolean boundsMostlyOverlap(double[] a, double[] b, double threshold) {
        if (a == null || b == null || a.length < 4 || b.length < 4) return false;
        double overlapTop = Math.max(a[0], b[0]);
        double overlapLeft = Math.max(a[1], b[1]);
        double overlapBottom = Math.min(a[2], b[2]);
        double overlapRight = Math.min(a[3], b[3]);
        double overlapW = Math.max(0.0, overlapRight - overlapLeft);
        double overlapH = Math.max(0.0, overlapBottom - overlapTop);
        double overlapArea = overlapW * overlapH;
        double aArea = area(a);
        double bArea = area(b);
        double minArea = Math.min(aArea, bArea);
        return minArea > 0.0 && overlapArea / minArea >= threshold;
    }

    private static boolean boundsContains(double[] outer, double[] inner, double tolerance) {
        if (outer == null || inner == null || outer.length < 4 || inner.length < 4) return false;
        return inner[0] >= outer[0] - tolerance
                && inner[1] >= outer[1] - tolerance
                && inner[2] <= outer[2] + tolerance
                && inner[3] <= outer[3] + tolerance;
    }

    private static boolean sharesAnySource(ObjectPlan a, ObjectPlan b) {
        if (a == null || b == null || a.sourceObjectIds == null || b.sourceObjectIds == null) return false;
        for (int ai : a.sourceObjectIds) {
            for (int bi : b.sourceObjectIds) {
                if (ai == bi) return true;
            }
        }
        return false;
    }

    private static boolean isVisualBackdropCluster(ObjectPlan plan) {
        return plan != null && "visual_backdrop_cluster".equals(plan.reason);
    }

    private boolean isPaperLikeContainerFace(ObjectPlan plan) {
        if (plan == null || plan.bounds == null || plan.bounds.length < 4) return false;
        if (isLineLikePlan(plan)) return false;
        double h = Math.abs(plan.bounds[2] - plan.bounds[0]);
        double w = Math.abs(plan.bounds[3] - plan.bounds[1]);
        if (w < 18.0 || h < 18.0 || (w * h) < 800.0) return false;
        for (int sourceId : plan.sourceObjectIds) {
            ResolvedPageItem item = data.getPageItem(String.valueOf(sourceId));
            if (isPaperStrokeBoxItem(item) || isPaperFillBackdropPatchItem(item)) {
                return true;
            }
        }
        return whiteOpaqueScore(plan.file) >= 0.45;
    }

    private static boolean isLineLikePlan(ObjectPlan plan) {
        if (plan == null || plan.bounds == null || plan.bounds.length < 4) return false;
        double h = Math.abs(plan.bounds[2] - plan.bounds[0]);
        double w = Math.abs(plan.bounds[3] - plan.bounds[1]);
        double min = Math.min(w, h);
        double max = Math.max(w, h);
        return min <= 3.5 && max >= 8.0;
    }

    private static boolean labelIntrudesIntoContainerFace(double[] label, double[] container) {
        if (label == null || container == null || label.length < 4 || container.length < 4) return false;
        double y1 = Math.max(label[0], container[0]);
        double x1 = Math.max(label[1], container[1]);
        double y2 = Math.min(label[2], container[2]);
        double x2 = Math.min(label[3], container[3]);
        if (y2 <= y1 || x2 <= x1) return false;
        double labelH = Math.max(1.0, label[2] - label[0]);
        double labelW = Math.max(1.0, label[3] - label[1]);
        double overlapH = y2 - y1;
        double overlapW = x2 - x1;
        double labelCenterY = (label[0] + label[2]) / 2.0;
        boolean crossesContainerTop = label[0] < container[0] && label[2] > container[0];
        boolean centerNearContainerTop = labelCenterY <= container[0] + labelH * 0.25;
        boolean substantialHorizontalOverlap = overlapW / labelW >= 0.45;
        boolean shallowVerticalOverlap = overlapH / labelH >= 0.12 && overlapH / labelH <= 0.72;
        return crossesContainerTop && centerNearContainerTop
                && substantialHorizontalOverlap && shallowVerticalOverlap;
    }

    private static double area(double[] b) {
        if (b == null || b.length < 4) return 0.0;
        return Math.max(0.0, b[3] - b[1]) * Math.max(0.0, b[2] - b[0]);
    }

    private static double overlapRatio(double[] a, double[] b) {
        if (a == null || b == null || a.length < 4 || b.length < 4) return 0.0;
        double y1 = Math.max(a[0], b[0]);
        double x1 = Math.max(a[1], b[1]);
        double y2 = Math.min(a[2], b[2]);
        double x2 = Math.min(a[3], b[3]);
        if (y2 <= y1 || x2 <= x1) return 0.0;
        double overlap = (y2 - y1) * (x2 - x1);
        double denom = Math.min(area(a), area(b));
        return denom > 0.0 ? overlap / denom : 0.0;
    }

    private double visualInkScore(ObjectPlan plan) {
        if (plan == null || plan.file == null || plan.file.isBlank()) return 0.0;
        String key = plan.file;
        Double cached = imageInkScoreCache.get(key);
        if (cached != null) return cached;
        double score = readVisualInkScore(plan.file);
        imageInkScoreCache.put(key, score);
        return score;
    }

    private double whiteOpaqueScore(String file) {
        if (file == null || file.isBlank()) return 0.0;
        Double cached = imageWhiteOpaqueScoreCache.get(file);
        if (cached != null) return cached;
        double score = readWhiteOpaqueScore(file);
        imageWhiteOpaqueScoreCache.put(file, score);
        return score;
    }

    private double readWhiteOpaqueScore(String file) {
        File imageFile = resolveImageFile(file);
        if (!imageFile.exists()) return 0.0;
        try {
            BufferedImage image = ImageIO.read(imageFile);
            if (image == null) return 0.0;
            int width = image.getWidth();
            int height = image.getHeight();
            if (width <= 0 || height <= 0) return 0.0;
            int step = Math.max(1, (int) Math.sqrt((width * (double) height) / 120_000.0));
            int samples = 0;
            int white = 0;
            for (int y = 0; y < height; y += step) {
                for (int x = 0; x < width; x += step) {
                    int argb = image.getRGB(x, y);
                    int alpha = (argb >>> 24) & 0xff;
                    if (alpha <= 240) continue;
                    int r = (argb >>> 16) & 0xff;
                    int g = (argb >>> 8) & 0xff;
                    int b = argb & 0xff;
                    samples++;
                    if (r >= 245 && g >= 245 && b >= 245) {
                        white++;
                    }
                }
            }
            return samples > 0 ? white / (double) samples : 0.0;
        } catch (IOException e) {
            return 0.0;
        }
    }

    private double readVisualInkScore(String file) {
        File imageFile = resolveImageFile(file);
        if (!imageFile.exists()) return 0.0;
        try {
            BufferedImage image = ImageIO.read(imageFile);
            if (image == null) return 0.0;
            int width = image.getWidth();
            int height = image.getHeight();
            if (width <= 0 || height <= 0) return 0.0;
            int step = Math.max(1, (int) Math.sqrt((width * (double) height) / 120_000.0));
            int samples = 0;
            int ink = 0;
            for (int y = 0; y < height; y += step) {
                for (int x = 0; x < width; x += step) {
                    int argb = image.getRGB(x, y);
                    int alpha = (argb >>> 24) & 0xff;
                    if (alpha <= 32) continue;
                    int r = (argb >>> 16) & 0xff;
                    int g = (argb >>> 8) & 0xff;
                    int b = argb & 0xff;
                    int max = Math.max(r, Math.max(g, b));
                    int min = Math.min(r, Math.min(g, b));
                    int saturation = max - min;
                    double brightness = (r + g + b) / 3.0;
                    samples++;
                    if (brightness < 130.0
                            || (brightness < 190.0 && saturation > 25)
                            || (brightness < 235.0 && saturation > 85)) {
                        ink++;
                    }
                }
            }
            return samples > 0 ? ink / (double) samples : 0.0;
        } catch (IOException e) {
            return 0.0;
        }
    }

    private File resolveImageFile(String file) {
        File imageFile = new File(file);
        if (imageFile.isAbsolute()) return imageFile;
        if (data == null || data.basePath() == null) return imageFile;
        return new File(data.basePath(), file);
    }

    private boolean coversAllParentSources(ObjectPlan parent, LinkedHashSet<Integer> childSources) {
        if (parent.sourceObjectIds.length == 0) return false;
        for (int sourceId : parent.sourceObjectIds) {
            if (sourceId == parent.domId && !parentSelfContributesVisibleVisual(parent)) continue;
            if (!childSources.contains(sourceId)) return false;
        }
        return true;
    }

    private boolean parentSelfContributesVisibleVisual(ObjectPlan parent) {
        if (parent == null) return false;
        ResolvedPageItem item = data.getPageItem(String.valueOf(parent.domId));
        return selfContributesFilledVisual(item);
    }

    private static boolean selfContributesFilledVisual(ResolvedPageItem item) {
        if (item == null) return false;
        if (item.opacity() <= 0.01) return false;
        String type = safe(item.type());
        if (!("Polygon".equals(type) || "Rectangle".equals(type) || "Oval".equals(type))) {
            return false;
        }
        String fill = safe(item.fillColorName());
        if (isNoneColor(fill) || isPaperColor(fill)) return false;
        return true;
    }

    private static boolean containsAll(int[] values, int[] candidates) {
        if (values == null || candidates == null) return false;
        for (int candidate : candidates) {
            if (!contains(values, candidate)) return false;
        }
        return true;
    }

    private static boolean contains(int[] values, int candidate) {
        if (values == null) return false;
        for (int value : values) {
            if (value == candidate) return true;
        }
        return false;
    }

    private static int[] toIntArray(List<Integer> values) {
        int[] out = new int[values.size()];
        for (int i = 0; i < values.size(); i++) out[i] = values.get(i);
        return out;
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (Exception e) {
            return fallback;
        }
    }

    private static String roundedBounds(double[] b) {
        StringBuilder sb = new StringBuilder(48);
        for (int i = 0; i < 4; i++) {
            if (i > 0) sb.append(',');
            sb.append(Math.round(b[i] * 10.0) / 10.0);
        }
        return sb.toString();
    }

    private static String planRefs(List<ObjectPlan> plans) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < plans.size(); i++) {
            if (i > 0) sb.append(';');
            ObjectPlan p = plans.get(i);
            sb.append(p.domId)
                    .append('/')
                    .append(p.kind)
                    .append('/')
                    .append(p.visualAction)
                    .append('/')
                    .append(p.visualPolicyLayer())
                    .append('/')
                    .append(p.placement);
        }
        return sb.toString();
    }

    private static String joinSources(LinkedHashSet<String> sources, int limit) {
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (String source : sources) {
            if (count > 0) sb.append(',');
            if (count >= limit) {
                sb.append("...");
                break;
            }
            sb.append(source);
            count++;
        }
        if (sources.size() > limit) {
            sb.append("(total=").append(sources.size()).append(')');
        }
        return sb.toString();
    }

    private static String safe(String value) {
        return value != null ? value : "";
    }
}
