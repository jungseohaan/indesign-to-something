package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.stage3;

import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ResolvedBuildContext;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.RenderedGroup;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedTextFrame;

/**
 * Stage 3 z-order inference for visual objects before AST materialization.
 */
public final class VisualZOrderPlanner {
    private static final double TF_VISUAL_SHELL_MIN_AREA_RATIO = 0.45;
    private static final double TF_VISUAL_SHELL_MAX_AREA_RATIO = 1.80;
    private static final double TF_VISUAL_SHELL_OVERLAP_MIN = 0.75;
    private static final double CONCEPT_LABEL_SHELL_MIN_AREA_RATIO = 0.30;
    private static final double CONCEPT_LABEL_SHELL_MAX_AREA_RATIO = 2.60;
    private static final double CONCEPT_LABEL_SHELL_OVERLAP_MIN = 0.55;

    private VisualZOrderPlanner() {
    }

    public static int effectiveZOrder(ResolvedBuildContext ctx, RenderedGroup rg) {
        if (rg == null) return 5;
        Integer plannedZ = ctx.zOrderByOwnershipPlan(rg);
        if (plannedZ != null) return plannedZ;
        int ownedShellZ = ownedTextFrameShellZOrder(ctx, rg);
        if (ownedShellZ >= 0) return ownedShellZ;
        int conceptLabelShellZ = conceptDiagramLabelShellZOrder(ctx, rg);
        if (conceptLabelShellZ >= 0) return conceptLabelShellZ;
        int inferredShellZ = inferredTextFrameVisualShellZOrder(ctx, rg);
        if (inferredShellZ >= 0) return inferredShellZ;
        int textLineBackdropZ = inferredTextLineBackdropZOrder(ctx, rg);
        if (textLineBackdropZ >= 0) return textLineBackdropZ;
        int titleLabelZ = titleLabelBackgroundZOrder(ctx, rg);
        if (titleLabelZ >= 0) return titleLabelZ;
        if (rg.zOrderKnown()) return rg.zOrder();
        return Math.max(rg.zOrder(), 5);
    }

    public static int inferredTextFrameVisualShellZOrder(ResolvedBuildContext ctx, RenderedGroup rg) {
        if (ctx == null || ctx.resolvedData == null || rg == null) return -1;
        if (ctx.hasOwnershipPlan(rg)) return -1;
        if (!VisualLayeringRules.isPageObject(rg)) return -1;
        if (Boolean.FALSE.equals(rg.placementAllowed())) return -1;
        if (!"indesign_png".equals(rg.visualOwner())) return -1;
        if (Boolean.TRUE.equals(rg.containsText()) || Boolean.TRUE.equals(rg.containsEditableText())) return -1;
        boolean explicitVisualShell = VisualLayeringRules.isTextFrameVisualShellReason(rg.reason());
        boolean vectorShapeShell = "vector_shape".equals(rg.reason());
        if (!explicitVisualShell && !vectorShapeShell) return -1;
        double[] rbRaw = rg.bounds();
        if (rbRaw == null || rbRaw.length < 4) return -1;

        int semanticOverlapZ = semanticTextOverlapShellZOrder(ctx, rg, rbRaw);
        if (semanticOverlapZ >= 0) return semanticOverlapZ;

        for (ResolvedTextFrame tf : ctx.resolvedData.textFrames()) {
            if (tf == null || tf.pageIndex() != rg.pageIndex()) continue;
            double[] tb = textFrameBounds(tf);
            if (tb == null || tb.length < 4) continue;
            double[] scaledRb = scaledBounds(ctx, rbRaw);
            if ((isSimilarTextFrameVisualShell(rbRaw, tb) || isSimilarTextFrameVisualShell(scaledRb, tb))
                    && (!vectorShapeShell || isBestVectorShapeBackdropForTextFrame(ctx, rg, tf, rbRaw, scaledRb))) {
                return Math.max(1, tf.zOrder() - 1);
            }
        }
        return -1;
    }

