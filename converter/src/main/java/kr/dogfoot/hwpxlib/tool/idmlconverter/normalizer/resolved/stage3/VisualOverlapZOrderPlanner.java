package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.stage3;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTBlock;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTFigure;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTSection;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTable;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTextFrameBlock;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ResolvedBuildContext;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.RenderedGroup;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedPageItem;

import java.util.List;

/**
 * Stage 3 z-order adjustments that depend on already-built AST objects or
 * neighboring rendered visuals.
 */
public final class VisualOverlapZOrderPlanner {
    private VisualOverlapZOrderPlanner() {
    }

    public static int foregroundMarkerZOrder(
            ASTSection section,
            long x,
            long y,
            long w,
            long h,
            int currentZ) {
        if (section == null || w <= 0 || h <= 0) return currentZ;
        int maxOverlapZ = currentZ;
        long markerArea = w * h;
        if (markerArea <= 0) return currentZ;
        for (ASTBlock block : section.blocks()) {
            if (block == null) continue;
            long[] b = astBlockBounds(block);
            if (b == null) continue;
            long bw = b[2] - b[0];
            long bh = b[3] - b[1];
            if (bw <= 0 || bh <= 0) continue;
            long overlap = overlapAreaHwp(x, y, w, h, b[0], b[1], bw, bh);
            if (overlap <= 0) continue;
            long blockArea = bw * bh;
            if (blockArea <= 0) continue;
            if ((double) overlap / (double) markerArea < 0.02
                    && (double) overlap / (double) blockArea < 0.02) {
                continue;
            }
            maxOverlapZ = Math.max(maxOverlapZ, astBlockZOrder(block));
        }
        return Math.min(999, maxOverlapZ + 1);
    }

    public static int foregroundOverlapShellZOrder(
            ASTSection section,
            RenderedGroup rg,
            long x,
            long y,
            long w,
            long h,
            int currentZ) {
        if (!isForegroundOverlapShellCandidate(rg) || section == null || w <= 0 || h <= 0) {
            return currentZ;
        }
        int minOverlapZ = Integer.MAX_VALUE;
        long shellArea = w * h;
        if (shellArea <= 0) return currentZ;
        for (ASTBlock block : section.blocks()) {
            if (block == null) continue;
            long[] b = astBlockBounds(block);
            if (b == null) continue;
            long bw = b[2] - b[0];
            long bh = b[3] - b[1];
            if (bw <= 0 || bh <= 0) continue;
            long overlap = overlapAreaHwp(x, y, w, h, b[0], b[1], bw, bh);
            if (overlap <= 0) continue;
            long blockArea = bw * bh;
            if (blockArea <= 0) continue;
            if ((double) overlap / (double) blockArea < 0.05
                    && (double) overlap / (double) shellArea < 0.01) {
                continue;
            }
            int z = astBlockZOrder(block);
            if (z <= 0) continue;
            minOverlapZ = Math.min(minOverlapZ, z);
        }
        if (minOverlapZ == Integer.MAX_VALUE || minOverlapZ >= currentZ) {
            return currentZ;
        }
        return Math.max(0, minOverlapZ - 1);
    }

    public static int containerShellZOrderBehindRenderedContent(
            ResolvedBuildContext ctx,
            List<RenderedGroup> items,
            RenderedGroup shell,
            int currentZ) {
        int minContentZ = minOverlappingRenderedContentZ(ctx, items, shell);
        if (minContentZ < 0 || currentZ < minContentZ) {
            return currentZ;
        }
        return Math.max(0, minContentZ - 1);
    }

    public static boolean isPaperOnlyContainerShell(ResolvedBuildContext ctx, RenderedGroup rg) {
        if (ctx == null || ctx.resolvedData == null || rg == null) return false;
        if (!isRenderedContainerShell(rg)) return false;
        if (!"vector_shape".equals(rg.reason())) return false;

        int[] sourceIds = rg.sourceObjectIds();
        boolean sawSource = false;
        boolean sawPaperFill = false;
        if (sourceIds != null) {
            for (int sourceId : sourceIds) {
                if (isPaperOnlyPageItem(ctx, String.valueOf(sourceId))) {
                    sawSource = true;
                    sawPaperFill = true;
                    continue;
                }
                ResolvedPageItem pi = ctx.resolvedData.getPageItem(String.valueOf(sourceId));
                if (pi != null) return false;
            }
        }
        if (!sawSource) {
            return isPaperOnlyPageItem(ctx, String.valueOf(rg.id()));
        }
        return sawPaperFill;
    }

