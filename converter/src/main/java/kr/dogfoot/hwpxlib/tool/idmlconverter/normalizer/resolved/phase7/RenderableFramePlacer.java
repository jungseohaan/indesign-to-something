package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.phase7;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTFigure;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTSection;
import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.CoordinateConverter;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.FrameDisposition;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ResolvedBuildContext;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.RenderedGroup;
import java.io.File;
import java.util.List;

/**
 * Phase 7: page_object PNG를 ASTFigure로 배치 (스프레드 걸침 오버플로우 포함).
 */
public final class RenderableFramePlacer {

    private RenderableFramePlacer() {}

    private static int[] clamp(int x, int y, int w, int h, int imgW, int imgH) {
        x = Math.max(0, Math.min(x, imgW - 1));
        y = Math.max(0, Math.min(y, imgH - 1));
        w = Math.max(1, Math.min(imgW - x, w));
        h = Math.max(1, Math.min(imgH - y, h));
        return new int[]{x, y, w, h};
    }

    public static void place(ResolvedBuildContext ctx, List<ASTSection> sections) {
        if (ctx.basePath == null) return;
        if (ctx.resolvedData == null) return;
        int count = 0;
        // 같은 PNG 파일을 여러 ID로 등록한 경우 중복 배치 방지 (페이지+파일 단위)
        java.util.Set<String> placedKeys = new java.util.HashSet<>();

        // Phase 7c: page_object 항목 (스프레드 걸침 배경/장식 PNG) 배치
        // 주 페이지 가시 영역 + 좌/우 오버플로우를 인접 페이지에 크롭해서 배치
        for (RenderedGroup rg3 : ctx.resolvedData.allRenderedFloatingItems()) {
            if (!"page_object".equals(rg3.itemType())) {
                continue;
            }
            if (rg3.file() == null) {
                ctx.recordRenderedDecision(rg3, "Phase7", "SKIP_NO_FILE", "rendered item has no file");
                continue;
            }
            if (ctx.isRenderedDisposed(rg3.id(), FrameDisposition.TEXT_BLOCK_PLACED)) {
                ctx.recordRenderedDecision(rg3, "Phase7", "SKIP_RENDERED_DISPOSED", "rendered item already handled");
                continue;
            }
            // Phase 6(BackgroundInjector)이 이미 배치한 항목은 중복 배치 방지
            if (ctx.phase6PlacedIds.contains(rg3.id())) {
                ctx.recordRenderedDecision(rg3, "Phase7", "SKIP_PHASE6_HANDLED", "Phase6 already placed or suppressed this id");
                continue;
            }
            // inline_object로도 등록된 ID는 Phase 3이 인라인으로 처리 → 플로팅 중복 방지
            if (ctx.resolvedData.isInlineObjectId(rg3.id())) {
                ctx.recordRenderedDecision(rg3, "Phase7", "SKIP_INLINE_OBJECT", "inline object handled by story flow");
                continue;
            }
            if (rg3.shouldSkipByOwnership()) {
                ctx.recordRenderedDecision(rg3, "Phase7", "SKIP_OWNERSHIP", "render ownership says text is not hidden");
                continue;
            }
            // 자식 TF가 editableTextFrame인 그룹은 기본적으로 Phase 3 텍스트와 중복된다.
            // extractor가 텍스트를 숨긴 visual-only PNG라고 명시한 경우는 배치한다.
            if (rg3.childIds() != null) {
                boolean hasEditableTfChild = false;
                for (int childId : rg3.childIds()) {
                    if (ctx.resolvedData.isEditableTextFrame(String.valueOf(childId))) {
                        hasEditableTfChild = true;
                        break;
                    }
                }
                if (hasEditableTfChild && !rg3.hasEditableTextHiddenFromPng()) {
                    ctx.recordRenderedDecision(rg3, "Phase7", "SKIP_EDITABLE_TEXT_CHILD", "editable child text not hidden from png");
                    continue;
                }
            }
            String dedupKey3 = rg3.pageIndex() + "|" + rg3.file();
            if (!placedKeys.add(dedupKey3)) {
                ctx.recordRenderedDecision(rg3, "Phase7", "SKIP_DUPLICATE_FILE_PAGE", "same page/file already processed");
                continue;
            }

            File pngFile3 = new File(ctx.basePath, rg3.file());
            if (!pngFile3.exists()) {
                ctx.recordRenderedDecision(rg3, "Phase7", "SKIP_PNG_MISSING", "png file does not exist");
                continue;
            }

            int secIdx3 = ctx.toSectionIndex.applyAsInt(rg3.pageIndex());
            if (secIdx3 < 0 || secIdx3 >= sections.size()) {
                ctx.recordRenderedDecision(rg3, "Phase7", "SKIP_NO_SECTION", "pageIndex not mapped to section");
                continue;
            }

            try {
                double[] bounds3 = rg3.bounds();
                if (bounds3 == null || bounds3.length < 4) {
                    ctx.recordRenderedDecision(rg3, "Phase7", "SKIP_NO_BOUNDS", "rendered item has no bounds");
                    continue;
                }

                // renderedFloatingItems(page_object) bounds: page-relative mm → sf 곱해 page-relative pt
                double sf3 = ctx.scaleFactor;
                double pageWidthPt3 = 1e9, pageHeightPt3 = 1e9;
                double currPageLeftSpread3 = 0;
                if (ctx.resolvedData.pages() != null
                        && rg3.pageIndex() >= 0
                        && rg3.pageIndex() < ctx.resolvedData.pages().size()) {
                    double[] pgB3 = ctx.resolvedData.pages().get(rg3.pageIndex()).bounds();
                    if (pgB3 != null && pgB3.length >= 4) {
                        pageWidthPt3      = pgB3[3] - pgB3[1];
                        pageHeightPt3     = pgB3[2] - pgB3[0];
                        currPageLeftSpread3 = pgB3[1];
                    }
                }
                double rawLeft3   = bounds3[1] * sf3;
                double rawTop3    = bounds3[0] * sf3;
                double rawRight3  = bounds3[3] * sf3;
                double rawBottom3 = bounds3[2] * sf3;
                double fullW3 = rawRight3 - rawLeft3;
                double fullH3 = rawBottom3 - rawTop3;
                if (fullW3 <= 0 || fullH3 <= 0) {
                    ctx.recordRenderedDecision(rg3, "Phase7", "SKIP_INVALID_BOUNDS", "bounds width or height <= 0");
                    continue;
                }

                java.awt.image.BufferedImage origImg3 = javax.imageio.ImageIO.read(pngFile3);
                if (origImg3 == null || origImg3.getWidth() <= 2) {
                    ctx.recordRenderedDecision(rg3, "Phase7", "SKIP_INVALID_PNG", "png decode failed or too small");
                    continue;
                }


                // 주 페이지 가시 영역 크롭
                double visLeft3   = Math.max(0.0, rawLeft3);
                double visTop3    = Math.max(0.0, rawTop3);
                double visRight3  = Math.min(rawRight3, pageWidthPt3);
                double visBottom3 = Math.min(rawBottom3, pageHeightPt3);

                if (visLeft3 < visRight3 && visTop3 < visBottom3) {
                    int pxX3 = (int) Math.round((visLeft3 - rawLeft3) / fullW3 * origImg3.getWidth());
                    int pxY3 = (int) Math.round((visTop3 - rawTop3) / fullH3 * origImg3.getHeight());
                    int pxW3 = (int) Math.round((visRight3 - rawLeft3) / fullW3 * origImg3.getWidth()) - pxX3;
                    int pxH3 = (int) Math.round((visBottom3 - rawTop3) / fullH3 * origImg3.getHeight()) - pxY3;
                    int[] c3 = clamp(pxX3, pxY3, pxW3, pxH3, origImg3.getWidth(), origImg3.getHeight());
                    pxX3 = c3[0]; pxY3 = c3[1]; pxW3 = c3[2]; pxH3 = c3[3];
                    java.awt.image.BufferedImage img3 = origImg3.getSubimage(pxX3, pxY3, pxW3, pxH3);
                    java.io.ByteArrayOutputStream baos3 = new java.io.ByteArrayOutputStream();
                    javax.imageio.ImageIO.write(img3, "png", baos3);
                    ASTFigure fig3 = new ASTFigure();
                    fig3.sourceId("page_object_" + rg3.id());
                    fig3.x(CoordinateConverter.pointsToHwpunits(visLeft3));
                    fig3.y(CoordinateConverter.pointsToHwpunits(visTop3));
                    fig3.width(CoordinateConverter.pointsToHwpunits(visRight3 - visLeft3));
                    fig3.height(CoordinateConverter.pointsToHwpunits(visBottom3 - visTop3));
                    fig3.imageData(baos3.toByteArray());
                    fig3.imageFormat("png");
                    fig3.pixelWidth(img3.getWidth());
                    fig3.pixelHeight(img3.getHeight());
                    boolean coversPageByArea3 = pageWidthPt3 < 1e9 && pageHeightPt3 < 1e9
                            && (rawRight3 - rawLeft3) * (rawBottom3 - rawTop3)
                                >= 0.3 * pageWidthPt3 * pageHeightPt3;
                    boolean isPageCoveringBg3 = rawLeft3 <= 10.0 * sf3
                            && rawTop3 <= 10.0 * sf3
                            && (rawBottom3 >= pageHeightPt3 - sf3 || coversPageByArea3);
                    boolean isBackgroundLike3 = isPageCoveringBg3 || coversPageByArea3;
                    int z3 = isBackgroundLike3
                            ? 0
                            : (rg3.zOrderKnown()
                                    ? rg3.zOrder()
                                    : (rg3.zOrder() > 0 ? rg3.zOrder() : 5));
                    fig3.zOrder(z3);
                    fig3.fromGroup(!isBackgroundLike3);
                    sections.get(secIdx3).addBlock(fig3);
                    count++;
                    ctx.recordRenderedDecision(rg3, "Phase7", "PLACE", "placed visible page intersection as ASTFigure");
                }

                // 우측 오버플로우: 다음 페이지(pageIndex+1)에 넘친 부분 배치
                if (rawRight3 > pageWidthPt3 + 1.0) {
                    int nextPi = rg3.pageIndex() + 1;
                    int nextSec = ctx.toSectionIndex.applyAsInt(nextPi);
                    if (nextSec >= 0 && nextSec < sections.size()
                            && !placedKeys.contains(nextPi + "|" + rg3.file())) {
                        double nextPageW = 1e9, nextPageH = 1e9;
                        if (ctx.resolvedData.pages() != null && nextPi < ctx.resolvedData.pages().size()) {
                            double[] npB = ctx.resolvedData.pages().get(nextPi).bounds();
                            if (npB != null && npB.length >= 4) {
                                nextPageW = npB[3] - npB[1];
                                nextPageH = npB[2] - npB[0];
                            }
                        }
                        double ovLeft  = 0.0;
                        double ovRight = Math.min(rawRight3 - pageWidthPt3, nextPageW);
                        double ovTop   = Math.max(0.0, rawTop3);
                        double ovBot   = Math.min(rawBottom3, nextPageH);
                        if (ovLeft < ovRight && ovTop < ovBot) {
                            int pxXov = (int) Math.round((pageWidthPt3 - rawLeft3) / fullW3 * origImg3.getWidth());
                            int pxYov = (int) Math.round((ovTop - rawTop3) / fullH3 * origImg3.getHeight());
                            int pxWov = (int) Math.round((rawRight3 - rawLeft3) / fullW3 * origImg3.getWidth()) - pxXov;
                            int pxHov = (int) Math.round((ovBot - rawTop3) / fullH3 * origImg3.getHeight()) - pxYov;
                            int[] cov = clamp(pxXov, pxYov, pxWov, pxHov, origImg3.getWidth(), origImg3.getHeight());
                            pxXov = cov[0]; pxYov = cov[1]; pxWov = cov[2]; pxHov = cov[3];
                            try {
                                java.awt.image.BufferedImage ovImg = origImg3.getSubimage(pxXov, pxYov, pxWov, pxHov);
                                java.io.ByteArrayOutputStream baosOv = new java.io.ByteArrayOutputStream();
                                javax.imageio.ImageIO.write(ovImg, "png", baosOv);
                                ASTFigure figOv = new ASTFigure();
                                figOv.sourceId("page_object_ov_" + rg3.id());
                                figOv.x(CoordinateConverter.pointsToHwpunits(ovLeft));
                                figOv.y(CoordinateConverter.pointsToHwpunits(ovTop));
                                figOv.width(CoordinateConverter.pointsToHwpunits(ovRight - ovLeft));
                                figOv.height(CoordinateConverter.pointsToHwpunits(ovBot - ovTop));
                                figOv.imageData(baosOv.toByteArray());
                                figOv.imageFormat("png");
                                figOv.pixelWidth(ovImg.getWidth());
                                figOv.pixelHeight(ovImg.getHeight());
                                figOv.zOrder(0); // overflow 배경은 항상 최하단
                                figOv.fromGroup(false);
                                sections.get(nextSec).addBlock(figOv);
                                count++;
                                placedKeys.add(nextPi + "|" + rg3.file());
                            } catch (Exception ovEx) { /* skip */ }
                        }
                    }
                }

                // 좌측 오버플로우: 이전 페이지(pageIndex-1)에 넘친 부분 배치
                if (rawLeft3 < -1.0 && rg3.pageIndex() > 0) {
                    int prevPi = rg3.pageIndex() - 1;
                    int prevSec = ctx.toSectionIndex.applyAsInt(prevPi);
                    if (prevSec >= 0 && prevSec < sections.size()
                            && !placedKeys.contains(prevPi + "|" + rg3.file())) {
                        double prevPageLeftSpread = 0;
                        double prevPageW = 1e9, prevPageH = 1e9;
                        if (ctx.resolvedData.pages() != null && prevPi < ctx.resolvedData.pages().size()) {
                            double[] ppB = ctx.resolvedData.pages().get(prevPi).bounds();
                            if (ppB != null && ppB.length >= 4) {
                                prevPageLeftSpread = ppB[1];
                                prevPageW = ppB[3] - ppB[1];
                                prevPageH = ppB[2] - ppB[0];
                            }
                        }
                        // item의 스프레드 기준 절대 left, 이전 페이지 기준 상대 x
                        double itemSpreadLeft = currPageLeftSpread3 + rawLeft3;
                        double ovXonPrev = itemSpreadLeft - prevPageLeftSpread;
                        double ovLeft2  = Math.max(0.0, ovXonPrev);
                        double ovRight2 = Math.min(prevPageW, ovXonPrev + (-rawLeft3));
                        double ovTop2   = Math.max(0.0, rawTop3);
                        double ovBot2   = Math.min(rawBottom3, prevPageH);
                        if (ovLeft2 < ovRight2 && ovTop2 < ovBot2) {
                            // PNG 크롭: item 내 x = 0 ~ (-rawLeft3) 범위 (item 왼쪽 끝부터 주 페이지 시작까지)
                            double itemRelStart = Math.max(0.0, ovLeft2 - ovXonPrev);
                            int pxXleft = (int) Math.round(itemRelStart / fullW3 * origImg3.getWidth());
                            int pxYleft = (int) Math.round((ovTop2 - rawTop3) / fullH3 * origImg3.getHeight());
                            int pxWleft = (int) Math.round((-rawLeft3 - itemRelStart) / fullW3 * origImg3.getWidth());
                            int pxHleft = (int) Math.round((ovBot2 - rawTop3) / fullH3 * origImg3.getHeight()) - pxYleft;
                            int[] cleft = clamp(pxXleft, pxYleft, pxWleft, pxHleft, origImg3.getWidth(), origImg3.getHeight());
                            pxXleft = cleft[0]; pxYleft = cleft[1]; pxWleft = cleft[2]; pxHleft = cleft[3];
                            try {
                                java.awt.image.BufferedImage leftImg = origImg3.getSubimage(pxXleft, pxYleft, pxWleft, pxHleft);
                                java.io.ByteArrayOutputStream baosLeft = new java.io.ByteArrayOutputStream();
                                javax.imageio.ImageIO.write(leftImg, "png", baosLeft);
                                ASTFigure figLeft = new ASTFigure();
                                figLeft.sourceId("page_object_lv_" + rg3.id());
                                figLeft.x(CoordinateConverter.pointsToHwpunits(ovLeft2));
                                figLeft.y(CoordinateConverter.pointsToHwpunits(ovTop2));
                                figLeft.width(CoordinateConverter.pointsToHwpunits(ovRight2 - ovLeft2));
                                figLeft.height(CoordinateConverter.pointsToHwpunits(ovBot2 - ovTop2));
                                figLeft.imageData(baosLeft.toByteArray());
                                figLeft.imageFormat("png");
                                figLeft.pixelWidth(leftImg.getWidth());
                                figLeft.pixelHeight(leftImg.getHeight());
                                boolean coversPageByAreaLeft = prevPageW < 1e9 && prevPageH < 1e9
                                        && (rawRight3 - rawLeft3) * (rawBottom3 - rawTop3)
                                            >= 0.3 * prevPageW * prevPageH;
                                boolean isPageCoveringBgLeft = ovXonPrev <= 10.0 * sf3
                                        && rawTop3 <= 10.0 * sf3
                                        && (rawBottom3 >= prevPageH - sf3 || coversPageByAreaLeft);
                                boolean isBackgroundLikeLeft = isPageCoveringBgLeft || coversPageByAreaLeft;
                                int zLeft = isBackgroundLikeLeft
                                        ? 0
                                        : (rg3.zOrderKnown()
                                                ? rg3.zOrder()
                                                : (rg3.zOrder() > 0 ? rg3.zOrder() : 5));
                                figLeft.zOrder(zLeft);
                                figLeft.fromGroup(!isBackgroundLikeLeft);
                                sections.get(prevSec).addBlock(figLeft);
                                count++;
                                placedKeys.add(prevPi + "|" + rg3.file());
                            } catch (Exception lvEx) { /* skip */ }
                        }
                    }
                }
            } catch (Exception e3) { /* skip */ }
        }

        if (count > 0) {
            System.err.println("[ResolvedToASTBuilder] Phase 7: " + count + " renderable frames placed");
        }
    }

}