    public static int inferredTextLineBackdropZOrder(ResolvedBuildContext ctx, RenderedGroup rg) {
        if (ctx == null || ctx.resolvedData == null || rg == null) return -1;
        if (ctx.hasOwnershipPlan(rg)) return -1;
        if (!VisualLayeringRules.isPageObject(rg)) return -1;
        if (Boolean.FALSE.equals(rg.placementAllowed())) return -1;
        if (!"indesign_png".equals(rg.visualOwner())) return -1;
        if ("hwpx_tf".equals(rg.textOwner())) return -1;
        if (Boolean.TRUE.equals(rg.containsText()) || Boolean.TRUE.equals(rg.containsEditableText())) return -1;
        if (!"vector_shape".equals(rg.reason())) return -1;

        double[] rbRaw = rg.bounds();
        if (rbRaw == null || rbRaw.length < 4) return -1;
        double w = rbRaw[3] - rbRaw[1];
        double h = rbRaw[2] - rbRaw[0];
        if (w < 20.0 || h < 1.0 || h > 8.0 || w / h < 4.0) return -1;

        double[] scaledRb = scaledBounds(ctx, rbRaw);
        int minZ = Integer.MAX_VALUE;
        for (ResolvedTextFrame tf : ctx.resolvedData.textFrames()) {
            if (tf == null || tf.pageIndex() != rg.pageIndex()) continue;
            if (!hasSemanticText(tf)) continue;
            double[] tb = textFrameBounds(tf);
            if (tb != null && tb.length >= 4
                    && (isTextLineBackdropInsideTextFrame(rbRaw, tb)
                    || isTextLineBackdropInsideTextFrame(scaledRb, tb))) {
                minZ = Math.min(minZ, tf.zOrder());
            }
            java.util.List<ResolvedTextFrame.ComposedLine> lines = tf.composedLines();
            if (lines == null || lines.isEmpty()) continue;
            for (ResolvedTextFrame.ComposedLine line : lines) {
                if (line == null || line.bounds() == null || line.bounds().length < 4) continue;
                double[] lb = line.bounds();
                double lineArea = area(lb);
                if (lineArea <= 0) continue;
                double overlap = Math.max(overlapArea(rbRaw, lb), overlapArea(scaledRb, lb));
                if (overlap / lineArea >= 0.20) {
                    minZ = Math.min(minZ, tf.zOrder());
                    break;
                }
            }
        }
        return minZ == Integer.MAX_VALUE ? -1 : Math.max(1, minZ - 1);
    }

    private static int ownedTextFrameShellZOrder(ResolvedBuildContext ctx, RenderedGroup rg) {
        if (ctx == null || ctx.resolvedData == null || rg == null) return -1;
        if (!"hwpx_tf".equals(rg.textOwner())) return -1;
        String[] editableIds = rg.editableTextFrameIds();
        if (editableIds == null || editableIds.length == 0) return -1;
        int minZ = Integer.MAX_VALUE;
        for (String editableId : editableIds) {
            ResolvedTextFrame tf = ctx.resolvedData.getTextFrame(editableId);
            if (tf == null || tf.pageIndex() != rg.pageIndex()) continue;
            minZ = Math.min(minZ, tf.zOrder());
        }
        return minZ == Integer.MAX_VALUE ? -1 : Math.max(1, minZ - 1);
    }

    private static int conceptDiagramLabelShellZOrder(ResolvedBuildContext ctx, RenderedGroup rg) {
        if (!isConceptDiagramLabelShell(ctx, rg)) return -1;
        int minZ = Integer.MAX_VALUE;
        for (ResolvedTextFrame tf : conceptDiagramTextFramesForPage(ctx, rg.pageIndex())) {
            double[] tb = textFrameBounds(tf);
            if (isConceptDiagramShellForTextFrame(ctx, rg, tb)) {
                minZ = Math.min(minZ, tf.zOrder());
            }
        }
        return minZ == Integer.MAX_VALUE ? -1 : Math.max(1, minZ - 1);
    }