    private static int minOverlappingRenderedContentZ(
            ResolvedBuildContext ctx,
            List<RenderedGroup> items,
            RenderedGroup shell) {
        if (ctx == null || shell == null || items == null || !isRenderedContainerShell(shell)) {
            return -1;
        }
        double[] shellBounds = shell.bounds();
        double shellArea = area(shellBounds);
        if (shellArea <= 0) return -1;

        int minContentZ = Integer.MAX_VALUE;
        for (RenderedGroup content : items) {
            if (!isRenderedContentLayer(content, shellArea) || content.pageIndex() != shell.pageIndex()) continue;
            if (content.id() == shell.id()) continue;
            double[] contentBounds = content.bounds();
            double contentArea = area(contentBounds);
            if (contentArea <= 0) continue;
            double overlap = overlapArea(shellBounds, contentBounds);
            if (overlap <= 0) continue;
            double contentOverlap = overlap / contentArea;
            double shellOverlap = overlap / shellArea;
            if (contentOverlap < 0.12 && shellOverlap < 0.04) continue;

            int contentZ = VisualZOrderPlanner.effectiveZOrder(ctx, content);
            if (contentZ > 0) {
                minContentZ = Math.min(minContentZ, contentZ);
            }
        }
        return minContentZ == Integer.MAX_VALUE ? -1 : minContentZ;
    }

    private static boolean isRenderedContainerShell(RenderedGroup rg) {
        if (rg == null || !VisualLayeringRules.isPageObject(rg)) return false;
        if (Boolean.FALSE.equals(rg.placementAllowed())) return false;
        if (!"indesign_png".equals(rg.visualOwner())) return false;
        if ("hwpx_tf".equals(rg.textOwner()) || "indesign_png".equals(rg.textOwner())) return false;
        if (Boolean.TRUE.equals(rg.containsText()) || Boolean.TRUE.equals(rg.containsEditableText())) return false;
        String reason = rg.reason();
        if (reason == null) return false;
        if (!"vector_shape".equals(reason) && !reason.contains("textframe_visual_shell")) return false;
        double[] b = rg.bounds();
        if (b == null || b.length < 4) return false;
        double w = b[3] - b[1];
        double h = b[2] - b[0];
        return w >= 18.0 && h >= 12.0 && area(b) >= 300.0;
    }

    private static boolean isRenderedContentLayer(RenderedGroup rg, double shellArea) {
        if (rg == null || !VisualLayeringRules.isPageObject(rg)) return false;
        if (Boolean.FALSE.equals(rg.placementAllowed())) return false;
        if (!"indesign_png".equals(rg.visualOwner())) return false;
        if ("hwpx_tf".equals(rg.textOwner())) return true;
        String reason = rg.reason();
        if (reason == null) return false;
        if (reason.contains("mixed_group_text_hidden")
                || reason.contains("image_group_text_hidden")
                || reason.contains("complex_graphic_text_hidden")) {
            return true;
        }
        return isSemanticImageContentLayer(rg, shellArea);
    }

    private static boolean isSemanticImageContentLayer(RenderedGroup rg, double shellArea) {
        if (rg == null) return false;
        if (Boolean.TRUE.equals(rg.containsText()) || Boolean.TRUE.equals(rg.containsEditableText())) return false;
        String reason = rg.reason();
        if (reason == null) return false;
        boolean imageLike = reason.contains("image_group")
                || reason.contains("graphic")
                || reason.contains("photo")
                || reason.contains("picture");
        if (!imageLike) return false;
        double[] b = rg.bounds();
        if (b == null || b.length < 4) return false;
        double w = b[3] - b[1];
        double h = b[2] - b[0];
        double a = area(b);
        if (w <= 0 || h <= 0 || a < 25.0) return false;
        if (w > 170.0 || h > 170.0 || a > 12000.0) return false;
        if (shellArea > 0 && a > shellArea * 1.25) return false;
        return true;
    }

