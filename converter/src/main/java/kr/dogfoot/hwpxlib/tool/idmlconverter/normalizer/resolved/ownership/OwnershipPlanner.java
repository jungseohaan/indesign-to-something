package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership;

import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ResolvedBuildContext;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.shared.InlineSemanticLabelPolicy;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLStory;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTable;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.stage3.VisualLayeringRules;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.RenderedGroup;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedData;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedPageItem;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedParagraph;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedRun;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedStory;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedTextFrame;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    private static final double CONCEPT_LABEL_SHELL_MIN_AREA_RATIO = 0.30;
    private static final double CONCEPT_LABEL_SHELL_MAX_AREA_RATIO = 2.60;
    private static final double CONCEPT_LABEL_SHELL_OVERLAP_MIN = 0.55;

    private OwnershipPlanner(ResolvedBuildContext ctx) {
        this.ctx = ctx;
        this.data = ctx != null ? ctx.resolvedData : null;
    }

    public static void runObservation(ResolvedBuildContext ctx) {
        if (ctx == null || ctx.resolvedData == null) return;
        new OwnershipPlanner(ctx).run();
    }

    /**
     * SPEC-036 (가): Stage 2 이후 Stage 3 실행 전, child-of-group/inline-coverage 계열의 시각 억제를
     * ObjectPlan으로 확정한다. 실행기는 plan을 최종 권위로만 해석한다.
     */
    public static void runVisualRefinement(ResolvedBuildContext ctx) {
        if (ctx == null || ctx.resolvedData == null) return;
        new OwnershipPlanner(ctx).runVisualRefinement();
    }

    private void run() {
        planRenderedItems();
        planTextFrames();
        resolveHwpxTextOwnedNonShellVisuals();
        resolveInlineCompositeHwpxTextParents();
        resolvePairedInlinePageObjectChannelOwners();
        resolveInlineFloatingSameDom();
        resolveFloatingChildrenOwnedByInlineParent();
        resolveFloatingPageObjectsOwnedByInlineHwpxText();
        resolveDuplicateRenderedChannels();
        resolveFloatingInlineObjectPageObjectDuplicates();
        resolveVisualBackdropClusterSources();
        resolveTextShellSharedSources();
        resolveCoveredParentGroups();
        resolveParentGroupsWithMoreSpecificChildren();
        resolveOverlappingImageExportDuplicates();
        resolveLargeLayeredImageExportBackdrops();
        resolveClippedDecorationParents();
        resolveContainerMasksOverIntrudingLabelBackdrops();
        resolveLayeredContainerFaces();
        resolveParentTextShellDescendantVisuals();
        resolveCompositeBakedChildVisuals();
        resolveNestedTextShellSources();
        resolveClusterOwnedTextFrameShells();
        normalizeCompositeParentChildSourceSlots();
        resolveNonVisibleFloatingVisuals();
        resolveDroppedRenderedTextOwnership();
        resolveVisibleVisualHwpxTextSourceSlots();
        resolveNonTextVisualEditableTextSources();
        resolveMasterGraphicsWithHwpxTextFallbacks();
        restoreInlineTextShellOwners();
        restoreLeafTextHiddenShellOwners();
        promoteInlineCompanionLeafShellOwners();
        resolveTextShellSourceDuplicates();
        writePlans();
        validate();
        System.err.println("[OwnershipPlanner] observation plans=" + plans.size()
                + " warnings=" + ctx.ownershipWarningLines.size());
    }

    private void runVisualRefinement() {
        ctx.dropVisualForDomIds(ctx.cellInlineEmbeddedDomIds,
                "cell_inline_embedded_visual_owned_by_inline");
        Set<Integer> childOfGroupDrops = computeChildOfGroupSuppression().nonProtected;
        ctx.dropVisualForDomIds(childOfGroupDrops, "child_baked_into_renderable_parent_group");
        ctx.dropVisualForDomIds(computeInlineCoverageSuppression(),
                "visual_owned_by_inline_object_coverage");
    }

    private boolean isPageObject(RenderedGroup rg) {
        return VisualLayeringRules.isPageObject(rg);
    }

    private Set<Integer> collectEditableLabelShells(List<RenderedGroup> floatingItems) {
        Set<Integer> ids = new HashSet<>();
        if (ctx == null || ctx.resolvedData == null || floatingItems == null) return ids;
        for (RenderedGroup rg : floatingItems) {
            if (!isEditableLabelShellCandidate(rg)) continue;
            ids.add(rg.id());
        }
        return ids;
    }

    private Set<Integer> collectConceptDiagramLabelShells(List<RenderedGroup> floatingItems) {
        Set<Integer> ids = new HashSet<>();
        if (ctx == null || ctx.resolvedData == null || floatingItems == null
                || ctx.conceptDiagramTextFrameIds == null || ctx.conceptDiagramTextFrameIds.isEmpty()) {
            return ids;
        }
        for (RenderedGroup rg : floatingItems) {
            if (isConceptDiagramLabelShell(rg)) {
                ids.add(rg.id());
            }
        }
        return ids;
    }

    private boolean isConceptDiagramLabelShell(RenderedGroup rg) {
        if (ctx == null || ctx.resolvedData == null || rg == null) return false;
        if (!isPageObject(rg)) return false;
        if (Boolean.FALSE.equals(rg.placementAllowed())) return false;
        if (!"indesign_png".equals(rg.visualOwner())) return false;
        if (Boolean.TRUE.equals(rg.containsText()) || Boolean.TRUE.equals(rg.containsEditableText())) return false;
        if (!VisualLayeringRules.isTextFrameVisualShellReason(rg.reason())) return false;
        double[] rb = rg.bounds();
        if (rb == null || rb.length < 4 || area(rb) <= 0) return false;
        for (ResolvedTextFrame tf : conceptDiagramTextFramesForPage(rg.pageIndex())) {
            if (isConceptDiagramShellForTextFrame(rg, boundsOf(tf))) {
                return true;
            }
        }
        return false;
    }

    private boolean isConceptDiagramInlineVisualShell(RenderedGroup rg) {
        if (ctx == null || ctx.resolvedData == null || rg == null) return false;
        if (!"inline_object".equals(rg.itemType()) && !"inline_object".equals(rg.type())) return false;
        if (Boolean.FALSE.equals(rg.placementAllowed())) return false;
        if (!"indesign_png".equals(rg.visualOwner())) return false;
        if (!"hwpx_tf".equals(rg.textOwner())) return false;
        if (Boolean.TRUE.equals(rg.containsText()) || Boolean.TRUE.equals(rg.containsEditableText())) return false;
        if (!VisualLayeringRules.isTextFrameVisualShellReason(rg.reason())) return false;
        if (rg.editableTextFrameIds() == null || rg.editableTextFrameIds().length == 0) return false;
        for (String id : rg.editableTextFrameIds()) {
            if (ctx.conceptDiagramTextFrameIds.contains(id)) {
                return true;
            }
        }
        return hasConceptDiagramEditableTextMix(rg);
    }

    private boolean hasConceptDiagramEditableTextMix(RenderedGroup rg) {
        if (ctx == null || ctx.resolvedData == null || rg == null || rg.editableTextFrameIds() == null) {
            return false;
        }
        for (String id : rg.editableTextFrameIds()) {
            if (ctx.conceptDiagramTextFrameIds.contains(id)) return true;
        }
        return false;
    }

    private List<ResolvedTextFrame> conceptDiagramTextFramesForPage(int pageIndex) {
        List<ResolvedTextFrame> frames = new ArrayList<>();
        if (ctx == null || ctx.resolvedData == null || ctx.conceptDiagramTextFrameIds == null) return frames;
        for (String tfId : ctx.conceptDiagramTextFrameIds) {
            if (tfId == null) continue;
            ResolvedTextFrame tf = ctx.resolvedData.getTextFrame(tfId);
            if (tf != null && tf.pageIndex() == pageIndex && hasSemanticText(tf)) {
                frames.add(tf);
            }
        }
        return frames;
    }

    private boolean isConceptDiagramShellForTextFrame(RenderedGroup shell, double[] tfBounds) {
        if (ctx == null || shell == null || tfBounds == null || tfBounds.length < 4) return false;
        double[] rb = shell.bounds();
        if (rb == null || rb.length < 4) return false;
        if (isConceptDiagramShellForTextFrameSameScale(rb, tfBounds)) return true;
        if (ctx.scaleFactor > 0 && Math.abs(ctx.scaleFactor - 1.0) > 0.001) {
            double[] scaled = new double[] {
                    rb[0] * ctx.scaleFactor,
                    rb[1] * ctx.scaleFactor,
                    rb[2] * ctx.scaleFactor,
                    rb[3] * ctx.scaleFactor
            };
            return isConceptDiagramShellForTextFrameSameScale(scaled, tfBounds);
        }
        return false;
    }

    private static boolean isConceptDiagramShellForTextFrameSameScale(double[] shellBounds, double[] tfBounds) {
        double tfArea = area(tfBounds);
        double shellArea = area(shellBounds);
        if (tfArea <= 0 || shellArea <= 0) return false;
        double areaRatio = shellArea / tfArea;
        if (areaRatio < CONCEPT_LABEL_SHELL_MIN_AREA_RATIO
                || areaRatio > CONCEPT_LABEL_SHELL_MAX_AREA_RATIO) {
            return false;
        }
        return overlapArea(shellBounds, tfBounds) / tfArea >= CONCEPT_LABEL_SHELL_OVERLAP_MIN;
    }

    private static double[] boundsOf(ResolvedTextFrame tf) {
        if (tf == null) return null;
        double[] b = tf.pageRelativeBounds();
        if (b != null && b.length >= 4) return b;
        return tf.geometricBounds();
    }

    private boolean isEditableLabelShellCandidate(RenderedGroup rg) {
        return VisualLayeringRules.isEditableLabelShellCandidate(rg);
    }

    private boolean shouldDecomposeToEditableLabelShell(
            RenderedGroup rg, Set<Integer> editableLabelShellIds,
            Map<Integer, RenderedGroup> idToRendered) {
        if (rg == null || editableLabelShellIds == null || editableLabelShellIds.isEmpty()) return false;
        String reason = rg.reason();
        if ("visual_label_text_hidden_shell".equals(reason)
                || "editable_composite_text_hidden_shell".equals(reason)) return false;
        if (rg.childIds() == null || rg.childIds().length == 0) return false;
        boolean hasProtectedShell = false;
        for (int cid : rg.childIds()) {
            if (editableLabelShellIds.contains(cid)) {
                hasProtectedShell = true;
                break;
            }
        }
        if (!hasProtectedShell) return false;
        if (hasSubstantialVisualOutsideEditableLabelShell(rg, editableLabelShellIds, idToRendered)) {
            return false;
        }
        return "visual_label_indesign_png".equals(reason)
                || (reason != null && reason.contains("text_hidden"));
    }

    private boolean hasSubstantialVisualOutsideEditableLabelShell(
            RenderedGroup parent, Set<Integer> editableLabelShellIds,
            Map<Integer, RenderedGroup> idToRendered) {
        if (parent == null || editableLabelShellIds == null || editableLabelShellIds.isEmpty()
                || parent.childIds() == null || parent.childIds().length == 0) {
            return false;
        }
        double[] pb = parent.bounds();
        if (pb == null || pb.length < 4) return false;
        double parentW = Math.max(0, pb[3] - pb[1]);
        double parentH = Math.max(0, pb[2] - pb[0]);
        double parentArea = parentW * parentH;
        if (parentArea <= 0) return false;

        for (int cid : parent.childIds()) {
            if (!editableLabelShellIds.contains(cid)) continue;
            RenderedGroup child = idToRendered != null ? idToRendered.get(cid) : null;
            if (child == null) continue;
            double[] cb = child.bounds();
            if (cb == null || cb.length < 4) continue;
            double childW = Math.max(0, cb[3] - cb[1]);
            double childH = Math.max(0, cb[2] - cb[0]);
            double childArea = childW * childH;
            if (childArea <= 0) continue;

            double below = pb[2] - cb[2];
            double above = cb[0] - pb[0];
            double left = cb[1] - pb[1];
            double right = pb[3] - cb[3];
            boolean hasLargeFrameRemainder = below >= Math.max(8.0, childH * 1.25)
                    || above >= Math.max(8.0, childH * 1.25)
                    || left >= Math.max(12.0, childW * 0.35)
                    || right >= Math.max(12.0, childW * 0.35);
            boolean parentMuchLarger = parentArea / childArea >= 2.4
                    || parentH / Math.max(childH, 0.1) >= 2.2;
            if (hasLargeFrameRemainder && parentMuchLarger) {
                return true;
            }
        }
        return false;
    }

    private boolean shouldPreserveEditableLabelShell(
            RenderedGroup rg, Set<Integer> editableLabelShellIds) {
        if (rg == null) return false;
        if (editableLabelShellIds != null && editableLabelShellIds.contains(rg.id())) return true;
        if (isLeafTextHiddenShell(rg)) return true;
        if (!isEditableLabelShellCandidate(rg)) return false;
        if (!isPageObject(rg) && !"inline_object".equals(rg.itemType()) && !"inline_object".equals(rg.type())) {
            return false;
        }
        if (Boolean.FALSE.equals(rg.placementAllowed())) return false;
        if (!"indesign_png".equals(rg.visualOwner())) return false;
        if (!"hwpx_tf".equals(rg.textOwner())) return false;
        return !Boolean.TRUE.equals(rg.containsText()) && !Boolean.TRUE.equals(rg.containsEditableText());
    }

    private static boolean isLeafTextHiddenShell(RenderedGroup rg) {
        return rg != null
                && "leaf_group_text_hidden_shell".equals(rg.reason())
                && "indesign_png".equals(rg.visualOwner())
                && "hwpx_tf".equals(rg.textOwner())
                && rg.editableTextFrameIds() != null
                && rg.editableTextFrameIds().length > 0
                && !Boolean.TRUE.equals(rg.containsText())
                && !Boolean.TRUE.equals(rg.containsEditableText());
    }

    private boolean shouldKeepPairedInlinePageShell(RenderedGroup rg) {
        if (rg == null || !isPageObject(rg)) return false;
        if (Boolean.FALSE.equals(rg.placementAllowed())) return false;
        if (!"indesign_png".equals(rg.visualOwner())) return false;
        if (!"hwpx_tf".equals(rg.textOwner())) return false;
        if (Boolean.TRUE.equals(rg.containsText()) || Boolean.TRUE.equals(rg.containsEditableText())) return false;
        if (!rg.hasEditableTextHiddenFromPng()) return false;
        String reason = rg.reason() == null ? "" : rg.reason();
        if (!reason.contains("text_composite_editable_text_hidden")
                && !reason.contains("editable_composite_text_hidden_shell")
                && !reason.contains("visual_label_text_hidden_shell")
                && !reason.contains("concept_label_shell")) {
            return false;
        }
        double[] b = rg.bounds();
        if (b == null || b.length < 4) return false;
        double w = b[3] - b[1];
        double h = b[2] - b[0];
        return w >= 6.0 && w <= 95.0
                && h >= 2.0 && h <= 18.0
                && w / Math.max(h, 0.1) >= 1.6;
    }

    private boolean shouldPreserveSourceChild(RenderedGroup child) {
        if (child == null) return false;
        return "visual_label_text_hidden_shell".equals(child.reason())
                || "editable_composite_text_hidden_shell".equals(child.reason())
                || "concept_label_shell".equals(child.reason())
                || "editable_textframe_visual_shell".equals(child.reason());
    }

    private static boolean canSuppressSourceChildren(RenderedGroup rg) {
        if (rg == null || !"hwpx_tf".equals(rg.textOwner())) return false;
        if (rg.sourceObjectIds() == null || rg.sourceObjectIds().length == 0) return false;
        String reason = rg.reason();
        if (reason == null) return false;
        return reason.contains("text_hidden")
                || reason.contains("visual_shell")
                || reason.contains("image_group");
    }

    private boolean shouldSkipByChildPolicy(RenderedGroup rg) {
        if (rg.childIds() == null || rg.childIds().length == 0) return false;

        boolean allChildrenAreEditableTf = true;
        boolean hasEditableTfChild = false;
        boolean anyChildIsInlineObject = false;
        for (int cid : rg.childIds()) {
            if (ctx.resolvedData.isInlineObjectId(cid)) {
                anyChildIsInlineObject = true;
                allChildrenAreEditableTf = false;
                continue;
            }
            if (ctx.resolvedData.isEditableTextFrame(String.valueOf(cid))) {
                hasEditableTfChild = true;
            } else {
                allChildrenAreEditableTf = false;
            }
        }

        if (anyChildIsInlineObject && !rg.hasEditableTextHiddenFromPng()) return true;
        if (hasEditableTfChild && !rg.hasEditableTextHiddenFromPng()) return true;
        return allChildrenAreEditableTf;
    }

    private boolean hasRenderablePng(RenderedGroup rg) {
        if (rg == null || rg.file() == null) return false;
        try {
            File pngFile = new File(ctx.basePath, rg.file());
            return pngFile.exists() && pngFile.length() > 0;
        } catch (Exception e) {
            return false;
        }
    }

    private Set<Integer> collectInlineObjectCoverage(List<RenderedGroup> floatingItems) {
        Set<Integer> coveredIds = new HashSet<>();
        if (floatingItems == null || floatingItems.isEmpty()) return coveredIds;

        boolean changed;
        do {
            int before = coveredIds.size();
            for (RenderedGroup rg : floatingItems) {
                if (rg == null) continue;
                boolean inlineObject = "inline_object".equals(rg.itemType())
                        || "inline_object".equals(rg.type());
                if (inlineObject && ctx.shouldDropVisualByOwnershipPlan(rg)) continue;
                if ((inlineObject && hasPageVisibleInlineBounds(rg)) || coveredIds.contains(rg.id())) {
                    addCoverageIds(rg, coveredIds);
                }
            }
            changed = coveredIds.size() != before;
        } while (changed);
        return coveredIds;
    }

    private boolean hasPageVisibleInlineBounds(RenderedGroup rg) {
        if (rg == null) return false;
        double[] b = rg.bounds();
        if (b == null || b.length < 4) return true;
        int pageIdx = rg.pageIndex();
        double pageWidth = pageWidthMm(pageIdx);
        double pageHeight = pageHeightMm(pageIdx);
        b = normalizeInlineSpreadBoundsToPage(rg, b);
        return b[3] > 0.0 && b[1] < pageWidth
                && b[2] > 0.0 && b[0] < pageHeight;
    }

    private double[] normalizeInlineSpreadBoundsToPage(RenderedGroup rg, double[] bounds) {
        boolean inlineObject = rg != null
                && ("inline_object".equals(rg.itemType()) || "inline_object".equals(rg.type()));
        return inlineObject ? normalizeSpreadBoundsToPage(rg.pageIndex(), bounds) : bounds;
    }

    private double[] normalizeSpreadBoundsToPage(int pageIdx, double[] bounds) {
        if (ctx == null || ctx.resolvedData == null || bounds == null || bounds.length < 4) {
            return bounds;
        }
        if (ctx.resolvedData.pages() == null || pageIdx < 0 || pageIdx >= ctx.resolvedData.pages().size()) {
            return bounds;
        }
        double[] pb = ctx.resolvedData.pages().get(pageIdx).bounds();
        if (pb == null || pb.length < 4) return bounds;

        double pageTop = pb[0];
        double pageLeft = pb[1];
        double pageBottom = pb[2];
        double pageRight = pb[3];
        double pageWidth = pageRight - pageLeft;
        double pageHeight = pageBottom - pageTop;
        if (pageWidth <= 0.0 || pageHeight <= 0.0) return bounds;

        boolean xInSpreadPage = bounds[1] >= pageLeft - 0.5
                && bounds[3] <= pageRight + 0.5
                && pageLeft > 1.0;
        boolean yInSpreadPage = bounds[0] >= pageTop - 0.5
                && bounds[2] <= pageBottom + 0.5
                && pageTop > 1.0;
        boolean xInRightSpreadPage = !xInSpreadPage
                && pageLeft <= 1.0
                && bounds[1] >= pageWidth - 0.5
                && bounds[3] <= pageWidth * 2.0 + 0.5;
        boolean yInBottomSpreadPage = !yInSpreadPage
                && pageTop <= 1.0
                && bounds[0] >= pageHeight - 0.5
                && bounds[2] <= pageHeight * 2.0 + 0.5;
        double scale = ctx.scaleFactor != 0.0 ? ctx.scaleFactor : 1.0;
        double localPageLeft = pageLeft / scale;
        double localPageTop = pageTop / scale;
        double localPageRight = pageRight / scale;
        double localPageBottom = pageBottom / scale;
        double localPageWidth = pageWidth / scale;
        double localPageHeight = pageHeight / scale;
        boolean xInLocalSpreadPage = !xInSpreadPage
                && bounds[1] >= localPageLeft - 0.5
                && bounds[3] <= localPageRight + 0.5
                && localPageLeft > 1.0;
        boolean yInLocalSpreadPage = !yInSpreadPage
                && bounds[0] >= localPageTop - 0.5
                && bounds[2] <= localPageBottom + 0.5
                && localPageTop > 1.0;
        boolean xInRightLocalSpreadPage = !xInSpreadPage
                && !xInRightSpreadPage
                && !xInLocalSpreadPage
                && pageLeft <= 1.0
                && localPageWidth > 0.0
                && bounds[1] >= localPageWidth - 0.5
                && bounds[3] <= localPageWidth * 2.0 + 0.5;
        boolean yInBottomLocalSpreadPage = !yInSpreadPage
                && !yInBottomSpreadPage
                && !yInLocalSpreadPage
                && pageTop <= 1.0
                && localPageHeight > 0.0
                && bounds[0] >= localPageHeight - 0.5
                && bounds[2] <= localPageHeight * 2.0 + 0.5;
        if (xInRightSpreadPage) {
            pageLeft = pageWidth;
            xInSpreadPage = true;
        } else if (xInLocalSpreadPage) {
            pageLeft = localPageLeft;
            xInSpreadPage = true;
        } else if (xInRightLocalSpreadPage) {
            pageLeft = localPageWidth;
            xInSpreadPage = true;
        }
        if (yInBottomSpreadPage) {
            pageTop = pageHeight;
            yInSpreadPage = true;
        } else if (yInLocalSpreadPage) {
            pageTop = localPageTop;
            yInSpreadPage = true;
        } else if (yInBottomLocalSpreadPage) {
            pageTop = localPageHeight;
            yInSpreadPage = true;
        }
        if (!xInSpreadPage && !yInSpreadPage) return bounds;

        return new double[] {
                bounds[0] - (yInSpreadPage ? pageTop : 0.0),
                bounds[1] - (xInSpreadPage ? pageLeft : 0.0),
                bounds[2] - (yInSpreadPage ? pageTop : 0.0),
                bounds[3] - (xInSpreadPage ? pageLeft : 0.0)
        };
    }

    private double pageWidthMm(int pageIdx) {
        double[] b = pageBounds(pageIdx);
        if (b == null || b.length < 4) return 1e9;
        return (b[3] - b[1]) / safeScaleFactor();
    }

    private double pageHeightMm(int pageIdx) {
        double[] b = pageBounds(pageIdx);
        if (b == null || b.length < 4) return 1e9;
        return (b[2] - b[0]) / safeScaleFactor();
    }

    private double[] pageBounds(int pageIdx) {
        if (ctx == null || ctx.resolvedData == null || ctx.resolvedData.pages() == null) return null;
        if (pageIdx < 0 || pageIdx >= ctx.resolvedData.pages().size()) return null;
        return ctx.resolvedData.pages().get(pageIdx).bounds();
    }

    private double safeScaleFactor() {
        return ctx != null && ctx.scaleFactor != 0.0 ? ctx.scaleFactor : 1.0;
    }

    private void addCoverageIds(RenderedGroup rg, Set<Integer> coveredIds) {
        if (rg == null || coveredIds == null) return;
        coveredIds.add(rg.id());
        addAll(rg.sourceObjectIds(), coveredIds);
        addAll(rg.childIds(), coveredIds);
        addAll(rg.childImageIds(), coveredIds);
        addAll(rg.visualOnlyChildIds(), coveredIds);
        addAll(rg.tfInlineVisualIds(), coveredIds);
    }

    private void addAll(int[] ids, Set<Integer> target) {
        if (ids == null || target == null) return;
        for (int id : ids) {
            target.add(id);
        }
    }

    private Set<Integer> computeChildOfGroup(
            Set<Integer> editableLabelShellIds, Set<Integer> conceptDiagramLabelShellIds,
            Map<Integer, Integer> idToPage, Map<Integer, RenderedGroup> idToRendered,
            List<RenderedGroup> floatingItems) {
        Set<Integer> childOfGroup = new HashSet<>();
        for (RenderedGroup rg : floatingItems) {
            if (!canSuppressChildren(rg, editableLabelShellIds, conceptDiagramLabelShellIds,
                    idToPage, idToRendered)) {
                continue;
            }
            int parentPage = rg.pageIndex();
            boolean parentKeepsContainerShell = hasSubstantialVisualOutsideEditableLabelShell(
                    rg, editableLabelShellIds, idToRendered);
            boolean parentIsTextShellOnly =
                    ctx.visualActionByOwnershipPlan(rg) == VisualAction.PLACE_TEXT_SHELL;

            if (rg.childIds() != null) {
                for (int cid : rg.childIds()) {
                    if (editableLabelShellIds.contains(cid) && !parentKeepsContainerShell) continue;
                    if (conceptDiagramLabelShellIds.contains(cid)) continue;
                    if (shouldPreserveSourceChild(idToRendered.get(cid))) continue;
                    if (parentIsTextShellOnly && ctx.resolvedData.isRenderedImageFrameDomId(cid)) continue;
                    Integer cp = idToPage.get(cid);
                    if (cp != null && cp == parentPage)
                        childOfGroup.add(cid);
                }
            }
            if (rg.childImageIds() != null) {
                for (int cid : rg.childImageIds()) {
                    if (editableLabelShellIds.contains(cid) && !parentKeepsContainerShell) continue;
                    if (conceptDiagramLabelShellIds.contains(cid)) continue;
                    if (shouldPreserveSourceChild(idToRendered.get(cid))) continue;
                    if (parentIsTextShellOnly && ctx.resolvedData.isRenderedImageFrameDomId(cid)) continue;
                    Integer cp = idToPage.get(cid);
                    if (cp != null && cp == parentPage)
                        childOfGroup.add(cid);
                }
            }
            if (rg.sourceObjectIds() != null && canSuppressSourceChildren(rg)) {
                for (int cid : rg.sourceObjectIds()) {
                    if (cid == rg.id()) continue;
                    if (conceptDiagramLabelShellIds.contains(cid)) continue;
                    RenderedGroup child = idToRendered.get(cid);
                    if (child == null) continue;
                    if (shouldPreserveSourceChild(child)) continue;
                    if (parentIsTextShellOnly && ctx.resolvedData.isRenderedImageFrameDomId(cid)) continue;
                    Integer cp = idToPage.get(cid);
                    if (cp != null && cp == parentPage) {
                        childOfGroup.add(cid);
                    }
                }
            }
        }
        return childOfGroup;
    }

    private static boolean hasSemanticText(ResolvedTextFrame tf) {
        return !visibleText(tf).isEmpty();
    }

    private static String visibleText(ResolvedTextFrame tf) {
        String text = tf != null ? tf.frameVisibleText() : null;
        if (text == null) return "";
        return text.replace("\uFFFC", "")
                .replace("\u0016", "")
                .replace("\u0018", "")
                .trim();
    }

    private static boolean isCompletePngSimpleButtonLabel(ResolvedBuildContext ctx, RenderedGroup rg) {
        return VisualLayeringRules.isCompletePngSimpleButtonLabel(ctx, rg);
    }

    private static final class ChildOfGroupSuppression {
        final Set<Integer> nonProtected;

        ChildOfGroupSuppression(Set<Integer> nonProtected) {
            this.nonProtected = nonProtected;
        }
    }

    private ChildOfGroupSuppression computeChildOfGroupSuppression() {
        Set<Integer> empty = new HashSet<>();
        List<RenderedGroup> floatingItems = data.allRenderedFloatingItems();
        if (floatingItems == null || floatingItems.isEmpty()) {
            return new ChildOfGroupSuppression(empty);
        }

        Set<Integer> editableLabelShellIds = collectEditableLabelShells(floatingItems);
        Set<Integer> conceptDiagramLabelShellIds = collectConceptDiagramLabelShells(floatingItems);
        Map<Integer, Integer> idToPage = new HashMap<>();
        Map<Integer, RenderedGroup> idToRendered = new HashMap<>();
        for (RenderedGroup rg : floatingItems) {
            if (rg == null) continue;
            idToPage.put(rg.id(), rg.pageIndex());
            idToRendered.putIfAbsent(rg.id(), rg);
        }

        Set<Integer> childOfGroup = computeChildOfGroup(
                editableLabelShellIds, conceptDiagramLabelShellIds,
                idToPage, idToRendered, floatingItems);
        Set<Integer> nonProtected = new HashSet<>();
        for (RenderedGroup rg : floatingItems) {
            if (!childOfGroup.contains(rg.id())) continue;
            boolean conceptDiagramLabelShell = conceptDiagramLabelShellIds.contains(rg.id());
            boolean conceptDiagramInlineShell = isConceptDiagramInlineVisualShell(rg);
            boolean editableLabelShell = shouldPreserveEditableLabelShell(rg, editableLabelShellIds);
            if (conceptDiagramLabelShell || conceptDiagramInlineShell || editableLabelShell) {
                continue;
            }
            nonProtected.add(rg.id());
        }
        return new ChildOfGroupSuppression(nonProtected);
    }

    private boolean canSuppressChildren(
            RenderedGroup rg, Set<Integer> editableLabelShellIds, Set<Integer> conceptDiagramLabelShellIds,
            Map<Integer, Integer> idToPage, Map<Integer, RenderedGroup> idToRendered) {
        if (rg == null) return false;
        if (!VisualLayeringRules.isPageObject(rg)) return false;
        if (ctx.hasOwnershipPlan(rg) && !ctx.hasVisibleVisualByOwnershipPlan(rg)) return false;
        if (data.isInlineObjectId(rg.id())) return false;
        if (!ctx.hasOwnershipPlan(rg) && ctx.resolvedData.shouldKeepVisualLabelTextEditable(rg)) return false;
        if (shouldDecomposeToEditableLabelShell(rg, editableLabelShellIds, idToRendered)) return false;
        if (!ctx.hasOwnershipPlan(rg) && rg.shouldSkipByOwnership()) return false;
        if (!ctx.hasOwnershipPlan(rg) && shouldSkipByChildPolicy(rg)) return false;
        if (idToPage != null && idToPage.getOrDefault(rg.id(), Integer.MAX_VALUE) < 0) return false;
        if (rg.bounds() == null || rg.bounds().length < 4) return false;
        if (!hasRenderablePng(rg)) return false;
        int pageIdx = rg.pageIndex();
        if (pageIdx < 0) return false;
        return data.pages() == null || pageIdx < data.pages().size();
    }

    private Set<Integer> computeInlineCoverageSuppression() {
        Set<Integer> covered = collectInlineObjectCoverage(data.allRenderedFloatingItems());
        Set<Integer> editableLabelShellIds = collectEditableLabelShells(data.allRenderedFloatingItems());
        Set<Integer> conceptDiagramLabelShellIds = collectConceptDiagramLabelShells(data.allRenderedFloatingItems());
        Set<Integer> dropVisual = new HashSet<>();

        for (RenderedGroup rg : data.allRenderedFloatingItems()) {
            if (rg == null) continue;
            if (!covered.contains(rg.id())) continue;
            if (isCompletePngSimpleButtonLabel(ctx, rg)) continue;
            if (isStandaloneGraphicOnlyInlineObject(rg)) continue;

            boolean conceptDiagramInlineShell = isConceptDiagramInlineVisualShell(rg);
            boolean protectedEditableLabelShell = conceptDiagramLabelShellIds.contains(rg.id())
                    || conceptDiagramInlineShell
                    || shouldPreserveEditableLabelShell(rg, editableLabelShellIds);

            boolean coverageSuppress = !protectedEditableLabelShell;
            boolean inlineObjectSuppress = isPageObject(rg)
                    && data.isInlineObjectId(rg.id())
                    && !shouldKeepPairedInlinePageShell(rg);
            if (coverageSuppress || inlineObjectSuppress) {
                dropVisual.add(rg.id());
            }
        }
        return dropVisual;
    }

    private boolean isStandaloneGraphicOnlyInlineObject(RenderedGroup rg) {
        if (rg == null || data == null) return false;
        if (!data.isInlineObjectId(rg.id())) return false;
        if (!"inline_graphic_only".equals(rg.reason())) return false;
        if (!"indesign_png".equals(rg.visualOwner())) return false;
        if (!"none".equals(rg.textOwner())) return false;
        if (Boolean.TRUE.equals(rg.containsText()) || Boolean.TRUE.equals(rg.containsEditableText())) {
            return false;
        }
        if (rg.editableTextFrameIds() != null && rg.editableTextFrameIds().length > 0) {
            return false;
        }
        return rg.file() != null && !rg.file().isEmpty();
    }

    private static boolean isStandaloneGraphicOnlyInlineObjectPlan(ObjectPlan plan) {
        if (plan == null) return false;
        if (plan.placement != Placement.INLINE) return false;
        if (!"inline_graphic_only".equals(plan.reason)) return false;
        if (plan.textAction != TextAction.DROP_TEXT) return false;
        if (plan.ownedTextFrameIds != null && plan.ownedTextFrameIds.length > 0) return false;
        return plan.file != null && !plan.file.isEmpty();
    }

    private void resolvePairedInlinePageObjectChannelOwners() {
        for (int i = 0; i < plans.size(); i++) {
            ObjectPlan pageObject = plans.get(i);
            if (!isVisibleRenderedVisual(pageObject) && !isLeafTextHiddenShellPlan(pageObject)) continue;
            if (!safe(pageObject.kind).contains("page_object")) continue;
            if (pageObject.domId < 0) continue;

            for (int j = 0; j < plans.size(); j++) {
                if (i == j) continue;
                ObjectPlan inline = plans.get(j);
                if (!isRenderedVisualPlan(inline)) continue;
                if (!safe(inline.kind).contains("inline_object")) continue;
                if (inline.pageIndex != pageObject.pageIndex) continue;
                if (inline.domId != pageObject.domId) continue;
                if (!pageObjectShouldOwnPairedInlineChannel(pageObject, inline)) continue;

                ObjectPlan nextPageObject = pageObject;
                if (isLeafTextHiddenShellPlan(pageObject)) {
                    nextPageObject = pageObject
                            .withVisualAction(VisualAction.PLACE_TEXT_SHELL, pageObject.reason)
                            .withVisualLayer(VisualLayer.CONTAINER_BACKDROP);
                    plans.set(i, nextPageObject);
                    alignOwnedTextFramePlans(nextPageObject.ownedTextFrameIds, nextPageObject.placement);
                }
                plans.set(j, inline.withVisualAction(VisualAction.DROP_VISUAL,
                        "owned_by_page_object_channel"));
                pageObject = nextPageObject;
            }
        }
    }

    private boolean pageObjectShouldOwnPairedInlineChannel(ObjectPlan pageObject, ObjectPlan inline) {
        RenderedGroup pageGroup = renderedGroupForPlan(pageObject);
        RenderedGroup inlineGroup = renderedGroupForPlan(inline);
        if (pageGroup == null || inlineGroup == null) return false;
        if (!isRenderedPageObject(pageGroup)) return false;
        if (!"inline_graphic_only".equals(inlineGroup.reason())) return false;

        if (isLeafTextHiddenShellPlan(pageObject)
                && pageObject.ownedTextFrameIds != null
                && pageObject.ownedTextFrameIds.length > 1) {
            return true;
        }
        return "pure_decoration_group".equals(pageGroup.reason())
                && InlineSemanticLabelPolicy.isStandaloneSemanticGraphicInlineGroup(data, inlineGroup);
    }

    private void alignOwnedTextFramePlans(int[] textFrameIds, Placement placement) {
        if (textFrameIds == null || textFrameIds.length == 0 || placement == null) return;
        for (int i = 0; i < plans.size(); i++) {
            ObjectPlan plan = plans.get(i);
            if (plan == null || !"text_frame".equals(plan.kind)) continue;
            if (!contains(textFrameIds, plan.domId)) continue;
            if (plan.placement == placement) continue;
            plans.set(i, plan.withPlacement(placement));
        }
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

    private void resolveHwpxTextOwnedNonShellVisuals() {
        HashSet<Integer> hwpxTextSources = new HashSet<>();
        for (ObjectPlan plan : plans) {
            if (plan == null) continue;
            if (!isTextFramePlanKind(plan.kind)) continue;
            if (plan.textAction != TextAction.OWNED_BY_HWPX_TEXT) continue;
            if (plan.sourceObjectIds == null || plan.sourceObjectIds.length == 0) continue;
            for (int sourceId : plan.sourceObjectIds) {
                ResolvedTextFrame tf = data.getTextFrame(String.valueOf(sourceId));
                if (tf != null) {
                    hwpxTextSources.add(sourceId);
                }
            }
            if (plan.ownedTextFrameIds != null) {
                for (int ownedTfId : plan.ownedTextFrameIds) {
                    if (data.getTextFrame(String.valueOf(ownedTfId)) != null) {
                        hwpxTextSources.add(ownedTfId);
                    }
                }
            }
        }
        if (hwpxTextSources.isEmpty()) return;

        for (int i = 0; i < plans.size(); i++) {
            ObjectPlan plan = plans.get(i);
            if (!isVisibleRenderedVisual(plan)) continue;
            if (plan.placement == Placement.INLINE) continue;
            if (plan.visualAction == VisualAction.PLACE_TEXT_SHELL
                    || plan.visualAction == VisualAction.ABSORB_TEXT_STYLE
                    || plan.visualAction == VisualAction.PLACE_TABLE_STYLE) {
                continue;
            }
            RenderedGroup rendered = renderedGroupForPlan(plan);
            if (rendered != null && isEditableVisualShellWithSeparateHwpxText(rendered)) {
                continue;
            }
            if (rendered != null && hasIndependentContentVisualBesideOwnedText(rendered)) {
                continue;
            }
            if (plan.sourceObjectIds == null || plan.sourceObjectIds.length == 0) continue;
            for (int sourceId : plan.sourceObjectIds) {
                if (hwpxTextSources.contains(sourceId)) {
                    plans.set(i, plan.withVisualAction(VisualAction.DROP_VISUAL,
                            "complete_visual_contains_hwpx_text_source"));
                    break;
                }
            }
        }
    }

    private static boolean isTextFramePlanKind(String kind) {
        return kind != null && (kind.equals("text_frame") || kind.startsWith("text_frame:"));
    }

    private void addPlanForRendered(RenderedGroup rg, String channel) {
        if (rg == null) return;
        Placement placement = placementOf(rg);
        TextAction textAction = textActionOf(rg);
        VisualAction visualAction = visualActionOf(rg, placement, textAction);
        VisualLayer visualLayer = visualLayerOf(rg, visualAction, textAction);
        int[] sourceIds = sourceIdsOrSelf(rg);
        if (hasIndependentContentVisualBesideOwnedText(rg)
                && (visualAction == VisualAction.PLACE_FLOATING_PNG
                || visualAction == VisualAction.PLACE_INLINE_PNG)) {
            sourceIds = independentContentVisualSourceIds(rg, sourceIds);
        }
        int[] ownedTextFrameIds = editableTextFrameIdsOf(rg);
        int[] visualSourceIds = visualSourceIdsForRendered(sourceIds, ownedTextFrameIds, visualAction);
        String sourceBundleKey = sourceBundleKeyOf(rg, sourceIds, ownedTextFrameIds);
        int zOrder = zOrderOf(rg, visualAction, visualSourceIds);
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
                visualSourceIds,
                ownedTextFrameIds,
                new int[0],
                sourceBundleKey,
                zOrder,
                safe(rg.reason()),
                rg.file(),
                rg.bounds(),
                sourceLayerId(rg, sourceIds),
                sourceLayerName(rg, sourceIds),
                sourceLayerIndex(rg, sourceIds)));
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
            IDMLStory idmlStory = loadStory(tf.storyId());
            boolean tableOnlyTextFrame =
                    TableFrameOwnershipPolicy.isTableOnlyTextFrame(tf, idmlStory);
            VisualAction visualAction = tableOnlyTextFrame
                    ? VisualAction.PLACE_TABLE_STYLE
                    : VisualAction.DROP_VISUAL;
            Placement placement = placementOfTextFrame(tf, domId, textAction, visualAction);
            int[] sourceIds = tableOnlyTextFrame
                    ? tableOnlySourceIds(domId, idmlStory)
                    : new int[] { domId };
            plans.add(new ObjectPlan(
                    domId,
                    tableOnlyTextFrame ? "text_frame:table_only" : "text_frame",
                    tf.pageIndex(),
                    textAction,
                    visualAction,
                    VisualLayer.CONTENT_VISUAL,
                    placement,
                    null,
                    sourceIds,
                    sourceIds,
                    new int[] { domId },
                    new int[0],
                    "p" + tf.pageIndex() + ":tf:" + domId,
                    tf.zOrder(),
                    tableOnlyTextFrame ? "table_only_text_frame" : textFrameReason(tf, textAction),
                    null,
                    tf.pageRelativeBounds() != null ? tf.pageRelativeBounds() : tf.geometricBounds(),
                    tf.layerId(),
                    tf.layerName(),
                    tf.layerIndex()));
        }
    }

    private int[] editableTextFrameIdsOf(RenderedGroup rg) {
        if (rg == null || rg.editableTextFrameIds() == null || rg.editableTextFrameIds().length == 0) {
            return new int[0];
        }
        LinkedHashSet<Integer> ids = new LinkedHashSet<>();
        for (String id : rg.editableTextFrameIds()) {
            int parsed = parseFlexibleId(id);
            if (parsed >= 0 && data != null && data.isTextOwnedByIndesignPng(String.valueOf(parsed))) {
                continue;
            }
            if (parsed >= 0) ids.add(parsed);
        }
        return toIntArray(ids);
    }

    private static String sourceBundleKeyOf(RenderedGroup rg, int[] sourceIds, int[] ownedTextFrameIds) {
        if (rg == null) return null;
        StringBuilder sb = new StringBuilder(64);
        sb.append('p').append(rg.pageIndex()).append(":r").append(rg.id());
        if (sourceIds != null && sourceIds.length > 0) {
            sb.append(":s");
            for (int id : sourceIds) sb.append('_').append(id);
        }
        if (ownedTextFrameIds != null && ownedTextFrameIds.length > 0) {
            sb.append(":t");
            for (int id : ownedTextFrameIds) sb.append('_').append(id);
        }
        return sb.toString();
    }

    private static int[] visualSourceIdsForRendered(
            int[] sourceIds,
            int[] ownedTextFrameIds,
            VisualAction visualAction) {
        if (sourceIds == null || sourceIds.length == 0) return new int[0];
        if (ownedTextFrameIds == null || ownedTextFrameIds.length == 0) return sourceIds;
        if (visualAction == VisualAction.DROP_VISUAL || visualAction == VisualAction.ABSORB_TEXT_STYLE
                || visualAction == VisualAction.PLACE_TABLE_STYLE) {
            return sourceIds;
        }
        LinkedHashSet<Integer> ids = new LinkedHashSet<>();
        for (int sourceId : sourceIds) {
            if (!contains(ownedTextFrameIds, sourceId)) {
                ids.add(sourceId);
            }
        }
        return ids.isEmpty() ? sourceIds : toIntArray(ids);
    }

    private String sourceLayerId(RenderedGroup rg, int[] sourceIds) {
        if (rg != null && rg.layerId() != null) return rg.layerId();
        ResolvedPageItem item = firstSourcePageItem(sourceIds);
        return item != null ? item.layerId() : null;
    }

    private String sourceLayerName(RenderedGroup rg, int[] sourceIds) {
        if (rg != null && rg.layerName() != null) return rg.layerName();
        ResolvedPageItem item = firstSourcePageItem(sourceIds);
        return item != null ? item.layerName() : null;
    }

    private int sourceLayerIndex(RenderedGroup rg, int[] sourceIds) {
        if (rg != null && rg.layerIndex() >= 0) return rg.layerIndex();
        ResolvedPageItem item = firstSourcePageItem(sourceIds);
        return item != null ? item.layerIndex() : -1;
    }

    private ResolvedPageItem firstSourcePageItem(int[] sourceIds) {
        if (data == null || sourceIds == null) return null;
        for (int sourceId : sourceIds) {
            ResolvedPageItem item = data.getPageItem(String.valueOf(sourceId));
            if (item != null) return item;
        }
        return null;
    }

    private Placement placementOfTextFrame(
            ResolvedTextFrame tf,
            int domId,
            TextAction textAction,
            VisualAction visualAction) {
        if (tf == null || !tf.isInline()) return Placement.FLOATING;
        if (textAction != TextAction.OWNED_BY_HWPX_TEXT) return Placement.INLINE;
        if (visualAction != VisualAction.DROP_VISUAL) return Placement.INLINE;
        if (hasFloatingTextHiddenShellForTextFrame(tf.id(), domId)) {
            return Placement.FLOATING;
        }
        if (hasInlineTextHiddenShellForTextFrame(tf.id())) {
            return Placement.INLINE;
        }
        return Placement.INLINE;
    }

    private boolean hasInlineTextHiddenShellForTextFrame(String textFrameId) {
        if (data == null || textFrameId == null) return false;
        for (RenderedGroup rg : data.allRenderedFloatingItems()) {
            if (rg == null) continue;
            if (!"indesign_png".equals(rg.visualOwner())) continue;
            if (!"hwpx_tf".equals(rg.textOwner())) continue;
            if (!containsString(rg.editableTextFrameIds(), textFrameId)) continue;
            ObjectPlan shellPlan = findRenderedPlan(rg.id(), rg.file());
            if (shellPlan == null || shellPlan.placement != Placement.INLINE) {
                continue;
            }
            if (!isDirectInlineTextShellReason(shellPlan.reason)) {
                continue;
            }
            return shellPlan.visualAction == VisualAction.PLACE_INLINE_PNG
                    || shellPlan.visualAction == VisualAction.PLACE_TEXT_SHELL;
        }
        return false;
    }

    private static boolean isDirectInlineTextShellReason(String reason) {
        if (reason == null) return false;
        return "visual_label_text_hidden_shell".equals(reason)
                || "editable_textframe_visual_shell".equals(reason)
                || "inline_text_hidden".equals(reason)
                || "leaf_group_text_hidden_shell".equals(reason);
    }

    private boolean renderedGroupClaimsTextFrame(RenderedGroup rg, String textFrameId) {
        if (rg == null || textFrameId == null) return false;
        if (containsString(rg.editableTextFrameIds(), textFrameId)) return true;
        int textFrameDomId = parseFlexibleId(textFrameId);
        if (textFrameDomId < 0 || rg.sourceObjectIds() == null) return false;
        for (int sourceObjectId : rg.sourceObjectIds()) {
            if (sourceObjectId == textFrameDomId) return true;
        }
        return false;
    }

    private boolean hasFloatingTextHiddenShellForTextFrame(String textFrameId, int domId) {
        if (data == null || textFrameId == null || domId < 0) return false;
        for (RenderedGroup rg : data.allRenderedFloatingItems()) {
            if (rg == null) continue;
            if (!"indesign_png".equals(rg.visualOwner())) continue;
            if (!renderedGroupClaimsTextFrame(rg, textFrameId)) continue;
            ObjectPlan shellPlan = findRenderedPlan(rg.id(), rg.file());
            if (shellPlan == null || shellPlan.placement != Placement.FLOATING) {
                continue;
            }
            if (!isDirectInlineTextShellReason(shellPlan.reason)) {
                continue;
            }
            if (shellPlan != null
                    && shellPlan.visualAction != VisualAction.PLACE_FLOATING_PNG
                    && shellPlan.visualAction != VisualAction.PLACE_TEXT_SHELL) {
                continue;
            }
            return true;
        }
        return false;
    }

    private static boolean isRenderedPageObject(RenderedGroup rg) {
        if (rg == null) return false;
        return "page_object".equals(rg.type()) || "page_object".equals(rg.itemType());
    }

    private ObjectPlan findRenderedPlan(int renderId, String file) {
        for (ObjectPlan plan : plans) {
            if (plan == null || plan.renderId == null || plan.renderId != renderId) continue;
            if (file != null && plan.file != null && !file.equals(plan.file)) continue;
            if (plan.hasVisibleVisual()) return plan;
        }
        return null;
    }

    private static boolean containsString(String[] values, String target) {
        if (values == null || target == null) return false;
        for (String value : values) {
            if (target.equals(value)) return true;
        }
        return false;
    }

    private IDMLStory loadStory(String storyId) {
        if (storyId == null || ctx == null || ctx.loadIDMLStory == null) return null;
        try {
            return ctx.loadIDMLStory.apply(storyId);
        } catch (Exception e) {
            return null;
        }
    }

    private static int[] tableOnlySourceIds(int textFrameDomId, IDMLStory story) {
        LinkedHashSet<Integer> ids = new LinkedHashSet<>();
        ids.add(textFrameDomId);
        if (story != null && story.tables() != null) {
            for (IDMLTable table : story.tables()) {
                int tableId = parseFlexibleId(table != null ? table.selfId() : null);
                if (tableId >= 0) ids.add(tableId);
            }
        }
        int[] out = new int[ids.size()];
        int i = 0;
        for (Integer id : ids) out[i++] = id != null ? id : -1;
        return out;
    }

    private TextAction textActionOf(RenderedGroup rg) {
        if (data.shouldUseCompletePngForSimpleButtonLabel(rg)) {
            return TextAction.OWNED_BY_PNG;
        }
        if (data.shouldUseTextlessShellForAtomicMarkerLabel(rg)) {
            return TextAction.OWNED_BY_HWPX_TEXT;
        }
        if (data.isNonCanonicalAtomicObjectRender(rg)) {
            return TextAction.DROP_TEXT;
        }
        if (data.shouldKeepVisualLabelTextEditable(rg)) {
            return TextAction.OWNED_BY_HWPX_TEXT;
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
        if (data.isNonCanonicalAtomicObjectRender(rg)) {
            return VisualAction.DROP_VISUAL;
        }
        if (data.shouldUseTextlessShellForAtomicMarkerLabel(rg)) {
            return VisualAction.PLACE_TEXT_SHELL;
        }
        if (isInlineCompleteGraphicWithSeparateTextHiddenShell(rg)) {
            return VisualAction.DROP_VISUAL;
        }
        if ("image_group_text_hidden".equals(rg.reason())
                && !isImageBackedContentShell(rg)
                && (textAction == TextAction.OWNED_BY_HWPX_TEXT || hasEditableTextOwnerSignal(rg))) {
            return VisualAction.PLACE_TEXT_SHELL;
        }
        if (isInlineCompleteGraphicWithHwpxTextSource(rg, placement, textAction)) {
            return VisualAction.DROP_VISUAL;
        }
        if (isCompanionShellOfCompleteSimpleLabel(rg)) {
            return VisualAction.DROP_VISUAL;
        }
        if (isCompleteVisualLabelWithEditableText(rg)) {
            return VisualAction.DROP_VISUAL;
        }
        if (Boolean.FALSE.equals(rg.placementAllowed())
                && (textAction != TextAction.OWNED_BY_HWPX_TEXT || !hasEditableTextOwnerSignal(rg))) {
            return VisualAction.DROP_VISUAL;
        }
        if (isLabelBackdropGroupWithUnclaimedHwpxText(rg)) {
            return VisualAction.DROP_VISUAL;
        }
        if (isLabelBackdropGroupWithForeignSources(rg)) {
            return VisualAction.DROP_VISUAL;
        }
        if (isTextCardBackdropVector(rg)) {
            return VisualAction.PLACE_TEXT_SHELL;
        }
        if (isUnabsorbedHwpxTextStyleInlineVisual(rg)) {
            return VisualAction.DROP_VISUAL;
        }
        if (textAction == TextAction.OWNED_BY_HWPX_TEXT
                && ("hwpx_tf".equals(rg.textOwner())
                || Boolean.TRUE.equals(rg.containsEditableText())
                || hasEditableTextFrameIds(rg))) {
            if (isEditableVisualShellWithSeparateHwpxText(rg)) {
                return VisualAction.PLACE_TEXT_SHELL;
            }
            if (hasIndependentContentVisualBesideOwnedText(rg)) {
                return placement == Placement.INLINE
                        ? VisualAction.PLACE_INLINE_PNG
                        : VisualAction.PLACE_FLOATING_PNG;
            }
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
        if (visualAction != VisualAction.PLACE_TEXT_SHELL
                && hasIndependentContentVisualBesideOwnedText(rg)) {
            return VisualLayer.CONTENT_VISUAL;
        }
        if (visualAction == VisualAction.PLACE_TEXT_SHELL) {
            if (isTextCardBackdropVector(rg)) {
                return VisualLayer.TEXT_CARD_BACKDROP;
            }
            if (isEditableLabelCardShell(rg)) {
                return VisualLayer.LABEL_BACKDROP;
            }
            if (isCalloutOrOutlineTextShell(rg)) {
                return VisualLayer.CONTAINER_OUTLINE;
            }
            if ("visual_label_text_hidden_shell".equals(rg.reason())
                    || "editable_composite_text_hidden_shell".equals(rg.reason())) {
                return VisualLayer.LABEL_BACKDROP;
            }
            if (isEditableVisualShellWithSeparateHwpxText(rg)) {
                if (isImageBackedContentShell(rg) && !isBackdropDominantImageShell(rg)) {
                    return VisualLayer.CONTENT_VISUAL;
                }
                return isLabelReason(rg) ? VisualLayer.LABEL_BACKDROP : VisualLayer.CONTAINER_BACKDROP;
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
            return isLabelReason(rg) ? VisualLayer.LABEL_BACKDROP : VisualLayer.CONTAINER_BACKDROP;
        }
        if ("visual_backdrop_cluster".equals(rg.reason())) {
            return VisualLayer.CONTAINER_BACKDROP;
        }
        if (isPaperStrokeForegroundMask(rg)) {
            return VisualLayer.FOREGROUND_MASK;
        }
        if (isPaperMaskInsideContainerBackdrop(rg)) {
            return VisualLayer.FOREGROUND_MASK;
        }
        if (isTextCardBackdropVector(rg)) {
            return VisualLayer.TEXT_CARD_BACKDROP;
        }
        if (isOverlayMarkerDecoration(rg)) {
            return VisualLayer.LABEL_OVERLAY_BACKDROP;
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
            return VisualLayer.CONTAINER_BACKDROP;
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
        if ("label_backdrop_group".equals(rg.reason())) {
            return VisualLayer.LABEL_BACKDROP;
        }
        if (isLabelBackdropLike(rg, textAction)) {
            return VisualLayer.LABEL_BACKDROP;
        }
        if (isContainerBackdropLike(rg)) {
            return VisualLayer.CONTAINER_BACKDROP;
        }
        return VisualLayer.CONTENT_VISUAL;
    }

    private boolean isOverlayMarkerDecoration(RenderedGroup rg) {
        if (rg == null || data == null || isLargeVisual(rg)) return false;
        if (isLineLikeVisual(rg) || isMaskLikeVisual(rg)) return false;
        String reason = safe(rg.reason());
        if (!"decoration_group".equals(reason)
                && !"pure_decoration_group".equals(reason)
                && !"vector_shape".equals(reason)) {
            return false;
        }
        double[] b = rg.bounds();
        if (b == null || b.length < 4) return false;
        double h = Math.abs(b[2] - b[0]);
        double w = Math.abs(b[3] - b[1]);
        double min = Math.min(w, h);
        double max = Math.max(w, h);
        if (min < 3.0 || max > 18.0 || min / Math.max(1.0, max) < 0.55) return false;

        boolean hasColoredOval = false;
        boolean hasPaperMarker = false;
        for (int id : sourceIdsOrSelf(rg)) {
            ResolvedPageItem item = data.getPageItem(String.valueOf(id));
            if (item == null) continue;
            String type = safe(item.type());
            String fill = safe(item.fillColorName());
            if ("Oval".equals(type)
                    && !fill.isEmpty()
                    && !isNoneColor(fill)
                    && !isPaperColor(fill)) {
                hasColoredOval = true;
            }
            if (("Polygon".equals(type) || "GraphicLine".equals(type) || "Oval".equals(type))
                    && isPaperColor(fill)) {
                hasPaperMarker = true;
            }
        }
        return hasColoredOval && hasPaperMarker;
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
        // w 상한 180: 작품+구조도/개관처럼 알약 옆에 넓은 빈 영역이 붙어 bounds가 넓은
        // 테이블 셀 배지(w≈173)도 backdrop(텍스트 뒤)으로 잡는다. 알약은 불투명이라
        // foreground로 두면 셀 텍스트를 가린다.
        return h <= 80.0 && w <= 180.0;
    }

    private boolean hasIndependentContentVisualBesideOwnedText(RenderedGroup rg) {
        if (rg == null || data == null) return false;
        String reason = safe(rg.reason());
        if (!reason.contains("mixed_group_text_hidden")
                && !reason.contains("image_group_text_hidden")
                && !reason.contains("complex_graphic_text_hidden")) {
            return false;
        }
        if (!"hwpx_tf".equals(rg.textOwner()) && !hasEditableTextFrameIds(rg)) {
            return false;
        }
        List<double[]> textBounds = new ArrayList<>();
        List<double[]> visualBounds = new ArrayList<>();
        int drawableVisuals = 0;
        for (int id : sourceIdsOrSelf(rg)) {
            ResolvedPageItem item = data.getPageItem(String.valueOf(id));
            if (item == null) continue;
            String type = safe(item.type());
            double[] b = boundsOf(item);
            if (b == null || b.length < 4 || area(b) <= 0.0) continue;
            if ("TextFrame".equals(type)) {
                textBounds.add(b);
                continue;
            }
            if ("Group".equals(type)) continue;
            if (!isSubstantialDrawableContentVisual(item, b)) continue;
            visualBounds.add(b);
            drawableVisuals++;
        }
        if (textBounds.isEmpty() || visualBounds.isEmpty() || drawableVisuals == 0) return false;

        for (double[] vb : visualBounds) {
            boolean overlapsOwnedText = false;
            for (double[] tb : textBounds) {
                if (overlapRatio(vb, tb) >= 0.35 || containsCenter(vb, tb) || containsCenter(tb, vb)) {
                    overlapsOwnedText = true;
                    break;
                }
            }
            if (!overlapsOwnedText) return true;
        }
        return false;
    }

    private int[] independentContentVisualSourceIds(RenderedGroup rg, int[] fallback) {
        if (rg == null || data == null) return fallback;
        LinkedHashSet<Integer> ids = new LinkedHashSet<>();
        for (int id : sourceIdsOrSelf(rg)) {
            ResolvedPageItem item = data.getPageItem(String.valueOf(id));
            if (item == null) continue;
            double[] b = boundsOf(item);
            if (isSubstantialDrawableContentVisual(item, b)) {
                ids.add(id);
            }
        }
        if (ids.isEmpty()) return fallback;
        int[] out = new int[ids.size()];
        int i = 0;
        for (Integer id : ids) out[i++] = id != null ? id : -1;
        return out;
    }

    private static boolean isSubstantialDrawableContentVisual(ResolvedPageItem item, double[] b) {
        if (item == null || b == null || b.length < 4) return false;
        String type = safe(item.type());
        if (!("Rectangle".equals(type) || "Polygon".equals(type) || "Oval".equals(type)
                || "GraphicLine".equals(type) || "Image".equals(type))) {
            return false;
        }
        double w = Math.abs(b[3] - b[1]);
        double h = Math.abs(b[2] - b[0]);
        if (w < 3.0 || h < 3.0 || area(b) < 18.0) return false;
        if ("GraphicLine".equals(type) && Math.min(w, h) < 1.5) return false;
        if ("Image".equals(type)) return true;
        boolean hasFill = !isNoneColor(item.fillColorName()) && !isPaperColor(item.fillColorName());
        boolean hasStroke = !isNoneColor(item.strokeColorName()) && item.strokeWeight() > 0.01;
        return hasFill || hasStroke;
    }

    private boolean isCompanionShellOfCompleteSimpleLabel(RenderedGroup rg) {
        if (rg == null) return false;
        if (!"visual_label_text_hidden_shell".equals(rg.reason())) return false;
        if (data.shouldUseTextlessShellForAtomicMarkerLabel(rg)) return false;
        return data.shouldUseCompletePngForSimpleButtonLabel(rg);
    }

    private boolean isCompleteVisualLabelWithEditableText(RenderedGroup rg) {
        if (rg == null || data == null) return false;
        return data.shouldKeepVisualLabelTextEditable(rg);
    }

    private boolean canAbsorbEditableLabelShellAsTextStyle(RenderedGroup rg) {
        if (rg == null || data == null) return false;
        // visual_label_text_hidden_shell is an extractor-owned visual-only shell:
        // text pixels are already hidden and the editable TextFrame is expected
        // to be placed above it.  Absorbing it into drawText style drops the
        // shell, and small badge labels lose both their backdrop and alignment.
        if ("visual_label_text_hidden_shell".equals(rg.reason())
                || "editable_composite_text_hidden_shell".equals(rg.reason())) return false;
        if (isCalloutOrOutlineTextShell(rg)) return false;
        if (isLargeVisual(rg) || isLineLikeVisual(rg) || isMaskLikeVisual(rg)) return false;
        if (isImageBackedContentShell(rg) || isPlacedContentImage(rg)) return false;
        if (isPaperStrokeContainerVisual(rg) || isPaperStrokeForegroundMask(rg)) return false;
        if (!looksLikeAbsorbableEditableLabelShell(rg)) return false;
        if (!hasOnlyAbsorbableEditableLabelShellSources(rg)) return false;

        List<ResolvedTextFrame> ownedTextFrames = ownedTextFramesOf(rg);
        if (ownedTextFrames.isEmpty()) return false;
        if (safe(rg.reason()).contains("text_composite_editable_text_hidden")) {
            return false;
        }
        for (ResolvedTextFrame tf : ownedTextFrames) {
            if (canTextFrameAbsorbVisualStyle(tf)) {
                return true;
            }
        }
        return false;
    }

    private boolean isEditableVisualShellWithSeparateHwpxText(RenderedGroup rg) {
        if (rg == null) return false;
        if (!hasEditableTextOwnerSignal(rg)) return false;
        return isExtractedTextlessVisual(rg);
    }

    private boolean isExtractedTextlessVisual(RenderedGroup rg) {
        if (rg == null) return false;
        if (!"indesign_png".equals(rg.visualOwner())) return false;
        if (rg.file() == null || rg.file().isEmpty()) return false;
        if (Boolean.TRUE.equals(rg.containsText()) || Boolean.TRUE.equals(rg.containsEditableText())) {
            return false;
        }
        return rg.hasEditableTextHiddenFromPng();
    }

    private boolean isInlineCompleteGraphicWithHwpxTextSource(RenderedGroup rg, Placement placement, TextAction textAction) {
        if (rg == null || placement != Placement.INLINE) return false;
        if (textAction != TextAction.OWNED_BY_HWPX_TEXT) return false;
        if (!"inline_graphic_only".equals(rg.reason())) return false;
        if (Boolean.TRUE.equals(rg.containsText()) || Boolean.TRUE.equals(rg.containsEditableText())) return true;
        return hasEditableTextOwnerSignal(rg);
    }

    private boolean isInlineCompleteGraphicWithSeparateTextHiddenShell(RenderedGroup rg) {
        if (rg == null || data == null) return false;
        if (!"inline_graphic_only".equals(rg.reason())) return false;
        if (!"inline_object".equals(rg.type()) && !"inline_object".equals(rg.itemType())) return false;
        for (RenderedGroup other : data.allRenderedFloatingItems()) {
            if (other == null || other == rg) continue;
            if (other.id() != rg.id()) continue;
            if (!isRenderedPageObject(other)) continue;
            if (isEditableVisualShellWithSeparateHwpxText(other)) return true;
        }
        return false;
    }

    private boolean hasEditableTextOwnerSignal(RenderedGroup rg) {
        if (rg == null || data == null) return false;
        if ("hwpx_tf".equals(rg.textOwner())) return true;
        if (hasEditableTextFrameIds(rg)) return true;
        if (rg.sourceObjectIds() != null) {
            for (int sourceId : rg.sourceObjectIds()) {
                ResolvedTextFrame tf = data.getTextFrame(String.valueOf(sourceId));
                if (tf != null && !tf.onHiddenLayer() && !tf.nonprinting()) {
                    return true;
                }
            }
        }
        return false;
    }

    private boolean hasTextFrameSource(ObjectPlan plan) {
        return hasTextFrameSource(plan != null ? plan.sourceObjectIds : null);
    }

    private boolean hasTextFrameSource(int[] sourceIds) {
        if (sourceIds == null || sourceIds.length == 0 || data == null) return false;
        for (int sourceId : sourceIds) {
            if (data.getTextFrame(String.valueOf(sourceId)) != null) {
                return true;
            }
        }
        return false;
    }

    private int[] withoutTextFrameSourceIds(int[] sourceIds) {
        if (sourceIds == null || sourceIds.length == 0 || data == null) return sourceIds;
        LinkedHashSet<Integer> ids = new LinkedHashSet<>();
        for (int sourceId : sourceIds) {
            if (data.getTextFrame(String.valueOf(sourceId)) == null) {
                ids.add(sourceId);
            }
        }
        if (ids.isEmpty()) return sourceIds;
        int[] out = new int[ids.size()];
        int i = 0;
        for (Integer id : ids) {
            out[i++] = id != null ? id : -1;
        }
        return out;
    }

    private int[] visualShellSourceIds(RenderedGroup rg, int[] fallback) {
        if (rg == null || data == null) return fallback;
        if (hasEditableTextOwnerSignal(rg)) {
            int[] filtered = withoutTextFrameSourceIds(rg.sourceObjectIds());
            if (filtered != null && filtered.length > 0) {
                return filtered;
            }
        }
        LinkedHashSet<Integer> ids = new LinkedHashSet<>();
        int selfId = rg.id();
        if (selfId >= 0) ids.add(selfId);
        int[] sources = rg.sourceObjectIds() != null ? rg.sourceObjectIds() : fallback;
        if (sources != null) {
            for (int id : sources) {
                ResolvedPageItem item = data.getPageItem(String.valueOf(id));
                if (item != null && "TextFrame".equals(safe(item.type()))) continue;
                ids.add(id);
            }
        }
        if (ids.isEmpty()) return fallback;
        int[] out = new int[ids.size()];
        int i = 0;
        for (Integer id : ids) out[i++] = id != null ? id : -1;
        return out;
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
                || reason.contains("text_composite_editable_text_hidden")
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
        if (hasImageSource(rg)) {
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

    private boolean hasImageSource(RenderedGroup rg) {
        if (rg == null || data == null || rg.sourceObjectIds() == null) return false;
        for (int id : rg.sourceObjectIds()) {
            ResolvedPageItem item = data.getPageItem(String.valueOf(id));
            if (item != null && "Image".equals(safe(item.type()))) return true;
        }
        return false;
    }

    private Placement placementOf(RenderedGroup rg) {
        if ("inline_object".equals(rg.type()) || "inline_object".equals(rg.itemType())) {
            if (InlineSemanticLabelPolicy.isStandaloneSemanticGraphicInlineGroup(data, rg)) {
                return Placement.FLOATING;
            }
            if (hasInlineSourceObject(rg)) {
                return Placement.INLINE;
            }
            if (!hasResolvedInlineAnchor(rg.id())) {
                return Placement.FLOATING;
            }
            return Placement.INLINE;
        }
        if (isMultiTextVisualLabelShell(rg)) {
            return Placement.FLOATING;
        }
        if (isTextHiddenShellForInlineAnchor(rg)) {
            return Placement.INLINE;
        }
        if (rg.isPageBackground()) {
            return Placement.FLOATING;
        }
        return Placement.FLOATING;
    }

    private boolean isMultiTextVisualLabelShell(RenderedGroup rg) {
        if (rg == null) return false;
        if (!"visual_label_text_hidden_shell".equals(rg.reason())) return false;
        String[] ids = rg.editableTextFrameIds();
        return ids != null && ids.length > 1;
    }

    private boolean isTextHiddenShellForInlineAnchor(RenderedGroup rg) {
        if (rg == null) return false;
        if (!isDirectInlineTextShellReason(rg.reason())) return false;
        if (!"indesign_png".equals(rg.visualOwner())) return false;
        if (!"hwpx_tf".equals(rg.textOwner())) return false;
        if (Boolean.TRUE.equals(rg.containsText()) || Boolean.TRUE.equals(rg.containsEditableText())) return false;
        if (!rg.hasEditableTextHiddenFromPng()) return false;
        if (!hasInlineSourceObject(rg) && !hasResolvedInlineAnchor(rg.id())) return false;
        String[] ids = rg.editableTextFrameIds();
        if (ids == null || ids.length == 0) return false;
        for (String id : ids) {
            ResolvedTextFrame tf = data != null ? data.getTextFrame(id) : null;
            if (tf == null || !tf.isInline()) return false;
        }
        return true;
    }

    private boolean hasInlineSourceObject(RenderedGroup rg) {
        if (rg == null || data == null) return false;
        if (data.isInlineObjectId(rg.id())) return true;
        ResolvedPageItem self = data.getPageItem(String.valueOf(rg.id()));
        if (self != null && self.isInline()) return true;
        if (rg.sourceObjectIds() == null) return false;
        for (int sourceId : rg.sourceObjectIds()) {
            if (data.isInlineObjectId(sourceId)) return true;
            ResolvedPageItem item = data.getPageItem(String.valueOf(sourceId));
            if (item != null && item.isInline()) return true;
        }
        return false;
    }

    private boolean hasResolvedInlineAnchor(int domId) {
        if (data == null || domId < 0 || data.stories() == null) return false;
        for (ResolvedStory story : data.stories()) {
            if (story == null || story.paragraphs() == null) continue;
            for (ResolvedParagraph paragraph : story.paragraphs()) {
                if (paragraph == null || paragraph.runs() == null) continue;
                for (ResolvedRun run : paragraph.runs()) {
                    if (run == null || !run.isInlineAnchor()) continue;
                    Integer anchoredId = run.anchoredObjectId();
                    if (anchoredId != null && anchoredId == domId) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private void resolveFloatingInlineObjectPageObjectDuplicates() {
        Map<String, Boolean> floatingInlineObjectByPageDom = new HashMap<>();
        for (ObjectPlan plan : plans) {
            if (!plan.hasVisibleVisual()) continue;
            if (plan.placement != Placement.FLOATING) continue;
            if (plan.domId < 0) continue;
            if (!safe(plan.kind).contains("inline_object")) continue;
            floatingInlineObjectByPageDom.put(pageDomKey(plan), Boolean.TRUE);
        }
        if (floatingInlineObjectByPageDom.isEmpty()) return;
        for (int i = 0; i < plans.size(); i++) {
            ObjectPlan plan = plans.get(i);
            if (!plan.hasVisibleVisual()) continue;
            if (plan.domId < 0) continue;
            if (safe(plan.kind).contains("inline_object")) continue;
            if (!Boolean.TRUE.equals(floatingInlineObjectByPageDom.get(pageDomKey(plan)))) continue;
            plans.set(i, plan.withVisualAction(VisualAction.DROP_VISUAL,
                    "page_object_duplicate_of_floating_inline_object"));
        }
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

    private boolean isFloatingVisualShellWithSeparateHwpxText(ObjectPlan plan) {
        if (plan == null || plan.placement != Placement.FLOATING) return false;
        if (plan.textAction != TextAction.OWNED_BY_HWPX_TEXT) return false;
        if (plan.visualAction != VisualAction.PLACE_FLOATING_PNG
                && plan.visualAction != VisualAction.PLACE_TEXT_SHELL) {
            return false;
        }
        RenderedGroup rendered = renderedGroupForPlan(plan);
        return rendered != null && isEditableVisualShellWithSeparateHwpxText(rendered);
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

    private void resolveFloatingChildrenOwnedByInlineParent() {
        List<ObjectPlan> inlineOwners = new ArrayList<>();
        for (ObjectPlan plan : plans) {
            if (!isVisibleRenderedVisual(plan)) continue;
            if (plan.placement != Placement.INLINE) continue;
            if (plan.visualAction != VisualAction.PLACE_INLINE_PNG) continue;
            if (plan.sourceObjectIds == null || plan.sourceObjectIds.length <= 1) continue;
            inlineOwners.add(plan);
        }
        if (inlineOwners.isEmpty()) return;
        for (int i = 0; i < plans.size(); i++) {
            ObjectPlan child = plans.get(i);
            if (!isVisibleRenderedVisual(child)) continue;
            if (child.placement != Placement.FLOATING) continue;
            if (isLabelBackdropGroupPlan(child)) continue;
            if (child.sourceObjectIds == null || child.sourceObjectIds.length == 0) continue;
            for (ObjectPlan parent : inlineOwners) {
                if (child.pageIndex != parent.pageIndex) continue;
                if (child.domId == parent.domId) continue;
                if (!sourceSetContainsAll(parent.sourceObjectIds, child.sourceObjectIds)) continue;
                plans.set(i, child.withVisualAction(VisualAction.DROP_VISUAL,
                        "floating_child_owned_by_inline_parent"));
                break;
            }
        }
    }

    private void resolveFloatingPageObjectsOwnedByInlineHwpxText() {
        List<ObjectPlan> inlineHwpxOwnedPlans = new ArrayList<>();
        for (ObjectPlan plan : plans) {
            if (plan == null) continue;
            if (plan.placement != Placement.INLINE) continue;
            if (plan.visualAction != VisualAction.DROP_VISUAL) continue;
            if (!safe(plan.kind).contains("inline_object")) continue;
            if (!"owned_by_hwpx_text_frame".equals(plan.reason)
                    && !"inline_parent_contains_hwpx_text_sources".equals(plan.reason)) {
                continue;
            }
            inlineHwpxOwnedPlans.add(plan);
        }
        if (inlineHwpxOwnedPlans.isEmpty()) return;

        for (int i = 0; i < plans.size(); i++) {
            ObjectPlan plan = plans.get(i);
            if (!isVisibleRenderedVisual(plan)) continue;
            if (plan.placement != Placement.FLOATING) continue;
            if (!isTextHiddenContainerRender(plan)) continue;
            RenderedGroup rendered = renderedGroupForPlan(plan);
            if (rendered != null && hasIndependentContentVisualBesideOwnedText(rendered)) continue;
            if (!isOwnedByInlineHwpxTextSource(plan, inlineHwpxOwnedPlans)) continue;
            plans.set(i, plan.withVisualAction(VisualAction.DROP_VISUAL,
                    "floating_page_object_owned_by_inline_hwpx_text"));
        }
    }

    private static boolean isOwnedByInlineHwpxTextSource(ObjectPlan floating, List<ObjectPlan> inlineOwners) {
        if (floating == null || inlineOwners == null || inlineOwners.isEmpty()) return false;
        for (ObjectPlan inline : inlineOwners) {
            if (inline == null) continue;
            if (floating.pageIndex != inline.pageIndex) continue;
            if (floating.domId == inline.domId) return true;
            if (sourceSetContainsAll(inline.sourceObjectIds, floating.sourceObjectIds)) return true;
        }
        return false;
    }

    private static boolean isTextHiddenContainerRender(ObjectPlan plan) {
        String reason = safe(plan != null ? plan.reason : null);
        return reason.contains("mixed_group_text_hidden")
                || reason.contains("complex_graphic_text_hidden")
                || reason.contains("inline_text_hidden")
                || reason.contains("container_face_shadow_pair");
    }

    private void resolveInlineCompositeHwpxTextParents() {
        for (int i = 0; i < plans.size(); i++) {
            ObjectPlan plan = plans.get(i);
            if (!isVisibleRenderedVisual(plan)) continue;
            if (plan.placement != Placement.INLINE) continue;
            if (plan.visualAction != VisualAction.PLACE_INLINE_PNG) continue;
            if (plan.textAction == TextAction.OWNED_BY_PNG) continue;
            if (plan.textAction != TextAction.OWNED_BY_HWPX_TEXT) continue;
            if (plan.sourceObjectIds == null || plan.sourceObjectIds.length <= 1) continue;
            if (!hasInlineCompositeHwpxTextSignal(plan)) continue;
            plans.set(i, plan.withVisualAction(VisualAction.DROP_VISUAL,
                    "inline_parent_contains_hwpx_text_sources"));
        }
    }

    private boolean hasInlineCompositeHwpxTextSignal(ObjectPlan plan) {
        if (containsHwpxOwnedTextFrameSource(plan)) return true;
        RenderedGroup rg = renderedGroupForPlan(plan);
        if (rg == null) return false;
        return rg.hasEditableTextHiddenFromPng()
                || hasEditableTextFrameIds(rg)
                || Boolean.TRUE.equals(rg.containsEditableText());
    }

    private void resolveTextShellSharedSources() {
        Map<String, Boolean> visibleNonShellSources = new HashMap<>();
        for (ObjectPlan plan : plans) {
            if (!plan.hasVisibleVisual()) continue;
            if (plan.visualAction == VisualAction.PLACE_TEXT_SHELL) continue;
            for (int sourceId : visualSourceIds(plan)) {
                visibleNonShellSources.put(pageSourceKey(plan.pageIndex, sourceId), Boolean.TRUE);
            }
        }
        for (int i = 0; i < plans.size(); i++) {
            ObjectPlan plan = plans.get(i);
            if (plan.visualAction != VisualAction.PLACE_TEXT_SHELL) continue;
            List<Integer> retained = new ArrayList<>();
            boolean changed = false;
            for (int sourceId : visualSourceIds(plan)) {
                boolean sourceOwnedByNonShell = Boolean.TRUE.equals(
                        visibleNonShellSources.get(pageSourceKey(plan.pageIndex, sourceId)));
                if (sourceOwnedByNonShell && sourceId != plan.domId) {
                    changed = true;
                    continue;
                }
                retained.add(sourceId);
            }
            if (!changed) continue;
            plans.set(i, plan.withVisualSourceObjectIds(toIntArray(retained)));
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

    /**
     * SPEC-036: when a large composite render and a smaller shell/decorative render
     * must both stay visible, do not let both plans claim the same child source slot.
     * Keep both visuals; trim the child-owned source ids from the composite parent.
     */
    private void normalizeCompositeParentChildSourceSlots() {
        for (int i = 0; i < plans.size(); i++) {
            ObjectPlan parent = plans.get(i);
            if (!isCompositeSourceParent(parent)) continue;
            for (int j = 0; j < plans.size(); j++) {
                if (i == j) continue;
                ObjectPlan child = plans.get(j);
                if (!isVisibleRenderedVisual(child)) continue;
                if (child.pageIndex != parent.pageIndex) continue;
                if (!shouldSplitChildSourceSlotFromComposite(parent, child)) continue;
                ObjectPlan retainedParent = parent.withVisualSourceObjectIds(
                        withoutChildVisualSources(parent, child));
                if (visualSourceIds(retainedParent).length != visualSourceIds(parent).length) {
                    plans.set(i, retainedParent);
                    parent = retainedParent;
                }
            }
        }
    }

    private boolean isCompositeSourceParent(ObjectPlan plan) {
        if (!isVisibleRenderedVisual(plan)) return false;
        if (plan.sourceObjectIds == null || plan.sourceObjectIds.length <= 1) return false;
        if (plan.visualAction != VisualAction.PLACE_FLOATING_PNG
                && plan.visualAction != VisualAction.PLACE_INLINE_PNG
                && plan.visualAction != VisualAction.PLACE_TEXT_SHELL) {
            return false;
        }
        String reason = safe(plan.reason);
        return reason.contains("complex_graphic")
                || reason.contains("inline_graphic")
                || reason.contains("text_hidden")
                || reason.contains("composite")
                || reason.contains("group");
    }

    private boolean shouldSplitChildSourceSlotFromComposite(ObjectPlan parent, ObjectPlan child) {
        if (parent == null || child == null) return false;
        if (parent.domId == child.domId) return false;
        if (child.sourceObjectIds == null || child.sourceObjectIds.length == 0) return false;
        if (!sharesAnySource(parent, child)) return false;
        boolean sourceParentRelation = hasSourceParentRelation(parent, child);
        if (!sourceParentRelation
                && !containsAnyDirectSource(parent.sourceObjectIds, child.sourceObjectIds)) {
            return false;
        }
        if (!sourceParentRelation
                && !boundsMostlyOverlap(parent.bounds, child.bounds, 0.05)
                && !boundsContains(parent.bounds, child.bounds, 4.0)) {
            return false;
        }
        if (isNestedCompositeVisualChild(child)) return true;
        if (isBackgroundParentWithContentChild(parent, child)) return true;
        if (child.visualPolicyLayer() == PolicyLayer.DECORATION) return true;
        return child.visualAction == VisualAction.PLACE_TEXT_SHELL
                || child.visualLayer == VisualLayer.LABEL_BACKDROP
                || child.visualLayer == VisualLayer.LABEL_OVERLAY_BACKDROP
                || child.visualLayer == VisualLayer.CONTAINER_OUTLINE
                || child.visualLayer == VisualLayer.FOREGROUND_MASK;
    }

    private static boolean isNestedCompositeVisualChild(ObjectPlan child) {
        if (child == null) return false;
        if (child.placement != Placement.FLOATING) return false;
        if (child.visualAction != VisualAction.PLACE_FLOATING_PNG
                && child.visualAction != VisualAction.PLACE_TEXT_SHELL) {
            return false;
        }
        String reason = safe(child.reason);
        return reason.contains("group")
                || reason.contains("composite")
                || reason.contains("complex_graphic")
                || reason.contains("text_hidden");
    }

    private static boolean containsAnyDirectSource(int[] parentSources, int[] childSources) {
        if (parentSources == null || childSources == null) return false;
        for (int childSource : childSources) {
            if (contains(parentSources, childSource)) return true;
        }
        return false;
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
                if (isBackgroundParentWithContentChild(parent, child)) {
                    ObjectPlan retainedParent = parent.withVisualSourceObjectIds(
                            withoutChildVisualSources(parent, child));
                    if (visualSourceIds(retainedParent).length != visualSourceIds(parent).length) {
                        plans.set(i, retainedParent);
                        parent = retainedParent;
                    }
                    continue;
                }
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

    private void resolveMasterGraphicsWithHwpxTextFallbacks() {
        for (int i = 0; i < plans.size(); i++) {
            ObjectPlan complete = plans.get(i);
            if (!isMasterGraphicCompleteRender(complete)) continue;
            if (!containsVisibleHwpxMasterTextSourceOnPage(complete)) continue;

            int fallbackIndex = findMasterGraphicFallbackIndex(complete);
            if (fallbackIndex < 0) continue;

            ObjectPlan fallback = plans.get(fallbackIndex);
            plans.set(i, complete.withVisualAction(VisualAction.DROP_VISUAL,
                    "master_graphic_text_owned_by_hwpx"));
            plans.set(fallbackIndex, fallback
                    .withVisualAction(VisualAction.PLACE_FLOATING_PNG,
                            "master_graphic_text_hidden_fallback")
                    .withVisualLayer(complete.visualLayer));
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

    private void resolveLayeredContainerFaces() {
        for (int i = 0; i < plans.size(); i++) {
            ObjectPlan face = plans.get(i);
            if (!isVisibleRenderedVisual(face)) continue;
            if (face.placement != Placement.FLOATING) continue;
            if (!isPaperLikeContainerFace(face)) continue;
            if (isLineLikePlan(face)) continue;

            for (int j = 0; j < plans.size(); j++) {
                if (i == j) continue;
                ObjectPlan shadow = plans.get(j);
                if (!isVisibleRenderedVisual(shadow)) continue;
                if (shadow.placement != Placement.FLOATING) continue;
                if (shadow.pageIndex != face.pageIndex) continue;
                if (!isColoredContainerShadow(shadow)) continue;
                if (!sameContainerFootprint(face, shadow)) continue;
                ObjectPlan textShellOwner = chooseTextShellContainerOwner(face, shadow);
                if (textShellOwner != null) {
                    boolean faceOwns = textShellOwner == face;
                    int ownerIndex = faceOwns ? i : j;
                    int duplicateIndex = faceOwns ? j : i;
                    ObjectPlan duplicate = faceOwns ? shadow : face;
                    plans.set(ownerIndex, textShellOwner.withZOrder(Math.min(face.zOrder, shadow.zOrder)));
                    plans.set(duplicateIndex, duplicate.withVisualAction(
                            VisualAction.DROP_VISUAL,
                            "text_owned_container_shell_duplicate_child"));
                    break;
                }

                ObjectPlan originalOwner = chooseOriginalContainerFaceOwner(face, shadow);
                if (originalOwner != null) {
                    int ownerIndex = originalOwner == face ? i : j;
                    int duplicateIndex = originalOwner == face ? j : i;
                    ObjectPlan duplicate = originalOwner == face ? shadow : face;
                    ObjectPlan retained = originalOwner
                            .withVisualLayer(VisualLayer.CONTAINER_BACKDROP)
                            .withZOrder(Math.min(face.zOrder, shadow.zOrder));
                    if (retained.visualAction == VisualAction.PLACE_TEXT_SHELL) {
                        VisualAction convertedVisual = retained.placement == Placement.INLINE
                                ? VisualAction.PLACE_INLINE_PNG
                                : VisualAction.PLACE_FLOATING_PNG;
                        retained = retained.withVisualAction(convertedVisual, "container_face_shadow_original_owner");
                    }
                    plans.set(ownerIndex, retained);
                    plans.set(duplicateIndex, duplicate.withVisualAction(
                            VisualAction.DROP_VISUAL,
                            "container_face_shadow_duplicate_child"));
                } else {
                    int faceZ = Math.min(face.zOrder, shadow.zOrder) - 1;
                    plans.set(i, face.withVisualLayer(VisualLayer.CONTAINER_FACE).withZOrder(faceZ));
                    plans.set(j, shadow.withVisualLayer(VisualLayer.CONTAINER_FACE));
                }
                break;
            }
        }
    }

    private ObjectPlan chooseOriginalContainerFaceOwner(ObjectPlan a, ObjectPlan b) {
        if (a == null || b == null) return null;
        if (isEditableVisualShellPlan(a) && !isEditableVisualShellPlan(b)) return a;
        if (isEditableVisualShellPlan(b) && !isEditableVisualShellPlan(a)) return b;
        if (isEditableVisualShellPlan(a) && isEditableVisualShellPlan(b)) {
            return sourceCount(a) >= sourceCount(b) ? a : b;
        }
        if (sourceCount(a) != sourceCount(b)) {
            return sourceCount(a) > sourceCount(b) ? a : b;
        }
        return null;
    }

    private static boolean isEditableVisualShellPlan(ObjectPlan plan) {
        return plan != null
                && plan.textAction == TextAction.OWNED_BY_HWPX_TEXT
                && isTextHiddenContainerRender(plan);
    }

    private static int sourceCount(ObjectPlan plan) {
        return plan != null && plan.sourceObjectIds != null ? plan.sourceObjectIds.length : 0;
    }

    private void resolveCompositeBakedChildVisuals() {
        for (int i = 0; i < plans.size(); i++) {
            ObjectPlan parent = plans.get(i);
            if (!isCompleteCompositeVisualOwner(parent)) continue;
            RenderedGroup parentRender = renderedGroupForPlan(parent);
            for (int j = 0; j < plans.size(); j++) {
                if (i == j) continue;
                ObjectPlan child = plans.get(j);
                if (!isVisibleRenderedVisual(child)) continue;
                if (child.pageIndex != parent.pageIndex) continue;
                if (isProtectedCompositeChildVisual(child)) continue;
                if (!isBakedIntoCompositeParent(parent, parentRender, child)) continue;
                plans.set(j, child.withVisualAction(
                        VisualAction.DROP_VISUAL,
                        "baked_into_composite_parent"));
            }
        }
    }

    private boolean isCompleteCompositeVisualOwner(ObjectPlan plan) {
        if (!isVisibleRenderedVisual(plan)) return false;
        if (plan.visualAction == VisualAction.PLACE_TEXT_SHELL) return false;
        if (plan.sourceObjectIds == null || plan.sourceObjectIds.length <= 1) return false;
        String reason = safe(plan.reason);
        return reason.contains("mixed_group_text_hidden")
                || reason.contains("complex_graphic_text_hidden")
                || reason.contains("image_group_text_hidden")
                || reason.contains("composite")
                || reason.contains("group");
    }

    private boolean isProtectedCompositeChildVisual(ObjectPlan child) {
        if (child == null) return true;
        if (child.visualAction == VisualAction.PLACE_TEXT_SHELL) return true;
        if (isLabelBackdropGroupPlan(child)) return true;
        return child.visualLayer == VisualLayer.LABEL_BACKDROP
                || child.visualLayer == VisualLayer.LABEL_OVERLAY_BACKDROP;
    }

    private boolean isBakedIntoCompositeParent(
            ObjectPlan parent, RenderedGroup parentRender, ObjectPlan child) {
        if (parent == null || child == null) return false;
        if (parent.domId == child.domId) return false;
        if (parent.bounds == null || child.bounds == null) return false;
        double parentArea = area(parent.bounds);
        double childArea = area(child.bounds);
        if (parentArea <= 0.0 || childArea <= 0.0 || parentArea <= childArea * 1.05) {
            return false;
        }
        if (!boundsContains(parent.bounds, child.bounds, 4.0)
                && !boundsMostlyOverlap(parent.bounds, child.bounds, 0.20)) {
            return false;
        }
        if (contains(parent.sourceObjectIds, child.domId)) return true;
        if (sharesAnySource(parent, child)) return true;
        RenderedGroup childRender = renderedGroupForPlan(child);
        if (childRender != null) {
            if (contains(parent.sourceObjectIds, childRender.id())) return true;
            if (containsAny(parent.sourceObjectIds, childRender.childIds())) return true;
            if (containsAny(parent.sourceObjectIds, childRender.sourceObjectIds())) return true;
        }
        if (parentRender != null && parentRender.childIds() != null) {
            if (contains(parentRender.childIds(), child.domId)) return true;
            return childRender != null && contains(parentRender.childIds(), childRender.id());
        }
        return false;
    }

    private static boolean isHwpxTextOwnedContainerShell(ObjectPlan plan) {
        return plan != null
                && plan.textAction == TextAction.OWNED_BY_HWPX_TEXT
                && plan.visualAction == VisualAction.PLACE_TEXT_SHELL
                && plan.visualLayer == VisualLayer.CONTAINER_BACKDROP;
    }

    private RenderedGroup renderedGroupForPlan(ObjectPlan plan) {
        if (plan == null || plan.renderId == null) return null;
        RenderedGroup best = null;
        int bestScore = Integer.MIN_VALUE;
        for (RenderedGroup rg : allRenderedGroups()) {
            if (rg == null || rg.id() != plan.renderId.intValue() || rg.pageIndex() != plan.pageIndex) {
                continue;
            }
            int score = 0;
            if (plan.file != null && plan.file.equals(rg.file())) score += 16;
            if (plan.placement == placementOf(rg)) score += 8;
            if (plan.reason != null && plan.reason.equals(rg.reason())) score += 4;
            if (plan.bounds != null && rg.bounds() != null && overlapRatio(plan.bounds, rg.bounds()) > 0.95) {
                score += 2;
            }
            if (score > bestScore) {
                best = rg;
                bestScore = score;
            }
        }
        return best;
    }

    private static int[] mergeSourceIds(int[] a, int[] b) {
        LinkedHashSet<Integer> ids = new LinkedHashSet<>();
        if (a != null) {
            for (int id : a) ids.add(id);
        }
        if (b != null) {
            for (int id : b) ids.add(id);
        }
        int[] out = new int[ids.size()];
        int i = 0;
        for (Integer id : ids) out[i++] = id;
        Arrays.sort(out);
        return out;
    }

    private boolean isColoredContainerShadow(ObjectPlan plan) {
        if (plan == null || plan.bounds == null || plan.bounds.length < 4) return false;
        if (isLineLikePlan(plan)) return false;
        if (area(plan.bounds) < 800.0) return false;
        for (int sourceId : plan.sourceObjectIds) {
            ResolvedPageItem item = data.getPageItem(String.valueOf(sourceId));
            if (isFilledContainerBoxItem(item)) return true;
        }
        return false;
    }

    private static boolean sameContainerFootprint(ObjectPlan a, ObjectPlan b) {
        if (a == null || b == null) return false;
        if (a.bounds == null || b.bounds == null || a.bounds.length < 4 || b.bounds.length < 4) return false;
        double aArea = area(a.bounds);
        double bArea = area(b.bounds);
        if (aArea <= 0.0 || bArea <= 0.0) return false;
        double ratio = Math.min(aArea, bArea) / Math.max(aArea, bArea);
        if (ratio < 0.72) return false;
        if (!boundsMostlyOverlap(a.bounds, b.bounds, 0.82)) return false;
        double aCy = (a.bounds[0] + a.bounds[2]) / 2.0;
        double aCx = (a.bounds[1] + a.bounds[3]) / 2.0;
        double bCy = (b.bounds[0] + b.bounds[2]) / 2.0;
        double bCx = (b.bounds[1] + b.bounds[3]) / 2.0;
        double h = Math.max(1.0, Math.min(Math.abs(a.bounds[2] - a.bounds[0]), Math.abs(b.bounds[2] - b.bounds[0])));
        double w = Math.max(1.0, Math.min(Math.abs(a.bounds[3] - a.bounds[1]), Math.abs(b.bounds[3] - b.bounds[1])));
        return Math.abs(aCy - bCy) <= h * 0.08 + 3.0
                && Math.abs(aCx - bCx) <= w * 0.08 + 3.0;
    }

    private ObjectPlan chooseTextShellContainerOwner(ObjectPlan a, ObjectPlan b) {
        if (a == null || b == null) return null;
        boolean aShell = isHwpxTextOwnedShell(a);
        boolean bShell = isHwpxTextOwnedShell(b);
        if (!aShell && !bShell) return null;
        if (aShell && !bShell) return a;
        if (!aShell) return b;
        boolean aContainsB = containsAllSources(a, b) || contains(a.sourceObjectIds, b.domId);
        boolean bContainsA = containsAllSources(b, a) || contains(b.sourceObjectIds, a.domId);
        if (aContainsB && !bContainsA) return a;
        if (bContainsA && !aContainsB) return b;
        double aArea = area(a.bounds);
        double bArea = area(b.bounds);
        if (aArea > bArea * 1.05) return a;
        if (bArea > aArea * 1.05) return b;
        if (a.visualLayer == VisualLayer.LABEL_BACKDROP && b.visualLayer != VisualLayer.LABEL_BACKDROP) {
            return a;
        }
        if (b.visualLayer == VisualLayer.LABEL_BACKDROP && a.visualLayer != VisualLayer.LABEL_BACKDROP) {
            return b;
        }
        return a.zOrder <= b.zOrder ? a : b;
    }

    private static boolean isHwpxTextOwnedShell(ObjectPlan plan) {
        return plan != null
                && plan.textAction == TextAction.OWNED_BY_HWPX_TEXT
                && plan.visualAction == VisualAction.PLACE_TEXT_SHELL;
    }

    private void resolveParentTextShellDescendantVisuals() {
        for (int i = 0; i < plans.size(); i++) {
            ObjectPlan parent = plans.get(i);
            if (!isParentTextShellOwner(parent)) continue;
            LinkedHashSet<Integer> descendants = new LinkedHashSet<>();
            for (int j = 0; j < plans.size(); j++) {
                if (i == j) continue;
                ObjectPlan child = plans.get(j);
                if (!isVisibleRenderedVisual(child)) continue;
                if (child.pageIndex != parent.pageIndex) continue;
                if (!parentTextShellMayOwnDescendantVisual(parent, child)) continue;
                if (isStandaloneGraphicOnlyInlineObjectPlan(child)) continue;
                if (!isDescendantVisualOfParentTextShell(parent, child)) continue;
                collectDescendantVisualIds(parent, child, descendants);
                ObjectPlan dropped = child.withVisualAction(VisualAction.DROP_VISUAL,
                        "owned_by_parent_text_shell");
                if (!"text_frame".equals(dropped.kind)) {
                    dropped = dropped.withTextAction(TextAction.DROP_TEXT);
                }
                plans.set(j, dropped);
            }
            if (!descendants.isEmpty()) {
                plans.set(i, parent.withDescendantVisualObjectIds(toIntArray(descendants)));
            }
        }
    }

    private static boolean isParentTextShellOwner(ObjectPlan plan) {
        return plan != null
                && plan.visualAction == VisualAction.PLACE_TEXT_SHELL
                && plan.hasVisibleVisual()
                && plan.ownedTextFrameIds != null
                && plan.ownedTextFrameIds.length > 0;
    }

    private boolean isDescendantVisualOfParentTextShell(ObjectPlan parent, ObjectPlan child) {
        if (parent == null || child == null) return false;
        if ("text_frame".equals(child.kind)) return false;
        if (parent.domId == child.domId) return false;
        if (isSameRenderPlan(parent, child)) return false;
        if (child.visualAction == VisualAction.PLACE_TEXT_SHELL
                && !ownedTextFramesCoveredBy(parent, child)) {
            return false;
        }
        if (isStrictChildPlan(parent, child)) return true;
        if (!sharesAnySource(parent, child) && !containsAny(parent.sourceObjectIds, visualSourceIds(child))) {
            return false;
        }
        if (parent.bounds == null || child.bounds == null) return false;
        return boundsContains(parent.bounds, child.bounds, 4.0)
                || boundsMostlyOverlap(parent.bounds, child.bounds, 0.70);
    }

    private static boolean ownedTextFramesCoveredBy(ObjectPlan parent, ObjectPlan child) {
        if (parent == null || child == null) return false;
        if (child.ownedTextFrameIds == null || child.ownedTextFrameIds.length == 0) return true;
        for (int id : child.ownedTextFrameIds) {
            if (!contains(parent.ownedTextFrameIds, id)) return false;
        }
        return true;
    }

    private static boolean isSameRenderPlan(ObjectPlan a, ObjectPlan b) {
        if (a == null || b == null) return false;
        if (a.renderId == null || b.renderId == null) return false;
        if (!a.renderId.equals(b.renderId)) return false;
        if (a.file == null || b.file == null) return true;
        return a.file.equals(b.file);
    }

    private void collectDescendantVisualIds(
            ObjectPlan parent,
            ObjectPlan child,
            LinkedHashSet<Integer> descendants) {
        if (child == null || descendants == null) return;
        if (child.domId >= 0) descendants.add(child.domId);
        if (child.renderId != null && child.renderId >= 0) descendants.add(child.renderId);
        int[] parentVisualSources = visualSourceIds(parent);
        for (int sourceId : visualSourceIds(child)) {
            if (contains(child.ownedTextFrameIds, sourceId)) continue;
            if (!contains(parentVisualSources, sourceId)) continue;
            if (sourceId >= 0) descendants.add(sourceId);
        }
    }

    private static boolean containsAllSources(ObjectPlan owner, ObjectPlan child) {
        if (owner == null || child == null || child.sourceObjectIds == null) return false;
        for (int sourceId : child.sourceObjectIds) {
            if (!contains(owner.sourceObjectIds, sourceId)) return false;
        }
        return true;
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
                for (int sourceId : visualSourceIds(child)) {
                    childSources.add(sourceId);
                }
            }
            if (childSources.isEmpty()) continue;
            List<Integer> retained = new ArrayList<>();
            boolean changed = false;
            for (int sourceId : visualSourceIds(parent)) {
                if (sourceId != parent.domId && childSources.contains(sourceId)) {
                    changed = true;
                    continue;
                }
                retained.add(sourceId);
            }
            if (!changed) continue;
            plans.set(i, parent.withVisualSourceObjectIds(toIntArray(retained)));
        }
    }

    private void restoreLeafTextHiddenShellOwners() {
        for (int i = 0; i < plans.size(); i++) {
            ObjectPlan plan = plans.get(i);
            if (!isLeafTextHiddenShellPlan(plan)) continue;
            if (isNonCanonicalAtomicObjectPlan(plan)) continue;
            if (plan.visualAction == VisualAction.PLACE_TEXT_SHELL) continue;
            if (hasAlternativeVisibleTextShellOwner(plan)) continue;
            plans.set(i, plan
                    .withVisualAction(VisualAction.PLACE_TEXT_SHELL, plan.reason)
                    .withVisualLayer(VisualLayer.CONTAINER_BACKDROP));
        }
    }

    private boolean hasAlternativeVisibleTextShellOwner(ObjectPlan shell) {
        if (shell == null) return false;
        for (ObjectPlan candidate : plans) {
            if (candidate == null || candidate == shell) continue;
            if (candidate.pageIndex != shell.pageIndex) continue;
            if (candidate.visualAction != VisualAction.PLACE_TEXT_SHELL) continue;
            if (!candidate.hasVisibleVisual()) continue;
            if (!ownedTextFramesCoveredBy(candidate, shell)) continue;
            if (!parentTextShellMayOwnDescendantVisual(candidate, shell)) continue;
            if (containsAll(visualSourceIds(candidate), visualSourceIds(shell))) return true;
        }
        return false;
    }

    private static boolean isLeafTextHiddenShellPlan(ObjectPlan plan) {
        return plan != null
                && "leaf_group_text_hidden_shell".equals(plan.reason)
                && safe(plan.kind).contains("page_object")
                && plan.ownedTextFrameIds != null
                && plan.ownedTextFrameIds.length > 0
                && visualSourceIds(plan).length > 0;
    }

    private void restoreInlineTextShellOwners() {
        for (int i = 0; i < plans.size(); i++) {
            ObjectPlan plan = plans.get(i);
            if (!isInlineTextShellPlan(plan)) continue;
            if (isNonCanonicalAtomicObjectPlan(plan)) continue;
            if ("owned_by_page_object_channel".equals(plan.reason)) continue;
            if (plan.visualAction == VisualAction.PLACE_TEXT_SHELL) continue;
            if (hasAlternativeVisibleInlineTextShellOwner(plan)) continue;
            plans.set(i, plan.withVisualAction(VisualAction.PLACE_TEXT_SHELL, plan.reason));
        }
    }

    private boolean hasAlternativeVisibleInlineTextShellOwner(ObjectPlan shell) {
        if (shell == null) return false;
        for (ObjectPlan candidate : plans) {
            if (candidate == null || candidate == shell) continue;
            if (candidate.pageIndex != shell.pageIndex) continue;
            if (candidate.placement != Placement.INLINE) continue;
            if (candidate.visualAction != VisualAction.PLACE_TEXT_SHELL
                    && candidate.visualAction != VisualAction.PLACE_INLINE_PNG) {
                continue;
            }
            if (!candidate.hasVisibleVisual()) continue;
            if (!ownedTextFramesCoveredBy(candidate, shell)) continue;
            if (containsAll(visualSourceIds(candidate), visualSourceIds(shell))) return true;
        }
        return false;
    }

    private boolean isNonCanonicalAtomicObjectPlan(ObjectPlan plan) {
        if (plan == null || data == null) return false;
        RenderedGroup rg = renderedGroupForPlan(plan);
        return rg != null && data.isNonCanonicalAtomicObjectRender(rg);
    }

    private boolean isInlineTextShellPlan(ObjectPlan plan) {
        if (plan == null || plan.placement != Placement.INLINE) return false;
        if (!safe(plan.kind).contains("inline_object")) return false;
        if (plan.ownedTextFrameIds == null || plan.ownedTextFrameIds.length == 0) return false;
        if (visualSourceIds(plan).length == 0) return false;
        for (int tfId : plan.ownedTextFrameIds) {
            ResolvedTextFrame tf = data != null ? data.getTextFrame(String.valueOf(tfId)) : null;
            if (tf == null || !tf.isInline()) return false;
        }
        return true;
    }

    private void promoteInlineCompanionLeafShellOwners() {
        for (int i = 0; i < plans.size(); i++) {
            ObjectPlan leafShell = plans.get(i);
            if (!isLeafTextHiddenShellPlan(leafShell)) continue;
            if (isNonCanonicalAtomicObjectPlan(leafShell)) continue;
            if (leafShell.ownedTextFrameIds == null || leafShell.ownedTextFrameIds.length == 0) continue;
            if (leafShell.ownedTextFrameIds.length != 1) continue;
            ObjectPlan inlineCompanion = findInlineCompanionForLeafShell(leafShell);
            if (inlineCompanion == null) continue;
            if (isNonCanonicalAtomicObjectPlan(inlineCompanion)) continue;
            int companionIndex = plans.indexOf(inlineCompanion);
            if (companionIndex < 0) continue;

            ObjectPlan promoted = new ObjectPlan(
                    inlineCompanion.domId,
                    inlineCompanion.kind,
                    inlineCompanion.pageIndex,
                    TextAction.OWNED_BY_HWPX_TEXT,
                    VisualAction.PLACE_TEXT_SHELL,
                    VisualLayer.CONTAINER_BACKDROP,
                    Placement.INLINE,
                    inlineCompanion.renderId,
                    leafShell.sourceObjectIds,
                    visualSourceIds(leafShell),
                    leafShell.ownedTextFrameIds,
                    leafShell.descendantVisualObjectIds,
                    leafShell.sourceBundleKey,
                    Math.max(inlineCompanion.zOrder, leafShell.zOrder),
                    "inline_companion_leaf_text_shell",
                    leafShell.file,
                    inlineCompanion.bounds,
                    leafShell.sourceLayerId,
                    leafShell.sourceLayerName,
                    leafShell.sourceLayerIndex);
            plans.set(companionIndex, promoted);
            plans.set(i, leafShell.withVisualAction(VisualAction.DROP_VISUAL,
                    "owned_by_inline_companion_text_shell"));
            promoteOwnedTextFramePlansToInline(leafShell.ownedTextFrameIds);
        }
    }

    private ObjectPlan findInlineCompanionForLeafShell(ObjectPlan leafShell) {
        if (leafShell == null) return null;
        for (ObjectPlan candidate : plans) {
            if (candidate == null || candidate == leafShell) continue;
            if (!isInlineCompanionCandidateForLeafShell(candidate)) continue;
            if (candidate.pageIndex != leafShell.pageIndex) continue;
            if (candidate.domId != leafShell.domId) continue;
            if (candidate.placement != Placement.INLINE) continue;
            if (!safe(candidate.kind).contains("inline_object")) continue;
            if (!hasInlineSourcePlan(candidate)) continue;
            if (candidate.domId != leafShell.domId
                    && !intersects(candidate.sourceObjectIds, leafShell.sourceObjectIds)) {
                continue;
            }
            if (candidate.file == null || candidate.file.isEmpty()) continue;
            return candidate;
        }
        return null;
    }

    private boolean isInlineCompanionCandidateForLeafShell(ObjectPlan candidate) {
        if (candidate == null) return false;
        if (candidate.hasVisibleVisual()) return true;
        if (candidate.visualAction != VisualAction.DROP_VISUAL) return false;
        if (candidate.textAction != TextAction.DROP_TEXT) return false;
        return "inline_graphic_only".equals(candidate.reason)
                || "owned_by_page_object_channel".equals(candidate.reason);
    }

    private boolean hasInlineSourcePlan(ObjectPlan plan) {
        if (plan == null || data == null) return false;
        if (data.isInlineObjectId(plan.domId)) return true;
        ResolvedPageItem self = data.getPageItem(String.valueOf(plan.domId));
        if (self != null && self.isInline()) return true;
        if (plan.sourceObjectIds == null) return false;
        for (int sourceId : plan.sourceObjectIds) {
            if (data.isInlineObjectId(sourceId)) return true;
            ResolvedPageItem item = data.getPageItem(String.valueOf(sourceId));
            if (item != null && item.isInline()) return true;
        }
        return false;
    }

    private void promoteOwnedTextFramePlansToInline(int[] textFrameIds) {
        if (textFrameIds == null || textFrameIds.length == 0) return;
        for (int i = 0; i < plans.size(); i++) {
            ObjectPlan plan = plans.get(i);
            if (plan == null || !"text_frame".equals(plan.kind)) continue;
            if (!contains(textFrameIds, plan.domId)) continue;
            if (plan.placement == Placement.INLINE) continue;
            plans.set(i, new ObjectPlan(
                    plan.domId,
                    plan.kind,
                    plan.pageIndex,
                    plan.textAction,
                    plan.visualAction,
                    plan.visualLayer,
                    Placement.INLINE,
                    plan.renderId,
                    plan.sourceObjectIds,
                    plan.visualSourceObjectIds,
                    plan.ownedTextFrameIds,
                    plan.descendantVisualObjectIds,
                    plan.sourceBundleKey,
                    plan.zOrder,
                    plan.reason,
                    plan.file,
                    plan.bounds,
                    plan.sourceLayerId,
                    plan.sourceLayerName,
                    plan.sourceLayerIndex));
        }
    }

    private static boolean intersects(int[] a, int[] b) {
        if (a == null || b == null || a.length == 0 || b.length == 0) return false;
        for (int x : a) {
            for (int y : b) {
                if (x == y) return true;
            }
        }
        return false;
    }

    private void resolveTextShellSourceDuplicates() {
        for (int i = 0; i < plans.size(); i++) {
            ObjectPlan owner = plans.get(i);
            if (!isVisibleTextShell(owner)) continue;
            if (owner.placement == Placement.INLINE) continue;
            for (int j = 0; j < plans.size(); j++) {
                if (i == j) continue;
                ObjectPlan child = plans.get(j);
                if (!isVisibleTextShell(child)) continue;
                if (child.placement == Placement.INLINE) continue;
                if (child.pageIndex != owner.pageIndex) continue;
                if (child.domId == owner.domId && isSameRenderPlan(owner, child)) continue;
                if (!parentTextShellMayOwnDescendantVisual(owner, child)) continue;
                if (!textShellOwnerCoversChild(owner, child)) continue;
                ObjectPlan dropped = child.withVisualAction(VisualAction.DROP_VISUAL,
                        "visual_source_owned_by_parent_text_shell");
                if (!"text_frame".equals(dropped.kind)) {
                    dropped = dropped.withTextAction(TextAction.DROP_TEXT);
                }
                plans.set(j, dropped);
            }
        }
    }

    private static boolean isVisibleTextShell(ObjectPlan plan) {
        return plan != null
                && plan.hasVisibleVisual()
                && plan.visualAction == VisualAction.PLACE_TEXT_SHELL
                && visualSourceIds(plan).length > 0;
    }

    private static boolean parentTextShellMayOwnDescendantVisual(ObjectPlan parent, ObjectPlan child) {
        if (parent == null || child == null) return false;
        if (effectiveVisualPolicyLayer(parent) != PolicyLayer.BACKGROUND) return true;
        return effectiveVisualPolicyLayer(child) == PolicyLayer.BACKGROUND;
    }

    private static PolicyLayer effectiveVisualPolicyLayer(ObjectPlan plan) {
        if (plan == null) return PolicyLayer.CONTENT;
        if (isLeafTextHiddenShellPlan(plan)) {
            return PolicyLayer.DECORATION;
        }
        if (isImageBackedContentShellPlan(plan)) {
            return PolicyLayer.CONTENT;
        }
        return plan.visualPolicyLayer();
    }

    private static boolean isImageBackedContentShellPlan(ObjectPlan plan) {
        if (plan == null) return false;
        if (!safe(plan.reason).contains("image_group_text_hidden")) return false;
        String file = safe(plan.file);
        int slash = Math.max(file.lastIndexOf('/'), file.lastIndexOf('\\'));
        String basename = slash >= 0 ? file.substring(slash + 1) : file;
        return basename.startsWith("img_");
    }

    private static boolean textShellOwnerCoversChild(ObjectPlan owner, ObjectPlan child) {
        if (owner == null || child == null) return false;
        if (owner.bounds != null && child.bounds != null) {
            if (!boundsContains(owner.bounds, child.bounds, 4.0)
                    && !boundsMostlyOverlap(owner.bounds, child.bounds, 0.70)) {
                return false;
            }
        }
        if (!containsAll(visualSourceIds(owner), visualSourceIds(child))) return false;
        return sourceCount(owner) > sourceCount(child)
                || (owner.ownedTextFrameIds != null
                    && child.ownedTextFrameIds != null
                    && owner.ownedTextFrameIds.length > child.ownedTextFrameIds.length);
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

    private void resolveNonVisibleFloatingVisuals() {
        for (int i = 0; i < plans.size(); i++) {
            ObjectPlan plan = plans.get(i);
            if (!isVisibleRenderedVisual(plan)) continue;
            if (plan.placement != Placement.FLOATING) continue;
            if (plan.visualAction != VisualAction.PLACE_FLOATING_PNG) continue;
            RenderedGroup rg = renderedGroupForPlan(plan);
            double[] bounds = plan.bounds != null ? plan.bounds : (rg != null ? rg.bounds() : null);
            if (bounds == null || bounds.length < 4) continue;
            double[] pageBounds = normalizeSpreadBoundsToPage(plan.pageIndex, bounds);
            if (hasMainPageIntersection(plan.pageIndex, pageBounds)) continue;
            if (isLabelBackdropGroupPlan(plan)
                    && hasMainPageIntersectionInPageBoundsUnits(plan.pageIndex, pageBounds)) {
                continue;
            }
            if (canPlaceAdjacentOverflowCopy(plan, rg, pageBounds)) continue;
            plans.set(i, plan.withVisualAction(VisualAction.DROP_VISUAL,
                    "no_visible_page_intersection"));
        }
    }

    private boolean hasMainPageIntersectionInPageBoundsUnits(int pageIndex, double[] bounds) {
        if (bounds == null || bounds.length < 4) return true;
        double[] page = pageBounds(pageIndex);
        if (page == null || page.length < 4) return false;
        double pageWidth = page[3] - page[1];
        double pageHeight = page[2] - page[0];
        if (pageWidth <= 0.0 || pageHeight <= 0.0) return false;
        return bounds[3] > 0.0 && bounds[1] < pageWidth
                && bounds[2] > 0.0 && bounds[0] < pageHeight;
    }

    private boolean hasMainPageIntersection(int pageIndex, double[] bounds) {
        if (bounds == null || bounds.length < 4) return true;
        double pageWidth = pageWidthMm(pageIndex);
        double pageHeight = pageHeightMm(pageIndex);
        return bounds[3] > 0.0 && bounds[1] < pageWidth
                && bounds[2] > 0.0 && bounds[0] < pageHeight;
    }

    private boolean canPlaceAdjacentOverflowCopy(ObjectPlan plan, RenderedGroup rg, double[] bounds) {
        if (plan == null || bounds == null || bounds.length < 4) return false;
        if (rg == null || rg.file() == null || rg.file().isEmpty()) return false;
        double fullW = bounds[3] - bounds[1];
        double fullH = bounds[2] - bounds[0];
        if (fullW <= 1.0 || fullH <= 1.0) return false;
        if (shouldSkipOverflowCopy(plan, rg)) return false;

        double pageWidth = pageWidthMm(plan.pageIndex);
        int pageCount = data != null && data.pages() != null ? data.pages().size() : 0;
        if (bounds[3] > pageWidth + 10.0 && plan.pageIndex + 1 < pageCount) {
            if (hasAdjacentPageIntersection(plan.pageIndex + 1,
                    bounds[1] - pageWidth,
                    bounds[0],
                    bounds[3] - pageWidth,
                    bounds[2])) {
                return true;
            }
        }
        if (bounds[1] < -10.0 && plan.pageIndex - 1 >= 0) {
            if (hasAdjacentPageIntersection(plan.pageIndex - 1,
                    bounds[1] + pageWidth,
                    bounds[0],
                    bounds[3] + pageWidth,
                    bounds[2])) {
                return true;
            }
        }
        return false;
    }

    private boolean shouldSkipOverflowCopy(ObjectPlan plan, RenderedGroup rg) {
        if (plan == null || rg == null) return false;
        if (!hasEditableTextOwnerSignal(rg)) return false;
        return plan.textAction == TextAction.OWNED_BY_HWPX_TEXT
                && plan.visualAction != VisualAction.PLACE_TEXT_SHELL
                && (data == null || !data.shouldUseCompletePngForSimpleButtonLabel(rg));
    }

    private boolean hasAdjacentPageIntersection(
            int pageIndex, double left, double top, double right, double bottom) {
        double pageWidth = pageWidthMm(pageIndex);
        double pageHeight = pageHeightMm(pageIndex);
        double visLeft = Math.max(0.0, left);
        double visTop = Math.max(0.0, top);
        double visRight = Math.min(right, pageWidth);
        double visBottom = Math.min(bottom, pageHeight);
        return visLeft < visRight && visTop < visBottom;
    }

    private void resolveDroppedRenderedTextOwnership() {
        for (int i = 0; i < plans.size(); i++) {
            ObjectPlan plan = plans.get(i);
            if ("text_frame".equals(plan.kind)) continue;
            if (plan.visualAction != VisualAction.DROP_VISUAL) continue;
            if (!shouldDropTextForDroppedRenderedPlan(plan)) continue;
            plans.set(i, plan.withTextAction(TextAction.DROP_TEXT));
        }
    }

    private static boolean shouldDropTextForDroppedRenderedPlan(ObjectPlan plan) {
        if (plan == null || plan.textAction == TextAction.DROP_TEXT) return false;
        if (plan.textAction == TextAction.OWNED_BY_PNG) return true;
        if (plan.textAction != TextAction.OWNED_BY_HWPX_TEXT) return false;
        String reason = safe(plan.reason);
        return reason.contains("text_hidden")
                || reason.contains("complex_graphic")
                || reason.contains("image_group")
                || reason.contains("mixed_group")
                || reason.contains("floating_child_owned_by_inline_parent")
                || reason.contains("child_baked_into_renderable_parent_group")
                || reason.contains("no_visible_page_intersection");
    }

    private void resolveVisibleVisualHwpxTextSourceSlots() {
        HashSet<String> textOwnedSlots = new HashSet<>();
        for (ObjectPlan plan : plans) {
            if (!"text_frame".equals(plan.kind)) continue;
            if (plan.textAction != TextAction.OWNED_BY_HWPX_TEXT) continue;
            for (int textFrameId : textFrameIdsForPlan(plan)) {
                textOwnedSlots.add(pageSourceKey(plan.pageIndex, textFrameId));
            }
        }
        if (textOwnedSlots.isEmpty()) return;

        for (int i = 0; i < plans.size(); i++) {
            ObjectPlan plan = plans.get(i);
            if ("text_frame".equals(plan.kind)) continue;
            if (!isVisibleRenderedVisual(plan)) continue;
            if (plan.visualAction == VisualAction.PLACE_TEXT_SHELL) continue;
            if (plan.textAction != TextAction.OWNED_BY_HWPX_TEXT) continue;
            List<Integer> retained = new ArrayList<>();
            boolean changed = false;
            for (int sourceId : visualSourceIds(plan)) {
                if (sourceId != plan.domId
                        && data.getTextFrame(String.valueOf(sourceId)) != null
                        && textOwnedSlots.contains(pageSourceKey(plan.pageIndex, sourceId))) {
                    changed = true;
                    continue;
                }
                retained.add(sourceId);
            }
            if (!changed || retained.isEmpty()) continue;
            ObjectPlan next = plan.withVisualSourceObjectIds(toIntArray(retained));
            if (textFrameIdsForPlan(next).length == 0) {
                next = next.withTextAction(TextAction.DROP_TEXT);
            }
            plans.set(i, next);
        }
    }

    private void resolveNonTextVisualEditableTextSources() {
        HashSet<Integer> hwpxTextSources = new HashSet<>();
        for (ObjectPlan plan : plans) {
            if (plan.textAction != TextAction.OWNED_BY_HWPX_TEXT) continue;
            for (int sourceId : textFrameIdsForPlan(plan)) {
                hwpxTextSources.add(sourceId);
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
            for (int sourceId : visualSourceIds(plan)) {
                if (sourceId != plan.domId && hwpxTextSources.contains(sourceId)) {
                    changed = true;
                    continue;
                }
                retained.add(sourceId);
            }
            if (!changed) continue;
            plans.set(i, plan.withVisualSourceObjectIds(toIntArray(retained)));
        }
    }

    private void warnDuplicateVisibleSourceIds() {
        Map<String, List<ObjectPlan>> byPageSource = new LinkedHashMap<>();
        for (ObjectPlan plan : plans) {
            if (!plan.hasVisibleVisual()) continue;
            for (int sourceId : visualSourceIds(plan)) {
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

    private boolean isLabelBackdropGroupWithUnclaimedHwpxText(RenderedGroup rg) {
        if (rg == null || data == null) return false;
        if (!"label_backdrop_group".equals(rg.reason())) return false;
        HashSet<Integer> claimedTextFrames = new HashSet<>();
        if (rg.editableTextFrameIds() != null) {
            for (String id : rg.editableTextFrameIds()) {
                int parsed = parseFlexibleId(id);
                if (parsed >= 0) claimedTextFrames.add(parsed);
            }
        }
        HashSet<Integer> visited = new HashSet<>();
        for (int sourceId : sourceIdsOrSelf(rg)) {
            if (sourceId < 0) continue;
            if (containsUnclaimedHwpxTextFrame(sourceId, claimedTextFrames, visited)) {
                return true;
            }
        }
        return false;
    }

    private boolean isLabelBackdropGroupWithForeignSources(RenderedGroup rg) {
        if (rg == null || data == null) return false;
        if (!"label_backdrop_group".equals(rg.reason())) return false;
        return labelBackdropGroupHasForeignSources(rg);
    }

    private boolean labelBackdropGroupHasForeignSources(RenderedGroup rg) {
        if (rg == null || data == null) return false;
        if (!"label_backdrop_group".equals(rg.reason())) return false;
        String[] editableIds = rg.editableTextFrameIds();
        if (editableIds == null || editableIds.length == 0) return false;
        HashSet<Integer> claimedTextFrames = new HashSet<>();
        HashSet<String> allowedAncestorIds = new HashSet<>();
        HashSet<String> allowedParentIds = new HashSet<>();
        for (String tfId : editableIds) {
            if (tfId == null) continue;
            int parsed = parseFlexibleId(tfId);
            if (parsed >= 0) claimedTextFrames.add(parsed);
            ResolvedPageItem tfItem = data.getPageItem(tfId);
            if (tfItem == null) continue;
            String parentId = tfItem.parentId();
            if (parentId == null || parentId.isEmpty()) continue;
            allowedParentIds.add(parentId);
            String cur = parentId;
            HashSet<String> visited = new HashSet<>();
            while (cur != null && !cur.isEmpty() && visited.add(cur)) {
                allowedAncestorIds.add(cur);
                ResolvedPageItem parent = data.getPageItem(cur);
                cur = parent != null ? parent.parentId() : null;
            }
        }
        if (allowedAncestorIds.isEmpty() && allowedParentIds.isEmpty()) return false;
        for (int sourceId : sourceIdsOrSelf(rg)) {
            if (sourceId < 0 || claimedTextFrames.contains(sourceId)) continue;
            String sid = String.valueOf(sourceId);
            if (allowedAncestorIds.contains(sid)) continue;
            ResolvedPageItem item = data.getPageItem(sid);
            if (item == null) continue;
            String parentId = item.parentId();
            if (parentId != null
                    && (allowedParentIds.contains(parentId) || allowedAncestorIds.contains(parentId))) {
                continue;
            }
            return true;
        }
        return false;
    }

    private boolean containsUnclaimedHwpxTextFrame(
            int sourceId,
            HashSet<Integer> claimedTextFrames,
            HashSet<Integer> visited) {
        if (!visited.add(sourceId)) return false;
        ResolvedTextFrame tf = data.getTextFrame(String.valueOf(sourceId));
        if (tf != null && data.isHwpxOwnedTextFrame(tf.id()) && !claimedTextFrames.contains(sourceId)) {
            return true;
        }
        ResolvedPageItem item = data.getPageItem(String.valueOf(sourceId));
        if (item != null && item.childIds() != null) {
            for (int childId : item.childIds()) {
                if (containsUnclaimedHwpxTextFrame(childId, claimedTextFrames, visited)) {
                    return true;
                }
            }
        }
        for (ResolvedPageItem candidate : data.pageItems()) {
            if (candidate == null || candidate.parentId() == null) continue;
            if (!candidate.parentId().equals(String.valueOf(sourceId))) continue;
            int childId = parseInt(candidate.id(), -1);
            if (childId >= 0 && containsUnclaimedHwpxTextFrame(childId, claimedTextFrames, visited)) {
                return true;
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
        if (isGraphicOnlyAtomicObject(rg)
                && rg.atomicSourceObjectIds() != null
                && rg.atomicSourceObjectIds().length > 0) {
            int[] copy = Arrays.copyOf(rg.atomicSourceObjectIds(), rg.atomicSourceObjectIds().length);
            Arrays.sort(copy);
            return copy;
        }
        int[] ids = rg.sourceObjectIds();
        if (ids == null || ids.length == 0) return new int[] { rg.id() };
        int[] copy = Arrays.copyOf(ids, ids.length);
        Arrays.sort(copy);
        return copy;
    }

    private static boolean isGraphicOnlyAtomicObject(RenderedGroup rg) {
        return rg != null
                && "GRAPHIC_ONLY".equals(rg.atomicObjectKind())
                && !"indesign_png".equals(rg.textOwner())
                && !Boolean.TRUE.equals(rg.containsText())
                && !Boolean.TRUE.equals(rg.containsEditableText());
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
        return isLabelReason(rg);
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

    private boolean isTextCardBackdropVector(RenderedGroup rg) {
        if (rg == null || data == null) return false;
        if (!"vector_shape".equals(safe(rg.reason()))) return false;
        double[] rb = rg.bounds();
        if (rb == null || rb.length < 4) return false;
        double rArea = area(rb);
        if (rArea < 40.0) return false;
        double pageArea = pageArea(rg.pageIndex());
        if (pageArea > 0.0 && rArea / pageArea > 0.30) return false;
        if (!hasPaperFillOnlyCardShapeSource(rg)) return false;

        for (ResolvedTextFrame tf : data.textFrames()) {
            if (!isEditableHwpxTextFrameOnPage(tf, rg.pageIndex())) continue;
            double[] tb = tf.pageRelativeBounds() != null ? tf.pageRelativeBounds() : tf.geometricBounds();
            if (tb == null || tb.length < 4) continue;
            if (area(tb) < 10.0) continue;
            if (isTextCardBackdropForTextBounds(rb, tb)) {
                return true;
            }
        }
        return false;
    }

    private boolean hasPaperFillOnlyCardShapeSource(RenderedGroup rg) {
        for (int id : sourceIdsOrSelf(rg)) {
            ResolvedPageItem item = data.getPageItem(String.valueOf(id));
            if (isPaperFillOnlyCardShapeItem(item)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isPaperFillOnlyCardShapeItem(ResolvedPageItem item) {
        if (item == null) return false;
        String type = safe(item.type());
        if (!("Rectangle".equals(type) || "Polygon".equals(type) || "Oval".equals(type))) {
            return false;
        }
        if (!isPaperColor(item.fillColorName())) return false;
        if (item.strokeWeight() > 0.01 && !isNoneColor(item.strokeColorName())) return false;
        if (item.opacity() < 0.5) return false;
        double[] b = boundsOf(item);
        if (b == null || b.length < 4) return false;
        double h = Math.abs(b[2] - b[0]);
        double w = Math.abs(b[3] - b[1]);
        return w >= 8.0 && h >= 8.0 && (w * h) >= 80.0;
    }

    private boolean isEditableHwpxTextFrameOnPage(ResolvedTextFrame tf, int pageIndex) {
        if (tf == null || tf.id() == null) return false;
        if (tf.pageIndex() != pageIndex) return false;
        if (tf.onHiddenLayer() || tf.nonprinting()) return false;
        if (data.isTextOwnedByIndesignPng(tf.id())) return false;
        String text = safe(tf.frameVisibleText()).trim();
        return !text.isEmpty();
    }

    private static boolean isTextCardBackdropForTextBounds(double[] card, double[] text) {
        double overlap = overlapRatio(card, text);
        if (overlap < 0.52 && !containsCenter(card, text) && !containsCenter(text, card)) {
            return false;
        }
        double cardH = Math.max(0.001, Math.abs(card[2] - card[0]));
        double cardW = Math.max(0.001, Math.abs(card[3] - card[1]));
        double textH = Math.max(0.001, Math.abs(text[2] - text[0]));
        double textW = Math.max(0.001, Math.abs(text[3] - text[1]));
        double hRatio = Math.min(cardH, textH) / Math.max(cardH, textH);
        double wRatio = Math.min(cardW, textW) / Math.max(cardW, textW);
        if (boundsContains(card, text, 8.0) || boundsContains(text, card, 8.0)) {
            return hRatio >= 0.35 && wRatio >= 0.35;
        }
        return overlap >= 0.70 && hRatio >= 0.55 && wRatio >= 0.55;
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

    private static boolean isLabelReason(RenderedGroup rg) {
        String reason = safe(rg != null ? rg.reason() : null);
        return reason.contains("label") || reason.contains("visual_label");
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

    private static boolean isRenderedVisualPlan(ObjectPlan plan) {
        return plan != null
                && plan.renderId != null
                && !"text_frame".equals(plan.kind);
    }

    private static boolean isLabelBackdropGroupPlan(ObjectPlan plan) {
        return plan != null && "label_backdrop_group".equals(plan.reason);
    }

    private static boolean isDroppableParentGroup(ObjectPlan plan) {
        if (!isVisibleRenderedVisual(plan)) return false;
        if (plan.visualAction == VisualAction.PLACE_TEXT_SHELL) return false;
        if (plan.placement == Placement.INLINE) return false;
        return plan.sourceObjectIds.length > 1;
    }

    private static boolean isMasterGraphicCompleteRender(ObjectPlan plan) {
        return isMasterGraphicRender(plan)
                && plan.hasVisibleVisual()
                && !isFallbackRenderFile(plan.file);
    }

    private static boolean isMasterGraphicFallbackRender(ObjectPlan plan) {
        return isMasterGraphicRender(plan) && isFallbackRenderFile(plan.file);
    }

    private static boolean isMasterGraphicRender(ObjectPlan plan) {
        return plan != null
                && plan.renderId != null
                && !"text_frame".equals(plan.kind)
                && "master_graphic".equals(plan.reason)
                && plan.placement == Placement.FLOATING;
    }

    private static boolean isFallbackRenderFile(String file) {
        return file != null && file.contains("_fallback_");
    }

    private boolean containsVisibleHwpxMasterTextSourceOnPage(ObjectPlan plan) {
        if (plan == null || plan.sourceObjectIds == null || plan.sourceObjectIds.length == 0) return false;
        for (ResolvedTextFrame tf : data.textFrames()) {
            if (!isVisibleHwpxMasterTextFrameOnPage(tf, plan.pageIndex)) continue;
            int sourceId = masterSourceDomId(tf);
            if (sourceId >= 0 && contains(plan.sourceObjectIds, sourceId)) return true;
        }
        return false;
    }

    private boolean isVisibleHwpxMasterTextFrameOnPage(ResolvedTextFrame tf, int pageIndex) {
        if (tf == null || !tf.isMasterInstance()) return false;
        if (tf.pageIndex() != pageIndex) return false;
        if (tf.onHiddenLayer() || tf.nonprinting()) return false;
        if (tf.id() != null && data.isTextOwnedByIndesignPng(tf.id())) return false;
        String text = tf.frameVisibleText();
        return text != null && !text.isBlank();
    }

    private static int masterSourceDomId(ResolvedTextFrame tf) {
        if (tf == null) return -1;
        int fromField = parseInt(tf.masterSourceId(), -1);
        if (fromField >= 0) return fromField;
        String id = tf.id();
        if (id == null) return -1;
        int pi = id.indexOf("_pi");
        int oc = id.indexOf("_oc");
        int suffix = -1;
        if (pi >= 0 && oc >= 0) suffix = Math.min(pi, oc);
        else if (pi >= 0) suffix = pi;
        else if (oc >= 0) suffix = oc;
        return suffix > 0 ? parseInt(id.substring(0, suffix), -1) : parseInt(id, -1);
    }

    private int findMasterGraphicFallbackIndex(ObjectPlan complete) {
        int best = -1;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int i = 0; i < plans.size(); i++) {
            ObjectPlan fallback = plans.get(i);
            if (!isMasterGraphicFallbackRender(fallback)) continue;
            if (fallback.pageIndex != complete.pageIndex) continue;
            if (fallback.domId == complete.domId) continue;
            if (!sharesAnySource(complete, fallback)) continue;
            if (!boundsContains(complete.bounds, fallback.bounds, 1.0)
                    && !boundsMostlyOverlap(complete.bounds, fallback.bounds, 0.70)) {
                continue;
            }
            if (!containsVisibleHwpxMasterTextSourceOnPage(fallback)) continue;
            double score = overlapRatio(complete.bounds, fallback.bounds);
            if (score > bestScore) {
                bestScore = score;
                best = i;
            }
        }
        return best;
    }

    private static boolean isStrictChildPlan(ObjectPlan parent, ObjectPlan child) {
        if (parent.domId == child.domId) return false;
        if (containsAll(parent.sourceObjectIds, child.sourceObjectIds)) {
            return parent.sourceObjectIds.length > child.sourceObjectIds.length
                    || sameSourceNestedFootprint(parent, child);
        }
        return contains(parent.sourceObjectIds, child.domId);
    }

    private static boolean sameSourceNestedFootprint(ObjectPlan parent, ObjectPlan child) {
        if (parent == null || child == null) return false;
        if (!containsAll(parent.sourceObjectIds, child.sourceObjectIds)) return false;
        double parentArea = area(parent.bounds);
        double childArea = area(child.bounds);
        if (parentArea <= 0.0 || childArea <= 0.0) return false;
        if (parentArea <= childArea * 1.05) return false;
        return boundsMostlyOverlap(parent.bounds, child.bounds, 0.35)
                || boundsContains(parent.bounds, child.bounds, 8.0);
    }

    private boolean shouldPreferCompositeParent(ObjectPlan parent, ObjectPlan child) {
        if (parent == null || child == null) return false;
        if (!boundsMostlyOverlap(parent.bounds, child.bounds, 0.90)) return false;
        if (parent.sourceObjectIds.length <= child.sourceObjectIds.length) return false;
        double parentScore = visualInkScore(parent);
        double childScore = visualInkScore(child);
        return parentScore > childScore + 0.006;
    }

    private static boolean isBackgroundParentWithContentChild(ObjectPlan parent, ObjectPlan child) {
        if (parent == null || child == null) return false;
        return parent.visualPolicyLayer() == PolicyLayer.BACKGROUND
                && child.visualPolicyLayer() == PolicyLayer.CONTENT;
    }

    private static int[] withoutChildVisualSources(ObjectPlan parent, ObjectPlan child) {
        if (parent == null || parent.sourceObjectIds == null) return new int[0];
        LinkedHashSet<Integer> childSources = new LinkedHashSet<>();
        if (child != null) {
            childSources.add(child.domId);
            if (child.sourceObjectIds != null) {
                for (int sourceId : child.sourceObjectIds) {
                    childSources.add(sourceId);
                }
            }
        }
        if (childSources.isEmpty()) return parent.sourceObjectIds;
        List<Integer> retained = new ArrayList<>();
        for (int sourceId : parent.sourceObjectIds) {
            if (sourceId != parent.domId && childSources.contains(sourceId)) {
                continue;
            }
            retained.add(sourceId);
        }
        return retained.isEmpty() ? parent.sourceObjectIds : toIntArray(retained);
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

    private static double overlapArea(double[] a, double[] b) {
        if (a == null || b == null || a.length < 4 || b.length < 4) return 0.0;
        double left = Math.max(a[1], b[1]);
        double top = Math.max(a[0], b[0]);
        double right = Math.min(a[3], b[3]);
        double bottom = Math.min(a[2], b[2]);
        double w = right - left;
        double h = bottom - top;
        return w > 0 && h > 0 ? w * h : 0.0;
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

    private static boolean sourceSetContainsAll(int[] ownerSources, int[] childSources) {
        if (ownerSources == null || childSources == null || childSources.length == 0) return false;
        for (int childSource : childSources) {
            boolean found = false;
            for (int ownerSource : ownerSources) {
                if (ownerSource == childSource) {
                    found = true;
                    break;
                }
            }
            if (!found) return false;
        }
        return true;
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

    private static boolean containsAny(int[] values, int[] candidates) {
        if (values == null || candidates == null) return false;
        for (int candidate : candidates) {
            if (contains(values, candidate)) return true;
        }
        return false;
    }

    private static int[] toIntArray(List<Integer> values) {
        int[] out = new int[values.size()];
        for (int i = 0; i < values.size(); i++) out[i] = values.get(i);
        return out;
    }

    private static int[] toIntArray(LinkedHashSet<Integer> values) {
        int[] out = new int[values.size()];
        int i = 0;
        for (Integer value : values) {
            out[i++] = value != null ? value : -1;
        }
        return out;
    }

    private static int[] visualSourceIds(ObjectPlan plan) {
        if (plan == null) return new int[0];
        if (plan.visualSourceObjectIds != null && plan.visualSourceObjectIds.length > 0) {
            return plan.visualSourceObjectIds;
        }
        return plan.sourceObjectIds != null ? plan.sourceObjectIds : new int[0];
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value);
        } catch (Exception e) {
            return fallback;
        }
    }

    private static int parseFlexibleId(String value) {
        if (value == null || value.isEmpty()) return -1;
        int decimal = parseInt(value, -1);
        if (decimal >= 0) return decimal;
        String s = value;
        int marker = Math.max(s.lastIndexOf('u'), s.lastIndexOf('U'));
        marker = Math.max(marker, Math.max(s.lastIndexOf('i'), s.lastIndexOf('I')));
        if (marker >= 0 && marker + 1 < s.length()) {
            String tail = s.substring(marker + 1);
            int slash = tail.indexOf('/');
            if (slash >= 0) tail = tail.substring(0, slash);
            try {
                return Integer.parseInt(tail, 16);
            } catch (NumberFormatException e) {
                return -1;
            }
        }
        return -1;
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
