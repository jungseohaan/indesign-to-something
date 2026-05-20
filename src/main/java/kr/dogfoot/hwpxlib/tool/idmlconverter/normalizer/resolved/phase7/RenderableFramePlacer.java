package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.phase7;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTFigure;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTSection;
import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.CoordinateConverter;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ResolvedBuildContext;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.RenderedGroup;

import java.io.File;
import java.util.List;

/**
 * SPEC-013 Phase 7: renderable TF(배지)를 플로팅 이미지로 배치.
 *
 * <p>resolved.allRenderedTextFrames() 중 type이 없는(일반 renderable) 항목의 PNG를
 * ASTFigure로 변환해 배경(zOrder=0) 위에 배치한다. badge_group은 인라인으로 이미
 * 처리되었으므로 건너뛴다.</p>
 *
 * <p>{@code ResolvedToASTBuilder.placeRenderableFrames}에서 stateless static helper로
 * 발췌. 동작은 동일하며 ResolvedToASTBuilder는 위임만 한다.</p>
 */
public final class RenderableFramePlacer {

    private RenderableFramePlacer() {}

    public static void place(ResolvedBuildContext ctx, List<ASTSection> sections) {
        if (ctx.basePath == null) return;
        if (ctx.resolvedData == null) return;
        int count = 0;
        // 같은 PNG 파일을 여러 ID로 등록한 경우 중복 배치 방지 (페이지+파일 단위)
        java.util.Set<String> placedKeys = new java.util.HashSet<>();
        for (RenderedGroup rt : ctx.resolvedData.allRenderedTextFrames()) {
            if (rt.file() == null) continue;
            String dedupKey = rt.pageIndex() + "|" + rt.file();
            if (!placedKeys.add(dedupKey)) continue; // 이미 배치된 동일 파일/페이지
            // badge_group은 인라인 앵커(inline_object)로 배치된 경우에만 건너뜀.
            // 인라인 참조가 없는 독립 badge는 여기서 플로팅으로 배치해야 함.
            if (rt.isBadgeGroup()) {
                boolean alsoInline = false;
                for (RenderedGroup rg : ctx.resolvedData.allRenderedFloatingItems()) {
                    if (rg.id() == rt.id() && "inline_object".equals(rg.itemType())) {
                        alsoInline = true;
                        break;
                    }
                }
                if (alsoInline) continue;
                // SPEC-025: 단순 scribble 배지는 editable TextFrame 으로 visual 전체를 흡수 → PNG 건너뜀.
                // 일러스트 배지(예: 선인장 + 작은 라벨)는 PNG 유지하고 라벨에만 흰 텍스트박스를 오버레이.
                boolean anySimpleChild = false;
                if (rt.childTextFrameIds() != null) {
                    for (int cid : rt.childTextFrameIds()) {
                        if (ctx.resolvedData.isSimpleBadgeChild(String.valueOf(cid))) {
                            anySimpleChild = true;
                            break;
                        }
                    }
                }
                if (anySimpleChild) continue;
            }

            File pngFile = new File(ctx.basePath, rt.file());
            if (!pngFile.exists()) continue;

            int pageIdx = ctx.toSectionIndex.applyAsInt(rt.pageIndex());
            if (pageIdx < 0 || pageIdx >= sections.size()) continue;

            try {
                byte[] imageData = java.nio.file.Files.readAllBytes(pngFile.toPath());
                java.awt.image.BufferedImage img = javax.imageio.ImageIO.read(pngFile);
                if (img == null || img.getWidth() <= 2) continue;

                double[] bounds = rt.bounds();
                if (bounds == null || bounds.length < 4) continue;

                // bounds는 normalizeToPoints()에서 이미 pt 단위로 변환됨
                double bw = Math.abs(bounds[3] - bounds[1]);
                double bh = Math.abs(bounds[2] - bounds[0]);
                if (bw <= 0 || bh <= 0) continue;

                // PNG 비율 보정 — 보정 후 원본 bounds 의 중심을 유지하도록 x/y 재계산
                double bwOrig = bw, bhOrig = bh;
                double pngRatio = (double) img.getWidth() / img.getHeight();
                double boundsRatio = bw / bh;
                if (Math.abs(pngRatio - boundsRatio) / Math.max(pngRatio, boundsRatio) > 0.1) {
                    if (pngRatio < 1.0) { bw = bh * pngRatio; } else { bh = bw / pngRatio; }
                }

                // 원본 중심을 유지하면서 좌상단(x,y) 재산출
                double centerX = bounds[1] + bwOrig / 2.0;
                double centerY = bounds[0] + bhOrig / 2.0;
                double x = centerX - bw / 2.0;
                double y = centerY - bh / 2.0;

                ASTFigure fig = new ASTFigure();
                fig.sourceId("renderable_" + rt.id());
                fig.x(CoordinateConverter.pointsToHwpunits(x));
                fig.y(CoordinateConverter.pointsToHwpunits(y));
                fig.width(CoordinateConverter.pointsToHwpunits(bw));
                fig.height(CoordinateConverter.pointsToHwpunits(bh));
                fig.imageData(imageData);
                fig.imageFormat("png");
                fig.pixelWidth(img.getWidth());
                fig.pixelHeight(img.getHeight());
                // InDesign allPageItems: index 0 = 맨 앞, 큰 값 = 뒤.
                // HWPX zOrder: 큰 값 = 앞. → 역매핑하여 겹침 순서 보존.
                int indesignIdx = rt.zOrder();
                int hwpxZ = (indesignIdx > 0) ? Math.max(10000 - indesignIdx, 10) : 10;
                fig.zOrder(hwpxZ);
                fig.fromGroup(true); // IN_FRONT_OF_TEXT
                sections.get(pageIdx).addBlock(fig);
                count++;
            } catch (Exception e) { /* skip */ }
        }
        if (count > 0) {
            System.err.println("[ResolvedToASTBuilder] Phase 7: " + count + " renderable frames placed");
        }
    }
}
