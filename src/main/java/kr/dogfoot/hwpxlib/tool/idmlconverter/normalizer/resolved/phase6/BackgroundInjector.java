package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.phase6;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTFigure;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTSection;
import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.CoordinateConverter;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ResolvedBuildContext;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.RenderedGroup;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.List;

/**
 * SPEC-013 Phase 6: 페이지 배경 PNG를 ASTFigure로 주입.
 *
 * <p>resolved.allRenderedFloatingItems() 중 itemType="page_background"인 항목의 PNG를
 * 페이지 배경(zOrder=0, BEHIND_TEXT)으로 배치한다.</p>
 *
 * <p>{@code ResolvedToASTBuilder.injectPageBackgrounds}에서 stateless static helper로
 * 발췌. 동작은 동일하며 ResolvedToASTBuilder는 위임만 한다.</p>
 */
public final class BackgroundInjector {

    private BackgroundInjector() {}

    public static void inject(ResolvedBuildContext ctx, List<ASTSection> sections) {
        if (ctx.resolvedData == null) return;
        List<RenderedGroup> floatingItems = ctx.resolvedData.allRenderedFloatingItems();
        if (floatingItems == null || floatingItems.isEmpty()) return;

        // PDF 래스터화 캐시 (한 번만 로드)
        java.util.List<byte[]> pdfPages = null;
        String loadedPdfPath = null;

        for (RenderedGroup rg : floatingItems) {
            if (!"page_background".equals(rg.itemType())) continue;

            int pageIdx = ctx.toSectionIndex.applyAsInt(rg.pageIndex());
            if (pageIdx < 0 || pageIdx >= sections.size()) continue;

            double[] bounds = rg.bounds();
            if (bounds == null || bounds.length < 4) continue;

            byte[] imageData = null;
            int pixelW = 0, pixelH = 0;

            // PDF 배경 래스터화 비활성화 — PNG 600dpi가 더 고품질
            if (false && rg.pdfFile() != null && rg.pdfPageIndex() >= 0) {
                try {
                    File pdfFile = new File(ctx.basePath, rg.pdfFile());
                    if (pdfFile.exists()) {
                        String pdfPath = pdfFile.getAbsolutePath();
                        if (pdfPages == null || !pdfPath.equals(loadedPdfPath)) {
                            pdfPages = kr.dogfoot.hwpxlib.tool.idmlconverter.converter
                                    .PdfPageRenderer.renderAllPages(pdfFile, 600);
                            loadedPdfPath = pdfPath;
                        }
                        int pdfIdx = rg.pdfPageIndex();
                        if (pdfIdx >= 0 && pdfIdx < pdfPages.size()) {
                            imageData = pdfPages.get(pdfIdx);
                            BufferedImage pdfImg = ImageIO.read(
                                    new java.io.ByteArrayInputStream(imageData));
                            if (pdfImg != null) {
                                pixelW = pdfImg.getWidth();
                                pixelH = pdfImg.getHeight();
                                pdfImg.flush();
                            }
                        }
                    }
                } catch (Exception e) {
                    System.err.println("[ResolvedToASTBuilder] PDF 배경 래스터화 실패: " + e.getMessage());
                    imageData = null;
                }
            }

            // PNG 폴백
            if (imageData == null && rg.file() != null) {
                try {
                    File pngFile = new File(ctx.basePath, rg.file());
                    if (pngFile.exists()) {
                        imageData = java.nio.file.Files.readAllBytes(pngFile.toPath());
                        BufferedImage img = ImageIO.read(pngFile);
                        if (img != null) {
                            pixelW = img.getWidth();
                            pixelH = img.getHeight();
                            img.flush();
                        }
                    }
                } catch (Exception e) {
                    System.err.println("[ResolvedToASTBuilder] PNG 배경 로드 실패: " + e.getMessage());
                    continue;
                }
            }

            if (imageData == null) continue;

            long figW = CoordinateConverter.pointsToHwpunits((bounds[3] - bounds[1]) * ctx.scaleFactor);
            long figH = CoordinateConverter.pointsToHwpunits((bounds[2] - bounds[0]) * ctx.scaleFactor);

            ASTFigure fig = new ASTFigure();
            fig.x(0);
            fig.y(0);
            fig.width(figW);
            fig.height(figH);
            fig.imageData(imageData);
            fig.imageFormat("png");
            fig.pixelWidth(pixelW);
            fig.pixelHeight(pixelH);
            fig.zOrder(0);
            fig.fromGroup(false);  // BEHIND_TEXT
            fig.sourceId("page_bg_" + pageIdx);

            sections.get(pageIdx).addBlock(fig);
        }
    }
}
