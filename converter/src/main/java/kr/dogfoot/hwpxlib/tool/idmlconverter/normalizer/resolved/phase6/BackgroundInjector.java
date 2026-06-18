package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.phase6;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTFigure;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTSection;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ConversionTiming;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ResolvedBuildContext;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.stage3.PreparedVisualImage;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.stage3.VisualCropper;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.stage3.VisualLayeringRules;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.stage3.VisualOverlapZOrderPlanner;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.stage3.VisualOverflowPlacer;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.stage3.VisualPlacementExecutor;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.stage3.VisualPlacementPlan;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.stage3.VisualPlacementPlanBuilder;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.stage3.VisualPngHeader;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.stage3.VisualSyntheticLinePlacer;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.stage3.VisualTextEmphasisAbsorber;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.stage3.VisualTfInlineCompositor;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.stage3.VisualZOrderPlanner;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.Placement;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.VisualAction;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.RenderedGroup;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedTextFrame;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Phase 6: 개별 객체 PNG를 ASTFigure로 주입.
 *
 * <p>resolved.allRenderedFloatingItems() 중 itemType="page_object"인 항목의 PNG를
 * 페이지 내 실제 좌표(x/y)에 BEHIND_TEXT로 배치한다.</p>
 *
 * <p>이전 itemType="page_background"(전체 페이지 PNG) 방식은 인라인 배지가 억제되지 않아 폐기됨.</p>
 */
public final class BackgroundInjector {

    private BackgroundInjector() {}
    private static final double TF_VISUAL_SHELL_MIN_AREA_RATIO = 0.45;
    private static final double TF_VISUAL_SHELL_MAX_AREA_RATIO = 1.80;
    private static final double TF_VISUAL_SHELL_OVERLAP_MIN = 0.75;
    private static final double CONCEPT_LABEL_SHELL_MIN_AREA_RATIO = 0.30;
    private static final double CONCEPT_LABEL_SHELL_MAX_AREA_RATIO = 2.60;
    private static final double CONCEPT_LABEL_SHELL_OVERLAP_MIN = 0.55;

