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
            if (!"page_object".equals(rg.itemType())) continue;
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

        Set<Integer> processedIds = new HashSet<>();

        for (RenderedGroup rg : floatingItems) {
            String itemType = rg.itemType();
            if (!"page_object".equals(itemType)) continue;
            // inline_object로 이미 처리된 ID는 Phase 3가 인라인으로 배치 → 중복 방지
            if (ctx.resolvedData.isInlineObjectId(rg.id())) continue;
            // 상위 그룹 PNG의 자식 항목은 그룹 PNG에 이미 포함됨 → 개별 렌더링 skip
            if (childOfGroup.contains(rg.id())) continue;
            // 같은 ID가 img_/deco_ 등으로 중복 추출된 경우 첫 번째만 사용
            if (!processedIds.add(rg.id())) continue;

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
            String fmt = rg.imageFormat();
            fig.imageFormat((fmt != null && !fmt.isEmpty()) ? fmt : "png");
            fig.pixelWidth(pixelW);
            fig.pixelHeight(pixelH);
            fig.zOrder(Math.max(rg.zOrder(), 0));
            fig.fromGroup(true);   // IN_FRONT_OF_TEXT — z-order로 텍스트TF와의 순서 결정
            fig.sourceId("page_obj_" + rg.id());

            // BEHIND_TEXT 항목은 XML 순서상 앞에 올수록 더 아래 레이어 → addBlockAtFront
            sections.get(pageIdx).addBlockAtFront(fig);
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
