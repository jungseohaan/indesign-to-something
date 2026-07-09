package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.phase6;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTFigure;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTSection;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ConversionTiming;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ResolvedBuildContext;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.stage3.PreparedVisualImage;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.stage3.VisualCropper;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.stage3.VisualLayeringRules;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.stage3.VisualPlacementExecutor;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.stage3.VisualPlacementPlan;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.stage3.VisualPlacementPlanBuilder;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.stage3.VisualPngHeader;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.stage3.VisualShellPreparationRules;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.stage3.VisualSyntheticLinePlacer;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.stage3.VisualTextEmphasisAbsorber;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.stage3.VisualTfInlineCompositor;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.CoordinateSpace;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.ObjectPlan;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.Placement;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.ShellRole;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.RenderedGroup;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedTextFrame;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.List;

    /**
     * Stage 3 bridge: materializes already-planned floating visual ObjectPlans.
     *
     * <p>This class does not decide ownership, placement, layer, or visibility.
     * Missing/hidden output must be fixed by extraction metadata or Stage 1
     * ObjectPlan generation, not by this executor.</p>
     */
public final class BackgroundInjector {

    private BackgroundInjector() {}
    private static final double TF_VISUAL_SHELL_MIN_AREA_RATIO = 0.45;
    private static final double TF_VISUAL_SHELL_MAX_AREA_RATIO = 1.80;
    private static final double TF_VISUAL_SHELL_OVERLAP_MIN = 0.75;
    public static void inject(ResolvedBuildContext ctx, List<ASTSection> sections) {
        if (ctx.resolvedData == null) return;
        List<RenderedGroup> floatingItems = ctx.resolvedData.allRenderedFloatingItems();
        if (floatingItems == null || floatingItems.isEmpty()) return;

        for (RenderedGroup rg : floatingItems) {
            ObjectPlan ownershipPlan = ctx.findOwnershipPlanForRendered(rg);
            if (ownershipPlan == null) {
                ctx.recordRenderedDecision(rg, "Stage3.VisualBuilder.Phase6",
                        "SKIP_NO_OBJECT_PLAN", "visible candidate has no OwnershipPlanner ObjectPlan");
                continue;
            }
            if (ownershipPlan.placement != Placement.FLOATING) {
                continue;
            }
            if (ownershipPlan.coordinateSpace != CoordinateSpace.PAGE) {
                ctx.recordRenderedDecision(rg, ownershipPlan, "Phase6", "SKIP_OBJECT_PLAN_NON_PAGE_COORDINATES",
                        "floating visual ObjectPlan must provide PAGE coordinateSpace");
                continue;
            }
            VisualPlacementResolver.PlanRejection planRej =
                    VisualPlacementResolver.planRejection(ctx, rg);
            if (planRej != null) {
                ctx.recordRenderedDecision(rg, "Stage3.VisualBuilder.Phase6",
                        planRej.code, planRej.detail);
                continue;
            }

            int pageIdx = ctx.toSectionIndex.applyAsInt(rg.pageIndex());
            if (pageIdx < 0 || pageIdx >= sections.size()) {
                ctx.recordRenderedDecision(rg, "Phase6", "SKIP_NO_SECTION", "pageIndex not mapped to section");
                continue;
            }

            double[] bounds = ownershipPlan.bounds;
            if (bounds == null || bounds.length < 4) {
                ctx.recordRenderedDecision(rg, ownershipPlan, "Phase6", "SKIP_OBJECT_PLAN_NO_BOUNDS",
                        "visible ObjectPlan has no executable bounds");
                continue;
            }

            if (VisualTextEmphasisAbsorber.tryAbsorbTextEmphasisBackdrop(ctx, sections, rg, bounds)) {
                ctx.recordRenderedDecision(rg, "Phase6", "ABSORB_TEXT_EMPHASIS_BACKDROP",
                        "thin text backdrop converted to HWPX character shading");
                continue;
            }

            byte[] imageData = loadPng(ctx, rg, ownershipPlan);
            if (imageData == null) {
                ctx.recordRenderedDecision(rg, "Phase6", "SKIP_PNG_LOAD_FAILED", "png file missing or unreadable");
                continue;
            }
            byte[] originalImageData = imageData;

            // bounds: [top, left, bottom, right] in document units (mm)
            double rawLeft = bounds[1], rawTop = bounds[0];
            double rawRight = bounds[3], rawBottom = bounds[2];
            double fullW = rawRight - rawLeft;
            double fullH = rawBottom - rawTop;
            String visualLayer = ownershipPlan.visualLayer != null ? ownershipPlan.visualLayer.name() : null;
            double cropRefLeft = rawLeft, cropRefTop = rawTop;
            double cropRefRight = rawRight, cropRefBottom = rawBottom;
            double[] cropSourceBounds = cropSourceBounds(ownershipPlan, rg);
            boolean hasCropSourceBounds = hasUsableCropSourceBounds(cropSourceBounds, bounds);
            if (hasCropSourceBounds) {
                cropRefLeft = cropSourceBounds[1];
                cropRefTop = cropSourceBounds[0];
                cropRefRight = cropSourceBounds[3];
                cropRefBottom = cropSourceBounds[2];
            }
            double cropRefW = cropRefRight - cropRefLeft;
            double cropRefH = cropRefBottom - cropRefTop;

            // 페이지 경계 밖으로 넘치는 PNG를 가시 영역으로 크롭
            double pageWidthMm = 1e9, pageHeightMm = 1e9;
            if (ctx.resolvedData.pages() != null && pageIdx < ctx.resolvedData.pages().size()) {
                double[] pgB = ctx.resolvedData.pages().get(pageIdx).bounds();
                if (pgB != null && pgB.length >= 4) {
                    // pages() bounds are in pt after normalizeToPoints; divide by scaleFactor to get mm
                    pageWidthMm = (pgB[3] - pgB[1]) / ctx.scaleFactor;
                    pageHeightMm = (pgB[2] - pgB[0]) / ctx.scaleFactor;
                }
            }
            double visLeft = Math.max(0.0, rawLeft);
            double visTop = Math.max(0.0, rawTop);
            double visRight = Math.min(rawRight, pageWidthMm);
            double visBottom = Math.min(rawBottom, pageHeightMm);
            if (visLeft >= visRight || visTop >= visBottom) {
                ctx.recordRenderedDecision(rg, "Phase6", "SKIP_OUTSIDE_PAGE", "no visible page intersection");
                continue;
            }
            PreparedVisualImage prepared = new PreparedVisualImage(imageData);
            boolean shouldCompositeTfInlineVisuals = VisualTfInlineCompositor.shouldCompositeTfInlineVisuals(ctx, rg);
            boolean keepPlannedContainerBackdropFill =
                    VisualShellPreparationRules.isPaperFilledContainerBackdrop(ctx, rg, visualLayer);
            boolean isPlannedTextShell = ShellRole.isTextShell(ownershipPlan);
            boolean needsContainerShellKnockout = VisualShellPreparationRules.shouldKnockOutContainerShell(
                    ctx, rg, visualLayer, rawLeft, rawTop, rawRight, rawBottom, pageWidthMm, pageHeightMm);
            boolean needsIntersectionCrop = fullW > 1.0 && fullH > 1.0
                    && (visLeft > rawLeft + 0.5 || visRight < rawRight - 0.5
                        || visTop > rawTop + 0.5 || visBottom < rawBottom - 0.5);
            boolean needsPageCrop = needsIntersectionCrop
                    || (hasCropSourceBounds && cropRefW > 1.0 && cropRefH > 1.0);
            boolean needsFullImageDecode = shouldCompositeTfInlineVisuals
                    || needsContainerShellKnockout
                    || rg.isWhiteStroke()
                    || needsPageCrop;
            try {
                BufferedImage img = needsFullImageDecode
                        ? VisualTfInlineCompositor.loadImageForPlacement(ctx, rg, ownershipPlan, prepared.imageData)
                        : null;
                if (img == null && !needsFullImageDecode) {
                    int[] dims = VisualPngHeader.readDimensions(prepared.imageData);
                    if (dims != null) {
                        prepared.pixelW = dims[0];
                        prepared.pixelH = dims[1];
                        ConversionTiming.addCounter("phase6.pngBytes.headerDimensionReads", 1);
                    }
                }
                if (img != null && shouldCompositeTfInlineVisuals) {
                    prepared.imageData = VisualCropper.encodePng(img);
                }
                if (img != null && needsContainerShellKnockout) {
                    BufferedImage transparentShell = VisualCropper.knockOutPaperLikeFill(img);
                    if (transparentShell != img) {
                        prepared.imageData = VisualCropper.encodePng(transparentShell);
                        img.flush();
                        img = transparentShell;
                    }
                }
                // whiteStroke: PNG가 흑색 획으로 내보낸 것 → 흰색으로 반전
                if (img != null && rg.isWhiteStroke()) {
                    BufferedImage inv = VisualCropper.invertVisiblePixels(img);
                    img.flush();
                    img = inv;
                    // imageData를 반전 이미지로 업데이트 (crop 없을 때도 흰색이 적용되도록)
                    try {
                        prepared.imageData = VisualCropper.encodePng(img);
                    } catch (Exception ignored2) {}
                }
                if (img != null) {
                    boolean currentNeedsIntersectionCrop = fullW > 1.0 && fullH > 1.0
                            && (visLeft > rawLeft + 0.5 || visRight < rawRight - 0.5
                                || visTop > rawTop + 0.5 || visBottom < rawBottom - 0.5);
                    boolean needsCrop = currentNeedsIntersectionCrop
                            || (hasCropSourceBounds && cropRefW > 1.0 && cropRefH > 1.0);
                    if (needsCrop) {
                        boolean masterEdgeStrip = VisualShellPreparationRules.isMasterEdgeStripPlan(
                                ctx, rg, pageWidthMm);
                        VisualCropper.PageCropPlan cropPlan = VisualCropper.pageCropPlan(
                                masterEdgeStrip, img, pageIdx, hasCropSourceBounds,
                                rawLeft, rawRight, rawTop, rawBottom,
                                cropRefLeft, cropRefTop, cropRefRight, cropRefW, cropRefH,
                                visLeft, visTop, visRight, visBottom,
                                fullW, pageWidthMm, pageHeightMm);
                        prepared.pageAnchoredStripCrop = cropPlan.pageAnchoredStripCrop;
                        VisualCropper.PageCropResult pageCrop = VisualCropper.pageCrop(
                                img, cropPlan.pxX, cropPlan.pxY, cropPlan.pxW, cropPlan.pxH);
                        if (pageCrop != null) {
                            if (pageCrop.imageData != null) {
                                prepared.imageData = pageCrop.imageData;
                            }
                            prepared.pixelW = pageCrop.pixelW;
                            prepared.pixelH = pageCrop.pixelH;
                        }
                    } else {
                        prepared.pixelW = img.getWidth();
                        prepared.pixelH = img.getHeight();
                    }
                    img.flush();
                }
            } catch (Exception ignored) {}

            VisualPlacementPlan placementPlan = VisualPlacementPlanBuilder.build(
                    ctx,
                    sections.get(pageIdx),
                    rg,
                    prepared,
                    ownershipPlan,
                    visLeft,
                    visTop,
                    visRight,
                    visBottom);
            if (placementPlan == null || !placementPlan.hasPositiveSize()) continue;

            VisualPlacementExecutor.PlacementResult placementResult = VisualPlacementExecutor.place(
                    ctx, sections.get(pageIdx), rg, prepared, placementPlan, ownershipPlan);

            if (placementResult.textShellPlaced) {
                continue;
            }
        }

        // Secondary pass: synthetic PNG for GraphicLine children of essentially-empty parent PNGs.
        // When a Rectangle frame contains a pasted-inside GraphicLine, InDesign's exportFile()
        // captures only the invisible frame shape — the child line is lost. Detect this by checking
        // if the parent PNG file is < 1 KB but has non-trivial pixel dimensions, then generate a
        // solid-color 1-row PNG from the child's pageItems stroke data.
        VisualSyntheticLinePlacer.injectSyntheticGraphicLines(ctx, sections);
    }

