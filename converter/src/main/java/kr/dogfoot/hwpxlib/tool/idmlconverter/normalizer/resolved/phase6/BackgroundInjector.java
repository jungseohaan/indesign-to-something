package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.phase6;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTBlock;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTFigure;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTInlineItem;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTParagraph;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTSection;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTable;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTextRun;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTextFrameBlock;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ConversionTiming;
import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.CoordinateConverter;
import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.VisualSourcePolicy;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.FrameDisposition;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ResolvedBuildContext;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.shared.ParagraphTextHelpers;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.VisualAction;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.RenderedGroup;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedPageItem;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedTextFrame;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
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
    private static final double TF_INLINE_VISUAL_UNION_MAX_RATIO = 1.25;
    private static final int TF_INLINE_VISUAL_MAX_CANVAS_PIXELS = 25_000_000;
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

        Set<Integer> editableLabelShellIds = collectEditableLabelShells(ctx, floatingItems);
        Set<Integer> conceptDiagramLabelShellIds = collectConceptDiagramLabelShells(ctx, floatingItems);

        // Pass 1: id → pageIndex 맵 구성 (자식의 페이지 판별용)
        Map<Integer, Integer> idToPage = new HashMap<>();
        Map<Integer, RenderedGroup> idToRendered = new HashMap<>();
        for (RenderedGroup rg : floatingItems) {
            idToPage.put(rg.id(), rg.pageIndex());
            idToRendered.putIfAbsent(rg.id(), rg);
        }

        // Pass 1b: 부모 그룹 PNG에 구워진 자식 렌더는 독립 배치하지 않는다(SPEC-036: child suppression 응집).
        Set<Integer> childOfGroup = computeChildOfGroup(
                ctx, sections, floatingItems, editableLabelShellIds, conceptDiagramLabelShellIds,
                idToPage, idToRendered);

        // childOfGroup 항목은 Phase 7c도 배치하지 않도록 phase6PlacedIds에 선제 등록
        ctx.phase6PlacedIds.addAll(childOfGroup);

        Set<Integer> coveredByInlineObjects = collectInlineObjectCoverage(ctx, floatingItems);
        ctx.phase6PlacedIds.addAll(coveredByInlineObjects);
        Set<Integer> completeInlineSimpleButtonLabels = ctx.inlineCompleteSimpleButtonLabelIds;
        Set<Integer> inlineEditableLabelShells = ctx.inlineEditableLabelShellIds;

        Set<String> processedKeys = new HashSet<>();
        Set<String> processedDomPageKeys = new HashSet<>();

        for (RenderedGroup rg : floatingItems) {
            boolean conceptDiagramInlineShell = isConceptDiagramInlineVisualShell(ctx, rg);
            if (!isPageObject(rg) && !conceptDiagramInlineShell) {
                continue;
            }
            // 셀 단락에 inline 객체(배지 drawText 등)로 이미 임베드된 id는 page_object 플로팅 배치 금지.
            // (텍스트가 셀 흐름에 들어갔으므로, 원본 절대 좌표의 배지 배경 PNG를 또 얹으면 어긋난 위치로 가림)
            if (isPageObject(rg) && ctx.cellInlineEmbeddedDomIds.contains(rg.id())) {
                ctx.phase6PlacedIds.add(rg.id());
                ctx.recordRenderedDecision(rg, "Phase6", "SKIP_CELL_INLINE_EMBEDDED",
                        "badge embedded inline in table cell");
                continue;
            }
            // SPEC-036: plan 권위 억제 판정 (Phase 6/7 공용 VisualPlacementResolver로 통합)
            VisualPlacementResolver.PlanRejection planRej = VisualPlacementResolver.planRejection(ctx, rg);
            if (planRej != null) {
                if (planRej.markPhase6Placed) ctx.phase6PlacedIds.add(rg.id());
                ctx.recordRenderedDecision(rg, "Stage3.VisualBuilder.Phase6", planRej.code, planRej.detail);
                continue;
            }
            if (shouldDecomposeToEditableLabelShell(rg, editableLabelShellIds, idToRendered)) {
                ctx.phase6PlacedIds.add(rg.id());
                ctx.recordRenderedDecision(rg, "Phase6", "SKIP_EDITABLE_LABEL_PARENT",
                        "editable label shell is placed from a tighter child render");
                continue;
            }
            if (ctx.isRenderedDisposed(rg.id(), FrameDisposition.TEXT_BLOCK_PLACED)) {
                ctx.phase6PlacedIds.add(rg.id());
                ctx.recordRenderedDecision(rg, "Phase6", "SKIP_RENDERED_DISPOSED", "rendered item already handled");
                continue;
            }
            boolean conceptDiagramLabelShell = conceptDiagramLabelShellIds.contains(rg.id());
            boolean protectedEditableLabelShell = conceptDiagramLabelShell
                    || conceptDiagramInlineShell
                    || shouldPreserveEditableLabelShell(rg, editableLabelShellIds);
            // 단순 배지 완성형 PNG가 inline_object/page_object 쌍으로 동시에 추출되면
            // page_object가 원본 절대 좌표를 보존한다. inline_object는 HWP 문장 흐름에
            // 들어가면서 플로팅 위치/선택 영역이 밀리는 경우가 있어 Phase 3에서 버린다.
            if (isPageObject(rg)
                    && completeInlineSimpleButtonLabels.contains(rg.id())
                    && isCompletePngSimpleButtonLabel(ctx, rg)) {
                ctx.phase6PlacedIds.add(rg.id());
                ctx.recordRenderedDecision(rg, "Phase6", "SKIP_INLINE_COMPLETE_LABEL",
                        "complete simple button label is owned by inline object");
                continue;
            }
            if (isPageObject(rg)
                    && inlineEditableLabelShells.contains(rg.id())
                    && isInlineEditableLabelShellRender(rg)) {
                ctx.phase6PlacedIds.add(rg.id());
                ctx.recordRenderedDecision(rg, "Phase6", "SKIP_INLINE_EDITABLE_LABEL_SHELL",
                        "editable label shell is owned by inline text frame");
                continue;
            }
            // 상위 그룹 PNG의 자식 항목은 그룹 PNG에 이미 포함됨 → 개별 렌더링 skip
            if (childOfGroup.contains(rg.id()) && !protectedEditableLabelShell) {
                ctx.recordRenderedDecision(rg, "Phase6", "SKIP_CHILD_OF_GROUP", "covered by renderable parent group");
                continue;
            }
            if (isCoveredByInlineObject(rg, coveredByInlineObjects)
                    && !protectedEditableLabelShell
                    && !isCompletePngSimpleButtonLabel(ctx, rg)) {
                ctx.recordRenderedDecision(rg, "Phase6", "SKIP_INLINE_COVERAGE", "covered by inline_object ownership");
                continue;
            }
            // 같은 ID가 inline_object로도 등록된 경우: 대개 Phase 3가 인라인으로 처리하므로
            // floating 중복을 막는다. 다만 텍스트가 숨겨진 라벨/버튼 껍데기 page_object는
            // HWPX 텍스트의 배경으로 필요하므로 살리고 뒤쪽 레이어로 배치한다.
            if (isPageObject(rg)
                    && ctx.resolvedData.isInlineObjectId(rg.id())
                    && coveredByInlineObjects.contains(rg.id())
                    && !isCompletePngSimpleButtonLabel(ctx, rg)
                    && !shouldKeepPairedInlinePageShell(rg)) {
                ctx.recordRenderedDecision(rg, "Phase6", "SKIP_INLINE_OBJECT", "inline object handled by story flow");
                continue;
            }
            if (ctx.resolvedData.shouldKeepVisualLabelTextEditable(rg) && !protectedEditableLabelShell) {
                ctx.phase6PlacedIds.add(rg.id());
                ctx.recordRenderedDecision(rg, "Phase6", "SKIP_VISUAL_LABEL_TEXT_EDITABLE", "text label kept editable");
                continue;
            }
            if (rg.shouldSkipByOwnership()) {
                ctx.phase6PlacedIds.add(rg.id());
                ctx.recordRenderedDecision(rg, "Phase6", "SKIP_OWNERSHIP", "render ownership says text is not hidden");
                continue;
            }
            // childIds 검사
            boolean planKeepsFloatingVisual = ctx.hasOwnershipPlan(rg)
                    && ctx.shouldPlaceFloatingVisualByOwnershipPlan(rg);
            if (!planKeepsFloatingVisual && shouldSkipByChildPolicy(ctx, rg) && !protectedEditableLabelShell) {
                ctx.phase6PlacedIds.add(rg.id());
                ctx.recordRenderedDecision(rg, "Phase6", "SKIP_CHILD_POLICY", "child policy suppresses render");
                continue;
            }
            if (!processedDomPageKeys.add(rg.id() + ":" + rg.pageIndex())) {
                ctx.recordRenderedDecision(rg, "Phase6", "SKIP_DUPLICATE_DOM_PAGE",
                        "same DOM id already rendered on this page");
                continue;
            }
            // 같은 페이지에서 같은 파일이 중복 추출된 경우만 스킵한다.
            // master_graphic은 같은 PNG asset을 여러 페이지 인스턴스가 공유하므로 pageIndex를 보존해야 한다.
            if (!processedKeys.add((rg.file() != null ? rg.file() : String.valueOf(rg.id()))
                    + ":" + rg.pageIndex())) {
                ctx.recordRenderedDecision(rg, "Phase6", "SKIP_DUPLICATE_FILE_PAGE", "same page/file already processed");
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
            bounds = shouldCompositeTfInlineVisuals(rg)
                    ? boundsWithTfInlineVisuals(ctx, rg, bounds)
                    : bounds;
            bounds = normalizeInlineSpreadBoundsToPage(ctx, pageIdx, rg, bounds);

            if (tryAbsorbTextEmphasisBackdrop(ctx, sections, rg, bounds)) {
                ctx.phase6PlacedIds.add(rg.id());
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
                ctx.recordRenderedDecision(rg, "Phase6", "SKIP_OUTSIDE_PAGE", "no visible page intersection");
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
            boolean isInferredTextFrameVisualShell = inferredTextFrameVisualShellZOrder(ctx, rg) >= 0;
            String visualLayer = ctx.visualLayerByOwnershipPlan(rg);
            boolean planKeepsForegroundZ = isPlanForegroundVisualLayer(visualLayer);

            int pixelW = 0, pixelH = 0;
            Double stripCropLeftOverride = null;
            Double stripCropWidthOverride = null;
            boolean pageAnchoredStripCrop = false;
            boolean shouldCompositeTfInlineVisuals = shouldCompositeTfInlineVisuals(rg);
            boolean keepPlannedContainerBackdropFill =
                    ("CONTAINER_BACKDROP".equals(visualLayer) || "CONTAINER_FACE".equals(visualLayer))
                    && isPaperOnlyContainerShell(ctx, rg);
            boolean mayNeedContainerShellKnockout = !planKeepsForegroundZ
                    && !keepPlannedContainerBackdropFill
                    && isContainerVisualShell
                    && !isInferredTextFrameVisualShell;
            boolean needsAlphaCrop = shouldCropOwnedTextFrameShellToAlpha(rg);
            boolean needsPageCrop = (fullW > 1.0 && fullH > 1.0
                    && (visLeft > rawLeft + 0.5 || visRight < rawRight - 0.5
                        || visTop > rawTop + 0.5 || visBottom < rawBottom - 0.5))
                    || (hasCropSourceBounds && cropRefW > 1.0 && cropRefH > 1.0);
            boolean needsFullImageDecode = shouldCompositeTfInlineVisuals
                    || mayNeedContainerShellKnockout
                    || rg.isWhiteStroke()
                    || needsAlphaCrop
                    || needsPageCrop;
            try {
                BufferedImage img = needsFullImageDecode ? loadImageForPlacement(ctx, rg, imageData) : null;
                if (img == null && !needsFullImageDecode) {
                    int[] dims = readPngDimensions(imageData);
                    if (dims != null) {
                        pixelW = dims[0];
                        pixelH = dims[1];
                        ConversionTiming.addCounter("phase6.pngBytes.headerDimensionReads", 1);
                    }
                }
                if (img != null && shouldCompositeTfInlineVisuals) {
                    imageData = encodePng(img);
                }
                if (img != null
                        && !planKeepsForegroundZ
                        && !keepPlannedContainerBackdropFill
                        && isContainerVisualShell
                        && !isInferredTextFrameVisualShell) {
                    BufferedImage transparentShell = knockOutPaperLikeFill(img);
                    if (transparentShell != img) {
                        imageData = encodePng(transparentShell);
                        img.flush();
                        img = transparentShell;
                    }
                }
                // whiteStroke: PNG가 흑색 획으로 내보낸 것 → 흰색으로 반전
                if (img != null && rg.isWhiteStroke()) {
                    BufferedImage inv = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_ARGB);
                    for (int iy = 0; iy < img.getHeight(); iy++) {
                        for (int ix = 0; ix < img.getWidth(); ix++) {
                            int argb = img.getRGB(ix, iy);
                            int a = (argb >> 24) & 0xFF;
                            if (a > 0) {
                                int r = (argb >> 16) & 0xFF;
                                int g = (argb >> 8) & 0xFF;
                                int b = argb & 0xFF;
                                argb = (a << 24) | ((255 - r) << 16) | ((255 - g) << 8) | (255 - b);
                            }
                            inv.setRGB(ix, iy, argb);
                        }
                    }
                    img.flush();
                    img = inv;
                    // imageData를 반전 이미지로 업데이트 (crop 없을 때도 흰색이 적용되도록)
                    try {
                        java.io.ByteArrayOutputStream invBaos = new java.io.ByteArrayOutputStream();
                        ImageIO.write(img, "png", invBaos);
                        imageData = invBaos.toByteArray();
                    } catch (Exception ignored2) {}
                }
                if (img != null) {
                    if (needsAlphaCrop) {
                        int[] alphaBounds = alphaBounds(img);
                        if (alphaBounds != null && shouldApplyAlphaCrop(img, alphaBounds)) {
                            int pxX = alphaBounds[0];
                            int pxY = alphaBounds[1];
                            int pxW = alphaBounds[2];
                            int pxH = alphaBounds[3];
                            double cropLeft = rawLeft + (double) pxX / (double) img.getWidth() * fullW;
                            double cropTop = rawTop + (double) pxY / (double) img.getHeight() * fullH;
                            double cropRight = rawLeft + (double) (pxX + pxW) / (double) img.getWidth() * fullW;
                            double cropBottom = rawTop + (double) (pxY + pxH) / (double) img.getHeight() * fullH;
                            BufferedImage cropped = img.getSubimage(pxX, pxY, pxW, pxH);
                            imageData = encodePng(cropped);
                            pixelW = cropped.getWidth();
                            pixelH = cropped.getHeight();
                            img.flush();
                            img = cropped;

                            rawLeft = cropLeft;
                            rawTop = cropTop;
                            rawRight = cropRight;
                            rawBottom = cropBottom;
                            fullW = rawRight - rawLeft;
                            fullH = rawBottom - rawTop;
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
                    boolean needsCrop = (fullW > 1.0 && fullH > 1.0
                            && (visLeft > rawLeft + 0.5 || visRight < rawRight - 0.5
                                || visTop > rawTop + 0.5 || visBottom < rawBottom - 0.5))
                            || (hasCropSourceBounds && cropRefW > 1.0 && cropRefH > 1.0);
                    if (needsCrop) {
                        pageAnchoredStripCrop = !hasCropSourceBounds && shouldAnchorStripCropToPage(
                                rg, rawLeft, rawRight, rawTop, rawBottom, pageWidthMm, pageHeightMm);
                        int[] stripRun = pageAnchoredStripCrop
                                ? edgeAlphaRun(img, pageIdx)
                                : null;
                        int pxX;
                        int pxY;
                        int pxW;
                        int pxH;
                        if (stripRun != null) {
                            pxX = stripRun[0];
                            pxY = 0;
                            pxW = stripRun[1] - stripRun[0] + 1;
                            pxH = img.getHeight();
                            stripCropLeftOverride = (double) pxX / (double) img.getWidth() * pageWidthMm;
                            stripCropWidthOverride = (double) pxW / (double) img.getWidth() * pageWidthMm;
                        } else {
                            pxX = pageAnchoredStripCrop
                                    ? 0
                                    : (int) Math.round((visLeft - cropRefLeft) / cropRefW * img.getWidth());
                            pxY = (int) Math.round((visTop - cropRefTop) / cropRefH * img.getHeight());
                            pxW = pageAnchoredStripCrop
                                    ? (int) Math.round((visRight - visLeft) / fullW * img.getWidth())
                                    : (int) Math.round((visRight - cropRefLeft) / cropRefW * img.getWidth()) - pxX;
                            pxH = (int) Math.round((visBottom - cropRefTop) / cropRefH * img.getHeight()) - pxY;
                            if (hasCropSourceBounds && isMasterEdgeStrip(rg, pageWidthMm)
                                    && cropRefLeft < -0.5 && Math.abs(visLeft) < 0.5
                                    && visRight > rawRight + 0.1) {
                                int desiredPxW = (int) Math.round((visRight - visLeft) / cropRefW * img.getWidth());
                                desiredPxW = Math.max(1, Math.min(img.getWidth(), desiredPxW));
                                pxX = Math.max(0, img.getWidth() - desiredPxW);
                                pxW = img.getWidth() - pxX;
                            } else if (hasCropSourceBounds && isMasterEdgeStrip(rg, pageWidthMm)
                                    && cropRefRight > pageWidthMm + 0.5 && Math.abs(visRight - pageWidthMm) < 0.5
                                    && visLeft < rawLeft - 0.1) {
                                int desiredPxW = (int) Math.round((visRight - visLeft) / cropRefW * img.getWidth());
                                pxX = 0;
                                pxW = Math.max(1, Math.min(img.getWidth(), desiredPxW));
                            }
                        }
                        pxX = Math.max(0, Math.min(pxX, img.getWidth() - 1));
                        pxY = Math.max(0, Math.min(pxY, img.getHeight() - 1));
                        pxW = Math.max(1, Math.min(img.getWidth() - pxX, pxW));
                        pxH = Math.max(1, Math.min(img.getHeight() - pxY, pxH));
                        try {
                            BufferedImage cropped = img.getSubimage(pxX, pxY, pxW, pxH);
                            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                            ImageIO.write(cropped, "png", baos);
                            imageData = baos.toByteArray();
                            pixelW = cropped.getWidth();
                            pixelH = cropped.getHeight();
                            cropped.flush();
                        } catch (Exception ignored2) {
                            pixelW = img.getWidth();
                            pixelH = img.getHeight();
                        }
                    } else {
                        pixelW = img.getWidth();
                        pixelH = img.getHeight();
                    }
                    img.flush();
                }
            } catch (Exception ignored) {}

            // whiteStroke PNG는 exportFile이 visibleBounds보다 큰 영역을 내보낼 수 있음.
            // arc 스트로크는 PNG 중앙에 위치하므로 중앙 기준으로 bounds를 확장.
            if (rg.isWhiteStroke() && !((fullW > 1.0 && fullH > 1.0)
                    && (visLeft > rawLeft + 0.5 || visRight < rawRight - 0.5
                        || visTop > rawTop + 0.5 || visBottom < rawBottom - 0.5))
                    && pixelW > 0 && pixelH > 0) {
                double pngWidthMm = pixelW * 25.4 / 220.0;
                double pngHeightMm = pixelH * 25.4 / 220.0;
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

            if (shouldPreserveVisualLabelAspect(rg, pixelW, pixelH)) {
                double storedW = visRight - visLeft;
                double storedH = visBottom - visTop;
                double imageRatio = (double) pixelW / (double) pixelH;
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

            long x = CoordinateConverter.pointsToHwpunits(visLeft * ctx.scaleFactor);
            long y = CoordinateConverter.pointsToHwpunits(visTop * ctx.scaleFactor);
            long w = CoordinateConverter.pointsToHwpunits((visRight - visLeft) * ctx.scaleFactor);
            long h = CoordinateConverter.pointsToHwpunits((visBottom - visTop) * ctx.scaleFactor);
            if (stripCropLeftOverride != null && stripCropWidthOverride != null) {
                x = CoordinateConverter.pointsToHwpunits(stripCropLeftOverride * ctx.scaleFactor);
                w = CoordinateConverter.pointsToHwpunits(stripCropWidthOverride * ctx.scaleFactor);
            }

            if (w <= 0 || h <= 0) continue;

            ASTFigure fig = new ASTFigure();
            fig.x(x);
            fig.y(y);
            fig.width(w);
            fig.height(h);
            fig.imageData(imageData);
            String fmt = rg.imageFormat();
            fig.imageFormat((fmt != null && !fmt.isEmpty()) ? fmt : "png");
            fig.pixelWidth(pixelW);
            fig.pixelHeight(pixelH);
            // 페이지 전체를 덮는 배경 항목 → zOrder=0 (최하단 레이어)
            // 기준: rawLeft≤1mm, rawTop≤1mm, rawBottom≥(pageHeight-1mm) — 페이지 전체 커버
            // 장식/부분 항목 → zOrder=5 (배경 위에 표시)
            // 페이지 배경 판별: 블리드 여유(최대 10mm) 허용 + 면적이 페이지 30% 이상이면 배경으로 간주.
            // graphic_3357처럼 페이지 높이의 일부만 덮는 스프레드 배경 이미지도 포함하기 위해 면적 조건 추가.
            boolean isTextFrameBackdrop = inferredTextLineBackdropZOrder(ctx, rg) >= 0;
            boolean isEditableLabelShell = isEditableLabelShellCandidate(rg);
            boolean isTextOwnedVisualShell = isTextOwnedVisualShell(rg) && !isEditableLabelShell;
            boolean isTextOwnedRenderedContent = isTextOwnedRenderedContent(rg);
            int resolvedZ = isBackgroundLike
                    ? 0
                    : Math.max(5, effectiveZOrder(ctx, rg));
            if (stripCropLeftOverride != null && stripCropWidthOverride != null) {
                resolvedZ = Math.max(resolvedZ, 900);
            }
            if (isCompletePngSimpleButtonLabel(ctx, rg)) {
                resolvedZ = Math.max(resolvedZ,
                        foregroundMarkerZOrder(sections.get(pageIdx), x, y, w, h, resolvedZ));
            }
            boolean demotedBehindForeground = false;
            if (!planKeepsForegroundZ) {
                int containerAdjustedZ = containerShellZOrderBehindRenderedContent(ctx, floatingItems, rg, resolvedZ);
                if (containerAdjustedZ < resolvedZ) {
                    resolvedZ = containerAdjustedZ;
                    demotedBehindForeground = true;
                }
            }
            if (!planKeepsForegroundZ
                    && !isBackgroundLike && !isTextFrameBackdrop
                    && !isTextOwnedVisualShell && !isTextOwnedRenderedContent
                    && !isEditableLabelShell
                    && !isCompletePngSimpleButtonLabel(ctx, rg)) {
                int adjustedZ = foregroundOverlapShellZOrder(sections.get(pageIdx), rg, x, y, w, h, resolvedZ);
                demotedBehindForeground = demotedBehindForeground || adjustedZ < resolvedZ;
                resolvedZ = adjustedZ;
            }
            boolean isPaperOnlyContainerShell = isPaperOnlyContainerShell(ctx, rg);
            if (!planKeepsForegroundZ
                    && isContainerVisualShell
                    && !isInferredTextFrameVisualShell
                    && (!rg.zOrderKnown() || isPaperOnlyContainerShell)) {
                resolvedZ = Math.max(1, Math.min(resolvedZ, 4));
            }
            fig.zOrder(resolvedZ);
            boolean keepShellInFrontLayer = isContainerVisualShell
                    || (isTextFrameVisualShell && !isBackgroundLike);
            if (visualLayer != null) {
                fig.visualLayer(visualLayer);
            }
            Boolean planInFrontLayer = ctx.inFrontLayerByOwnershipPlan(rg);
            fig.fromGroup(planInFrontLayer != null
                    ? planInFrontLayer
                    : keepShellInFrontLayer
                    || !(isBackgroundLike || isTextFrameBackdrop || isTextOwnedVisualShell
                    || demotedBehindForeground));
            fig.sourceId("page_obj_" + rg.id());

            // BEHIND_TEXT 항목은 XML 순서상 앞에 올수록 더 아래 레이어 → addBlockAtFront
            sections.get(pageIdx).addBlockAtFront(fig);
            ctx.phase6PlacedIds.add(rg.id());
            ctx.recordRenderedDecision(rg, "Phase6", "PLACE", "placed as ASTFigure");

            // 스프레드를 가로질러 다음 페이지로 넘치는 경우: 우측 반을 다음 페이지에 별도 배치
            // 얇은 master/haseera strip은 각 페이지별 렌더 항목이 따로 있으므로
            // neighbor overflow를 만들면 반대쪽 페이지 placeholder(PB 등)가 중복 배치된다.
            boolean overflowsRight = !pageAnchoredStripCrop
                    && rawRight > pageWidthMm + 10.0 && pageIdx + 1 < sections.size();
            if (overflowsRight) {
                int nextPageIdx = pageIdx + 1;
                double nextPageWidthMm = 1e9, nextPageHeightMm = 1e9;
                if (ctx.resolvedData.pages() != null && nextPageIdx < ctx.resolvedData.pages().size()) {
                    double[] npB = ctx.resolvedData.pages().get(nextPageIdx).bounds();
                    if (npB != null && npB.length >= 4) {
                        nextPageWidthMm = (npB[3] - npB[1]) / ctx.scaleFactor;
                        nextPageHeightMm = (npB[2] - npB[0]) / ctx.scaleFactor;
                    }
                }
                // 다음 페이지 상대 좌표 (수평 스프레드: X에서 현재 페이지 폭만큼 뺌)
                double nextVisLeft = Math.max(0.0, rawLeft - pageWidthMm);
                double nextVisTop = Math.max(0.0, rawTop);
                double nextVisRight = Math.min(rawRight - pageWidthMm, nextPageWidthMm);
                double nextVisBottom = Math.min(rawBottom, nextPageHeightMm);
                if (nextVisLeft < nextVisRight && nextVisTop < nextVisBottom) {
                    byte[] overflowData = originalImageData;
                    if (overflowData != null) {
                        int ovPixelW = 0, ovPixelH = 0;
                        try {
                            BufferedImage ovImg = decodePngBytes(overflowData);
                            if (ovImg != null) {
                                // 다음 페이지 가시 영역을 page-0 좌표계로 변환하여 크롭
                                double cropLeft = nextVisLeft + pageWidthMm;
                                double cropTop = nextVisTop;
                                double cropRight = nextVisRight + pageWidthMm;
                                double cropBottom = nextVisBottom;
                                int pxX2 = (int) Math.round((cropLeft - rawLeft) / fullW * ovImg.getWidth());
                                int pxY2 = (int) Math.round((cropTop - rawTop) / fullH * ovImg.getHeight());
                                int pxW2 = (int) Math.round((cropRight - rawLeft) / fullW * ovImg.getWidth()) - pxX2;
                                int pxH2 = (int) Math.round((cropBottom - rawTop) / fullH * ovImg.getHeight()) - pxY2;
                                pxX2 = Math.max(0, Math.min(pxX2, ovImg.getWidth() - 1));
                                pxY2 = Math.max(0, Math.min(pxY2, ovImg.getHeight() - 1));
                                pxW2 = Math.max(1, Math.min(ovImg.getWidth() - pxX2, pxW2));
                                pxH2 = Math.max(1, Math.min(ovImg.getHeight() - pxY2, pxH2));
                                try {
                                    BufferedImage ovCropped = ovImg.getSubimage(pxX2, pxY2, pxW2, pxH2);
                                    java.io.ByteArrayOutputStream baos2 = new java.io.ByteArrayOutputStream();
                                    ImageIO.write(ovCropped, "png", baos2);
                                    overflowData = baos2.toByteArray();
                                    ovPixelW = ovCropped.getWidth();
                                    ovPixelH = ovCropped.getHeight();
                                    ovCropped.flush();
                                } catch (Exception ignored3) {
                                    ovPixelW = ovImg.getWidth();
                                    ovPixelH = ovImg.getHeight();
                                }
                                ovImg.flush();
                            }
                        } catch (Exception ignored2) {}

                        long nx = CoordinateConverter.pointsToHwpunits(nextVisLeft * ctx.scaleFactor);
                        long ny = CoordinateConverter.pointsToHwpunits(nextVisTop * ctx.scaleFactor);
                        long nw = CoordinateConverter.pointsToHwpunits((nextVisRight - nextVisLeft) * ctx.scaleFactor);
                        long nh = CoordinateConverter.pointsToHwpunits((nextVisBottom - nextVisTop) * ctx.scaleFactor);
                        if (nw > 0 && nh > 0) {
                            ASTFigure fig2 = new ASTFigure();
                            fig2.x(nx);
                            fig2.y(ny);
                            fig2.width(nw);
                            fig2.height(nh);
                            fig2.imageData(overflowData);
                            String fmt2 = rg.imageFormat();
                            fig2.imageFormat((fmt2 != null && !fmt2.isEmpty()) ? fmt2 : "png");
                            fig2.pixelWidth(ovPixelW);
                            fig2.pixelHeight(ovPixelH);
                            fig2.zOrder(0); // 오버플로우 배경은 항상 최하단 레이어
                            fig2.fromGroup(false);
                            fig2.sourceId("page_obj_" + rg.id() + "_ov");
                            sections.get(nextPageIdx).addBlockAtFront(fig2);
                        }
                    }
                }
            }
            // 스프레드를 가로질러 이전 페이지로 넘치는 경우: 왼쪽 반을 이전 페이지에 별도 배치.
            // InDesign은 오른쪽 페이지에 소유된 큰 장식/이미지가 왼쪽 페이지 영역까지
            // 보이도록 둘 수 있다. HWPX는 페이지 단위로 잘리므로 교차한 각 페이지에
            // 같은 source visual의 page-local 인스턴스를 만들어야 한다.
            boolean overflowsLeft = !pageAnchoredStripCrop
                    && rawLeft < -10.0 && pageIdx - 1 >= 0;
            if (overflowsLeft) {
                int prevPageIdx = pageIdx - 1;
                double prevPageWidthMm = 1e9, prevPageHeightMm = 1e9;
                if (ctx.resolvedData.pages() != null && prevPageIdx < ctx.resolvedData.pages().size()) {
                    double[] ppB = ctx.resolvedData.pages().get(prevPageIdx).bounds();
                    if (ppB != null && ppB.length >= 4) {
                        prevPageWidthMm = (ppB[3] - ppB[1]) / ctx.scaleFactor;
                        prevPageHeightMm = (ppB[2] - ppB[0]) / ctx.scaleFactor;
                    }
                }
                double prevVisLeft = Math.max(0.0, rawLeft + pageWidthMm);
                double prevVisTop = Math.max(0.0, rawTop);
                double prevVisRight = Math.min(rawRight + pageWidthMm, prevPageWidthMm);
                double prevVisBottom = Math.min(rawBottom, prevPageHeightMm);
                if (prevVisLeft < prevVisRight && prevVisTop < prevVisBottom) {
                    byte[] overflowData = originalImageData;
                    if (overflowData != null) {
                        int ovPixelW = 0, ovPixelH = 0;
                        try {
                            BufferedImage ovImg = decodePngBytes(overflowData);
                            if (ovImg != null) {
                                // 이전 페이지 가시 영역을 현재 페이지-local raw 좌표계로 되돌려 크롭한다.
                                double cropLeft = prevVisLeft - pageWidthMm;
                                double cropTop = prevVisTop;
                                double cropRight = prevVisRight - pageWidthMm;
                                double cropBottom = prevVisBottom;
                                int pxX2 = (int) Math.round((cropLeft - rawLeft) / fullW * ovImg.getWidth());
                                int pxY2 = (int) Math.round((cropTop - rawTop) / fullH * ovImg.getHeight());
                                int pxW2 = (int) Math.round((cropRight - rawLeft) / fullW * ovImg.getWidth()) - pxX2;
                                int pxH2 = (int) Math.round((cropBottom - rawTop) / fullH * ovImg.getHeight()) - pxY2;
                                pxX2 = Math.max(0, Math.min(pxX2, ovImg.getWidth() - 1));
                                pxY2 = Math.max(0, Math.min(pxY2, ovImg.getHeight() - 1));
                                pxW2 = Math.max(1, Math.min(ovImg.getWidth() - pxX2, pxW2));
                                pxH2 = Math.max(1, Math.min(ovImg.getHeight() - pxY2, pxH2));
                                try {
                                    BufferedImage ovCropped = ovImg.getSubimage(pxX2, pxY2, pxW2, pxH2);
                                    java.io.ByteArrayOutputStream baos2 = new java.io.ByteArrayOutputStream();
                                    ImageIO.write(ovCropped, "png", baos2);
                                    overflowData = baos2.toByteArray();
                                    ovPixelW = ovCropped.getWidth();
                                    ovPixelH = ovCropped.getHeight();
                                    ovCropped.flush();
                                } catch (Exception ignored3) {
                                    ovPixelW = ovImg.getWidth();
                                    ovPixelH = ovImg.getHeight();
                                }
                                ovImg.flush();
                            }
                        } catch (Exception ignored2) {}

                        long px = CoordinateConverter.pointsToHwpunits(prevVisLeft * ctx.scaleFactor);
                        long py = CoordinateConverter.pointsToHwpunits(prevVisTop * ctx.scaleFactor);
                        long pw = CoordinateConverter.pointsToHwpunits((prevVisRight - prevVisLeft) * ctx.scaleFactor);
                        long ph = CoordinateConverter.pointsToHwpunits((prevVisBottom - prevVisTop) * ctx.scaleFactor);
                        if (pw > 0 && ph > 0) {
                            ASTFigure figPrev = new ASTFigure();
                            figPrev.x(px);
                            figPrev.y(py);
                            figPrev.width(pw);
                            figPrev.height(ph);
                            figPrev.imageData(overflowData);
                            String fmt2 = rg.imageFormat();
                            figPrev.imageFormat((fmt2 != null && !fmt2.isEmpty()) ? fmt2 : "png");
                            figPrev.pixelWidth(ovPixelW);
                            figPrev.pixelHeight(ovPixelH);
                            figPrev.zOrder(0);
                            figPrev.fromGroup(false);
                            if (visualLayer != null) {
                                figPrev.visualLayer(visualLayer);
                            }
                            figPrev.sourceId("page_obj_" + rg.id() + "_ov_prev");
                            sections.get(prevPageIdx).addBlockAtFront(figPrev);
                            ctx.recordRenderedDecision(rg, "Phase6", "PLACE_OVERFLOW_PREVIOUS_PAGE",
                                    "spread-crossing visual placed on previous page");
                        }
                    }
                }
            }
        }

        // Secondary pass: synthetic PNG for GraphicLine children of essentially-empty parent PNGs.
        // When a Rectangle frame contains a pasted-inside GraphicLine, InDesign's exportFile()
        // captures only the invisible frame shape — the child line is lost. Detect this by checking
        // if the parent PNG file is < 1 KB but has non-trivial pixel dimensions, then generate a
        // solid-color 1-row PNG from the child's pageItems stroke data.
        injectSyntheticGraphicLines(ctx, sections);
    }

    private static void injectSyntheticGraphicLines(ResolvedBuildContext ctx, List<ASTSection> sections) {
        if (!VisualSourcePolicy.useJavaSyntheticGraphicPngs()) return;
        if (ctx.resolvedData == null || ctx.resolvedData.pageItems() == null) return;
        List<RenderedGroup> floatingItems = ctx.resolvedData.allRenderedFloatingItems();
        if (floatingItems == null) return;

        Set<Integer> syntheticDone = new HashSet<>();

        for (RenderedGroup rg : floatingItems) {
            if (!isPageObject(rg)) continue;
            if (rg.childIds() == null || rg.childIds().length == 0) continue;
            if (rg.file() == null) continue;

            // Only process items whose PNG file is essentially empty (< 1 KB)
            File pngFile = new File(ctx.basePath, rg.file());
            if (!pngFile.exists() || pngFile.length() > 1000) continue;

            // Confirm non-trivial pixel dimensions (large image but tiny file = transparent)
            int[] dims = readPngDimensions(pngFile);
            if (dims == null || dims[0] < 50 || dims[1] < 5) continue;

            int pageIdx = ctx.toSectionIndex.applyAsInt(rg.pageIndex());
            if (pageIdx < 0 || pageIdx >= sections.size()) continue;
            if (ctx.resolvedData.pages() == null || pageIdx >= ctx.resolvedData.pages().size()) continue;

            double[] pgBounds = ctx.resolvedData.pages().get(pageIdx).bounds();
            if (pgBounds == null || pgBounds.length < 4) continue;
            double pagePtLeft = pgBounds[1];
            double pagePtTop = pgBounds[0];
            double pageWidthPt = pgBounds[3] - pgBounds[1];
            double pageHeightPt = pgBounds[2] - pgBounds[0];

            for (int cid : rg.childIds()) {
                if (syntheticDone.contains(cid)) continue;
                ResolvedPageItem pi = ctx.resolvedData.getPageItem(String.valueOf(cid));
                if (pi == null || !"GraphicLine".equals(pi.type())) continue;
                if (pi.strokeWeight() <= 0 || pi.strokeColorName() == null) continue;
                if (pi.opacity() <= 0) continue;

                double[] lineBounds = pi.geometricBounds();
                if (lineBounds == null || lineBounds.length < 4) continue;

                // Page-relative pt coordinates
                double lineX1 = lineBounds[1] - pagePtLeft;
                double lineX2 = lineBounds[3] - pagePtLeft;
                double lineY1 = lineBounds[0] - pagePtTop;
                double lineY2 = lineBounds[2] - pagePtTop;

                // Clip to parent frame (paste-inside clipping)
                if (pi.parentId() != null) {
                    ResolvedPageItem parentPi = ctx.resolvedData.getPageItem(pi.parentId());
                    if (parentPi != null && parentPi.geometricBounds() != null) {
                        double[] fb = parentPi.geometricBounds();
                        lineX1 = Math.max(lineX1, fb[1] - pagePtLeft);
                        lineX2 = Math.min(lineX2, fb[3] - pagePtLeft);
                        lineY1 = Math.max(lineY1, fb[0] - pagePtTop);
                        lineY2 = Math.min(lineY2, fb[2] - pagePtTop);
                    }
                }

                // Clip to page
                lineX1 = Math.max(0.0, lineX1);
                lineX2 = Math.min(lineX2, pageWidthPt);
                if (lineX1 >= lineX2) continue;

                // For a horizontal line, lineY1 ≈ lineY2; use strokeWeight for height.
                // Minimum 1 pt so sub-point lines are still rendered.
                double strokePt = Math.max(pi.strokeWeight(), 1.0);
                double lineYCenter = (lineY1 + lineY2) / 2.0;
                double visTop = Math.max(0.0, lineYCenter - strokePt / 2.0);
                double visBottom = Math.min(lineYCenter + strokePt / 2.0, pageHeightPt);
                if (visTop >= visBottom) continue;

                byte[] imageData = generateSolidLinePng(pi, ctx);
                if (imageData == null) continue;

                long x = CoordinateConverter.pointsToHwpunits(lineX1);
                long y = CoordinateConverter.pointsToHwpunits(visTop);
                long w = CoordinateConverter.pointsToHwpunits(lineX2 - lineX1);
                long h = CoordinateConverter.pointsToHwpunits(visBottom - visTop);
                if (w <= 0 || h <= 0) continue;

                ASTFigure fig = new ASTFigure();
                fig.x(x);
                fig.y(y);
                fig.width(w);
                fig.height(h);
                fig.imageData(imageData);
                fig.imageFormat("png");
                fig.pixelWidth(100);
                fig.pixelHeight(4);
                fig.zOrder(1); // above default zOrder=0 page items
                fig.fromGroup(true); // IN_FRONT_OF_TEXT
                fig.sourceId("synth_line_" + cid);
                sections.get(pageIdx).addBlockAtFront(fig);
                syntheticDone.add(cid);
                System.err.println("[BackgroundInjector] synthetic line id=" + cid
                        + " x=" + String.format("%.1f", lineX1) + "pt y=" + String.format("%.1f", visTop)
                        + "pt w=" + String.format("%.1f", lineX2 - lineX1) + "pt h=" + String.format("%.1f", visBottom - visTop)
                        + "pt hwpW=" + w + " hwpH=" + h + " stroke=" + pi.strokeColorName());
            }
        }
    }

    /** Read PNG width/height from file header without decoding pixel data. */
    private static int[] readPngDimensions(File pngFile) {
        try (java.io.FileInputStream fis = new java.io.FileInputStream(pngFile)) {
            byte[] header = new byte[24];
            if (fis.read(header) < 24) return null;
            // PNG magic: 0x89 P N G \r \n 0x1a \n
            if ((header[0] & 0xFF) != 0x89 || header[1] != 'P') return null;
            int w = ((header[16] & 0xFF) << 24) | ((header[17] & 0xFF) << 16)
                  | ((header[18] & 0xFF) << 8)  |  (header[19] & 0xFF);
            int h = ((header[20] & 0xFF) << 24) | ((header[21] & 0xFF) << 16)
                  | ((header[22] & 0xFF) << 8)  |  (header[23] & 0xFF);
            return new int[]{w, h};
        } catch (Exception e) { return null; }
    }

    /** Read PNG width/height from cached bytes without decoding pixel data. */
    private static int[] readPngDimensions(byte[] pngData) {
        if (pngData == null || pngData.length < 24) return null;
        try {
            if ((pngData[0] & 0xFF) != 0x89 || pngData[1] != 'P') return null;
            int w = ((pngData[16] & 0xFF) << 24) | ((pngData[17] & 0xFF) << 16)
                  | ((pngData[18] & 0xFF) << 8)  |  (pngData[19] & 0xFF);
            int h = ((pngData[20] & 0xFF) << 24) | ((pngData[21] & 0xFF) << 16)
                  | ((pngData[22] & 0xFF) << 8)  |  (pngData[23] & 0xFF);
            return new int[]{w, h};
        } catch (Exception e) {
            return null;
        }
    }

    /** Generate a 100×4 solid-color PNG from the pageItem's stroke color and opacity. */
    private static byte[] generateSolidLinePng(ResolvedPageItem pi, ResolvedBuildContext ctx) {
        String colorName = pi.strokeColorName();
        int r = 128, g = 128, b = 128;
        String hex = ctx.resolvedData.resolveColorHex(colorName);
        if (hex != null && hex.startsWith("#") && hex.length() >= 7) {
            try {
                r = Integer.parseInt(hex.substring(1, 3), 16);
                g = Integer.parseInt(hex.substring(3, 5), 16);
                b = Integer.parseInt(hex.substring(5, 7), 16);
            } catch (NumberFormatException ignored) {}
        }
        int alpha = (int) Math.round(255 * pi.opacity() / 100.0);
        try {
            BufferedImage img = new BufferedImage(100, 4, BufferedImage.TYPE_INT_ARGB);
            int argb = (alpha << 24) | (r << 16) | (g << 8) | b;
            for (int px = 0; px < 100; px++)
                for (int py = 0; py < 4; py++)
                    img.setRGB(px, py, argb);
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            ImageIO.write(img, "png", baos);
            return baos.toByteArray();
        } catch (Exception e) { return null; }
    }

    private static boolean isPageObject(RenderedGroup rg) {
        String t = rg.itemType();
        if ("page_object".equals(t)) return true;
        if (t != null) return false;
        // 하위 호환: 구 캐시는 itemType 없음 → 파일명으로 추론
        String f = rg.file();
        return f != null && (f.contains("img_") || f.contains("deco_")
                || f.contains("shape_") || f.contains("graphic_") || f.contains("master_")
                || f.contains("haseera_"));
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

    private static boolean shouldAnchorStripCropToPage(RenderedGroup rg,
                                                       double rawLeft,
                                                       double rawRight,
                                                       double rawTop,
                                                       double rawBottom,
                                                       double pageWidth) {
        return shouldAnchorStripCropToPage(rg, rawLeft, rawRight, rawTop, rawBottom, pageWidth, 1e9);
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

    private static boolean isMasterEdgeStrip(RenderedGroup rg, double pageWidth) {
        if (rg == null || pageWidth >= 1e8) return false;
        String file = rg.file();
        String reason = rg.reason();
        return (file != null && (file.contains("master_") || file.contains("haseera_")))
                || "master_graphic".equals(reason)
                || "haseera_graphic".equals(reason);
    }

    private static boolean shouldAnchorStripCropToPage(RenderedGroup rg,
                                                       double rawLeft,
                                                       double rawRight,
                                                       double rawTop,
                                                       double rawBottom,
                                                       double pageWidth,
                                                       double pageHeight) {
        if (pageWidth >= 1e8) return false;
        if (rawLeft >= -0.5 || rawRight <= pageWidth + 0.5) return false;
        double fullH = rawBottom - rawTop;
        double maxStripHeight = pageHeight < 1e8 ? Math.min(40.0, pageHeight * 0.15) : 40.0;
        if (fullH > maxStripHeight) return false;
        String file = rg.file();
        String reason = rg.reason();
        return (file != null && (file.contains("master_") || file.contains("haseera_")))
                || "master_graphic".equals(reason)
                || "haseera_graphic".equals(reason);
    }

    private static int[] edgeAlphaRun(BufferedImage img, int pageIdx) {
        int w = img.getWidth();
        int h = img.getHeight();
        if (w <= 0 || h <= 0) return null;

        int[] colorRun = edgeColorRun(img, pageIdx);
        if (colorRun != null) return colorRun;

        boolean[] occupied = new boolean[w];
        int occupiedCount = 0;
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                if (((img.getRGB(x, y) >>> 24) & 0xFF) > 8) {
                    occupied[x] = true;
                    occupiedCount++;
                    break;
                }
            }
        }
        if (occupiedCount == 0 || occupiedCount > w * 0.35) return null;

        int gapLimit = Math.max(8, w / 180);
        List<int[]> runs = new ArrayList<>();
        int runStart = -1;
        int lastOccupied = -1;
        for (int x = 0; x < w; x++) {
            if (!occupied[x]) continue;
            if (runStart < 0 || (lastOccupied >= 0 && x - lastOccupied > gapLimit)) {
                if (runStart >= 0) runs.add(new int[]{runStart, lastOccupied});
                runStart = x;
            }
            lastOccupied = x;
        }
        if (runStart >= 0) runs.add(new int[]{runStart, lastOccupied});
        if (runs.isEmpty()) return null;

        int[] selected = runs.get(0);
        if ((pageIdx & 1) == 1) {
            selected = runs.get(runs.size() - 1);
        }
        int pad = Math.max(2, w / 1000);
        int left = Math.max(0, selected[0] - pad);
        int right = Math.min(w - 1, selected[1] + pad);
        if (right - left + 1 > w * 0.35) return null;
        return new int[]{left, right};
    }

    private static int[] edgeColorRun(BufferedImage img, int pageIdx) {
        int w = img.getWidth();
        int h = img.getHeight();
        boolean[] occupied = new boolean[w];
        int occupiedCount = 0;
        for (int x = 0; x < w; x++) {
            for (int y = 0; y < h; y++) {
                int argb = img.getRGB(x, y);
                int a = (argb >>> 24) & 0xFF;
                if (a <= 8) continue;
                int r = (argb >>> 16) & 0xFF;
                int g = (argb >>> 8) & 0xFF;
                int b = argb & 0xFF;
                int max = Math.max(r, Math.max(g, b));
                int min = Math.min(r, Math.min(g, b));
                if (max >= 80 && max - min >= 35) {
                    occupied[x] = true;
                    occupiedCount++;
                    break;
                }
            }
        }
        if (occupiedCount == 0 || occupiedCount > w * 0.35) return null;
        return selectEdgeRun(occupied, pageIdx, w);
    }

    private static int[] selectEdgeRun(boolean[] occupied, int pageIdx, int width) {
        int gapLimit = Math.max(8, width / 180);
        List<int[]> runs = new ArrayList<>();
        int runStart = -1;
        int lastOccupied = -1;
        for (int x = 0; x < width; x++) {
            if (!occupied[x]) continue;
            if (runStart < 0 || (lastOccupied >= 0 && x - lastOccupied > gapLimit)) {
                if (runStart >= 0) runs.add(new int[]{runStart, lastOccupied});
                runStart = x;
            }
            lastOccupied = x;
        }
        if (runStart >= 0) runs.add(new int[]{runStart, lastOccupied});
        if (runs.isEmpty()) return null;

        int[] selected = runs.get(0);
        if ((pageIdx & 1) == 1) {
            selected = runs.get(runs.size() - 1);
        }
        int pad = Math.max(2, width / 1000);
        int left = Math.max(0, selected[0] - pad);
        int right = Math.min(width - 1, selected[1] + pad);
        if (right - left + 1 > width * 0.35) return null;
        return new int[]{left, right};
    }


    /**
     * SPEC-036: 부모 그룹 PNG에 이미 구워진 자식 렌더 id를 모은다(childOfGroup).
     * Phase 6/7은 이 집합의 항목을 독립 배치하지 않는다. (단, PLACE_TEXT_SHELL 부모 아래
     * 자체 래스터 렌더를 가진 자식은 부모가 굽지 않으므로 보존.)
     *
     * <p>주의: editableLabelShellIds/conceptDiagramLabelShellIds 등 Phase 3 산출물에 의존하므로
     * Phase 0 OwnershipPlanner로 단순 이전 불가 — Tier 2 일원화의 phase-ordering 제약 지점.</p>
     */
    private static Set<Integer> computeChildOfGroup(
            ResolvedBuildContext ctx, List<ASTSection> sections, List<RenderedGroup> floatingItems,
            Set<Integer> editableLabelShellIds, Set<Integer> conceptDiagramLabelShellIds,
            Map<Integer, Integer> idToPage, Map<Integer, RenderedGroup> idToRendered) {
        Set<Integer> childOfGroup = new HashSet<>();
        for (RenderedGroup rg : floatingItems) {
            if (!canSuppressChildren(ctx, sections, rg, editableLabelShellIds, idToRendered)) continue;
            int parentPage = rg.pageIndex();
            boolean parentKeepsContainerShell = hasSubstantialVisualOutsideEditableLabelShell(
                    rg, editableLabelShellIds, idToRendered);
            // PLACE_TEXT_SHELL 부모는 배경/텍스트 셸만 렌더하고 자식 이미지를 PNG에 굽지 않는다.
            // → 자체 래스터 이미지 렌더를 가진 자식(renderedImageFrame)은 억제하면 영영 사라지므로 보존.
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
                    if (shouldPreserveSourceChild(idToRendered.get(cid))) continue;
                    if (parentIsTextShellOnly && ctx.resolvedData.isRenderedImageFrameDomId(cid)) continue;
                    childOfGroup.add(cid);
                }
            }
        }
        return childOfGroup;
    }

    private static boolean canSuppressChildren(
            ResolvedBuildContext ctx, List<ASTSection> sections, RenderedGroup rg,
            Set<Integer> editableLabelShellIds, Map<Integer, RenderedGroup> idToRendered) {
        if (!isPageObject(rg)) return false;
        if (ctx.hasOwnershipPlan(rg) && !ctx.hasVisibleVisualByOwnershipPlan(rg)) return false;
        if (ctx.resolvedData.isInlineObjectId(rg.id())) return false;
        if (ctx.resolvedData.shouldKeepVisualLabelTextEditable(rg)) return false;
        if (shouldDecomposeToEditableLabelShell(rg, editableLabelShellIds, idToRendered)) return false;
        if (rg.shouldSkipByOwnership()) return false;
        boolean planKeepsVisibleVisual = ctx.hasOwnershipPlan(rg)
                && ctx.hasVisibleVisualByOwnershipPlan(rg);
        if (!planKeepsVisibleVisual && shouldSkipByChildPolicy(ctx, rg)) return false;
        if (rg.bounds() == null || rg.bounds().length < 4) return false;
        int pageIdx = ctx.toSectionIndex.applyAsInt(rg.pageIndex());
        if (pageIdx < 0 || pageIdx >= sections.size()) return false;
        return hasRenderablePng(ctx, rg);
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

    private static boolean shouldPreserveSourceChild(RenderedGroup child) {
        if (child == null) return false;
        // Preserve an explicitly rendered full shell child, but suppress partial
        // decoration children owned by a parent visual shell. This prevents
        // "triangle fill only" renders from replacing the full rounded-label shell.
        return "visual_label_text_hidden_shell".equals(child.reason())
                || "editable_composite_text_hidden_shell".equals(child.reason())
                || "concept_label_shell".equals(child.reason())
                || "editable_textframe_visual_shell".equals(child.reason());
    }

    private static boolean shouldPreserveEditableLabelShell(
            RenderedGroup rg, Set<Integer> editableLabelShellIds) {
        if (rg == null) return false;
        if (editableLabelShellIds != null && editableLabelShellIds.contains(rg.id())) return true;
        if (!isEditableLabelShellReason(rg.reason())) return false;
        if (!isPageObject(rg) && !"inline_object".equals(rg.itemType()) && !"inline_object".equals(rg.type())) {
            return false;
        }
        if (Boolean.FALSE.equals(rg.placementAllowed())) return false;
        if (!"indesign_png".equals(rg.visualOwner())) return false;
        if (!"hwpx_tf".equals(rg.textOwner())) return false;
        return !Boolean.TRUE.equals(rg.containsText())
                && !Boolean.TRUE.equals(rg.containsEditableText());
    }

    private static boolean shouldKeepPairedInlinePageShell(RenderedGroup rg) {
        if (rg == null || !isPageObject(rg)) return false;
        if (Boolean.FALSE.equals(rg.placementAllowed())) return false;
        if (!"indesign_png".equals(rg.visualOwner())) return false;
        if (!"hwpx_tf".equals(rg.textOwner())) return false;
        if (Boolean.TRUE.equals(rg.containsText()) || Boolean.TRUE.equals(rg.containsEditableText())) return false;
        if (!rg.hasEditableTextHiddenFromPng()) return false;
        String reason = rg.reason() == null ? "" : rg.reason();
        if (!reason.contains("text_composite_editable_text_hidden")
                && !reason.contains("editable_composite_text_hidden_shell")
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

    private static boolean isTextOwnedVisualShell(RenderedGroup rg) {
        if (rg == null) return false;
        if (isTextOwnedRenderedContent(rg)) return false;
        if (!"hwpx_tf".equals(rg.textOwner())) return false;
        if (!"indesign_png".equals(rg.visualOwner())) return false;
        if (Boolean.TRUE.equals(rg.containsText()) || Boolean.TRUE.equals(rg.containsEditableText())) return false;
        return isTextFrameVisualShellReason(rg.reason());
    }

    private static boolean isTextOwnedRenderedContent(RenderedGroup rg) {
        if (rg == null) return false;
        if (!"hwpx_tf".equals(rg.textOwner())) return false;
        if (!"indesign_png".equals(rg.visualOwner())) return false;
        if (Boolean.TRUE.equals(rg.containsText()) || Boolean.TRUE.equals(rg.containsEditableText())) return false;
        String reason = rg.reason();
        if (reason == null) return false;
        return reason.contains("mixed_group_text_hidden")
                || reason.contains("image_group_text_hidden")
                || reason.contains("complex_graphic_text_hidden");
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
        if (rg == null || !isPageObject(rg)) return false;
        if (Boolean.TRUE.equals(rg.containsText()) || Boolean.TRUE.equals(rg.containsEditableText())) return false;
        if ("indesign_png".equals(rg.textOwner())) return false;
        if (Boolean.FALSE.equals(rg.placementAllowed())) return false;
        if (!"indesign_png".equals(rg.visualOwner())) return false;
        if (!isTextFrameVisualShellReason(rg.reason())) return false;
        double[] b = rg.bounds();
        if (b == null || b.length < 4) return false;
        double w = b[3] - b[1];
        double h = b[2] - b[0];
        return w >= 8.0 && w <= 90.0
                && h >= 2.5 && h <= 14.0
                && w / h >= 2.0;
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

    private static boolean shouldSkipByChildPolicy(ResolvedBuildContext ctx, RenderedGroup rg) {
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

        // 자식 중 inline_object가 있어도, extractor가 TF 텍스트를 숨긴 visual-only
        // PNG라고 명시한 경우는 배치한다. 예: 제목 배경 그룹 안에 inline badge가
        // 함께 묶인 케이스에서 그룹 전체를 스킵하면 배경 그래픽까지 사라진다.
        if (anyChildIsInlineObject && !rg.hasEditableTextHiddenFromPng()) return true;

        // editable TF를 포함한 장식 그룹 PNG는 기본적으로 텍스트 중복 위험이 있다.
        // 단, extractor가 텍스트를 숨긴 visual-only PNG라고 명시한 경우는 배치한다.
        if (hasEditableTfChild && !rg.hasEditableTextHiddenFromPng()) return true;

        // 자식 모두가 ETF이면 → 순수 텍스트 컨테이너 → floating 불필요
        return allChildrenAreEditableTf;
    }

    private static Set<Integer> collectInlineObjectCoverage(
            ResolvedBuildContext ctx, List<RenderedGroup> floatingItems) {
        Set<Integer> coveredIds = new HashSet<>();
        if (floatingItems == null || floatingItems.isEmpty()) return coveredIds;

        boolean changed;
        do {
            int before = coveredIds.size();
            for (RenderedGroup rg : floatingItems) {
                if (rg == null) continue;
                boolean inlineObject = "inline_object".equals(rg.itemType())
                        || "inline_object".equals(rg.type());
                // 시각이 드롭(DROP_VISUAL)된 inline_object는 실제로 인라인 배치되지 않으므로
                // 같은 id의 page_object를 "인라인이 덮는다"고 보면 안 된다(그 page_object가 유일한 시각).
                if (inlineObject && ctx.shouldDropVisualByOwnershipPlan(rg)) continue;
                if ((inlineObject && hasPageVisibleInlineBounds(ctx, rg))
                        || coveredIds.contains(rg.id())) {
                    addCoverageIds(rg, coveredIds);
                }
            }
            changed = coveredIds.size() != before;
        } while (changed);
        return coveredIds;
    }

    private static boolean hasPageVisibleInlineBounds(ResolvedBuildContext ctx, RenderedGroup rg) {
        if (rg == null) return false;
        double[] b = rg.bounds();
        if (b == null || b.length < 4) return true;
        int pageIdx = -1;
        double pageWidth = 1e9;
        double pageHeight = 1e9;
        if (ctx != null && ctx.resolvedData != null && ctx.resolvedData.pages() != null) {
            pageIdx = ctx.toSectionIndex.applyAsInt(rg.pageIndex());
            if (pageIdx >= 0 && pageIdx < ctx.resolvedData.pages().size()) {
                double[] pb = ctx.resolvedData.pages().get(pageIdx).bounds();
                if (pb != null && pb.length >= 4) {
                    pageWidth = (pb[3] - pb[1]) / ctx.scaleFactor;
                    pageHeight = (pb[2] - pb[0]) / ctx.scaleFactor;
                }
            }
        }
        b = normalizeInlineSpreadBoundsToPage(ctx, pageIdx, rg, b);
        return b[3] > 0.0 && b[1] < pageWidth
                && b[2] > 0.0 && b[0] < pageHeight;
    }

    private static double[] normalizeInlineSpreadBoundsToPage(
            ResolvedBuildContext ctx, int pageIdx, RenderedGroup rg, double[] bounds) {
        if (ctx == null || ctx.resolvedData == null || rg == null
                || bounds == null || bounds.length < 4) {
            return bounds;
        }
        boolean inlineObject = "inline_object".equals(rg.itemType())
                || "inline_object".equals(rg.type());
        if (!inlineObject) return bounds;
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

        // Right-page inline anchors are sometimes exported in spread coordinates
        // while the HWPX page expects page-relative coordinates. Fold those into
        // the local page before visibility/crop/z-order decisions.
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
        double localPageWidth = ctx.scaleFactor != 0.0 ? pageWidth / ctx.scaleFactor : pageWidth;
        double localPageHeight = ctx.scaleFactor != 0.0 ? pageHeight / ctx.scaleFactor : pageHeight;
        boolean xInRightLocalSpreadPage = !xInSpreadPage
                && !xInRightSpreadPage
                && pageLeft <= 1.0
                && localPageWidth > 0.0
                && bounds[1] >= localPageWidth - 0.5
                && bounds[3] <= localPageWidth * 2.0 + 0.5;
        boolean yInBottomLocalSpreadPage = !yInSpreadPage
                && !yInBottomSpreadPage
                && pageTop <= 1.0
                && localPageHeight > 0.0
                && bounds[0] >= localPageHeight - 0.5
                && bounds[2] <= localPageHeight * 2.0 + 0.5;
        if (xInRightSpreadPage) {
            pageLeft = pageWidth;
            xInSpreadPage = true;
        } else if (xInRightLocalSpreadPage) {
            pageLeft = localPageWidth;
            xInSpreadPage = true;
        }
        if (yInBottomSpreadPage) {
            pageTop = pageHeight;
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

    private static void addCoverageIds(RenderedGroup rg, Set<Integer> coveredIds) {
        if (rg == null || coveredIds == null) return;
        coveredIds.add(rg.id());
        addAll(rg.sourceObjectIds(), coveredIds);
        addAll(rg.childIds(), coveredIds);
        addAll(rg.childImageIds(), coveredIds);
        addAll(rg.visualOnlyChildIds(), coveredIds);
        addAll(rg.tfInlineVisualIds(), coveredIds);
    }

    private static boolean shouldPreferInlineCompleteLabelPair(ResolvedBuildContext ctx, RenderedGroup pageObject) {
        RenderedGroup inline = findInlinePair(ctx, pageObject);
        if (inline == null || inline.bounds() == null || pageObject == null || pageObject.bounds() == null) {
            return false;
        }
        double[] ib = inline.bounds();
        double[] pb = pageObject.bounds();
        if (ib.length < 4 || pb.length < 4) return false;
        double dx = Math.abs(ib[1] - pb[1]);
        double dy = Math.abs(ib[0] - pb[0]);
        if (dx <= 1.0 && dy <= 1.0) return true;

        double pageWidth = localPageWidth(ctx, pageObject.pageIndex());
        double pageHeight = localPageHeight(ctx, pageObject.pageIndex());
        boolean shiftedByPageWidth = pageWidth > 0.0 && Math.abs(dx - pageWidth) <= 2.0;
        boolean shiftedByPageHeight = pageHeight > 0.0 && Math.abs(dy - pageHeight) <= 2.0;
        if (shiftedByPageWidth || shiftedByPageHeight) return false;
        return dx <= 4.0 && dy <= 4.0;
    }

    private static RenderedGroup findInlinePair(ResolvedBuildContext ctx, RenderedGroup pageObject) {
        if (ctx == null || ctx.resolvedData == null || pageObject == null
                || ctx.resolvedData.allRenderedFloatingItems() == null) {
            return null;
        }
        for (RenderedGroup rg : ctx.resolvedData.allRenderedFloatingItems()) {
            if (rg == null || rg.id() != pageObject.id()) continue;
            if ("inline_object".equals(rg.itemType()) || "inline_object".equals(rg.type())) {
                return rg;
            }
        }
        return null;
    }

    private static double localPageWidth(ResolvedBuildContext ctx, int pageIndex) {
        double[] bounds = pageBounds(ctx, pageIndex);
        if (bounds == null) return 0.0;
        double width = bounds[3] - bounds[1];
        return ctx.scaleFactor != 0.0 ? width / ctx.scaleFactor : width;
    }

    private static double localPageHeight(ResolvedBuildContext ctx, int pageIndex) {
        double[] bounds = pageBounds(ctx, pageIndex);
        if (bounds == null) return 0.0;
        double height = bounds[2] - bounds[0];
        return ctx.scaleFactor != 0.0 ? height / ctx.scaleFactor : height;
    }

    private static double[] pageBounds(ResolvedBuildContext ctx, int pageIndex) {
        if (ctx == null || ctx.resolvedData == null || ctx.resolvedData.pages() == null) return null;
        if (pageIndex < 0 || pageIndex >= ctx.resolvedData.pages().size()) return null;
        if (ctx.resolvedData.pages().get(pageIndex) == null) return null;
        double[] bounds = ctx.resolvedData.pages().get(pageIndex).bounds();
        return bounds != null && bounds.length >= 4 ? bounds : null;
    }

    private static void addAll(int[] ids, Set<Integer> target) {
        if (ids == null || target == null) return;
        for (int id : ids) {
            target.add(id);
        }
    }

    private static boolean isCoveredByInlineObject(RenderedGroup rg, Set<Integer> coveredIds) {
        if (rg == null || coveredIds == null || coveredIds.isEmpty()) return false;
        return coveredIds.contains(rg.id());
    }

    private static boolean hasRenderablePng(ResolvedBuildContext ctx, RenderedGroup rg) {
        if (rg.file() == null) return false;
        try {
            File pngFile = new File(ctx.basePath, rg.file());
            return pngFile.exists() && pngFile.length() > 0;
        } catch (Exception e) {
            return false;
        }
    }


    private static byte[] loadPng(ResolvedBuildContext ctx, RenderedGroup rg) {
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

    private static BufferedImage loadImageForPlacement(ResolvedBuildContext ctx, RenderedGroup rg, byte[] pngData) {
        if (ctx == null || rg == null || rg.file() == null) return null;
        try {
            BufferedImage base = decodePngBytes(pngData);
            if (base == null || !shouldCompositeTfInlineVisuals(rg)) return base;
            BufferedImage merged = compositeTfInlineVisuals(ctx, rg, base);
            return merged != null ? merged : base;
        } catch (Exception e) {
            System.err.println("[BackgroundInjector] PNG 합성 실패: " + e.getMessage());
            return null;
        }
    }

    private static BufferedImage decodePngBytes(byte[] pngData) {
        if (pngData == null || pngData.length == 0) return null;
        try {
            ConversionTiming.addCounter("phase6.pngBytes.imageDecodes", 1);
            return ImageIO.read(new java.io.ByteArrayInputStream(pngData));
        } catch (Exception e) {
            return null;
        }
    }

    private static BufferedImage knockOutPaperLikeFill(BufferedImage img) {
        if (img == null || img.getWidth() <= 0 || img.getHeight() <= 0) return img;
        long total = (long) img.getWidth() * (long) img.getHeight();
        if (total <= 0) return img;

        long paperLike = 0;
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                int argb = img.getRGB(x, y);
                int a = (argb >>> 24) & 0xFF;
                if (a < 220) continue;
                int r = (argb >>> 16) & 0xFF;
                int g = (argb >>> 8) & 0xFF;
                int b = argb & 0xFF;
                if (isPaperLikeRgb(r, g, b)) paperLike++;
            }
        }

        if ((double) paperLike / (double) total < 0.55) {
            return img;
        }

        BufferedImage out = new BufferedImage(img.getWidth(), img.getHeight(), BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                int argb = img.getRGB(x, y);
                int a = (argb >>> 24) & 0xFF;
                int r = (argb >>> 16) & 0xFF;
                int g = (argb >>> 8) & 0xFF;
                int b = argb & 0xFF;
                out.setRGB(x, y, (a >= 180 && isPaperLikeRgb(r, g, b)) ? 0x00000000 : argb);
            }
        }
        return out;
    }

    private static boolean isPaperLikeRgb(int r, int g, int b) {
        int max = Math.max(r, Math.max(g, b));
        int min = Math.min(r, Math.min(g, b));
        return r >= 246 && g >= 246 && b >= 246 && (max - min) <= 8;
    }

    private static boolean hasTfInlineVisuals(RenderedGroup rg) {
        return rg != null && rg.tfInlineVisualIds() != null && rg.tfInlineVisualIds().length > 0;
    }

    private static boolean shouldCompositeTfInlineVisuals(RenderedGroup rg) {
        if (!hasTfInlineVisuals(rg)) return false;
        // If the text frame remains editable in HWPX, its inline visuals must stay
        // in the text flow instead of being baked into the floating visual shell.
        return !"hwpx_tf".equals(rg.textOwner());
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
                || reason.contains("image_group");
    }

    private static int[] alphaBounds(BufferedImage img) {
        if (img == null || img.getWidth() <= 0 || img.getHeight() <= 0) return null;
        int minX = img.getWidth();
        int minY = img.getHeight();
        int maxX = -1;
        int maxY = -1;
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                int alpha = (img.getRGB(x, y) >>> 24) & 0xFF;
                if (alpha <= 10) continue;
                if (x < minX) minX = x;
                if (y < minY) minY = y;
                if (x > maxX) maxX = x;
                if (y > maxY) maxY = y;
            }
        }
        if (maxX < minX || maxY < minY) return null;
        int pad = 1;
        minX = Math.max(0, minX - pad);
        minY = Math.max(0, minY - pad);
        maxX = Math.min(img.getWidth() - 1, maxX + pad);
        maxY = Math.min(img.getHeight() - 1, maxY + pad);
        return new int[] { minX, minY, maxX - minX + 1, maxY - minY + 1 };
    }

    private static boolean shouldApplyAlphaCrop(BufferedImage img, int[] bounds) {
        if (img == null || bounds == null || bounds.length < 4) return false;
        int cropW = bounds[2];
        int cropH = bounds[3];
        if (cropW <= 0 || cropH <= 0) return false;
        int padLeft = bounds[0];
        int padTop = bounds[1];
        int padRight = img.getWidth() - (bounds[0] + cropW);
        int padBottom = img.getHeight() - (bounds[1] + cropH);
        int maxPad = Math.max(Math.max(padLeft, padRight), Math.max(padTop, padBottom));
        if (maxPad < 2) return false;
        double areaRatio = (double) cropW * (double) cropH
                / ((double) img.getWidth() * (double) img.getHeight());
        return areaRatio > 0.01 && areaRatio < 0.98;
    }

    private static boolean shouldPreserveVisualLabelAspect(RenderedGroup rg, int pixelW, int pixelH) {
        return rg != null
                && pixelW > 0
                && pixelH > 0
                && "visual_label_indesign_png".equals(rg.reason())
                && "indesign_png".equals(rg.textOwner());
    }

    private static boolean isCompletePngSimpleButtonLabel(ResolvedBuildContext ctx, RenderedGroup rg) {
        return ctx != null
                && ctx.resolvedData != null
                && ctx.resolvedData.shouldUseCompletePngForSimpleButtonLabel(rg);
    }

    private static Set<Integer> collectCompleteInlineSimpleButtonLabels(
            ResolvedBuildContext ctx,
            List<RenderedGroup> items) {
        Set<Integer> ids = new HashSet<>();
        if (items == null) return ids;
        for (RenderedGroup rg : items) {
            if (rg == null) continue;
            if (!isCompletePngSimpleButtonLabel(ctx, rg)) continue;
            boolean inlineOwned = "inline_object".equals(rg.itemType())
                    || "inline_object".equals(rg.type());
            if (inlineOwned) ids.add(rg.id());
        }
        return ids;
    }

    private static int effectiveZOrder(ResolvedBuildContext ctx, RenderedGroup rg) {
        if (rg == null) return 5;
        Integer plannedZ = ctx.zOrderByOwnershipPlan(rg);
        if (plannedZ != null) return plannedZ;
        int ownedShellZ = ownedTextFrameShellZOrder(ctx, rg);
        if (ownedShellZ >= 0) return ownedShellZ;
        int conceptLabelShellZ = conceptDiagramLabelShellZOrder(ctx, rg);
        if (conceptLabelShellZ >= 0) return conceptLabelShellZ;
        int inferredShellZ = inferredTextFrameVisualShellZOrder(ctx, rg);
        if (inferredShellZ >= 0) return inferredShellZ;
        int textLineBackdropZ = inferredTextLineBackdropZOrder(ctx, rg);
        if (textLineBackdropZ >= 0) return textLineBackdropZ;
        int titleLabelZ = titleLabelBackgroundZOrder(ctx, rg);
        if (titleLabelZ >= 0) return titleLabelZ;
        if (rg.zOrderKnown()) return rg.zOrder();
        return Math.max(rg.zOrder(), 5);
    }

    private static int foregroundMarkerZOrder(
            ASTSection section,
            long x,
            long y,
            long w,
            long h,
            int currentZ) {
        if (section == null || w <= 0 || h <= 0) return currentZ;
        int maxOverlapZ = currentZ;
        long markerArea = w * h;
        if (markerArea <= 0) return currentZ;
        for (ASTBlock block : section.blocks()) {
            if (block == null) continue;
            long[] b = astBlockBounds(block);
            if (b == null) continue;
            long bw = b[2] - b[0];
            long bh = b[3] - b[1];
            if (bw <= 0 || bh <= 0) continue;
            long overlap = overlapAreaHwp(x, y, w, h, b[0], b[1], bw, bh);
            if (overlap <= 0) continue;
            long blockArea = bw * bh;
            if (blockArea <= 0) continue;
            if ((double) overlap / (double) markerArea < 0.02
                    && (double) overlap / (double) blockArea < 0.02) {
                continue;
            }
            maxOverlapZ = Math.max(maxOverlapZ, astBlockZOrder(block));
        }
        return Math.min(999, maxOverlapZ + 1);
    }

    private static int foregroundOverlapShellZOrder(
            ASTSection section,
            RenderedGroup rg,
            long x,
            long y,
            long w,
            long h,
            int currentZ) {
        if (!isForegroundOverlapShellCandidate(rg) || section == null || w <= 0 || h <= 0) {
            return currentZ;
        }
        int minOverlapZ = Integer.MAX_VALUE;
        long shellArea = w * h;
        if (shellArea <= 0) return currentZ;
        for (ASTBlock block : section.blocks()) {
            if (block == null) continue;
            long[] b = astBlockBounds(block);
            if (b == null) continue;
            long bw = b[2] - b[0];
            long bh = b[3] - b[1];
            if (bw <= 0 || bh <= 0) continue;
            long overlap = overlapAreaHwp(x, y, w, h, b[0], b[1], bw, bh);
            if (overlap <= 0) continue;
            long blockArea = bw * bh;
            if (blockArea <= 0) continue;
            if ((double) overlap / (double) blockArea < 0.05
                    && (double) overlap / (double) shellArea < 0.01) {
                continue;
            }
            int z = astBlockZOrder(block);
            if (z <= 0) continue;
            minOverlapZ = Math.min(minOverlapZ, z);
        }
        if (minOverlapZ == Integer.MAX_VALUE || minOverlapZ >= currentZ) {
            return currentZ;
        }
        return Math.max(0, minOverlapZ - 1);
    }

    private static int containerShellZOrderBehindRenderedContent(
            ResolvedBuildContext ctx,
            List<RenderedGroup> items,
            RenderedGroup shell,
            int currentZ) {
        int minContentZ = minOverlappingRenderedContentZ(ctx, items, shell);
        if (minContentZ < 0 || currentZ < minContentZ) {
            return currentZ;
        }
        return Math.max(0, minContentZ - 1);
    }

    private static int minOverlappingRenderedContentZ(
            ResolvedBuildContext ctx,
            List<RenderedGroup> items,
            RenderedGroup shell) {
        if (ctx == null || shell == null || items == null || !isRenderedContainerShell(shell)) {
            return -1;
        }
        double[] shellBounds = shell.bounds();
        double shellArea = area(shellBounds);
        if (shellArea <= 0) return -1;

        int minContentZ = Integer.MAX_VALUE;
        for (RenderedGroup content : items) {
            if (!isRenderedContentLayer(content, shellArea) || content.pageIndex() != shell.pageIndex()) continue;
            if (content.id() == shell.id()) continue;
            double[] contentBounds = content.bounds();
            double contentArea = area(contentBounds);
            if (contentArea <= 0) continue;
            double overlap = overlapArea(shellBounds, contentBounds);
            if (overlap <= 0) continue;
            double contentOverlap = overlap / contentArea;
            double shellOverlap = overlap / shellArea;
            if (contentOverlap < 0.12 && shellOverlap < 0.04) continue;

            int contentZ = effectiveZOrder(ctx, content);
            if (contentZ > 0) {
                minContentZ = Math.min(minContentZ, contentZ);
            }
        }
        return minContentZ == Integer.MAX_VALUE ? -1 : minContentZ;
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
        if (ctx == null || ctx.resolvedData == null || rg == null) return false;
        if (!isRenderedContainerShell(rg)) return false;
        if (!"vector_shape".equals(rg.reason())) return false;

        int[] sourceIds = rg.sourceObjectIds();
        boolean sawSource = false;
        boolean sawPaperFill = false;
        if (sourceIds != null) {
            for (int sourceId : sourceIds) {
                if (isPaperOnlyPageItem(ctx, String.valueOf(sourceId))) {
                    sawSource = true;
                    sawPaperFill = true;
                    continue;
                }
                ResolvedPageItem pi = ctx.resolvedData.getPageItem(String.valueOf(sourceId));
                if (pi != null) return false;
            }
        }
        if (!sawSource) {
            return isPaperOnlyPageItem(ctx, String.valueOf(rg.id()));
        }
        return sawPaperFill;
    }

    private static boolean isPaperOnlyPageItem(ResolvedBuildContext ctx, String domId) {
        if (ctx == null || ctx.resolvedData == null || domId == null) return false;
        ResolvedPageItem pi = ctx.resolvedData.getPageItem(domId);
        if (pi == null) return false;
        if (!isPaperColor(pi.fillColorName())) return false;
        if (pi.strokeWeight() > 0.01 && !isNoneColor(pi.strokeColorName())) return false;
        return true;
    }

    private static boolean isPaperColor(String colorName) {
        return "Paper".equals(colorName) || "[Paper]".equals(colorName);
    }

    private static boolean isNoneColor(String colorName) {
        return colorName == null
                || colorName.isEmpty()
                || "None".equals(colorName)
                || "[None]".equals(colorName);
    }

    private static boolean isRenderedContentLayer(RenderedGroup rg) {
        return isRenderedContentLayer(rg, -1.0);
    }

    private static boolean isRenderedContentLayer(RenderedGroup rg, double shellArea) {
        if (rg == null || !isPageObject(rg)) return false;
        if (Boolean.FALSE.equals(rg.placementAllowed())) return false;
        if (!"indesign_png".equals(rg.visualOwner())) return false;
        if ("hwpx_tf".equals(rg.textOwner())) return true;
        String reason = rg.reason();
        if (reason == null) return false;
        if (reason.contains("mixed_group_text_hidden")
                || reason.contains("image_group_text_hidden")
                || reason.contains("complex_graphic_text_hidden")) {
            return true;
        }
        return isSemanticImageContentLayer(rg, shellArea);
    }

    private static boolean isSemanticImageContentLayer(RenderedGroup rg) {
        return isSemanticImageContentLayer(rg, -1.0);
    }

    private static boolean isSemanticImageContentLayer(RenderedGroup rg, double shellArea) {
        if (rg == null) return false;
        if (Boolean.TRUE.equals(rg.containsText()) || Boolean.TRUE.equals(rg.containsEditableText())) return false;
        String reason = rg.reason();
        if (reason == null) return false;
        boolean imageLike = reason.contains("image_group")
                || reason.contains("graphic")
                || reason.contains("photo")
                || reason.contains("picture");
        if (!imageLike) return false;
        double[] b = rg.bounds();
        if (b == null || b.length < 4) return false;
        double w = b[3] - b[1];
        double h = b[2] - b[0];
        double a = area(b);
        if (w <= 0 || h <= 0 || a < 25.0) return false;
        if (w > 170.0 || h > 170.0 || a > 12000.0) return false;
        if (shellArea > 0 && a > shellArea * 1.25) return false;
        return true;
    }

    private static boolean isForegroundOverlapShellCandidate(RenderedGroup rg) {
        if (rg == null) return false;
        if (!isPageObject(rg)) return false;
        if (Boolean.FALSE.equals(rg.placementAllowed())) return false;
        if (!"indesign_png".equals(rg.visualOwner())) return false;
        if (Boolean.TRUE.equals(rg.containsText()) || Boolean.TRUE.equals(rg.containsEditableText())) return false;
        String reason = rg.reason();
        if (reason == null) return false;
        return "decoration_group".equals(reason)
                || reason.contains("complex_graphic_text_hidden")
                || reason.contains("mixed_group_text_hidden")
                || reason.contains("image_group_text_hidden")
                || reason.contains("text_hidden_shell")
                || reason.contains("visual_shell")
                || reason.contains("textframe_visual_shell");
    }

    private static long[] astBlockBounds(ASTBlock block) {
        if (block instanceof ASTTextFrameBlock) {
            ASTTextFrameBlock tf = (ASTTextFrameBlock) block;
            return new long[] { tf.x(), tf.y(), tf.x() + tf.effectiveWidth(), tf.y() + tf.height() };
        }
        if (block instanceof ASTFigure) {
            ASTFigure fig = (ASTFigure) block;
            return new long[] { fig.x(), fig.y(), fig.x() + fig.width(), fig.y() + fig.height() };
        }
        if (block instanceof ASTTable) {
            ASTTable table = (ASTTable) block;
            return new long[] { table.x(), table.y(), table.x() + table.width(), table.y() + table.height() };
        }
        return null;
    }

    private static int astBlockZOrder(ASTBlock block) {
        if (block instanceof ASTTextFrameBlock) return ((ASTTextFrameBlock) block).zOrder();
        if (block instanceof ASTFigure) return ((ASTFigure) block).zOrder();
        if (block instanceof ASTTable) return ((ASTTable) block).zOrder();
        return 0;
    }

    private static long overlapAreaHwp(
            long ax, long ay, long aw, long ah,
            long bx, long by, long bw, long bh) {
        long left = Math.max(ax, bx);
        long top = Math.max(ay, by);
        long right = Math.min(ax + aw, bx + bw);
        long bottom = Math.min(ay + ah, by + bh);
        if (right <= left || bottom <= top) return 0L;
        return (right - left) * (bottom - top);
    }

    private static int ownedTextFrameShellZOrder(ResolvedBuildContext ctx, RenderedGroup rg) {
        if (ctx == null || ctx.resolvedData == null || rg == null) return -1;
        if (!"hwpx_tf".equals(rg.textOwner())) return -1;
        String[] editableIds = rg.editableTextFrameIds();
        if (editableIds == null || editableIds.length == 0) return -1;
        int minZ = Integer.MAX_VALUE;
        for (String editableId : editableIds) {
            ResolvedTextFrame tf = ctx.resolvedData.getTextFrame(editableId);
            if (tf == null || tf.pageIndex() != rg.pageIndex()) continue;
            minZ = Math.min(minZ, tf.zOrder());
        }
        return minZ == Integer.MAX_VALUE ? -1 : Math.max(1, minZ - 1);
    }

    private static int conceptDiagramLabelShellZOrder(ResolvedBuildContext ctx, RenderedGroup rg) {
        if (!isConceptDiagramLabelShell(ctx, rg)) return -1;
        int minZ = Integer.MAX_VALUE;
        for (ResolvedTextFrame tf : conceptDiagramTextFramesForPage(ctx, rg.pageIndex())) {
            double[] tb = boundsOf(tf);
            if (isConceptDiagramShellForTextFrame(ctx, rg, tb)) {
                minZ = Math.min(minZ, tf.zOrder());
            }
        }
        return minZ == Integer.MAX_VALUE ? -1 : Math.max(1, minZ - 1);
    }

    private static int inferredTextFrameVisualShellZOrder(ResolvedBuildContext ctx, RenderedGroup rg) {
        if (ctx == null || ctx.resolvedData == null || rg == null) return -1;
        if (!isPageObject(rg)) return -1;
        if (Boolean.FALSE.equals(rg.placementAllowed())) return -1;
        if (!"indesign_png".equals(rg.visualOwner())) return -1;
        if (Boolean.TRUE.equals(rg.containsText()) || Boolean.TRUE.equals(rg.containsEditableText())) return -1;
        boolean explicitVisualShell = isTextFrameVisualShellReason(rg.reason());
        boolean vectorShapeShell = "vector_shape".equals(rg.reason());
        if (!explicitVisualShell && !vectorShapeShell) return -1;
        double[] rbRaw = rg.bounds();
        if (rbRaw == null || rbRaw.length < 4) return -1;

        int semanticOverlapZ = semanticTextOverlapShellZOrder(ctx, rg, rbRaw);
        if (semanticOverlapZ >= 0) return semanticOverlapZ;

        for (ResolvedTextFrame tf : ctx.resolvedData.textFrames()) {
            if (tf == null || tf.pageIndex() != rg.pageIndex()) continue;
            double[] tb = tf.pageRelativeBounds() != null ? tf.pageRelativeBounds() : tf.geometricBounds();
            if (tb == null || tb.length < 4) continue;
            double[] scaledRb = new double[] {
                    rbRaw[0] * ctx.scaleFactor,
                    rbRaw[1] * ctx.scaleFactor,
                    rbRaw[2] * ctx.scaleFactor,
                    rbRaw[3] * ctx.scaleFactor
            };
            if ((isSimilarTextFrameVisualShell(rbRaw, tb) || isSimilarTextFrameVisualShell(scaledRb, tb))
                    && (!vectorShapeShell || isBestVectorShapeBackdropForTextFrame(ctx, rg, tf, rbRaw, scaledRb))) {
                return Math.max(1, tf.zOrder() - 1);
            }
        }
        return -1;
    }

    private static boolean isBestVectorShapeBackdropForTextFrame(
            ResolvedBuildContext ctx,
            RenderedGroup candidate,
            ResolvedTextFrame tf,
            double[] candidateRawBounds,
            double[] candidateScaledBounds) {
        if (ctx == null || ctx.resolvedData == null || candidate == null || tf == null) return false;
        double[] tb = tf.pageRelativeBounds() != null ? tf.pageRelativeBounds() : tf.geometricBounds();
        if (tb == null || tb.length < 4) return false;

        double candidateScore = textFrameBackdropScore(candidateRawBounds, candidateScaledBounds, tb);
        if (candidateScore <= 0) return false;

        for (RenderedGroup other : ctx.resolvedData.allRenderedFloatingItems()) {
            if (other == null || other.id() == candidate.id()) continue;
            if (other.pageIndex() != candidate.pageIndex()) continue;
            if (!isPageObject(other)) continue;
            if (Boolean.FALSE.equals(other.placementAllowed())) continue;
            if (!"indesign_png".equals(other.visualOwner())) continue;
            if (Boolean.TRUE.equals(other.containsText()) || Boolean.TRUE.equals(other.containsEditableText())) continue;
            if (!"vector_shape".equals(other.reason())) continue;
            double[] ob = other.bounds();
            if (ob == null || ob.length < 4) continue;
            if (!isSimilarTextFrameVisualShell(ob, tb)) {
                double[] scaledOb = new double[] {
                        ob[0] * ctx.scaleFactor,
                        ob[1] * ctx.scaleFactor,
                        ob[2] * ctx.scaleFactor,
                        ob[3] * ctx.scaleFactor
                };
                if (!isSimilarTextFrameVisualShell(scaledOb, tb)) continue;
                double otherScore = textFrameBackdropScore(ob, scaledOb, tb);
                if (otherScore > candidateScore + 0.0001) return false;
            } else {
                double[] scaledOb = new double[] {
                        ob[0] * ctx.scaleFactor,
                        ob[1] * ctx.scaleFactor,
                        ob[2] * ctx.scaleFactor,
                        ob[3] * ctx.scaleFactor
                };
                double otherScore = textFrameBackdropScore(ob, scaledOb, tb);
                if (otherScore > candidateScore + 0.0001) return false;
            }
        }
        return true;
    }

    private static double textFrameBackdropScore(double[] rawBounds, double[] scaledBounds, double[] tfBounds) {
        return Math.max(textFrameBackdropScore(rawBounds, tfBounds),
                textFrameBackdropScore(scaledBounds, tfBounds));
    }

    private static double textFrameBackdropScore(double[] shellBounds, double[] tfBounds) {
        double tfArea = area(tfBounds);
        double shellArea = area(shellBounds);
        if (tfArea <= 0 || shellArea <= 0) return -1.0;
        double overlapRatio = overlapArea(shellBounds, tfBounds) / tfArea;
        if (overlapRatio < TF_VISUAL_SHELL_OVERLAP_MIN) return -1.0;
        double areaRatio = shellArea / tfArea;
        if (areaRatio < TF_VISUAL_SHELL_MIN_AREA_RATIO || areaRatio > TF_VISUAL_SHELL_MAX_AREA_RATIO) {
            return -1.0;
        }
        double shellCenterY = (shellBounds[0] + shellBounds[2]) / 2.0;
        double shellCenterX = (shellBounds[1] + shellBounds[3]) / 2.0;
        double tfCenterY = (tfBounds[0] + tfBounds[2]) / 2.0;
        double tfCenterX = (tfBounds[1] + tfBounds[3]) / 2.0;
        double centerDistance = Math.hypot(shellCenterY - tfCenterY, shellCenterX - tfCenterX);
        double areaPenalty = Math.abs(Math.log(areaRatio));
        return overlapRatio * 1000.0 - centerDistance * 10.0 - areaPenalty * 100.0;
    }

    private static int semanticTextOverlapShellZOrder(ResolvedBuildContext ctx, RenderedGroup rg, double[] rbRaw) {
        if (!"editable_textframe_visual_shell".equals(rg.reason())) return -1;
        int minZ = Integer.MAX_VALUE;
        String shellId = String.valueOf(rg.id());
        double[] scaledRb = new double[] {
                rbRaw[0] * ctx.scaleFactor,
                rbRaw[1] * ctx.scaleFactor,
                rbRaw[2] * ctx.scaleFactor,
                rbRaw[3] * ctx.scaleFactor
        };
        for (ResolvedTextFrame tf : ctx.resolvedData.textFrames()) {
            if (tf == null || tf.pageIndex() != rg.pageIndex()) continue;
            if (shellId.equals(tf.id())) continue;
            if (!hasSemanticText(tf)) continue;
            double[] tb = tf.pageRelativeBounds() != null ? tf.pageRelativeBounds() : tf.geometricBounds();
            if (tb == null || tb.length < 4) continue;
            double tfArea = area(tb);
            if (tfArea <= 0) continue;
            double overlap = Math.max(overlapArea(rbRaw, tb), overlapArea(scaledRb, tb));
            if (overlap / tfArea >= 0.10) {
                minZ = Math.min(minZ, tf.zOrder());
            }
        }
        return minZ == Integer.MAX_VALUE ? -1 : Math.max(1, minZ - 2);
    }

    private static int titleLabelBackgroundZOrder(ResolvedBuildContext ctx, RenderedGroup rg) {
        if (ctx == null || ctx.resolvedData == null || rg == null) return -1;
        if (!isPageObject(rg)) return -1;
        if (!"indesign_png".equals(rg.visualOwner())) return -1;
        if ("hwpx_tf".equals(rg.textOwner())) return -1;
        if (Boolean.TRUE.equals(rg.containsText()) || Boolean.TRUE.equals(rg.containsEditableText())) return -1;
        double[] rbRaw = rg.bounds();
        if (rbRaw == null || rbRaw.length < 4) return -1;

        double w = rbRaw[3] - rbRaw[1];
        double h = rbRaw[2] - rbRaw[0];
        if (w < 20.0 || h < 3.0 || h > 16.0 || w / h < 3.0) return -1;

        double[] scaledRb = new double[] {
                rbRaw[0] * ctx.scaleFactor,
                rbRaw[1] * ctx.scaleFactor,
                rbRaw[2] * ctx.scaleFactor,
                rbRaw[3] * ctx.scaleFactor
        };
        int minZ = Integer.MAX_VALUE;
        for (ResolvedTextFrame tf : ctx.resolvedData.textFrames()) {
            if (tf == null || tf.pageIndex() != rg.pageIndex()) continue;
            if (!hasShortSemanticText(tf)) continue;
            double[] tb = tf.pageRelativeBounds() != null ? tf.pageRelativeBounds() : tf.geometricBounds();
            if (tb == null || tb.length < 4) continue;
            double tfArea = area(tb);
            if (tfArea <= 0) continue;
            double overlap = Math.max(overlapArea(rbRaw, tb), overlapArea(scaledRb, tb));
            if (overlap / tfArea >= 0.35) {
                minZ = Math.min(minZ, tf.zOrder());
            }
        }
        return minZ == Integer.MAX_VALUE ? -1 : Math.max(1, minZ - 1);
    }

    private static int inferredTextLineBackdropZOrder(ResolvedBuildContext ctx, RenderedGroup rg) {
        if (ctx == null || ctx.resolvedData == null || rg == null) return -1;
        if (!isPageObject(rg)) return -1;
        if (Boolean.FALSE.equals(rg.placementAllowed())) return -1;
        if (!"indesign_png".equals(rg.visualOwner())) return -1;
        if ("hwpx_tf".equals(rg.textOwner())) return -1;
        if (Boolean.TRUE.equals(rg.containsText()) || Boolean.TRUE.equals(rg.containsEditableText())) return -1;
        if (!"vector_shape".equals(rg.reason())) return -1;

        double[] rbRaw = rg.bounds();
        if (rbRaw == null || rbRaw.length < 4) return -1;
        double w = rbRaw[3] - rbRaw[1];
        double h = rbRaw[2] - rbRaw[0];
        if (w < 20.0 || h < 1.0 || h > 8.0 || w / h < 4.0) return -1;

        double[] scaledRb = new double[] {
                rbRaw[0] * ctx.scaleFactor,
                rbRaw[1] * ctx.scaleFactor,
                rbRaw[2] * ctx.scaleFactor,
                rbRaw[3] * ctx.scaleFactor
        };
        int minZ = Integer.MAX_VALUE;
        for (ResolvedTextFrame tf : ctx.resolvedData.textFrames()) {
            if (tf == null || tf.pageIndex() != rg.pageIndex()) continue;
            if (!hasSemanticText(tf)) continue;
            double[] tb = tf.pageRelativeBounds() != null ? tf.pageRelativeBounds() : tf.geometricBounds();
            if (tb != null && tb.length >= 4
                    && (isTextLineBackdropInsideTextFrame(rbRaw, tb)
                        || isTextLineBackdropInsideTextFrame(scaledRb, tb))) {
                minZ = Math.min(minZ, tf.zOrder());
            }
            java.util.List<ResolvedTextFrame.ComposedLine> lines = tf.composedLines();
            if (lines == null || lines.isEmpty()) continue;
            for (ResolvedTextFrame.ComposedLine line : lines) {
                if (line == null || line.bounds() == null || line.bounds().length < 4) continue;
                double[] lb = line.bounds();
                double lineArea = area(lb);
                if (lineArea <= 0) continue;
                double overlap = Math.max(overlapArea(rbRaw, lb), overlapArea(scaledRb, lb));
                if (overlap / lineArea >= 0.20) {
                    minZ = Math.min(minZ, tf.zOrder());
                    break;
                }
            }
        }
        return minZ == Integer.MAX_VALUE ? -1 : Math.max(1, minZ - 1);
    }

    private static boolean isTextLineBackdropInsideTextFrame(double[] rb, double[] tb) {
        if (rb == null || rb.length < 4 || tb == null || tb.length < 4) return false;
        double w = rb[3] - rb[1];
        double h = rb[2] - rb[0];
        if (w < 20.0 || h < 1.0 || h > 8.0 || w / h < 4.0) return false;
        double rbArea = area(rb);
        if (rbArea <= 0.0) return false;
        double overlap = overlapArea(rb, tb);
        if (overlap / rbArea < 0.65) return false;
        double centerY = (rb[0] + rb[2]) / 2.0;
        double centerX = (rb[1] + rb[3]) / 2.0;
        double yTol = Math.max(2.0, h);
        double xTol = Math.max(2.0, h);
        return centerY >= tb[0] - yTol
                && centerY <= tb[2] + yTol
                && centerX >= tb[1] - xTol
                && centerX <= tb[3] + xTol;
    }

    private static boolean hasSemanticText(ResolvedTextFrame tf) {
        return !visibleText(tf).isEmpty();
    }

    private static boolean hasShortSemanticText(ResolvedTextFrame tf) {
        String text = visibleText(tf);
        if (text.isEmpty() || text.length() > 60) return false;
        return !text.contains("\n") && !text.contains("\r");
    }

    private static String visibleText(ResolvedTextFrame tf) {
        String text = tf != null ? tf.frameVisibleText() : null;
        if (text == null) return "";
        return text.replace("\uFFFC", "")
                .replace("\u0016", "")
                .replace("\u0018", "")
                .trim();
    }

    /**
     * A very thin visual object that tracks one composed text line is better
     * represented as text emphasis than as a floating PNG. This keeps the
     * emphasis attached to editable text when the user edits or reflows it.
     */
    private static boolean tryAbsorbTextEmphasisBackdrop(
            ResolvedBuildContext ctx,
            List<ASTSection> sections,
            RenderedGroup rg,
            double[] rbRaw) {
        if (ctx == null || ctx.resolvedData == null || sections == null || rg == null) return false;
        if (!isAbsorbableTextEmphasisBackdrop(ctx, rg, rbRaw)) {
            return false;
        }

        TextLineMatch match = findBestTextLineMatch(ctx, rg, rbRaw);
        if (match == null || match.tf == null || match.line == null) {
            return false;
        }
        ASTParagraph para = findAstParagraphForLine(ctx, sections, rg.pageIndex(), match.tf, match.line);
        if (para == null) {
            return false;
        }

        byte[] png = loadPng(ctx, rg);
        String color = inferEmphasisColorFromPng(png);
        if (color == null) {
            return false;
        }

        return applyCharacterShadeToComposedLine(para, match.line, color);
    }

    private static boolean applyCharacterShadeToComposedLine(
            ASTParagraph para,
            ResolvedTextFrame.ComposedLine line,
            String color) {
        if (para == null || line == null || color == null || color.isEmpty()) return false;
        String lineText = line.text();
        if (lineText == null || lineText.isEmpty()) return false;

        TextOffsetMap paraMap = buildParagraphCompactTextMap(para);
        TextOffsetMap lineMap = buildCompactTextMap(lineText);
        if (paraMap.compact.isEmpty() || lineMap.compact.isEmpty()) return false;

        int compactStart = paraMap.compact.indexOf(lineMap.compact);
        if (compactStart < 0) {
            // Some composed-line strings include paragraph-leading inline anchors
            // or layout-only punctuation. Try a conservative suffix match before
            // giving up; this still maps to an actual paragraph character span.
            String needle = trimCompactNeedle(lineMap.compact);
            compactStart = needle.isEmpty() ? -1 : paraMap.compact.indexOf(needle);
            if (compactStart < 0) return false;
            lineMap = buildCompactTextMap(needle);
        }

        int compactEnd = compactStart + lineMap.compact.length();
        if (compactStart < 0 || compactEnd > paraMap.originalOffsetsAfterCompactChars.length) return false;
        int originalStart = paraMap.originalOffsetsAfterCompactChars[compactStart] - 1;
        int originalEnd = paraMap.originalOffsetsAfterCompactChars[compactEnd - 1];
        if (originalStart < 0 || originalEnd <= originalStart) return false;
        return shadeParagraphTextRange(para, originalStart, originalEnd, color);
    }

    private static String trimCompactNeedle(String compact) {
        if (compact == null) return "";
        String result = compact;
        while (result.length() > 2 && isDecorativeLinePrefix(result.charAt(0))) {
            result = result.substring(1);
        }
        return result;
    }

    private static boolean isDecorativeLinePrefix(char ch) {
        return ch == '\uFFFC' || ch == '•' || ch == '●' || ch == '·';
    }

    private static final class TextOffsetMap {
        final String compact;
        final int[] originalOffsetsAfterCompactChars;

        TextOffsetMap(String compact, int[] originalOffsetsAfterCompactChars) {
            this.compact = compact;
            this.originalOffsetsAfterCompactChars = originalOffsetsAfterCompactChars;
        }
    }

    private static TextOffsetMap buildParagraphCompactTextMap(ASTParagraph para) {
        StringBuilder original = new StringBuilder();
        if (para != null && para.items() != null) {
            for (ASTInlineItem item : para.items()) {
                if (item instanceof ASTTextRun) {
                    String text = ((ASTTextRun) item).text();
                    if (text != null) original.append(text);
                }
            }
        }
        return buildCompactTextMap(original.toString());
    }

    private static TextOffsetMap buildCompactTextMap(String text) {
        if (text == null || text.isEmpty()) return new TextOffsetMap("", new int[0]);
        StringBuilder compact = new StringBuilder();
        List<Integer> offsets = new ArrayList<>();
        for (int i = 0; i < text.length(); i++) {
            char ch = text.charAt(i);
            if (isLayoutControlForEmphasisMatch(ch) || Character.isWhitespace(ch)) continue;
            compact.append(ch);
            offsets.add(i + 1);
        }
        int[] resultOffsets = new int[offsets.size()];
        for (int i = 0; i < offsets.size(); i++) resultOffsets[i] = offsets.get(i);
        return new TextOffsetMap(compact.toString(), resultOffsets);
    }

    private static boolean isLayoutControlForEmphasisMatch(char ch) {
        return ch == '\uFFFC'
                || ch == '\u0003'
                || ch == '\u0007'
                || ch == '\u0008'
                || ch == '\u0016'
                || ch == '\u0018'
                || ch == '\r'
                || ch == '\n';
    }

    private static boolean shadeParagraphTextRange(
            ASTParagraph para,
            int originalStart,
            int originalEnd,
            String color) {
        if (para == null || para.items() == null || originalEnd <= originalStart) return false;
        List<ASTInlineItem> rebuilt = new ArrayList<>();
        int cursor = 0;
        boolean changed = false;
        for (ASTInlineItem item : para.items()) {
            if (!(item instanceof ASTTextRun)) {
                rebuilt.add(item);
                continue;
            }
            ASTTextRun run = (ASTTextRun) item;
            String text = run.text();
            int len = text != null ? text.length() : 0;
            int runStart = cursor;
            int runEnd = cursor + len;
            cursor = runEnd;
            if (len == 0 || runEnd <= originalStart || runStart >= originalEnd) {
                rebuilt.add(item);
                continue;
            }

            int localStart = Math.max(0, originalStart - runStart);
            int localEnd = Math.min(len, originalEnd - runStart);
            if (localStart > 0) {
                rebuilt.add(copyTextRun(run, text.substring(0, localStart)));
            }
            ASTTextRun shaded = copyTextRun(run, text.substring(localStart, localEnd));
            shaded.shadeColor(color);
            rebuilt.add(shaded);
            if (localEnd < len) {
                rebuilt.add(copyTextRun(run, text.substring(localEnd)));
            }
            changed = true;
        }
        if (changed) {
            para.items().clear();
            para.items().addAll(rebuilt);
        }
        return changed;
    }

    private static ASTTextRun copyTextRun(ASTTextRun source, String text) {
        ASTTextRun copy = new ASTTextRun();
        copy.characterStyleRef(source.characterStyleRef());
        copy.text(text);
        copy.fontFamily(source.fontFamily());
        copy.fontStyle(source.fontStyle());
        copy.fontSizeHwpunits(source.fontSizeHwpunits());
        copy.textColor(source.textColor());
        copy.shadeColor(source.shadeColor());
        copy.letterSpacing(source.letterSpacing());
        copy.subscript(source.subscript());
        copy.superscript(source.superscript());
        copy.grepMathFont(source.grepMathFont());
        copy.underline(source.underline());
        copy.underlineColor(source.underlineColor());
        copy.underlineShape(source.underlineShape());
        copy.strikeThrough(source.strikeThrough());
        copy.horizontalScale(source.horizontalScale());
        copy.verticalScale(source.verticalScale());
        copy.baselineShift(source.baselineShift());
        copy.grepStyleApplied(source.grepStyleApplied());
        return copy;
    }

    private static boolean isAbsorbableTextEmphasisBackdrop(
            ResolvedBuildContext ctx,
            RenderedGroup rg,
            double[] rbRaw) {
        if (rbRaw == null || rbRaw.length < 4) return false;
        if (!isPageObject(rg)) return false;
        if (Boolean.FALSE.equals(rg.placementAllowed())) return false;
        if (!"indesign_png".equals(rg.visualOwner())) return false;
        if (Boolean.TRUE.equals(rg.containsText()) || Boolean.TRUE.equals(rg.containsEditableText())) return false;
        String reason = rg.reason();
        if (isEditableLabelShellReason(reason)) return false;
        if (!isTextEmphasisBackdropReason(reason)) return false;
        if (isLargePaperVectorShape(ctx, rg, rbRaw)) return false;

        double w = rbRaw[3] - rbRaw[1];
        double h = rbRaw[2] - rbRaw[0];
        if (w < 25.0 || h < 2.5 || h > 18.0 || w / h < 3.0) return false;

        TextLineMatch match = findBestTextLineMatch(ctx, rg, rbRaw);
        if (match == null || match.line == null || match.line.bounds() == null) return false;
        if (isShortOwnedLabelShell(ctx, rg, match.tf)) return false;
        double[] lb = match.line.bounds();
        double lineH = Math.max(0.1, lb[2] - lb[0]);
        double rawOverlap = overlapArea(rbRaw, lb);
        double[] scaledRb = new double[] {
                rbRaw[0] * ctx.scaleFactor,
                rbRaw[1] * ctx.scaleFactor,
                rbRaw[2] * ctx.scaleFactor,
                rbRaw[3] * ctx.scaleFactor
        };
        double scaledOverlap = overlapArea(scaledRb, lb);
        double comparableH = scaledOverlap > rawOverlap ? h * ctx.scaleFactor : h;
        if (comparableH > Math.max(6.5, lineH * 1.35)) return false;
        if (countMatchedLineOverlaps(ctx, rg, rbRaw, match.tf) > 1) return false;
        double overlapRatio = Math.max(rawOverlap, scaledOverlap)
                / Math.max(0.1, area(lb));
        if (overlapRatio < 0.18) return false;

        // A true underline is usually much thinner than the text line. Keep it in
        // the underline path rather than converting it to character shading.
        return h >= Math.max(2.5, lineH * 0.25);
    }

    private static boolean isLargePaperVectorShape(
            ResolvedBuildContext ctx,
            RenderedGroup rg,
            double[] bounds) {
        if (ctx == null || ctx.resolvedData == null || rg == null || bounds == null || bounds.length < 4) {
            return false;
        }
        if (!"vector_shape".equals(rg.reason())) return false;
        double w = bounds[3] - bounds[1];
        double h = bounds[2] - bounds[0];
        if (w < 30.0 || h < 9.0) return false;

        int[] sourceIds = rg.sourceObjectIds();
        if (sourceIds != null) {
            for (int sourceId : sourceIds) {
                ResolvedPageItem item = ctx.resolvedData.getPageItem(String.valueOf(sourceId));
                if (isPaperFilledVector(item)) {
                    return true;
                }
            }
        }
        return isPaperFilledVector(ctx.resolvedData.getPageItem(String.valueOf(rg.id())));
    }

    private static boolean isPaperFilledVector(ResolvedPageItem item) {
        if (item == null) return false;
        String type = item.type();
        if (!"Rectangle".equals(type) && !"Polygon".equals(type) && !"Oval".equals(type)) return false;
        return isPaperColor(item.fillColorName());
    }

    private static int countMatchedLineOverlaps(
            ResolvedBuildContext ctx,
            RenderedGroup rg,
            double[] rbRaw,
            ResolvedTextFrame tf) {
        if (ctx == null || rg == null || rbRaw == null || tf == null) return 0;
        List<ResolvedTextFrame.ComposedLine> lines = tf.composedLines();
        if (lines == null || lines.isEmpty()) return 0;
        double[] scaledRb = new double[] {
                rbRaw[0] * ctx.scaleFactor,
                rbRaw[1] * ctx.scaleFactor,
                rbRaw[2] * ctx.scaleFactor,
                rbRaw[3] * ctx.scaleFactor
        };
        int count = 0;
        for (ResolvedTextFrame.ComposedLine line : lines) {
            if (line == null || line.bounds() == null || line.bounds().length < 4) continue;
            double[] lb = line.bounds();
            double lineArea = area(lb);
            if (lineArea <= 0.0) continue;
            double overlap = Math.max(overlapArea(rbRaw, lb), overlapArea(scaledRb, lb));
            if (overlap / lineArea >= 0.12) {
                count++;
            }
        }
        return count;
    }

    private static boolean isTextEmphasisBackdropReason(String reason) {
        if (reason == null) return false;
        if (isEditableLabelShellReason(reason)) return false;
        String r = reason.toLowerCase();
        return r.contains("vector")
                || r.contains("decoration")
                || r.contains("text_hidden_shell")
                || r.contains("editable_textframe_visual_shell");
    }

    private static boolean isEditableLabelShellReason(String reason) {
        if (reason == null) return false;
        String r = reason.toLowerCase();
        return r.contains("visual_label_text_hidden_shell")
                || r.contains("editable_composite_text_hidden_shell")
                || r.contains("concept_label_shell");
    }

    private static boolean isShortOwnedLabelShell(
            ResolvedBuildContext ctx,
            RenderedGroup rg,
            ResolvedTextFrame matchedTf) {
        if (ctx == null || rg == null || matchedTf == null) return false;
        if (!"hwpx_tf".equals(rg.textOwner())) return false;
        String text = visibleText(matchedTf);
        if (text.isEmpty() || text.length() > 18) return false;

        String[] editableIds = rg.editableTextFrameIds();
        if (editableIds == null || editableIds.length == 0) return false;
        for (String id : editableIds) {
            if (id != null && id.equals(matchedTf.id())) {
                return true;
            }
        }
        return false;
    }

    private static final class TextLineMatch {
        ResolvedTextFrame tf;
        ResolvedTextFrame.ComposedLine line;
        double score;
    }

    private static TextLineMatch findBestTextLineMatch(
            ResolvedBuildContext ctx,
            RenderedGroup rg,
            double[] rbRaw) {
        if (ctx == null || ctx.resolvedData == null || rg == null || rbRaw == null) return null;
        TextLineMatch best = null;
        double[] scaledRb = new double[] {
                rbRaw[0] * ctx.scaleFactor,
                rbRaw[1] * ctx.scaleFactor,
                rbRaw[2] * ctx.scaleFactor,
                rbRaw[3] * ctx.scaleFactor
        };
        for (ResolvedTextFrame tf : ctx.resolvedData.textFrames()) {
            if (tf == null || tf.pageIndex() != rg.pageIndex()) continue;
            if (!hasSemanticText(tf)) continue;
            List<ResolvedTextFrame.ComposedLine> lines = tf.composedLines();
            if (lines == null || lines.isEmpty()) continue;
            for (ResolvedTextFrame.ComposedLine line : lines) {
                if (line == null || line.bounds() == null || line.bounds().length < 4) continue;
                double[] lb = line.bounds();
                double lineArea = area(lb);
                if (lineArea <= 0) continue;
                double rawOverlap = overlapArea(rbRaw, lb);
                double scaledOverlap = overlapArea(scaledRb, lb);
                double overlap = Math.max(rawOverlap, scaledOverlap);
                if (overlap <= 0) continue;
                double score = overlap / lineArea;
                if (best == null || score > best.score) {
                    best = new TextLineMatch();
                    best.tf = tf;
                    best.line = line;
                    best.score = score;
                }
            }
        }
        return best;
    }

    private static ASTParagraph findAstParagraphForLine(
            ResolvedBuildContext ctx,
            List<ASTSection> sections,
            int resolvedPageIndex,
            ResolvedTextFrame tf,
            ResolvedTextFrame.ComposedLine line) {
        int pageIdx = ctx.toSectionIndex.applyAsInt(resolvedPageIndex);
        if (pageIdx < 0 || pageIdx >= sections.size()) return null;
        ASTSection section = sections.get(pageIdx);
        if (section == null || section.blocks() == null) return null;

        String storyId = tf.storyId();
        String tfId = tf.id();
        int paraIndex = Math.max(0, line.paraIndex());
        ASTParagraph fallback = null;
        String lineCompact = buildCompactTextMap(line.text()).compact;

        // Best path: use the actual originating TextFrame block. Story IDs can
        // diverge between resolved fallback and IDML-loaded stories, but the
        // source frame id remains stable.
        if (tfId != null) {
            for (ASTBlock block : section.blocks()) {
                if (!(block instanceof ASTTextFrameBlock)) continue;
                ASTTextFrameBlock tfBlock = (ASTTextFrameBlock) block;
                String blockDomId = ParagraphTextHelpers.domIdFromSourceId(tfBlock.sourceId());
                if (!tfId.equals(blockDomId)) continue;
                List<ASTParagraph> paras = tfBlock.paragraphs();
                if (paras == null || paras.isEmpty()) continue;
                if (paraIndex < paras.size()
                        && paragraphContainsCompactLine(paras.get(paraIndex), lineCompact)) {
                    return paras.get(paraIndex);
                }
                for (ASTParagraph para : paras) {
                    if (paragraphContainsCompactLine(para, lineCompact)) {
                        return para;
                    }
                }
                if (lineCompact.isEmpty()) {
                    return paras.get(Math.min(paras.size() - 1, 0));
                }
            }
        }

        for (ASTBlock block : section.blocks()) {
            if (!(block instanceof ASTTextFrameBlock)) continue;
            ASTTextFrameBlock tfBlock = (ASTTextFrameBlock) block;
            if (storyId != null && !storyId.equals(tfBlock.storyId())) continue;
            List<ASTParagraph> paras = tfBlock.paragraphs();
            if (paras == null || paras.isEmpty()) continue;
            if (paraIndex < paras.size()
                    && paragraphContainsCompactLine(paras.get(paraIndex), lineCompact)) {
                return paras.get(paraIndex);
            }
            for (ASTParagraph para : paras) {
                if (paragraphContainsCompactLine(para, lineCompact)) {
                    return para;
                }
            }
            if (fallback == null) fallback = paras.get(Math.min(paras.size() - 1, 0));
        }
        if (fallback != null && lineCompact.isEmpty()) return fallback;

        // Last fallback: match by composed line text. This is intentionally
        // scoped to the same page/section so it cannot steal unrelated stories.
        if (lineCompact.isEmpty()) return null;
        for (ASTBlock block : section.blocks()) {
            if (!(block instanceof ASTTextFrameBlock)) continue;
            ASTTextFrameBlock tfBlock = (ASTTextFrameBlock) block;
            List<ASTParagraph> paras = tfBlock.paragraphs();
            if (paras == null || paras.isEmpty()) continue;
            for (ASTParagraph para : paras) {
                if (para == null) continue;
                String paraCompact = buildParagraphCompactTextMap(para).compact;
                if (compactParagraphContainsLine(paraCompact, lineCompact)) {
                    return para;
                }
            }
        }
        return null;
    }

    private static boolean paragraphContainsCompactLine(ASTParagraph para, String lineCompact) {
        if (para == null || lineCompact == null || lineCompact.isEmpty()) return false;
        String paraCompact = buildParagraphCompactTextMap(para).compact;
        return compactParagraphContainsLine(paraCompact, lineCompact);
    }

    private static boolean compactParagraphContainsLine(String paraCompact, String lineCompact) {
        if (paraCompact == null || lineCompact == null || paraCompact.isEmpty() || lineCompact.isEmpty()) {
            return false;
        }
        if (paraCompact.contains(lineCompact)) return true;
        String needle = trimCompactNeedle(lineCompact);
        return !needle.isEmpty() && paraCompact.contains(needle);
    }

    private static String inferEmphasisColorFromPng(byte[] png) {
        if (png == null || png.length == 0) return null;
        try {
            BufferedImage img = ImageIO.read(new java.io.ByteArrayInputStream(png));
            if (img == null) return null;
            long r = 0, g = 0, b = 0, count = 0;
            int stepX = Math.max(1, img.getWidth() / 160);
            int stepY = Math.max(1, img.getHeight() / 80);
            for (int y = 0; y < img.getHeight(); y += stepY) {
                for (int x = 0; x < img.getWidth(); x += stepX) {
                    int argb = img.getRGB(x, y);
                    int a = (argb >>> 24) & 0xff;
                    if (a < 12) continue;
                    int rr = (argb >>> 16) & 0xff;
                    int gg = (argb >>> 8) & 0xff;
                    int bb = argb & 0xff;
                    // Approximate transparent PNG compositing on white paper.
                    rr = (rr * a + 255 * (255 - a)) / 255;
                    gg = (gg * a + 255 * (255 - a)) / 255;
                    bb = (bb * a + 255 * (255 - a)) / 255;
                    if (rr > 248 && gg > 248 && bb > 248) continue;
                    r += rr;
                    g += gg;
                    b += bb;
                    count++;
                }
            }
            img.flush();
            if (count == 0) return null;
            return String.format("#%02X%02X%02X",
                    (int) Math.round((double) r / count),
                    (int) Math.round((double) g / count),
                    (int) Math.round((double) b / count));
        } catch (Exception ignored) {
            return null;
        }
    }

    private static boolean isTextFrameVisualShellReason(String reason) {
        if (reason == null) return false;
        return reason.contains("decoration")
                || reason.contains("text_hidden")
                || reason.contains("visual_shell")
                || reason.contains("textframe_visual_shell")
                || reason.contains("complex_graphic");
    }

    private static boolean isSimilarTextFrameVisualShell(double[] shellBounds, double[] tfBounds) {
        double tfArea = area(tfBounds);
        double shellArea = area(shellBounds);
        if (tfArea <= 0 || shellArea <= 0) return false;
        double areaRatio = shellArea / tfArea;
        if (areaRatio < TF_VISUAL_SHELL_MIN_AREA_RATIO || areaRatio > TF_VISUAL_SHELL_MAX_AREA_RATIO) {
            return false;
        }
        return overlapArea(shellBounds, tfBounds) / tfArea >= TF_VISUAL_SHELL_OVERLAP_MIN;
    }

    private static double area(double[] b) {
        if (b == null || b.length < 4) return 0.0;
        double w = b[3] - b[1];
        double h = b[2] - b[0];
        return w > 0 && h > 0 ? w * h : 0.0;
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

    private static double[] boundsWithTfInlineVisuals(
            ResolvedBuildContext ctx, RenderedGroup rg, double[] fallback) {
        if (!hasTfInlineVisuals(rg) || fallback == null || fallback.length < 4) return fallback;
        double[] union = new double[] { fallback[0], fallback[1], fallback[2], fallback[3] };
        for (int id : rg.tfInlineVisualIds()) {
            RenderedGroup child = findRenderedGroup(ctx, id);
            if (child == null || child.bounds() == null || child.bounds().length < 4) continue;
            double[] b = child.bounds();
            union[0] = Math.min(union[0], b[0]);
            union[1] = Math.min(union[1], b[1]);
            union[2] = Math.max(union[2], b[2]);
            union[3] = Math.max(union[3], b[3]);
        }
        double parentW = fallback[3] - fallback[1];
        double parentH = fallback[2] - fallback[0];
        double unionW = union[3] - union[1];
        double unionH = union[2] - union[0];
        if (parentW <= 0 || parentH <= 0 || unionW <= 0 || unionH <= 0) return fallback;
        double maxRatio = Math.max(unionW / parentW, unionH / parentH);
        if (maxRatio > TF_INLINE_VISUAL_UNION_MAX_RATIO) return fallback;
        return union;
    }

    private static BufferedImage compositeTfInlineVisuals(
            ResolvedBuildContext ctx, RenderedGroup parent, BufferedImage base) {
        if (ctx == null || parent == null || base == null || parent.bounds() == null
                || parent.bounds().length < 4 || !hasTfInlineVisuals(parent)) {
            return null;
        }
        double[] parentBounds = parent.bounds();
        double[] union = boundsWithTfInlineVisuals(ctx, parent, parentBounds);
        double unionW = union[3] - union[1];
        double unionH = union[2] - union[0];
        double parentW = parentBounds[3] - parentBounds[1];
        double parentH = parentBounds[2] - parentBounds[0];
        if (unionW <= 0 || unionH <= 0 || parentW <= 0 || parentH <= 0) return null;
        double maxRatio = Math.max(unionW / parentW, unionH / parentH);
        if (maxRatio > TF_INLINE_VISUAL_UNION_MAX_RATIO) return null;

        int canvasW = Math.max(1, (int) Math.round(base.getWidth() * unionW / parentW));
        int canvasH = Math.max(1, (int) Math.round(base.getHeight() * unionH / parentH));
        if ((long) canvasW * (long) canvasH > TF_INLINE_VISUAL_MAX_CANVAS_PIXELS) return null;
        BufferedImage canvas = new BufferedImage(canvasW, canvasH, BufferedImage.TYPE_INT_ARGB);
        drawAtBounds(canvas, base, parentBounds, union);

        for (int id : parent.tfInlineVisualIds()) {
            RenderedGroup child = findRenderedGroup(ctx, id);
            if (child == null || child.file() == null || child.bounds() == null
                    || child.bounds().length < 4) {
                continue;
            }
            try {
                File childFile = new File(ctx.basePath, child.file());
                if (!childFile.exists()) continue;
                BufferedImage childImg = ImageIO.read(childFile);
                if (childImg == null) continue;
                drawAtBounds(canvas, childImg, child.bounds(), union);
                childImg.flush();
            } catch (Exception ignored) {
            }
        }
        return canvas;
    }

    private static void drawAtBounds(
            BufferedImage canvas, BufferedImage image, double[] bounds, double[] union) {
        int canvasW = canvas.getWidth();
        int canvasH = canvas.getHeight();
        double unionW = union[3] - union[1];
        double unionH = union[2] - union[0];
        int x = (int) Math.round((bounds[1] - union[1]) / unionW * canvasW);
        int y = (int) Math.round((bounds[0] - union[0]) / unionH * canvasH);
        int w = Math.max(1, (int) Math.round((bounds[3] - bounds[1]) / unionW * canvasW));
        int h = Math.max(1, (int) Math.round((bounds[2] - bounds[0]) / unionH * canvasH));
        int x0 = Math.max(0, x);
        int y0 = Math.max(0, y);
        int x1 = Math.min(canvasW, x + w);
        int y1 = Math.min(canvasH, y + h);
        if (x0 >= x1 || y0 >= y1) return;
        for (int dy = y0; dy < y1; dy++) {
            int sy = Math.max(0, Math.min(image.getHeight() - 1,
                    (int) Math.floor((dy - y) * (double) image.getHeight() / h)));
            for (int dx = x0; dx < x1; dx++) {
                int sx = Math.max(0, Math.min(image.getWidth() - 1,
                        (int) Math.floor((dx - x) * (double) image.getWidth() / w)));
                int src = image.getRGB(sx, sy);
                int sa = (src >>> 24) & 0xFF;
                if (sa == 0) continue;
                if (sa == 255) {
                    canvas.setRGB(dx, dy, src);
                    continue;
                }
                int dst = canvas.getRGB(dx, dy);
                int da = (dst >>> 24) & 0xFF;
                int outA = sa + da * (255 - sa) / 255;
                if (outA == 0) {
                    canvas.setRGB(dx, dy, 0);
                    continue;
                }
                int sr = (src >> 16) & 0xFF;
                int sg = (src >> 8) & 0xFF;
                int sb = src & 0xFF;
                int dr = (dst >> 16) & 0xFF;
                int dg = (dst >> 8) & 0xFF;
                int db = dst & 0xFF;
                int outR = (sr * sa + dr * da * (255 - sa) / 255) / outA;
                int outG = (sg * sa + dg * da * (255 - sa) / 255) / outA;
                int outB = (sb * sa + db * da * (255 - sa) / 255) / outA;
                canvas.setRGB(dx, dy, (outA << 24) | (outR << 16) | (outG << 8) | outB);
            }
        }
    }

    private static RenderedGroup findRenderedGroup(ResolvedBuildContext ctx, int id) {
        if (ctx == null || ctx.resolvedData == null
                || ctx.resolvedData.allRenderedFloatingItems() == null) {
            return null;
        }
        for (RenderedGroup rg : ctx.resolvedData.allRenderedFloatingItems()) {
            if (rg != null && rg.id() == id) return rg;
        }
        return null;
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

    private static byte[] encodePng(BufferedImage image) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(image, "png", baos);
        return baos.toByteArray();
    }
}
