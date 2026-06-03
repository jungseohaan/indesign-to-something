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
 * Phase 7: inline_object / page_object PNG를 ASTFigure로 배치.
 * renderedFloatingItems 중 inline_object(7b) + page_object(7c) 항목을 처리한다.
 */
public final class RenderableFramePlacer {

    private RenderableFramePlacer() {}

    public static void place(ResolvedBuildContext ctx, List<ASTSection> sections) {
        if (ctx.basePath == null) return;
        if (ctx.resolvedData == null) return;
        int count = 0;
        // 같은 PNG 파일을 여러 ID로 등록한 경우 중복 배치 방지 (페이지+파일 단위)
        java.util.Set<String> placedKeys = new java.util.HashSet<>();

        // Phase 7b: inline_object items that were suppressed from inline placement (Phase 3)
        // and need to be re-placed as floating ASTFigures behind their inlineToFloating TF.
        for (RenderedGroup rg : ctx.resolvedData.allRenderedFloatingItems()) {
            if (!"inline_object".equals(rg.itemType())) continue;
            if (!ctx.isDisposed(rg.id(), FrameDisposition.PNG_CONVERT_TO_FLOATING)) continue;
            if (rg.file() == null) continue;
            String dedupKey2 = rg.pageIndex() + "|" + rg.file();
            if (!placedKeys.add(dedupKey2)) continue;

            File pngFile2 = new File(ctx.basePath, rg.file());
            if (!pngFile2.exists()) continue;

            // TF 의 pageIndex 를 우선 사용 (rg.pageIndex()는 앵커 텍스트 기준으로 TF 섹션과 다를 수 있음)
            Integer tfPi = ctx.inlineObjectTfPageIndex.get(rg.id());
            int pageIdx2 = ctx.toSectionIndex.applyAsInt(tfPi != null ? tfPi : rg.pageIndex());
            if (pageIdx2 < 0 || pageIdx2 >= sections.size()) continue;

            try {
                byte[] imageData2 = java.nio.file.Files.readAllBytes(pngFile2.toPath());
                java.awt.image.BufferedImage img2 = javax.imageio.ImageIO.read(pngFile2);
                if (img2 == null || img2.getWidth() <= 2) continue;

                double[] bounds2 = rg.bounds();
                if (bounds2 == null || bounds2.length < 4) continue;
                // renderedFloatingItems bounds는 normalizeToPoints() 미적용 (mm단위, spread 절대좌표)
                // → TF 의 page offset(page bounds top/left)을 빼서 page-relative 로 변환 후 scaleFactor로 pt 환산
                double sf2 = ctx.scaleFactor;
                double pageTop2 = 0, pageLeft2 = 0;
                int boundsPageIdx = tfPi != null ? tfPi : rg.pageIndex();
                if (ctx.resolvedData.pages() != null
                        && boundsPageIdx >= 0 && boundsPageIdx < ctx.resolvedData.pages().size()) {
                    double[] pgB2 = ctx.resolvedData.pages().get(boundsPageIdx).bounds();
                    if (pgB2 != null && pgB2.length >= 4) {
                        pageTop2 = pgB2[0];
                        pageLeft2 = pgB2[1];
                    }
                }
                // bounds2는 mm (spread 절대), pages()는 pt (normalizeToPoints 적용됨)
                // → bounds2를 pt로 변환 후 page offset(pt) 차감하여 page-relative pt 좌표로
                double bw2 = Math.abs(bounds2[3] - bounds2[1]) * sf2;
                double bh2 = Math.abs(bounds2[2] - bounds2[0]) * sf2;
                if (bw2 <= 0 || bh2 <= 0) continue;
                double x2 = bounds2[1] * sf2 - pageLeft2;
                double y2 = bounds2[0] * sf2 - pageTop2;

                ASTFigure fig2 = new ASTFigure();
                fig2.sourceId("inline_float_" + rg.id());
                fig2.x(CoordinateConverter.pointsToHwpunits(x2));
                fig2.y(CoordinateConverter.pointsToHwpunits(y2));
                fig2.width(CoordinateConverter.pointsToHwpunits(bw2));
                fig2.height(CoordinateConverter.pointsToHwpunits(bh2));
                fig2.imageData(imageData2);
                fig2.imageFormat("png");
                fig2.pixelWidth(img2.getWidth());
                fig2.pixelHeight(img2.getHeight());
                // inline_object는 inlineToFloating TF 의 배경 컨테이너 → TF 보다 뒤에 놓여야 함.
                // InDesign z-order 는 inline anchor 기준이라 변환 불가 → 낮은 고정값 사용.
                fig2.zOrder(10);
                fig2.fromGroup(true);
                sections.get(pageIdx2).addBlock(fig2);
                count++;
            } catch (Exception e2) { /* skip */ }
        }

        // Phase 7c: page_object 항목 (스프레드 걸침 배경/장식 PNG) 배치
        // 주 페이지 가시 영역 + 좌/우 오버플로우를 인접 페이지에 크롭해서 배치
        for (RenderedGroup rg3 : ctx.resolvedData.allRenderedFloatingItems()) {
            if (!"page_object".equals(rg3.itemType())) {
                continue;
            }
            if (rg3.file() == null) continue;
            // Phase 6(BackgroundInjector)이 이미 배치한 항목은 중복 배치 방지
            if (ctx.phase6PlacedIds.contains(rg3.id())) continue;
            String dedupKey3 = rg3.pageIndex() + "|" + rg3.file();
            if (!placedKeys.add(dedupKey3)) continue;

            File pngFile3 = new File(ctx.basePath, rg3.file());
            if (!pngFile3.exists()) continue;

            int secIdx3 = ctx.toSectionIndex.applyAsInt(rg3.pageIndex());
            if (secIdx3 < 0 || secIdx3 >= sections.size()) continue;

            try {
                double[] bounds3 = rg3.bounds();
                if (bounds3 == null || bounds3.length < 4) continue;

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
                if (fullW3 <= 0 || fullH3 <= 0) continue;

                java.awt.image.BufferedImage origImg3 = javax.imageio.ImageIO.read(pngFile3);
                if (origImg3 == null || origImg3.getWidth() <= 2) continue;


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
                    pxX3 = Math.max(0, Math.min(pxX3, origImg3.getWidth()  - 1));
                    pxY3 = Math.max(0, Math.min(pxY3, origImg3.getHeight() - 1));
                    pxW3 = Math.max(1, Math.min(origImg3.getWidth()  - pxX3, pxW3));
                    pxH3 = Math.max(1, Math.min(origImg3.getHeight() - pxY3, pxH3));
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
                    fig3.zOrder(rg3.zOrder() > 0 ? rg3.zOrder() : 5);
                    fig3.fromGroup(true);
                    sections.get(secIdx3).addBlock(fig3);
                    count++;
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
                            pxXov = Math.max(0, Math.min(pxXov, origImg3.getWidth()  - 1));
                            pxYov = Math.max(0, Math.min(pxYov, origImg3.getHeight() - 1));
                            pxWov = Math.max(1, Math.min(origImg3.getWidth()  - pxXov, pxWov));
                            pxHov = Math.max(1, Math.min(origImg3.getHeight() - pxYov, pxHov));
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
                                figOv.zOrder(1); // overflow 배경은 항상 최하단
                                figOv.fromGroup(true);
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
                            pxXleft = Math.max(0, Math.min(pxXleft, origImg3.getWidth()  - 1));
                            pxYleft = Math.max(0, Math.min(pxYleft, origImg3.getHeight() - 1));
                            pxWleft = Math.max(1, Math.min(origImg3.getWidth()  - pxXleft, pxWleft));
                            pxHleft = Math.max(1, Math.min(origImg3.getHeight() - pxYleft, pxHleft));
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
                                figLeft.zOrder(rg3.zOrder() > 0 ? rg3.zOrder() : 5);
                                figLeft.fromGroup(true);
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
