package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.stage3;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ConversionTiming;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTBlock;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTFigure;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTSection;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTextFrameBlock;
import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.CoordinateConverter;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ResolvedBuildContext;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.Materialization;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.ObjectPlan;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.Placement;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.VisualAction;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.phase6.BackgroundInjector;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedPage;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedPageItem;
import kr.dogfoot.hwpxlib.tool.idmlconverter.util.ColorResolver;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.util.List;

/**
 * Stage 3 visual execution entry point.
 *
 * <p>SPEC-035의 목표 구조에서는 Stage 1 ObjectPlan이 모든 visual ownership,
 * placement, layer를 결정하고 이 클래스는 plan을 실행만 한다. 현재는 Phase 6/7의
 * legacy executors를 내부 브리지로 호출하되, 상위 파이프라인에서 Phase 6/7 직접 의존을
 * 제거해 이후 executor를 하나씩 흡수할 수 있게 한다.</p>
 */
public final class VisualBuilder {
    private VisualBuilder() {
    }

    public static void place(ResolvedBuildContext ctx, List<ASTSection> sections) {
        if (ctx == null || sections == null || sections.isEmpty()) return;

        try (ConversionTiming.Scope ignored =
                     ConversionTiming.time("stage3.visualBuilder.legacyBackgroundInjector")) {
            BackgroundInjector.inject(ctx, sections);
        }
        try (ConversionTiming.Scope ignored =
                     ConversionTiming.time("stage3.visualBuilder.nativeVisualOnlyShapes")) {
            placeNativeVisualOnlyShapes(ctx, sections);
        }
    }

    private static void placeNativeVisualOnlyShapes(
            ResolvedBuildContext ctx,
            List<ASTSection> sections) {
        if (ctx == null || ctx.resolvedData == null || ctx.ownershipPlans == null
                || sections == null || sections.isEmpty()) {
            return;
        }
        for (ObjectPlan plan : ctx.ownershipPlans) {
            if (!isNativeShapeVisualPlan(plan)) continue;
            ConversionTiming.addCounter("stage3.nativeVisualOnlyShapes.candidates", 1);
            int pageIdx = ctx.toSectionIndex != null
                    ? ctx.toSectionIndex.applyAsInt(plan.pageIndex)
                    : plan.pageIndex;
            if (pageIdx < 0 || pageIdx >= sections.size()) {
                ConversionTiming.addCounter("stage3.nativeVisualOnlyShapes.skipPage", 1);
                continue;
            }
            ResolvedPageItem item = firstVisualSourceItem(ctx, plan);
            if (item == null) {
                ConversionTiming.addCounter("stage3.nativeVisualOnlyShapes.skipMissingSource", 1);
                continue;
            }
            ASTBlock visual = buildNativeVisualOnlyShellBlock(ctx, plan, item);
            if (visual == null) {
                ConversionTiming.addCounter("stage3.nativeVisualOnlyShapes.skipNoNativePaint", 1);
                continue;
            }
            addVisualByPlannedOrder(sections.get(pageIdx), visual);
            ConversionTiming.addCounter("stage3.nativeVisualOnlyShapes.inserted", 1);
        }
    }

    private static boolean isNativeShapeVisualPlan(ObjectPlan plan) {
        return plan != null
                && plan.visualAction == VisualAction.PLACE_FLOATING_PNG
                && plan.materialization == Materialization.NATIVE_SOURCE_SHAPE
                && plan.placement == Placement.FLOATING
                && plan.sourceObjectIds != null
                && plan.sourceObjectIds.length > 0;
    }

    private static ResolvedPageItem firstVisualSourceItem(ResolvedBuildContext ctx, ObjectPlan plan) {
        int[] ids = plan.visualSourceObjectIds != null && plan.visualSourceObjectIds.length > 0
                ? plan.visualSourceObjectIds
                : plan.sourceObjectIds;
        if (ids == null) return null;
        for (int id : ids) {
            ResolvedPageItem item = ctx.resolvedData.getPageItem(String.valueOf(id));
            if (item != null) return item;
        }
        return null;
    }