    private static double[] cropSourceBounds(ObjectPlan plan, RenderedGroup rg) {
        if (plan != null && plan.cropSourceBounds != null && plan.cropSourceBounds.length >= 4) {
            return plan.cropSourceBounds;
        }
        return rg != null ? rg.cropSourceBounds() : null;
    }

    private static boolean hasUsableCropSourceBounds(double[] crop, double[] bounds) {
        if (crop == null || crop.length < 4 || bounds == null || bounds.length < 4) return false;
        double cropW = crop[3] - crop[1];
        double cropH = crop[2] - crop[0];
        double boundsW = bounds[3] - bounds[1];
        double boundsH = bounds[2] - bounds[0];
        if (cropW <= 1.0 || cropH <= 1.0 || boundsW <= 0.0 || boundsH <= 0.0) return false;
        boolean containsBounds = crop[0] <= bounds[0] + 0.05
                && crop[1] <= bounds[1] + 0.05
                && crop[2] >= bounds[2] - 0.05
                && crop[3] >= bounds[3] - 0.05;
        boolean materiallyLarger = cropW > boundsW + 0.5 || cropH > boundsH + 0.5;
        return containsBounds && materiallyLarger;
    }

    public static byte[] loadPng(ResolvedBuildContext ctx, RenderedGroup rg) {
        ObjectPlan plan = ctx != null && rg != null ? ctx.findOwnershipPlanForRendered(rg) : null;
        return loadPng(ctx, rg, plan);
    }