    private static boolean isConceptDiagramLabelShell(ResolvedBuildContext ctx, RenderedGroup rg) {
        if (ctx == null || ctx.resolvedData == null || rg == null) return false;
        if (!VisualLayeringRules.isPageObject(rg)) return false;
        if (Boolean.FALSE.equals(rg.placementAllowed())) return false;
        if (!"indesign_png".equals(rg.visualOwner())) return false;
        if (Boolean.TRUE.equals(rg.containsText()) || Boolean.TRUE.equals(rg.containsEditableText())) return false;
        if (!VisualLayeringRules.isTextFrameVisualShellReason(rg.reason())) return false;
        double[] rb = rg.bounds();
        if (rb == null || rb.length < 4 || area(rb) <= 0) return false;
        for (ResolvedTextFrame tf : conceptDiagramTextFramesForPage(ctx, rg.pageIndex())) {
            if (isConceptDiagramShellForTextFrame(ctx, rg, textFrameBounds(tf))) {
                return true;
            }
        }
        return false;
    }

    private static java.util.List<ResolvedTextFrame> conceptDiagramTextFramesForPage(
            ResolvedBuildContext ctx, int pageIndex) {
        java.util.List<ResolvedTextFrame> frames = new java.util.ArrayList<>();
        if (ctx == null || ctx.resolvedData == null || ctx.conceptDiagramTextFrameIds == null) return frames;
        for (String tfId : ctx.conceptDiagramTextFrameIds) {
            if (tfId == null) continue;
            ResolvedTextFrame tf = ctx.resolvedData.getTextFrame(tfId);
            if (tf != null && tf.pageIndex() == pageIndex && hasSemanticText(tf)) {
                frames.add(tf);
            }
        }
        return frames;
    }

    private static boolean isConceptDiagramShellForTextFrame(
            ResolvedBuildContext ctx, RenderedGroup shell, double[] tfBounds) {
        if (ctx == null || shell == null || tfBounds == null || tfBounds.length < 4) return false;
        double[] rb = shell.bounds();
        if (rb == null || rb.length < 4) return false;
        if (isConceptDiagramShellForTextFrameSameScale(rb, tfBounds)) return true;
        if (ctx.scaleFactor > 0 && Math.abs(ctx.scaleFactor - 1.0) > 0.001) {
            return isConceptDiagramShellForTextFrameSameScale(scaledBounds(ctx, rb), tfBounds);
        }
        return false;
    }

    private static boolean isConceptDiagramShellForTextFrameSameScale(double[] shellBounds, double[] tfBounds) {
        double tfArea = area(tfBounds);
        double shellArea = area(shellBounds);
        if (tfArea <= 0 || shellArea <= 0) return false;
        double areaRatio = shellArea / tfArea;
        if (areaRatio < CONCEPT_LABEL_SHELL_MIN_AREA_RATIO
                || areaRatio > CONCEPT_LABEL_SHELL_MAX_AREA_RATIO) {
            return false;
        }
        return overlapArea(shellBounds, tfBounds) / tfArea >= CONCEPT_LABEL_SHELL_OVERLAP_MIN;
    }

    private static boolean isBestVectorShapeBackdropForTextFrame(
            ResolvedBuildContext ctx,
            RenderedGroup candidate,
            ResolvedTextFrame tf,
            double[] candidateRawBounds,
            double[] candidateScaledBounds) {
        if (ctx == null || ctx.resolvedData == null || candidate == null || tf == null) return false;
        double[] tb = textFrameBounds(tf);
        if (tb == null || tb.length < 4) return false;

        double candidateScore = textFrameBackdropScore(candidateRawBounds, candidateScaledBounds, tb);
        if (candidateScore <= 0) return false;

        for (RenderedGroup other : ctx.resolvedData.allRenderedFloatingItems()) {
            if (other == null || other.id() == candidate.id()) continue;
            if (other.pageIndex() != candidate.pageIndex()) continue;
            if (!VisualLayeringRules.isPageObject(other)) continue;
            if (Boolean.FALSE.equals(other.placementAllowed())) continue;
            if (!"indesign_png".equals(other.visualOwner())) continue;
            if (Boolean.TRUE.equals(other.containsText()) || Boolean.TRUE.equals(other.containsEditableText())) {
                continue;
            }
            if (!"vector_shape".equals(other.reason())) continue;
            double[] ob = other.bounds();
            if (ob == null || ob.length < 4) continue;
            double[] scaledOb = scaledBounds(ctx, ob);
            if (!isSimilarTextFrameVisualShell(ob, tb)
                    && !isSimilarTextFrameVisualShell(scaledOb, tb)) {
                continue;
            }
            double otherScore = textFrameBackdropScore(ob, scaledOb, tb);
            if (otherScore > candidateScore + 0.0001) return false;
        }
        return true;
    }