    private static ASTBlock buildNativeVisualOnlyShellBlock(
            ResolvedBuildContext ctx,
            ObjectPlan plan,
            ResolvedPageItem item) {
        double[] b = pageRelativeNativeShapeBounds(ctx, plan, item);
        if (b == null || b.length < 4) return null;
        double topPt = b[0];
        double leftPt = b[1];
        double bottomPt = b[2];
        double rightPt = b[3];
        double wPt = rightPt - leftPt;
        double hPt = bottomPt - topPt;
        double strokePt = normalizeSourceStrokeWeightPt(ctx, item.strokeWeight());
        if (isStrokeOnlyNativeShape(item) && strokePt > 0) {
            if (hPt > 0 && hPt < strokePt) {
                double cy = (topPt + bottomPt) / 2.0;
                topPt = cy - strokePt / 2.0;
                bottomPt = cy + strokePt / 2.0;
                hPt = strokePt;
            }
            if (wPt > 0 && wPt < strokePt) {
                double cx = (leftPt + rightPt) / 2.0;
                leftPt = cx - strokePt / 2.0;
                rightPt = cx + strokePt / 2.0;
                wPt = strokePt;
            }
        }
        if (wPt <= 0 || hPt <= 0) return null;

        if (isStrokeOnlyThinNativeSource(item, wPt, hPt, strokePt)) {
            return buildNativeStrokeLineFigure(ctx, plan, item, leftPt, topPt, wPt, hPt);
        }

        ASTTextFrameBlock block = new ASTTextFrameBlock();
        block.sourceId(nativeVisualOnlySourceId(plan));
        block.x(CoordinateConverter.pointsToHwpunits(leftPt));
        block.y(CoordinateConverter.pointsToHwpunits(topPt));
        block.width(CoordinateConverter.pointsToHwpunits(wPt));
        block.height(CoordinateConverter.pointsToHwpunits(hPt));
        block.zOrder(plan.zOrder);
        block.plannedShellVisualLayer(plan.visualLayer != null ? plan.visualLayer.name() : null);
        block.nativeGraphicsAllowed(true);
        block.forceNativeFill(true);
        applyNativeShapeStyle(ctx, item, block);
        if (!hasNativePaint(block)) return null;
        return block;
    }

    private static ASTFigure buildNativeStrokeLineFigure(
            ResolvedBuildContext ctx,
            ObjectPlan plan,
            ResolvedPageItem item,
            double leftPt,
            double topPt,
            double widthPt,
            double heightPt) {
        byte[] png = renderStrokeOnlySourcePng(ctx, item, widthPt >= heightPt);
        if (png == null) return null;

        ASTFigure fig = new ASTFigure();
        fig.kind(ASTFigure.FigureKind.RENDERED_SHAPE);
        fig.sourceId(nativeVisualOnlySourceId(plan));
        fig.x(CoordinateConverter.pointsToHwpunits(leftPt));
        fig.y(CoordinateConverter.pointsToHwpunits(topPt));
        fig.width(CoordinateConverter.pointsToHwpunits(widthPt));
        fig.height(CoordinateConverter.pointsToHwpunits(heightPt));
        fig.zOrder(plan.zOrder);
        if (plan.visualLayer != null) {
            fig.visualLayer(plan.visualLayer.name());
        }
        fig.fromGroup(true);
        fig.imageData(png);
        fig.imageFormat("png");
        if (widthPt >= heightPt) {
            fig.pixelWidth(100);
            fig.pixelHeight(4);
        } else {
            fig.pixelWidth(4);
            fig.pixelHeight(100);
        }
        return fig;
    }