    public static byte[] loadPng(ResolvedBuildContext ctx, RenderedGroup rg, ObjectPlan plan) {
        if (plan == null || plan.file == null || plan.file.isEmpty()) return null;
        return loadPng(ctx, plan.file);
    }

    private static byte[] loadPng(ResolvedBuildContext ctx, String file) {
        if (ctx == null || file == null) return null;
        try {
            File pngFile = new File(ctx.basePath, file);
            if (!pngFile.exists()) return null;
            String key = pngFile.getAbsolutePath();
            byte[] cached = ctx.renderedPngByteCache.get(key);
            if (cached != null) {
                ConversionTiming.addCounter("phase6.pngBytes.cacheHits", 1);
                return cached;
            }
            ConversionTiming.addCounter("phase6.pngBytes.diskReads", 1);
            byte[] data = java.nio.file.Files.readAllBytes(pngFile.toPath());
            ctx.renderedPngByteCache.put(key, data);
            ConversionTiming.addCounter("phase6.pngBytes.readBytes", data.length);
            return data;
        } catch (Exception e) {
            System.err.println("[BackgroundInjector] PNG 로드 실패: " + e.getMessage());
            return null;
        }
    }

    private static boolean isCompletePngSimpleButtonLabel(ResolvedBuildContext ctx, RenderedGroup rg) {
        return VisualLayeringRules.isCompletePngSimpleButtonLabel(ctx, rg);
    }