    private static double textFrameBackdropScore(double[] rawBounds, double[] scaledBounds, double[] tfBounds) {
        return Math.max(textFrameBackdropScore(rawBounds, tfBounds),
                textFrameBackdropScore(scaledBounds, tfBounds));
    }

    private static double textFrameBackdropScore(double[] shellBounds, double[] tfBounds) {
        double tfArea = area(tfBounds);
        double shellArea = area(shellBounds);
        if (tfArea <= 0 || shellArea <= 0) return -1.0;
        double overlapRatio = overlapArea(shellBounds, tfBounds) / tfArea;
        if (overlapRatio < TF_VISUAL_SHELL_OVERLAP_MIN) return -1.0;
        double areaRatio = shellArea / tfArea;
        if (areaRatio < TF_VISUAL_SHELL_MIN_AREA_RATIO || areaRatio > TF_VISUAL_SHELL_MAX_AREA_RATIO) {
            return -1.0;
        }
        double shellCenterY = (shellBounds[0] + shellBounds[2]) / 2.0;
        double shellCenterX = (shellBounds[1] + shellBounds[3]) / 2.0;
        double tfCenterY = (tfBounds[0] + tfBounds[2]) / 2.0;
        double tfCenterX = (tfBounds[1] + tfBounds[3]) / 2.0;
        double centerDistance = Math.hypot(shellCenterY - tfCenterY, shellCenterX - tfCenterX);
        double areaPenalty = Math.abs(Math.log(areaRatio));
        return overlapRatio * 1000.0 - centerDistance * 10.0 - areaPenalty * 100.0;
    }

    private static int semanticTextOverlapShellZOrder(ResolvedBuildContext ctx, RenderedGroup rg, double[] rbRaw) {
        if (!"editable_textframe_visual_shell".equals(rg.reason())) return -1;
        int minZ = Integer.MAX_VALUE;
        String shellId = String.valueOf(rg.id());
        double[] scaledRb = scaledBounds(ctx, rbRaw);
        for (ResolvedTextFrame tf : ctx.resolvedData.textFrames()) {
            if (tf == null || tf.pageIndex() != rg.pageIndex()) continue;
            if (shellId.equals(tf.id())) continue;
            if (!hasSemanticText(tf)) continue;
            double[] tb = textFrameBounds(tf);
            if (tb == null || tb.length < 4) continue;
            double tfArea = area(tb);
            if (tfArea <= 0) continue;
            double overlap = Math.max(overlapArea(rbRaw, tb), overlapArea(scaledRb, tb));
            if (overlap / tfArea >= 0.10) {
                minZ = Math.min(minZ, tf.zOrder());
            }
        }
        return minZ == Integer.MAX_VALUE ? -1 : Math.max(1, minZ - 2);
    }