    public static void inject(ResolvedBuildContext ctx, List<ASTSection> sections) {
        if (ctx.resolvedData == null) return;
        List<RenderedGroup> floatingItems = ctx.resolvedData.allRenderedFloatingItems();
        if (floatingItems == null || floatingItems.isEmpty()) return;

        // (SPEC-036 (가)) childOfGroup(부모 PNG에 구워진 자식) 억제는 Stage 2.5 refinement가
        // 비보호 항목을 plan(DROP_VISUAL)으로 확정한다.
        // → 아래 VisualPlacementResolver.planRejection이 처리하므로 여기서 별도 체크 불필요.

        // (SPEC-036 (가)) coveredByInlineObjects(inline_object 소유 커버리지) 억제도 Stage 2.5
        // refinement가 plan(DROP_VISUAL) 확정 → 아래 별도 체크 불필요.
        for (RenderedGroup rg : floatingItems) {
            boolean conceptDiagramInlineShell = isConceptDiagramInlineVisualShell(ctx, rg);
            boolean plannedFloatingTextShell =
                    ctx.visualActionByOwnershipPlan(rg) == VisualAction.PLACE_TEXT_SHELL
                            && ctx.placementByOwnershipPlan(rg) == Placement.FLOATING;
            if (!isPageObject(rg) && !conceptDiagramInlineShell && !plannedFloatingTextShell) {
                continue;
            }
            if (!ctx.hasOwnershipPlan(rg)) {
                ctx.recordRenderedDecision(rg, "Stage3.VisualBuilder.Phase6",
                        "SKIP_NO_OBJECT_PLAN", "visible candidate has no OwnershipPlanner ObjectPlan");
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

            double[] bounds = rg.bounds();
            if (bounds == null || bounds.length < 4) {
                ctx.recordRenderedDecision(rg, "Phase6", "SKIP_NO_BOUNDS", "rendered item has no bounds");
                continue;
            }
            bounds = VisualTfInlineCompositor.shouldCompositeTfInlineVisuals(rg)
                    ? VisualTfInlineCompositor.boundsWithTfInlineVisuals(ctx, rg, bounds)
                    : bounds;
            bounds = normalizeSpreadBoundsToPage(ctx, pageIdx, rg, bounds);

            if (VisualTextEmphasisAbsorber.tryAbsorbTextEmphasisBackdrop(ctx, sections, rg, bounds)) {
                ctx.recordRenderedDecision(rg, "Phase6", "ABSORB_TEXT_EMPHASIS_BACKDROP",
                        "thin text backdrop converted to HWPX character shading");
                continue;
            }

            byte[] imageData = loadPng(ctx, rg);
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
            double cropRefLeft = rawLeft, cropRefTop = rawTop;
            double cropRefRight = rawRight, cropRefBottom = rawBottom;
            boolean hasCropSourceBounds = hasUsableCropSourceBounds(rg, bounds);
            if (hasCropSourceBounds) {
                double[] cropSource = rg.cropSourceBounds();
                cropRefLeft = cropSource[1];
                cropRefTop = cropSource[0];
                cropRefRight = cropSource[3];
                cropRefBottom = cropSource[2];
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
                int overflowPlaced = 0;
                if (!ctx.shouldSkipOverflowCopyByOwnershipPlan(rg)) {
                    overflowPlaced = VisualOverflowPlacer.placeSpreadOverflowCopies(
                            ctx, sections, rg, pageIdx,
                            rawLeft, rawTop, rawRight, rawBottom, fullW, fullH,
                            pageWidthMm, originalImageData, ctx.visualLayerByOwnershipPlan(rg));
                }
                if (overflowPlaced > 0) {
                    ctx.recordRenderedDecision(rg, "Phase6", "PLACE_OUTSIDE_PAGE_OVERFLOW",
                            "no main page intersection, but spread overflow was placed on adjacent page");
                } else {
                    ctx.recordRenderedDecision(rg, "Phase6", "SKIP_OUTSIDE_PAGE", "no visible page intersection");
                }
                continue;
            }
            double minEdgeStripVisibleWidth = minimumVisibleWidthForMasterEdgeStrip(
                    rg, rawLeft, rawRight, rawTop, rawBottom,
                    cropRefLeft, cropRefRight, visLeft, visRight, pageWidthMm, pageHeightMm);
            if (minEdgeStripVisibleWidth > 0 && (visRight - visLeft) < minEdgeStripVisibleWidth) {
                if (rawLeft < 0.0 || cropRefLeft < 0.0) {
                    visRight = Math.min(pageWidthMm, visLeft + minEdgeStripVisibleWidth);
                } else if (rawRight > pageWidthMm || cropRefRight > pageWidthMm) {
                    visLeft = Math.max(0.0, visRight - minEdgeStripVisibleWidth);
                }
            }
            boolean isTextFrameVisualShell = "editable_textframe_visual_shell".equals(rg.reason());
            boolean coversPageByArea = pageWidthMm < 1e9 && pageHeightMm < 1e9
                    && (rawRight - rawLeft) * (rawBottom - rawTop)
                        >= 0.3 * pageWidthMm * pageHeightMm;
            boolean isFullPageBg = rawLeft <= 10.0
                    && rawTop <= 10.0
                    && (rawBottom >= pageHeightMm - 1.0 || coversPageByArea);
            boolean isBackgroundLike = isFullPageBg || (isTextFrameVisualShell && coversPageByArea);
            boolean isContainerVisualShell = !isBackgroundLike
                    && (isRenderedContainerShell(rg) || isTextFrameVisualShell);
            boolean isInferredTextFrameVisualShell =
                    VisualZOrderPlanner.inferredTextFrameVisualShellZOrder(ctx, rg) >= 0;
            String visualLayer = ctx.visualLayerByOwnershipPlan(rg);
            boolean planKeepsForegroundZ = isPlanForegroundVisualLayer(visualLayer);

            PreparedVisualImage prepared = new PreparedVisualImage(imageData);
            boolean shouldCompositeTfInlineVisuals = VisualTfInlineCompositor.shouldCompositeTfInlineVisuals(rg);
            boolean keepPlannedContainerBackdropFill =
                    ("CONTAINER_BACKDROP".equals(visualLayer) || "CONTAINER_FACE".equals(visualLayer))
                    && isPaperOnlyContainerShell(ctx, rg);
            boolean mayNeedContainerShellKnockout = !planKeepsForegroundZ
                    && !keepPlannedContainerBackdropFill
                    && isContainerVisualShell
                    && !isInferredTextFrameVisualShell;
            boolean isPlannedTextShell =
                    ctx.visualActionByOwnershipPlan(rg) == VisualAction.PLACE_TEXT_SHELL;
            boolean needsAlphaCrop = shouldCropOwnedTextFrameShellToAlpha(rg);
            boolean needsIntersectionCrop = fullW > 1.0 && fullH > 1.0
                    && (visLeft > rawLeft + 0.5 || visRight < rawRight - 0.5
                        || visTop > rawTop + 0.5 || visBottom < rawBottom - 0.5);
            boolean needsPageCrop = needsIntersectionCrop
                    || (!isPlannedTextShell && hasCropSourceBounds && cropRefW > 1.0 && cropRefH > 1.0);
            boolean needsFullImageDecode = shouldCompositeTfInlineVisuals
                    || mayNeedContainerShellKnockout
                    || rg.isWhiteStroke()
                    || needsAlphaCrop
                    || needsPageCrop;
            try {
                BufferedImage img = needsFullImageDecode ? VisualTfInlineCompositor.loadImageForPlacement(ctx, rg, prepared.imageData) : null;
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
                if (img != null
                        && !planKeepsForegroundZ
                        && !keepPlannedContainerBackdropFill
                        && isContainerVisualShell
                        && !isInferredTextFrameVisualShell) {
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
                    if (needsAlphaCrop) {
                        VisualCropper.AlphaCropResult alphaCrop = VisualCropper.alphaCrop(img);
                        if (alphaCrop != null) {
                            int pxX = alphaCrop.pxX;
                            int pxY = alphaCrop.pxY;
                            int pxW = alphaCrop.pxW;
                            int pxH = alphaCrop.pxH;
                            boolean preserveAlphaCropBounds = shouldPreserveBoundsForAlphaCroppedTextShell(
                                    rg, img.getWidth(), img.getHeight(), pxW, pxH, fullW, fullH);
                            double cropLeft = rawLeft + (double) pxX / (double) img.getWidth() * fullW;
                            double cropTop = rawTop + (double) pxY / (double) img.getHeight() * fullH;
                            double cropRight = rawLeft + (double) (pxX + pxW) / (double) img.getWidth() * fullW;
                            double cropBottom = rawTop + (double) (pxY + pxH) / (double) img.getHeight() * fullH;
                            if (shouldUsePhysicalAlphaCropBounds(rg, img.getWidth(), img.getHeight(), pxW, pxH)) {
                                double pxToMm = 25.4 / 220.0;
                                cropLeft = rawLeft + pxX * pxToMm;
                                cropTop = rawTop + pxY * pxToMm;
                                cropRight = cropLeft + pxW * pxToMm;
                                cropBottom = cropTop + pxH * pxToMm;
                            }
                            prepared.imageData = alphaCrop.imageData;
                            prepared.pixelW = alphaCrop.image.getWidth();
                            prepared.pixelH = alphaCrop.image.getHeight();
                            img.flush();
                            img = alphaCrop.image;

                            if (!preserveAlphaCropBounds) {
                                rawLeft = cropLeft;
                                rawTop = cropTop;
                                rawRight = cropRight;
                                rawBottom = cropBottom;
                                fullW = rawRight - rawLeft;
                                fullH = rawBottom - rawTop;
                                // The image buffer now represents the alpha-cropped bounds.
                                // Keep subsequent page/intersection cropping in the same
                                // coordinate frame; otherwise a second crop can collapse a
                                // composite visual to only its left-edge child.
                                cropRefLeft = rawLeft;
                                cropRefTop = rawTop;
                                cropRefRight = rawRight;
                                cropRefBottom = rawBottom;
                                cropRefW = cropRefRight - cropRefLeft;
                                cropRefH = cropRefBottom - cropRefTop;
                                visLeft = Math.max(0.0, rawLeft);
                                visTop = Math.max(0.0, rawTop);
                                visRight = Math.min(rawRight, pageWidthMm);
                                visBottom = Math.min(rawBottom, pageHeightMm);
                                if (visLeft >= visRight || visTop >= visBottom) {
                                    ctx.recordRenderedDecision(rg, "Phase6", "SKIP_OUTSIDE_PAGE_AFTER_ALPHA_CROP", "alpha crop removed page intersection");
                                    continue;
                                }
                            }
                        }
                    }
                    boolean currentNeedsIntersectionCrop = fullW > 1.0 && fullH > 1.0
                            && (visLeft > rawLeft + 0.5 || visRight < rawRight - 0.5
                                || visTop > rawTop + 0.5 || visBottom < rawBottom - 0.5);
                    boolean needsCrop = currentNeedsIntersectionCrop
                            || (!isPlannedTextShell && hasCropSourceBounds && cropRefW > 1.0 && cropRefH > 1.0);
                    if (needsCrop) {
                        VisualCropper.PageCropPlan cropPlan = VisualCropper.pageCropPlan(
                                rg, img, pageIdx, hasCropSourceBounds,
                                rawLeft, rawRight, rawTop, rawBottom,
                                cropRefLeft, cropRefTop, cropRefRight, cropRefW, cropRefH,
                                visLeft, visTop, visRight, visBottom,
                                fullW, pageWidthMm, pageHeightMm);
                        prepared.pageAnchoredStripCrop = cropPlan.pageAnchoredStripCrop;
                        prepared.stripCropLeftOverride = cropPlan.stripCropLeftOverride;
                        prepared.stripCropWidthOverride = cropPlan.stripCropWidthOverride;
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

            // whiteStroke PNG는 exportFile이 visibleBounds보다 큰 영역을 내보낼 수 있음.
            // arc 스트로크는 PNG 중앙에 위치하므로 중앙 기준으로 bounds를 확장.
            if (rg.isWhiteStroke() && !((fullW > 1.0 && fullH > 1.0)
                    && (visLeft > rawLeft + 0.5 || visRight < rawRight - 0.5
                        || visTop > rawTop + 0.5 || visBottom < rawBottom - 0.5))
                    && prepared.pixelW > 0 && prepared.pixelH > 0) {
                double pngWidthMm = prepared.pixelW * 25.4 / 220.0;
                double pngHeightMm = prepared.pixelH * 25.4 / 220.0;
                double storedW = visRight - visLeft;
                double storedH = visBottom - visTop;
                if (pngWidthMm > storedW + 1.0) {
                    double extraX = (pngWidthMm - storedW) / 2.0;
                    visLeft -= extraX;
                    visRight += extraX;
                }
                if (pngHeightMm > storedH + 1.0) {
                    double extraY = (pngHeightMm - storedH) / 2.0;
                    visTop -= extraY;
                    visBottom += extraY;
                }
            }

            if (shouldPreserveVisualLabelAspect(rg, prepared.pixelW, prepared.pixelH)) {
                double storedW = visRight - visLeft;
                double storedH = visBottom - visTop;
                double imageRatio = (double) prepared.pixelW / (double) prepared.pixelH;
                double storedRatio = storedW / storedH;
                if (storedW > 0 && storedH > 0 && imageRatio > 0
                        && storedRatio > imageRatio * 1.10) {
                    double targetH = storedW / imageRatio;
                    double growRatio = targetH / storedH;
                    if (growRatio > 1.0 && growRatio <= 3.0) {
                        double cy = (visTop + visBottom) / 2.0;
                        visTop = cy - targetH / 2.0;
                        visBottom = cy + targetH / 2.0;
                    }
                }
            }

            VisualPlacementPlan placementPlan = VisualPlacementPlanBuilder.build(
                    ctx,
                    sections.get(pageIdx),
                    floatingItems,
                    rg,
                    prepared,
                    visLeft,
                    visTop,
                    visRight,
                    visBottom,
                    visualLayer,
                    isBackgroundLike,
                    planKeepsForegroundZ,
                    isContainerVisualShell,
                    isInferredTextFrameVisualShell,
                    isTextFrameVisualShell);
            if (placementPlan == null || !placementPlan.hasPositiveSize()) continue;

            VisualPlacementExecutor.PlacementResult placementResult = VisualPlacementExecutor.place(
                    ctx, sections.get(pageIdx), rg, prepared, placementPlan);
            if (placementResult.textShellPlaced) {
                continue;
            }

            if (!prepared.pageAnchoredStripCrop) {
                if (ctx.shouldSkipOverflowCopyByOwnershipPlan(rg)) {
                    ctx.recordRenderedDecision(rg, "Phase6", "SKIP_OVERFLOW_COPY_TEXT_OWNED",
                            "text-owned source with non-text-shell visual; avoid duplicate rendering on adjacent page");
                    continue;
                }
                VisualOverflowPlacer.placeSpreadOverflowCopies(
                        ctx, sections, rg, pageIdx,
                        rawLeft, rawTop, rawRight, rawBottom, fullW, fullH,
                        pageWidthMm, originalImageData, visualLayer);
            }
        }

        // Secondary pass: synthetic PNG for GraphicLine children of essentially-empty parent PNGs.
        // When a Rectangle frame contains a pasted-inside GraphicLine, InDesign's exportFile()
        // captures only the invisible frame shape — the child line is lost. Detect this by checking
        // if the parent PNG file is < 1 KB but has non-trivial pixel dimensions, then generate a
        // solid-color 1-row PNG from the child's pageItems stroke data.
        VisualSyntheticLinePlacer.injectSyntheticGraphicLines(ctx, sections);
    }

    /**
     * 배지 셸 그래픽을 텍스트 "뒤"에 깔아야 하는가. inline_badge(편집 텍스트 보유) + 배지 크기의
     * mixed_group/shell(텍스트 숨김, 편집 TF 별도 렌더)을 포함. 큰 사이드박스(높이 큼)는 제외.
     * (기존 isTextHiddenBadgeShell은 !containsEditableText·w≤95 제약이라 inline_badge/넓은 배지를
     *  놓치므로 별도 판정.)
     */
    public static boolean isPageObject(RenderedGroup rg) {
        return VisualLayeringRules.isPageObject(rg);
    }

    private static boolean hasUsableCropSourceBounds(RenderedGroup rg, double[] bounds) {
        double[] crop = rg.cropSourceBounds();
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

    private static double minimumVisibleWidthForMasterEdgeStrip(RenderedGroup rg,
                                                               double rawLeft,
                                                               double rawRight,
                                                               double rawTop,
                                                               double rawBottom,
                                                               double cropRefLeft,
                                                               double cropRefRight,
                                                               double visLeft,
                                                               double visRight,
                                                               double pageWidth,
                                                               double pageHeight) {
        if (rg == null || pageWidth >= 1e8) return 0.0;
        String file = rg.file();
        String reason = rg.reason();
        boolean masterStrip = (file != null && (file.contains("master_") || file.contains("haseera_")))
                || "master_graphic".equals(reason)
                || "haseera_graphic".equals(reason);
        if (!masterStrip) return 0.0;

        boolean leftEdge = (rawLeft < -0.5 || cropRefLeft < -0.5) && Math.abs(visLeft) < 0.5;
        boolean rightEdge = (rawRight > pageWidth + 0.5 || cropRefRight > pageWidth + 0.5)
                && Math.abs(visRight - pageWidth) < 0.5;
        if (!leftEdge && !rightEdge) return 0.0;

        double fullW = Math.max(rawRight - rawLeft, cropRefRight - cropRefLeft);
        double fullH = rawBottom - rawTop;
        if (fullW <= 1.0 || fullH <= 0.5) return 0.0;
        double maxStripHeight = pageHeight < 1e8 ? Math.min(40.0, pageHeight * 0.15) : 40.0;
        if (fullH > maxStripHeight) return 0.0;

        // Page-edge master badges often have a large off-page overhang.
        // A literal page intersection can leave only a tiny cap, which reads as missing in HWP.
        // Keep a conservative visible fraction while still clipping at the page edge.
        double minVisible = Math.min(fullW * 0.60, 24.0);
        return Math.max(minVisible, 0.0);
    }

    /**
     * SPEC-036 (가): Stage 2.5 refinement용. childOfGroup 전체와,
     * 그중 SKIP_CHILD_OF_GROUP 실제 억제 대상(비보호 = !protectedEditableLabelShell)을 분리 반환.
     * 비보호 집합만 plan(DROP_VISUAL)으로 확정하면 Stage 3가 휴리스틱 없이 동일 결과를 낸다.
     */
    /**
     * SPEC-036 (가): Stage 2.5 refinement용. inline_object 소유 커버리지 억제.
     * SKIP_INLINE_COVERAGE/SKIP_INLINE_OBJECT 두 체크의 실제 억제 대상(게이트 적용 합집합)을
     * 분리 반환 → 후자만 plan(DROP_VISUAL)으로 확정.
     */
    /**
     * SPEC-036: 부모 그룹 PNG에 이미 구워진 자식 렌더 id를 모은다(childOfGroup).
     * Phase 6/7은 이 집합의 항목을 독립 배치하지 않는다. (단, PLACE_TEXT_SHELL 부모 아래
     * 자체 래스터 렌더를 가진 자식은 부모가 굽지 않으므로 보존.)
     *
     * <p>주의: editableLabelShellIds/conceptDiagramLabelShellIds 등 Phase 3 산출물에 의존하므로
     * Phase 0 OwnershipPlanner로 단순 이전 불가 — Tier 2 일원화의 phase-ordering 제약 지점.</p>
     */
    private static boolean shouldPreserveEditableLabelShell(
            RenderedGroup rg, Set<Integer> editableLabelShellIds) {
        if (rg == null) return false;
        if (editableLabelShellIds != null && editableLabelShellIds.contains(rg.id())) return true;
        if (!VisualTextEmphasisAbsorber.isEditableLabelShellReason(rg.reason())) return false;
        if (!isPageObject(rg) && !"inline_object".equals(rg.itemType()) && !"inline_object".equals(rg.type())) {
            return false;
        }
        if (Boolean.FALSE.equals(rg.placementAllowed())) return false;
        if (!"indesign_png".equals(rg.visualOwner())) return false;
        if (!"hwpx_tf".equals(rg.textOwner())) return false;
        return !Boolean.TRUE.equals(rg.containsText())
                && !Boolean.TRUE.equals(rg.containsEditableText());
    }

    private static Set<Integer> collectEditableLabelShells(
            ResolvedBuildContext ctx, List<RenderedGroup> floatingItems) {
        Set<Integer> ids = new HashSet<>();
        if (ctx == null || ctx.resolvedData == null || floatingItems == null) return ids;
        for (RenderedGroup rg : floatingItems) {
            if (!isEditableLabelShellCandidate(rg)) continue;
            if (matchesShortEditableLabelText(ctx, rg)) {
                ids.add(rg.id());
            }
        }
        return ids;
    }

    private static Set<Integer> collectConceptDiagramLabelShells(
            ResolvedBuildContext ctx, List<RenderedGroup> floatingItems) {
        Set<Integer> ids = new HashSet<>();
        if (ctx == null || ctx.resolvedData == null || floatingItems == null
                || ctx.conceptDiagramTextFrameIds == null || ctx.conceptDiagramTextFrameIds.isEmpty()) {
            return ids;
        }
        for (RenderedGroup rg : floatingItems) {
            if (isConceptDiagramLabelShell(ctx, rg)) {
                ids.add(rg.id());
            }
        }
        return ids;
    }

    private static boolean isConceptDiagramLabelShell(ResolvedBuildContext ctx, RenderedGroup rg) {
        if (ctx == null || ctx.resolvedData == null || rg == null) return false;
        if (!isPageObject(rg)) return false;
        if (Boolean.FALSE.equals(rg.placementAllowed())) return false;
        if (!"indesign_png".equals(rg.visualOwner())) return false;
        if (Boolean.TRUE.equals(rg.containsText()) || Boolean.TRUE.equals(rg.containsEditableText())) return false;
        if (!isTextFrameVisualShellReason(rg.reason())) return false;
        double[] rb = rg.bounds();
        if (rb == null || rb.length < 4 || area(rb) <= 0) return false;
        for (ResolvedTextFrame tf : conceptDiagramTextFramesForPage(ctx, rg.pageIndex())) {
            if (isConceptDiagramShellForTextFrame(ctx, rg, boundsOf(tf))) {
                return true;
            }
        }
        return false;
    }

    private static boolean isConceptDiagramInlineVisualShell(ResolvedBuildContext ctx, RenderedGroup rg) {
        if (ctx == null || ctx.resolvedData == null || rg == null) return false;
        if (!"inline_object".equals(rg.itemType()) && !"inline_object".equals(rg.type())) return false;
        if (Boolean.FALSE.equals(rg.placementAllowed())) return false;
        if (!"indesign_png".equals(rg.visualOwner())) return false;
        if (!"hwpx_tf".equals(rg.textOwner())) return false;
        if (Boolean.TRUE.equals(rg.containsText()) || Boolean.TRUE.equals(rg.containsEditableText())) return false;
        if (!isTextFrameVisualShellReason(rg.reason())) return false;
        if (rg.editableTextFrameIds() == null || rg.editableTextFrameIds().length == 0) return false;
        for (String id : rg.editableTextFrameIds()) {
            if (ctx.conceptDiagramTextFrameIds.contains(id)) {
                return true;
            }
        }
        return hasConceptDiagramEditableTextMix(ctx, rg);
    }

    private static boolean hasConceptDiagramEditableTextMix(ResolvedBuildContext ctx, RenderedGroup rg) {
        if (ctx == null || ctx.resolvedData == null || rg == null || rg.editableTextFrameIds() == null) {
            return false;
        }
        if (rg.editableTextFrameIds().length < 3) return false;
        int shortLabels = 0;
        int longTexts = 0;
        for (String id : rg.editableTextFrameIds()) {
            ResolvedTextFrame tf = ctx.resolvedData.getTextFrame(id);
            String text = visibleText(tf);
            if (text.isEmpty()) continue;
            if (text.length() <= 18) shortLabels++;
            if (text.length() >= 18) longTexts++;
        }
        return shortLabels >= 1 && longTexts >= 1;
    }

    private static List<ResolvedTextFrame> conceptDiagramTextFramesForPage(
            ResolvedBuildContext ctx, int pageIndex) {
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

    private static boolean isConceptDiagramShellForTextFrame(
            ResolvedBuildContext ctx, RenderedGroup shell, double[] tfBounds) {
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

    private static boolean isEditableLabelShellCandidate(RenderedGroup rg) {
        return VisualLayeringRules.isEditableLabelShellCandidate(rg);
    }

    private static boolean matchesShortEditableLabelText(ResolvedBuildContext ctx, RenderedGroup shell) {
        double[] sb = shell.bounds();
        if (sb == null || sb.length < 4) return false;
        double shellArea = area(sb);
        if (shellArea <= 0) return false;
        double[] scaledSb = new double[] {
                sb[0] * ctx.scaleFactor,
                sb[1] * ctx.scaleFactor,
                sb[2] * ctx.scaleFactor,
                sb[3] * ctx.scaleFactor
        };
        for (ResolvedTextFrame tf : ctx.resolvedData.textFrames()) {
            if (tf == null || tf.pageIndex() != shell.pageIndex()) continue;
            if (!hasShortSemanticText(tf)) continue;
            double[] tb = tf.pageRelativeBounds() != null ? tf.pageRelativeBounds() : tf.geometricBounds();
            if (tb == null || tb.length < 4) continue;
            double tfArea = area(tb);
            if (tfArea <= 0) continue;
            double overlap = Math.max(overlapArea(sb, tb), overlapArea(scaledSb, tb));
            if (overlap / tfArea < 0.60) continue;
            double areaRatio = shellArea / tfArea;
            if (areaRatio >= 0.35 && areaRatio <= 2.20) return true;
        }
        return false;
    }

    private static boolean shouldDecomposeToEditableLabelShell(
            RenderedGroup rg, Set<Integer> editableLabelShellIds,
            Map<Integer, RenderedGroup> idToRendered) {
        if (rg == null || editableLabelShellIds == null || editableLabelShellIds.isEmpty()) return false;
        String reason = rg.reason();
        // A visual_label_text_hidden_shell is already the intended full visual shell
        // (text hidden, editable TF overlaid). Do not decompose it to smaller child
        // decoration renders, otherwise outlines/shadows can disappear and only a
        // partial fill child remains.
        if ("visual_label_text_hidden_shell".equals(reason)
                || "editable_composite_text_hidden_shell".equals(reason)) return false;
        if (rg.childIds() == null || rg.childIds().length == 0) return false;
        boolean ownsEditableText = "hwpx_tf".equals(rg.textOwner())
                || Boolean.TRUE.equals(rg.containsEditableText())
                || (rg.editableTextFrameIds() != null && rg.editableTextFrameIds().length > 0);
        if (!ownsEditableText) return false;
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

    private static boolean hasSubstantialVisualOutsideEditableLabelShell(
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

    private static double[] normalizeSpreadBoundsToPage(
            ResolvedBuildContext ctx, int pageIdx, RenderedGroup rg, double[] bounds) {
        if (ctx == null || ctx.resolvedData == null || rg == null
                || bounds == null || bounds.length < 4) {
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

        // Some extracted visuals are exported in spread coordinates while the
        // HWPX page expects page-relative coordinates. Fold those into the local
        // page before visibility/crop/z-order decisions.
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

    public static byte[] loadPng(ResolvedBuildContext ctx, RenderedGroup rg) {
        if (ctx == null || rg == null || rg.file() == null) return null;
        try {
            File pngFile = new File(ctx.basePath, rg.file());
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

    private static boolean shouldCropOwnedTextFrameShellToAlpha(RenderedGroup rg) {
        if (rg == null) return false;
        if (!isPageObject(rg)) return false;
        if (!"hwpx_tf".equals(rg.textOwner())) return false;
        String reason = rg.reason();
        if (reason == null) return false;
        return reason.contains("text_hidden")
                || reason.contains("visual_shell")
                || reason.contains("editable_textframe_visual_shell")
                || reason.contains("image_group")
                || reason.contains("decoration_group")
                || reason.contains("mixed_group_text_hidden")
                || reason.contains("complex_graphic_text_hidden")
                || reason.contains("label_backdrop_group");
    }

    private static boolean shouldPreserveBoundsForAlphaCroppedTextShell(
            RenderedGroup rg,
            int imageW,
            int imageH,
            int alphaW,
            int alphaH,
            double boundsW,
            double boundsH) {
        if (rg == null || !"hwpx_tf".equals(rg.textOwner())) return false;
        if (!isPageObject(rg)) return false;
        String reason = rg.reason();
        if (reason == null || !reason.contains("text_hidden")) return false;
        if (imageW <= 0 || imageH <= 0 || alphaW <= 0 || alphaH <= 0
                || boundsW <= 0.0 || boundsH <= 0.0) {
            return false;
        }
        double cropAreaRatio = (double) alphaW * (double) alphaH / ((double) imageW * (double) imageH);
        if (cropAreaRatio > 0.92) return false;
        double boundsAspect = boundsW / boundsH;
        double imageAspect = (double) imageW / (double) imageH;
        double alphaAspect = (double) alphaW / (double) alphaH;
        double imageDelta = Math.abs(Math.log(Math.max(1e-6, imageAspect / boundsAspect)));
        double alphaDelta = Math.abs(Math.log(Math.max(1e-6, alphaAspect / boundsAspect)));
        return alphaDelta + 0.10 < imageDelta;
    }

    private static boolean shouldUsePhysicalAlphaCropBounds(
            RenderedGroup rg,
            int imageW,
            int imageH,
            int alphaW,
            int alphaH) {
        if (rg == null || imageW <= 0 || imageH <= 0 || alphaW <= 0 || alphaH <= 0) return false;
        if (!isPageObject(rg)) return false;
        if (!"hwpx_tf".equals(rg.textOwner())) return false;
        if (!"leaf_group_text_hidden_shell".equals(rg.reason())) return false;
        double cropAreaRatio = (double) alphaW * (double) alphaH / ((double) imageW * (double) imageH);
        return cropAreaRatio < 0.50;
    }

    private static boolean shouldPreserveVisualLabelAspect(RenderedGroup rg, int pixelW, int pixelH) {
        return rg != null
                && pixelW > 0
                && pixelH > 0
                && "visual_label_indesign_png".equals(rg.reason())
                && "indesign_png".equals(rg.textOwner());
    }

    private static boolean isCompletePngSimpleButtonLabel(ResolvedBuildContext ctx, RenderedGroup rg) {
        return VisualLayeringRules.isCompletePngSimpleButtonLabel(ctx, rg);
    }

    private static boolean isRenderedContainerShell(RenderedGroup rg) {
        if (rg == null || !isPageObject(rg)) return false;
        if (Boolean.FALSE.equals(rg.placementAllowed())) return false;
        if (!"indesign_png".equals(rg.visualOwner())) return false;
        if ("hwpx_tf".equals(rg.textOwner()) || "indesign_png".equals(rg.textOwner())) return false;
        if (Boolean.TRUE.equals(rg.containsText()) || Boolean.TRUE.equals(rg.containsEditableText())) return false;
        String reason = rg.reason();
        if (reason == null) return false;
        if (!"vector_shape".equals(reason) && !reason.contains("textframe_visual_shell")) return false;
        double[] b = rg.bounds();
        if (b == null || b.length < 4) return false;
        double w = b[3] - b[1];
        double h = b[2] - b[0];
        return w >= 18.0 && h >= 12.0 && area(b) >= 300.0;
    }

    private static boolean isPaperOnlyContainerShell(ResolvedBuildContext ctx, RenderedGroup rg) {
        return VisualOverlapZOrderPlanner.isPaperOnlyContainerShell(ctx, rg);
    }

    public static boolean isPaperColor(String colorName) {
        return "Paper".equals(colorName) || "[Paper]".equals(colorName);
    }

    public static boolean hasSemanticText(ResolvedTextFrame tf) {
        return !visibleText(tf).isEmpty();
    }

    private static boolean hasShortSemanticText(ResolvedTextFrame tf) {
        String text = visibleText(tf);
        if (text.isEmpty() || text.length() > 60) return false;
        return !text.contains("\n") && !text.contains("\r");
    }

    public static String visibleText(ResolvedTextFrame tf) {
        String text = tf != null ? tf.frameVisibleText() : null;
        if (text == null) return "";
        return text.replace("\uFFFC", "")
                .replace("\u0016", "")
                .replace("\u0018", "")
                .trim();
    }

    private static boolean isTextFrameVisualShellReason(String reason) {
        return VisualLayeringRules.isTextFrameVisualShellReason(reason);
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

    private static boolean isInlineEditableLabelShellRender(RenderedGroup rg) {
        if (rg == null) return false;
        return "inline_graphic_only".equals(rg.reason())
                || "text_composite_editable_text_hidden".equals(rg.reason());
    }

    private static boolean isPlanForegroundVisualLayer(String visualLayer) {
        return "CONTENT_VISUAL".equals(visualLayer)
                || "CONTAINER_OUTLINE".equals(visualLayer)
                || "FOREGROUND_MASK".equals(visualLayer);
    }

}