    private static boolean isPlannedTextShell(ResolvedBuildContext ctx, RenderedGroup rg) {
        return ShellRole.isTextShell(ctx != null && rg != null ? ctx.findOwnershipPlanForRendered(rg) : null);
    }

    public static boolean isPaperColor(String colorName) {
        return "Paper".equals(colorName) || "[Paper]".equals(colorName);
    }

    public static boolean hasSemanticText(ResolvedTextFrame tf) {
        return !visibleText(tf).isEmpty();
    }

    public static String visibleText(ResolvedTextFrame tf) {
        String text = tf != null ? tf.frameVisibleText() : null;
        if (text == null) return "";
        return text.replace("\uFFFC", "")
                .replace("\u0016", "")
                .replace("\u0018", "")
                .trim();
    }

    public static double area(double[] b) {
        if (b == null || b.length < 4) return 0.0;
        double w = b[3] - b[1];
        double h = b[2] - b[0];
        return w > 0 && h > 0 ? w * h : 0.0;
    }

    public static double overlapArea(double[] a, double[] b) {
        if (a == null || b == null || a.length < 4 || b.length < 4) return 0.0;
        double left = Math.max(a[1], b[1]);
        double top = Math.max(a[0], b[0]);
        double right = Math.min(a[3], b[3]);
        double bottom = Math.min(a[2], b[2]);
        double w = right - left;
        double h = bottom - top;
        return w > 0 && h > 0 ? w * h : 0.0;
    }

}
