package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.phase6;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTFigure;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTSection;
import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.CoordinateConverter;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ResolvedBuildContext;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.RenderedGroup;

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

        // Pass 1b: page_object 아이템의 childIds/childImageIds 중 부모와 같은 페이지의 자식만 수집
        // 다른 페이지 자식은 그룹 PNG에 포함되지 않으므로 개별 렌더링 필요
        Set<Integer> childOfGroup = new HashSet<>();
        for (RenderedGroup rg : floatingItems) {
            if (!isPageObject(rg)) continue;
            int parentPage = rg.pageIndex();
            if (rg.childIds() != null) {
                for (int cid : rg.childIds()) {
                    Integer cp = idToPage.get(cid);
                    if (cp != null && cp == parentPage) childOfGroup.add(cid);
                }
            }
            if (rg.childImageIds() != null) {
                for (int cid : rg.childImageIds()) {
                    Integer cp = idToPage.get(cid);
                    if (cp != null && cp == parentPage) childOfGroup.add(cid);
                }
            }
        }

        Set<String> processedKeys = new HashSet<>();

        for (RenderedGroup rg : floatingItems) {
            if (!isPageObject(rg)) continue;
            // inline_object로 이미 처리된 ID는 Phase 3가 인라인으로 배치 → 중복 방지
            if (ctx.resolvedData.isInlineObjectId(rg.id())) continue;
            // 상위 그룹 PNG의 자식 항목은 그룹 PNG에 이미 포함됨 → 개별 렌더링 skip
            if (childOfGroup.contains(rg.id())) continue;
            // 같은 (id, pageIndex) 쌍이 중복 추출된 경우만 스킵
            // (master page item은 동일 id가 여러 page에 나타날 수 있으므로 pageIndex 포함)
            if (!processedKeys.add(rg.id() + ":" + rg.pageIndex())) continue;

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
            fig.zOrder(Math.max(rg.zOrder(), 0));
            fig.fromGroup(true);   // IN_FRONT_OF_TEXT — z-order로 텍스트TF와의 순서 결정
            fig.sourceId("page_obj_" + rg.id());

            // BEHIND_TEXT 항목은 XML 순서상 앞에 올수록 더 아래 레이어 → addBlockAtFront
            sections.get(pageIdx).addBlockAtFront(fig);

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
                            fig2.zOrder(Math.max(rg.zOrder(), 0));
                            fig2.fromGroup(true);
                            fig2.sourceId("page_obj_" + rg.id() + "_ov");
                            sections.get(nextPageIdx).addBlockAtFront(fig2);
                        }
                    }
                }
            }
        }
    }

    private static boolean isPageObject(RenderedGroup rg) {
        String t = rg.itemType();
        if ("page_object".equals(t)) return true;
        if (t != null) return false;
        // 하위 호환: 구 캐시는 itemType 없음 → 파일명으로 추론
        String f = rg.file();
        return f != null && (f.contains("img_") || f.contains("deco_")
                || f.contains("shape_") || f.contains("graphic_") || f.contains("master_"));
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
