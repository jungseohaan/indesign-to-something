package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.phase6;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTFigure;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTSection;
import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.CoordinateConverter;
import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.VisualSourcePolicy;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.FrameDisposition;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ResolvedBuildContext;
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

    public static void inject(ResolvedBuildContext ctx, List<ASTSection> sections) {
        if (ctx.resolvedData == null) return;
        List<RenderedGroup> floatingItems = ctx.resolvedData.allRenderedFloatingItems();
        if (floatingItems == null || floatingItems.isEmpty()) return;

        Set<Integer> editableLabelShellIds = collectEditableLabelShells(ctx, floatingItems);

        // Pass 1: id → pageIndex 맵 구성 (자식의 페이지 판별용)
        Map<Integer, Integer> idToPage = new HashMap<>();
        for (RenderedGroup rg : floatingItems) {
            idToPage.put(rg.id(), rg.pageIndex());
        }

        // Pass 1b: page_object 아이템의 모든 자식(childIds/childImageIds)을 childOfGroup에 수집.
        // 부모 그룹 PNG에 자식 내용이 이미 포함되어 있으므로 자식을 독립 배치하지 않는다.
        // 같은 페이지의 자식만 수집 (다른 페이지 자식은 독립 배치 허용).
        Set<Integer> childOfGroup = new HashSet<>();
        for (RenderedGroup rg : floatingItems) {
            if (!canSuppressChildren(ctx, sections, rg, editableLabelShellIds)) continue;
            int parentPage = rg.pageIndex();
            if (rg.childIds() != null) {
                for (int cid : rg.childIds()) {
                    if (editableLabelShellIds.contains(cid)) continue;
                    Integer cp = idToPage.get(cid);
                    if (cp != null && cp == parentPage)
                        childOfGroup.add(cid);
                }
            }
            if (rg.childImageIds() != null) {
                for (int cid : rg.childImageIds()) {
                    if (editableLabelShellIds.contains(cid)) continue;
                    Integer cp = idToPage.get(cid);
                    if (cp != null && cp == parentPage)
                        childOfGroup.add(cid);
                }
            }
        }

        // childOfGroup 항목은 Phase 7c도 배치하지 않도록 phase6PlacedIds에 선제 등록
        ctx.phase6PlacedIds.addAll(childOfGroup);

        Set<Integer> coveredByInlineObjects = collectInlineObjectCoverage(floatingItems);
        ctx.phase6PlacedIds.addAll(coveredByInlineObjects);

        Set<String> processedKeys = new HashSet<>();
        Set<String> processedDomPageKeys = new HashSet<>();

        for (RenderedGroup rg : floatingItems) {
            if (!isPageObject(rg)) {
                continue;
            }
            if (shouldDecomposeToEditableLabelShell(rg, editableLabelShellIds)) {
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
            // 상위 그룹 PNG의 자식 항목은 그룹 PNG에 이미 포함됨 → 개별 렌더링 skip
            if (childOfGroup.contains(rg.id())) {
                ctx.recordRenderedDecision(rg, "Phase6", "SKIP_CHILD_OF_GROUP", "covered by renderable parent group");
                continue;
            }
            if (isCoveredByInlineObject(rg, coveredByInlineObjects)) {
                ctx.recordRenderedDecision(rg, "Phase6", "SKIP_INLINE_COVERAGE", "covered by inline_object ownership");
                continue;
            }
            // 같은 ID가 inline_object로도 등록된 경우: Phase 3가 인라인으로 처리하므로 floating 중복 금지.
            if (ctx.resolvedData.isInlineObjectId(rg.id())) {
                ctx.recordRenderedDecision(rg, "Phase6", "SKIP_INLINE_OBJECT", "inline object handled by story flow");
                continue;
            }
            if (ctx.resolvedData.shouldKeepVisualLabelTextEditable(rg)) {
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
            if (shouldSkipByChildPolicy(ctx, rg)) {
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

            byte[] imageData = loadPng(ctx, rg);
            if (imageData == null) {
                ctx.recordRenderedDecision(rg, "Phase6", "SKIP_PNG_LOAD_FAILED", "png file missing or unreadable");
                continue;
            }

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

            int pixelW = 0, pixelH = 0;
            Double stripCropLeftOverride = null;
            Double stripCropWidthOverride = null;
            boolean pageAnchoredStripCrop = false;
            try {
                BufferedImage img = loadImageForPlacement(ctx, rg);
                if (img != null && shouldCompositeTfInlineVisuals(rg)) {
                    imageData = encodePng(img);
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
                    if (shouldCropOwnedTextFrameShellToAlpha(rg)) {
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
            boolean isTextFrameVisualShell = "editable_textframe_visual_shell".equals(rg.reason());
            boolean coversPageByArea = pageWidthMm < 1e9 && pageHeightMm < 1e9
                    && (rawRight - rawLeft) * (rawBottom - rawTop)
                        >= 0.3 * pageWidthMm * pageHeightMm;
            boolean isFullPageBg = rawLeft <= 10.0
                    && rawTop <= 10.0
                    && (rawBottom >= pageHeightMm - 1.0 || coversPageByArea);
            boolean isBackgroundLike = isFullPageBg || (isTextFrameVisualShell && coversPageByArea);
            int resolvedZ = isBackgroundLike
                    ? 0
                    : effectiveZOrder(ctx, rg);
            if (stripCropLeftOverride != null && stripCropWidthOverride != null) {
                resolvedZ = Math.max(resolvedZ, 900);
            }
            fig.zOrder(resolvedZ);
            fig.fromGroup(!isBackgroundLike);
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
                    byte[] overflowData = loadPng(ctx, rg);
                    if (overflowData != null) {
                        int ovPixelW = 0, ovPixelH = 0;
                        try {
                            File pngFile2 = new File(ctx.basePath, rg.file());
                            BufferedImage ovImg = ImageIO.read(pngFile2);
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

    private static boolean canSuppressChildren(
            ResolvedBuildContext ctx, List<ASTSection> sections, RenderedGroup rg,
            Set<Integer> editableLabelShellIds) {
        if (!isPageObject(rg)) return false;
        if (ctx.resolvedData.isInlineObjectId(rg.id())) return false;
        if (ctx.resolvedData.shouldKeepVisualLabelTextEditable(rg)) return false;
        if (shouldDecomposeToEditableLabelShell(rg, editableLabelShellIds)) return false;
        if (rg.shouldSkipByOwnership()) return false;
        if (shouldSkipByChildPolicy(ctx, rg)) return false;
        if (rg.bounds() == null || rg.bounds().length < 4) return false;
        int pageIdx = ctx.toSectionIndex.applyAsInt(rg.pageIndex());
        if (pageIdx < 0 || pageIdx >= sections.size()) return false;
        return hasRenderablePng(ctx, rg);
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
            RenderedGroup rg, Set<Integer> editableLabelShellIds) {
        if (rg == null || editableLabelShellIds == null || editableLabelShellIds.isEmpty()) return false;
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
        String reason = rg.reason();
        return "visual_label_indesign_png".equals(reason)
                || (reason != null && reason.contains("text_hidden"));
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

    private static Set<Integer> collectInlineObjectCoverage(List<RenderedGroup> floatingItems) {
        Set<Integer> coveredIds = new HashSet<>();
        if (floatingItems == null || floatingItems.isEmpty()) return coveredIds;

        boolean changed;
        do {
            int before = coveredIds.size();
            for (RenderedGroup rg : floatingItems) {
                if (rg == null) continue;
                if ("inline_object".equals(rg.itemType()) || "inline_object".equals(rg.type())
                        || coveredIds.contains(rg.id())) {
                    addCoverageIds(rg, coveredIds);
                }
            }
            changed = coveredIds.size() != before;
        } while (changed);
        return coveredIds;
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
        if (rg.file() == null) return null;
        try {
            File pngFile = new File(ctx.basePath, rg.file());
            if (!pngFile.exists()) return null;
            return java.nio.file.Files.readAllBytes(pngFile.toPath());
        } catch (Exception e) {
            System.err.println("[BackgroundInjector] PNG 로드 실패: " + e.getMessage());
            return null;
        }
    }

    private static BufferedImage loadImageForPlacement(ResolvedBuildContext ctx, RenderedGroup rg) {
        if (ctx == null || rg == null || rg.file() == null) return null;
        try {
            File pngFile = new File(ctx.basePath, rg.file());
            if (!pngFile.exists()) return null;
            BufferedImage base = ImageIO.read(pngFile);
            if (base == null || !shouldCompositeTfInlineVisuals(rg)) return base;
            BufferedImage merged = compositeTfInlineVisuals(ctx, rg, base);
            return merged != null ? merged : base;
        } catch (Exception e) {
            System.err.println("[BackgroundInjector] PNG 합성 실패: " + e.getMessage());
            return null;
        }
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

    private static int effectiveZOrder(ResolvedBuildContext ctx, RenderedGroup rg) {
        if (rg == null) return 5;
        int ownedShellZ = ownedTextFrameShellZOrder(ctx, rg);
        if (ownedShellZ >= 0) return ownedShellZ;
        int inferredShellZ = inferredTextFrameVisualShellZOrder(ctx, rg);
        if (inferredShellZ >= 0) return inferredShellZ;
        int titleLabelZ = titleLabelBackgroundZOrder(ctx, rg);
        if (titleLabelZ >= 0) return titleLabelZ;
        if (rg.zOrderKnown()) return rg.zOrder();
        return Math.max(rg.zOrder(), 5);
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

    private static int inferredTextFrameVisualShellZOrder(ResolvedBuildContext ctx, RenderedGroup rg) {
        if (ctx == null || ctx.resolvedData == null || rg == null) return -1;
        if (!isPageObject(rg)) return -1;
        if (Boolean.FALSE.equals(rg.placementAllowed())) return -1;
        if (!"indesign_png".equals(rg.visualOwner())) return -1;
        if (Boolean.TRUE.equals(rg.containsText()) || Boolean.TRUE.equals(rg.containsEditableText())) return -1;
        if (!isTextFrameVisualShellReason(rg.reason())) return -1;
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
            if (isSimilarTextFrameVisualShell(rbRaw, tb) || isSimilarTextFrameVisualShell(scaledRb, tb)) {
                return Math.max(1, tf.zOrder() - 1);
            }
        }
        return -1;
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

    private static boolean hasSemanticText(ResolvedTextFrame tf) {
        String text = tf.frameVisibleText();
        if (text == null) return false;
        return text.replace("\uFFFC", "")
                .replace("\u0016", "")
                .replace("\u0018", "")
                .trim()
                .length() > 0;
    }

    private static boolean hasShortSemanticText(ResolvedTextFrame tf) {
        String text = tf.frameVisibleText();
        if (text == null) return false;
        text = text.replace("\uFFFC", "")
                .replace("\u0016", "")
                .replace("\u0018", "")
                .trim();
        if (text.isEmpty() || text.length() > 60) return false;
        return !text.contains("\n") && !text.contains("\r");
    }

    private static boolean isTextFrameVisualShellReason(String reason) {
        if (reason == null) return false;
        return reason.contains("decoration")
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

    private static byte[] encodePng(BufferedImage image) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(image, "png", baos);
        return baos.toByteArray();
    }
}
