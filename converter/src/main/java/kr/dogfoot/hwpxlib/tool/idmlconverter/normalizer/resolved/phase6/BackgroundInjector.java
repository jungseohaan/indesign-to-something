package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.phase6;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTFigure;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTSection;
import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.CoordinateConverter;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ResolvedBuildContext;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.RenderedGroup;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedPageItem;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
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

    public static void inject(ResolvedBuildContext ctx, List<ASTSection> sections) {
        if (ctx.resolvedData == null) return;
        List<RenderedGroup> floatingItems = ctx.resolvedData.allRenderedFloatingItems();
        if (floatingItems == null || floatingItems.isEmpty()) return;

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
            if (!isPageObject(rg)) continue;
            int parentPage = rg.pageIndex();
            if (rg.childIds() != null) {
                for (int cid : rg.childIds()) {
                    Integer cp = idToPage.get(cid);
                    if (cp != null && cp == parentPage)
                        childOfGroup.add(cid);
                }
            }
            if (rg.childImageIds() != null) {
                for (int cid : rg.childImageIds()) {
                    Integer cp = idToPage.get(cid);
                    if (cp != null && cp == parentPage)
                        childOfGroup.add(cid);
                }
            }
        }

        // childOfGroup 항목은 Phase 7c도 배치하지 않도록 phase6PlacedIds에 선제 등록
        ctx.phase6PlacedIds.addAll(childOfGroup);

        Set<String> processedKeys = new HashSet<>();

        for (RenderedGroup rg : floatingItems) {
            if (!isPageObject(rg)) continue;
            // 상위 그룹 PNG의 자식 항목은 그룹 PNG에 이미 포함됨 → 개별 렌더링 skip
            if (childOfGroup.contains(rg.id())) continue;
            // 같은 ID가 inline_object로도 등록된 경우: Phase 3가 인라인으로 처리하므로 floating 중복 금지.
            if (ctx.resolvedData.isInlineObjectId(rg.id())) continue;
            // childIds 검사
            if (rg.childIds() != null && rg.childIds().length > 0) {
                boolean allChildrenAreEditableTf = true;
                boolean anyChildIsInlineObject = false;
                for (int cid : rg.childIds()) {
                    if (ctx.resolvedData.isInlineObjectId(cid)) {
                        anyChildIsInlineObject = true;
                        break;
                    }
                    if (!ctx.resolvedData.isEditableTextFrame(String.valueOf(cid))) {
                        allChildrenAreEditableTf = false;
                    }
                }
                // 자식 중 inline_object가 있으면 → Phase 3가 인라인 처리 → floating 중복 금지
                if (anyChildIsInlineObject) {
                    ctx.phase6PlacedIds.add(rg.id());
                    continue;
                }
                // 자식 모두가 ETF이면 → 순수 텍스트 컨테이너 → floating 불필요
                if (allChildrenAreEditableTf) {
                    ctx.phase6PlacedIds.add(rg.id());
                    continue;
                }
            }
            // 같은 파일이 중복 추출된 경우만 스킵 (id가 같아도 deco/graphic 등 파일이 다르면 둘 다 배치)
            if (!processedKeys.add(rg.file() != null ? rg.file() : rg.id() + ":" + rg.pageIndex())) continue;

            int pageIdx = ctx.toSectionIndex.applyAsInt(rg.pageIndex());
            if (pageIdx < 0 || pageIdx >= sections.size()) continue;

            double[] bounds = rg.bounds();
            if (bounds == null || bounds.length < 4) continue;

            byte[] imageData = loadPng(ctx, rg);
            if (imageData == null) continue;

            // bounds: [top, left, bottom, right] in document units (mm)
            double rawLeft = bounds[1], rawTop = bounds[0];
            double rawRight = bounds[3], rawBottom = bounds[2];
            double fullW = rawRight - rawLeft;
            double fullH = rawBottom - rawTop;

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
            if (visLeft >= visRight || visTop >= visBottom) continue;

            int pixelW = 0, pixelH = 0;
            try {
                File pngFile = new File(ctx.basePath, rg.file());
                BufferedImage img = ImageIO.read(pngFile);
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
                    boolean needsCrop = fullW > 1.0 && fullH > 1.0
                            && (visLeft > rawLeft + 0.5 || visRight < rawRight - 0.5
                                || visTop > rawTop + 0.5 || visBottom < rawBottom - 0.5);
                    if (needsCrop) {
                        int pxX = (int) Math.round((visLeft - rawLeft) / fullW * img.getWidth());
                        int pxY = (int) Math.round((visTop - rawTop) / fullH * img.getHeight());
                        int pxW = (int) Math.round((visRight - rawLeft) / fullW * img.getWidth()) - pxX;
                        int pxH = (int) Math.round((visBottom - rawTop) / fullH * img.getHeight()) - pxY;
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

            long x = CoordinateConverter.pointsToHwpunits(visLeft * ctx.scaleFactor);
            long y = CoordinateConverter.pointsToHwpunits(visTop * ctx.scaleFactor);
            long w = CoordinateConverter.pointsToHwpunits((visRight - visLeft) * ctx.scaleFactor);
            long h = CoordinateConverter.pointsToHwpunits((visBottom - visTop) * ctx.scaleFactor);

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
            boolean coversPageByArea = pageWidthMm < 1e9 && pageHeightMm < 1e9
                    && (rawRight - rawLeft) * (rawBottom - rawTop)
                        >= 0.3 * pageWidthMm * pageHeightMm;
            boolean isFullPageBg = rg.zOrder() <= 0
                    && rawLeft <= 10.0
                    && rawTop <= 10.0
                    && (rawBottom >= pageHeightMm - 1.0 || coversPageByArea);
            fig.zOrder(isFullPageBg ? 0 : Math.max(rg.zOrder(), 5));
            fig.fromGroup(true);   // IN_FRONT_OF_TEXT — z-order로 텍스트TF와의 순서 결정
            fig.sourceId("page_obj_" + rg.id());

            // BEHIND_TEXT 항목은 XML 순서상 앞에 올수록 더 아래 레이어 → addBlockAtFront
            sections.get(pageIdx).addBlockAtFront(fig);
            ctx.phase6PlacedIds.add(rg.id());

            // 스프레드를 가로질러 다음 페이지로 넘치는 경우: 우측 반을 다음 페이지에 별도 배치
            boolean overflowsRight = rawRight > pageWidthMm + 10.0 && pageIdx + 1 < sections.size();
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
                            fig2.fromGroup(true);
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
}