    private static byte[] renderStrokeOnlySourcePng(
            ResolvedBuildContext ctx,
            ResolvedPageItem item,
            boolean horizontal) {
        int r = 128;
        int g = 128;
        int b = 128;
        String hex = ctx != null && ctx.resolvedData != null
                ? ctx.resolvedData.resolveColorHex(item.strokeColorName())
                : null;
        if (hex != null && hex.startsWith("#") && hex.length() >= 7) {
            try {
                r = Integer.parseInt(hex.substring(1, 3), 16);
                g = Integer.parseInt(hex.substring(3, 5), 16);
                b = Integer.parseInt(hex.substring(5, 7), 16);
            } catch (NumberFormatException ignored) {
            }
        }
        double opacity = item.opacity() > 0 ? item.opacity() : 100.0;
        int alpha = Math.max(0, Math.min(255, (int) Math.round(255 * opacity / 100.0)));
        int pixelW = horizontal ? 100 : 4;
        int pixelH = horizontal ? 4 : 100;
        try {
            BufferedImage image = new BufferedImage(pixelW, pixelH, BufferedImage.TYPE_INT_ARGB);
            int argb = (alpha << 24) | (r << 16) | (g << 8) | b;
            for (int x = 0; x < pixelW; x++) {
                for (int y = 0; y < pixelH; y++) {
                    image.setRGB(x, y, argb);
                }
            }
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "png", out);
            return out.toByteArray();
        } catch (Exception e) {
            return null;
        }
    }

    private static double[] pageRelativeNativeShapeBounds(
            ResolvedBuildContext ctx,
            ObjectPlan plan,
            ResolvedPageItem item) {
        boolean usingPlanBounds = plan != null && plan.bounds != null && plan.bounds.length >= 4;
        double[] sourceBounds = usingPlanBounds ? plan.bounds : (item != null ? item.geometricBounds() : null);
        if (sourceBounds == null || sourceBounds.length < 4) return null;

        double width = sourceBounds[3] - sourceBounds[1];
        double height = sourceBounds[2] - sourceBounds[0];
        if (width <= 0 || height <= 0) return sourceBounds;

        if (item != null && item.pageRelativeBounds() != null
                && item.pageRelativeBounds().length >= 4) {
            double[] local = item.pageRelativeBounds();
            return new double[] { local[0], local[1], local[2], local[3] };
        }

        ResolvedPage page = null;
        int pageIndex = plan != null ? plan.pageIndex : (item != null ? item.pageIndex() : -1);
        if (ctx != null && ctx.resolvedData != null && pageIndex >= 0) {
            Integer sectionIndex = ctx.pageDocOffsetToSection != null
                    ? ctx.pageDocOffsetToSection.get(pageIndex)
                    : null;
            if (sectionIndex != null) {
                page = ctx.resolvedData.getPage(sectionIndex);
            }
            if (page == null) {
                page = ctx.resolvedData.getPage(pageIndex);
            }
        }
        if (page == null) return sourceBounds;

        if (usingPlanBounds) {
            double scale = ctx != null && ctx.scaleFactor > 0 ? ctx.scaleFactor : 1.0;
            double[] pageBounds = page.bounds();
            if (pageBounds == null || pageBounds.length < 4) return sourceBounds;
            double rawPageTop = pageBounds[0] / scale;
            double rawPageLeft = pageBounds[1] / scale;
            boolean pageRelativeCoords = rawPageLeft > 1.0 && sourceBounds[1] < rawPageLeft;
            double left = pageRelativeCoords ? sourceBounds[1] : (sourceBounds[1] - rawPageLeft);
            double top = pageRelativeCoords ? sourceBounds[0] : (sourceBounds[0] - rawPageTop);
            return new double[] {
                    top * scale,
                    left * scale,
                    (top + height) * scale,
                    (left + width) * scale
            };
        }

        double[] xy = page.toPageRelative(sourceBounds);
        if (xy == null || xy.length < 2) return sourceBounds;
        return new double[] { xy[1], xy[0], xy[1] + height, xy[0] + width };
    }

    private static boolean isStrokeOnlyNativeShape(ResolvedPageItem item) {
        if (item == null) return false;
        if (item.strokeWeight() <= 0) return false;
        String strokeName = item.strokeColorName();
        if (strokeName == null || "None".equals(strokeName) || "[None]".equals(strokeName)) return false;
        String fillName = item.fillColorName();
        return fillName == null || "None".equals(fillName) || "[None]".equals(fillName);
    }

    private static String nativeVisualOnlySourceId(ObjectPlan plan) {
        if (plan != null && "native_page_backdrop_shape".equals(plan.kind)) {
            return "page_obj_" + plan.domId + "_p" + plan.pageIndex;
        }
        return "page_obj_" + (plan != null ? plan.domId : -1);
    }

    private static void applyNativeShapeStyle(
            ResolvedBuildContext ctx,
            ResolvedPageItem item,
            ASTTextFrameBlock block) {
        String fillName = item.fillColorName();
        if (fillName != null && !"None".equals(fillName) && !"[None]".equals(fillName)) {
            String fillHex = ctx.resolvedData.resolveTintedColorHex(fillName, item.fillTint());
            if (fillHex != null) {
                block.fillColor(fillHex);
                block.fillTint(100);
            }
        }

        String strokeName = item.strokeColorName();
        if (strokeName != null && !"None".equals(strokeName) && !"[None]".equals(strokeName)
                && item.strokeWeight() > 0) {
            String strokeHex = ctx.resolvedData.resolveColorHex(strokeName);
            if (strokeHex != null) {
                block.strokeColor(strokeHex);
                block.strokeWeight(normalizeSourceStrokeWeightPt(ctx, item.strokeWeight()));
                block.strokeTint(ColorResolver.normalizeTint(item.strokeTint()));
            }
        }

        if (item.cornerRadius() > 0) {
            block.cornerRadius(item.cornerRadius());
        }
    }

    private static boolean isStrokeOnlyThinNativeSource(
            ResolvedPageItem item,
            double widthPt,
            double heightPt,
            double strokePt) {
        if (!isStrokeOnlyNativeShape(item) || strokePt <= 0) return false;
        double minAxis = Math.min(Math.abs(widthPt), Math.abs(heightPt));
        return minAxis <= strokePt * 1.25;
    }

    private static boolean hasNativePaint(ASTTextFrameBlock block) {
        return isPaintColor(block.fillColor())
                || isPaintColor(block.strokeColor())
                || block.cornerRadius() > 0;
    }

    private static boolean isPaintColor(String value) {
        return value != null && value.startsWith("#");
    }

    private static double normalizeSourceStrokeWeightPt(ResolvedBuildContext ctx, double strokeWeight) {
        if (strokeWeight <= 0) return strokeWeight;
        double scale = ctx != null && ctx.scaleFactor > 0 ? ctx.scaleFactor : 1.0;
        if (Math.abs(scale - 1.0) < 0.001) return strokeWeight;
        return strokeWeight / scale;
    }

    private static void addVisualByPlannedOrder(ASTSection section, ASTBlock block) {
        int index = 0;
        while (index < section.blocks().size()) {
            ASTBlock existing = section.blocks().get(index);
            if (!isVisualBlock(existing)) break;
            if (zOrderOf(existing) > zOrderOf(block)) break;
            index++;
        }
        section.blocks().add(index, block);
    }

    private static boolean isVisualBlock(ASTBlock block) {
        if (block instanceof ASTFigure) return true;
        if (block instanceof ASTTextFrameBlock) {
            return ((ASTTextFrameBlock) block).isBackgroundOnly();
        }
        return false;
    }

    private static int zOrderOf(ASTBlock block) {
        if (block instanceof ASTFigure) return ((ASTFigure) block).zOrder();
        if (block instanceof ASTTextFrameBlock) return ((ASTTextFrameBlock) block).zOrder();
        return Integer.MAX_VALUE;
    }
}
