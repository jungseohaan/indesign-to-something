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

        for (RenderedGroup rg : floatingItems) {
            String itemType = rg.itemType();
            if (!"page_object".equals(itemType)) continue;

            int pageIdx = ctx.toSectionIndex.applyAsInt(rg.pageIndex());
            if (pageIdx < 0 || pageIdx >= sections.size()) continue;

            double[] bounds = rg.bounds();
            if (bounds == null || bounds.length < 4) continue;

            byte[] imageData = loadPng(ctx, rg);
            if (imageData == null) continue;

            int pixelW = 0, pixelH = 0;
            try {
                File pngFile = new File(ctx.basePath, rg.file());
                BufferedImage img = ImageIO.read(pngFile);
                if (img != null) {
                    pixelW = img.getWidth();
                    pixelH = img.getHeight();
                    img.flush();
                }
            } catch (Exception ignored) {}

            // bounds: [top, left, bottom, right] relative to page top-left
            long x = CoordinateConverter.pointsToHwpunits(bounds[1] * ctx.scaleFactor);
            long y = CoordinateConverter.pointsToHwpunits(bounds[0] * ctx.scaleFactor);
            long w = CoordinateConverter.pointsToHwpunits((bounds[3] - bounds[1]) * ctx.scaleFactor);
            long h = CoordinateConverter.pointsToHwpunits((bounds[2] - bounds[0]) * ctx.scaleFactor);

            if (w <= 0 || h <= 0) continue;

            ASTFigure fig = new ASTFigure();
            fig.x(x);
            fig.y(y);
            fig.width(w);
            fig.height(h);
            fig.imageData(imageData);
            fig.imageFormat("png");
            fig.pixelWidth(pixelW);
            fig.pixelHeight(pixelH);
            fig.zOrder(Math.max(rg.zOrder(), 0));
            fig.fromGroup(false);  // BEHIND_TEXT
            fig.sourceId("page_obj_" + rg.id());

            sections.get(pageIdx).addBlock(fig);
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
}