    private static boolean isForegroundOverlapShellCandidate(RenderedGroup rg) {
        if (rg == null) return false;
        if (!VisualLayeringRules.isPageObject(rg)) return false;
        if (Boolean.FALSE.equals(rg.placementAllowed())) return false;
        if (!"indesign_png".equals(rg.visualOwner())) return false;
        if (Boolean.TRUE.equals(rg.containsText()) || Boolean.TRUE.equals(rg.containsEditableText())) return false;
        String reason = rg.reason();
        if (reason == null) return false;
        return "decoration_group".equals(reason)
                || reason.contains("complex_graphic_text_hidden")
                || reason.contains("mixed_group_text_hidden")
                || reason.contains("image_group_text_hidden")
                || reason.contains("text_hidden_shell")
                || reason.contains("visual_shell")
                || reason.contains("textframe_visual_shell");
    }

    private static boolean isPaperOnlyPageItem(ResolvedBuildContext ctx, String domId) {
        if (ctx == null || ctx.resolvedData == null || domId == null) return false;
        ResolvedPageItem pi = ctx.resolvedData.getPageItem(domId);
        if (pi == null) return false;
        if (!isPaperColor(pi.fillColorName())) return false;
        if (pi.strokeWeight() > 0.01 && !isNoneColor(pi.strokeColorName())) return false;
        return true;
    }

    private static boolean isPaperColor(String colorName) {
        return "Paper".equals(colorName) || "[Paper]".equals(colorName);
    }

    private static boolean isNoneColor(String colorName) {
        return colorName == null
                || colorName.isEmpty()
                || "None".equals(colorName)
                || "[None]".equals(colorName);
    }

    private static long[] astBlockBounds(ASTBlock block) {
        if (block instanceof ASTTextFrameBlock) {
            ASTTextFrameBlock tf = (ASTTextFrameBlock) block;
            return new long[] { tf.x(), tf.y(), tf.x() + tf.effectiveWidth(), tf.y() + tf.height() };
        }
        if (block instanceof ASTFigure) {
            ASTFigure fig = (ASTFigure) block;
            return new long[] { fig.x(), fig.y(), fig.x() + fig.width(), fig.y() + fig.height() };
        }
        if (block instanceof ASTTable) {
            ASTTable table = (ASTTable) block;
            return new long[] { table.x(), table.y(), table.x() + table.width(), table.y() + table.height() };
        }
        return null;
    }

    private static int astBlockZOrder(ASTBlock block) {
        if (block instanceof ASTTextFrameBlock) return ((ASTTextFrameBlock) block).zOrder();
        if (block instanceof ASTFigure) return ((ASTFigure) block).zOrder();
        if (block instanceof ASTTable) return ((ASTTable) block).zOrder();
        return 0;
    }

    private static long overlapAreaHwp(
            long ax, long ay, long aw, long ah,
            long bx, long by, long bw, long bh) {
        long left = Math.max(ax, bx);
        long top = Math.max(ay, by);
        long right = Math.min(ax + aw, bx + bw);
        long bottom = Math.min(ay + ah, by + bh);
        if (right <= left || bottom <= top) return 0L;
        return (right - left) * (bottom - top);
    }

    private static double area(double[] b) {
        if (b == null || b.length < 4) return 0.0;
        return Math.max(0.0, b[2] - b[0]) * Math.max(0.0, b[3] - b[1]);
    }

    private static double overlapArea(double[] a, double[] b) {
        if (a == null || b == null || a.length < 4 || b.length < 4) return 0.0;
        double top = Math.max(a[0], b[0]);
        double left = Math.max(a[1], b[1]);
        double bottom = Math.min(a[2], b[2]);
        double right = Math.min(a[3], b[3]);
        if (bottom <= top || right <= left) return 0.0;
        return (bottom - top) * (right - left);
    }
}