    private static int titleLabelBackgroundZOrder(ResolvedBuildContext ctx, RenderedGroup rg) {
        if (ctx == null || ctx.resolvedData == null || rg == null) return -1;
        if (!VisualLayeringRules.isPageObject(rg)) return -1;
        if (!"indesign_png".equals(rg.visualOwner())) return -1;
        if ("hwpx_tf".equals(rg.textOwner())) return -1;
        if (Boolean.TRUE.equals(rg.containsText()) || Boolean.TRUE.equals(rg.containsEditableText())) return -1;
        double[] rbRaw = rg.bounds();
        if (rbRaw == null || rbRaw.length < 4) return -1;

        double w = rbRaw[3] - rbRaw[1];
        double h = rbRaw[2] - rbRaw[0];
        if (w < 20.0 || h < 3.0 || h > 16.0 || w / h < 3.0) return -1;

        double[] scaledRb = scaledBounds(ctx, rbRaw);
        int minZ = Integer.MAX_VALUE;
        for (ResolvedTextFrame tf : ctx.resolvedData.textFrames()) {
            if (tf == null || tf.pageIndex() != rg.pageIndex()) continue;
            if (!hasShortSemanticText(tf)) continue;
            double[] tb = textFrameBounds(tf);
            if (tb == null || tb.length < 4) continue;
            double tfArea = area(tb);
            if (tfArea <= 0) continue;
            double overlap = Math.max(overlapArea(rbRaw, tb), overlapArea(scaledRb, tb));
            if (overlap / tfArea >= 0.35) {
                minZ = Math.min(minZ, tf.zOrder());
            }
        }
        return minZ == Integer.MAX_VALUE ? -1 : Math.max(1, minZ - 1);
    }

    private static boolean isTextLineBackdropInsideTextFrame(double[] rb, double[] tb) {
        if (rb == null || rb.length < 4 || tb == null || tb.length < 4) return false;
        double w = rb[3] - rb[1];
        double h = rb[2] - rb[0];
        if (w < 20.0 || h < 1.0 || h > 8.0 || w / h < 4.0) return false;
        double rbArea = area(rb);
        if (rbArea <= 0.0) return false;
        double overlap = overlapArea(rb, tb);
        if (overlap / rbArea < 0.65) return false;
        double centerY = (rb[0] + rb[2]) / 2.0;
        double centerX = (rb[1] + rb[3]) / 2.0;
        double yTol = Math.max(2.0, h);
        double xTol = Math.max(2.0, h);
        return centerY >= tb[0] - yTol
                && centerY <= tb[2] + yTol
                && centerX >= tb[1] - xTol
                && centerX <= tb[3] + xTol;
    }

    private static boolean isSimilarTextFrameVisualShell(double[] shellBounds, double[] tfBounds) {
        double tfArea = area(tfBounds);
        double shellArea = area(shellBounds);
        if (tfArea <= 0 || shellArea <= 0) return false;
        double areaRatio = shellArea / tfArea;
        if (areaRatio < TF_VISUAL_SHELL_MIN_AREA_RATIO || areaRatio > TF_VISUAL_SHELL_MAX_AREA_RATIO) {
            return false;
        }
        return overlapArea(shellBounds, tfBounds) / tfArea >= TF_VISUAL_SHELL_OVERLAP_MIN;
    }

    private static boolean hasSemanticText(ResolvedTextFrame tf) {
        return !visibleText(tf).isEmpty();
    }

    private static boolean hasShortSemanticText(ResolvedTextFrame tf) {
        String text = visibleText(tf);
        if (text.isEmpty() || text.length() > 60) return false;
        return !text.contains("\n") && !text.contains("\r");
    }

    private static String visibleText(ResolvedTextFrame tf) {
        String text = tf != null ? tf.frameVisibleText() : null;
        if (text == null) return "";
        return text.replace("\uFFFC", "")
                .replace("\u0016", "")
                .replace("\u0018", "")
                .trim();
    }

    private static double[] textFrameBounds(ResolvedTextFrame tf) {
        if (tf == null) return null;
        double[] b = tf.pageRelativeBounds();
        if (b != null && b.length >= 4) return b;
        return tf.geometricBounds();
    }

    private static double[] scaledBounds(ResolvedBuildContext ctx, double[] b) {
        return new double[] {
                b[0] * ctx.scaleFactor,
                b[1] * ctx.scaleFactor,
                b[2] * ctx.scaleFactor,
                b[3] * ctx.scaleFactor
        };
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
