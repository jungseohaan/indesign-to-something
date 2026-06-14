package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.phase3;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTInlineObject;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTParagraph;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTextRun;
import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.CoordinateConverter;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.TextStyleApplicator;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.TextRunSegmenter;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ResolvedBuildContext;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.SimpleButtonLabelPlan;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedPageItem;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;

/** Executes Stage 1 SimpleButtonLabelPlan as an inline AST object. */
public final class SimpleButtonLabelInlineFactory {
    private SimpleButtonLabelInlineFactory() {}

    public static boolean hasPlan(ResolvedBuildContext ctx, int anchorDomId) {
        return ctx != null && ctx.simpleButtonLabelPlan(anchorDomId) != null;
    }

    public static ASTInlineObject create(ResolvedBuildContext ctx, int anchorDomId) {
        if (ctx == null || ctx.resolvedData == null) return null;
        SimpleButtonLabelPlan plan = ctx.simpleButtonLabelPlan(anchorDomId);
        if (plan == null) return null;
        if (plan.mode == SimpleButtonLabelPlan.Mode.COMPLETE_PNG) {
            return InlineFrameHandler.loadCompleteSimpleButtonLabelInlineObject(ctx, plan.anchorDomId);
        }
        return createTextShell(ctx, plan);
    }

    private static ASTInlineObject createTextShell(ResolvedBuildContext ctx, SimpleButtonLabelPlan plan) {
        ResolvedPageItem anchor = ctx.resolvedData.getPageItem(String.valueOf(plan.anchorDomId));
        ResolvedPageItem shell = ctx.resolvedData.getPageItem(String.valueOf(plan.shellDomId));
        if (anchor == null || shell == null || plan.labelText == null || plan.labelText.isEmpty()) {
            return null;
        }
        double[] bounds = anchor.geometricBounds();
        if (bounds == null || bounds.length < 4) bounds = anchor.pageRelativeBounds();
        if (bounds == null || bounds.length < 4) {
            bounds = shell.geometricBounds();
            if (bounds == null || bounds.length < 4) bounds = shell.pageRelativeBounds();
        }
        if (bounds == null || bounds.length < 4) return null;
        double w = Math.abs(bounds[3] - bounds[1]);
        double h = Math.abs(bounds[2] - bounds[0]);
        if (w <= 0 || h <= 0) return null;

        ASTInlineObject obj = new ASTInlineObject();
        obj.kind(ASTInlineObject.ObjectKind.INLINE_TEXT_FRAME);
        obj.sourceId("u" + Integer.toHexString(plan.anchorDomId));
        obj.width(CoordinateConverter.pointsToHwpunits(w));
        obj.height(CoordinateConverter.pointsToHwpunits(h));
        obj.verticalJustification("CenterAlign");
        obj.nativeGraphicsAllowed(true);
        obj.bundlePath("simple-button-label-raster");
        obj.shellShapeType(shell.type());

        String fillHex = ctx.resolvedData.resolveColorHex(shell.fillColorName());
        if (fillHex != null) {
            obj.fillColor(fillHex);
            obj.fillTint(shell.fillTint() > 0 ? shell.fillTint() : 100);
        }
        String strokeHex = ctx.resolvedData.resolveColorHex(shell.strokeColorName());
        if (strokeHex != null && shell.strokeWeight() > 0) {
            obj.strokeColor(strokeHex);
            obj.strokeTint(shell.strokeTint() > 0 ? shell.strokeTint() : 100);
            obj.strokeWeight(shell.strokeWeight());
        }
        if ("Oval".equals(shell.type())) {
            obj.cornerRadius(h / 2.0);
        } else if (shell.cornerRadius() > 0) {
            obj.cornerRadius(shell.cornerRadius());
        }

        ASTParagraph paragraph = new ASTParagraph();
        paragraph.alignment("CenterAlign");
        TextStyleApplicator.ExplicitStyle style = new TextStyleApplicator.ExplicitStyle();
        style.fontFamily = plan.labelFontFamily;
        style.fontStyle = plan.labelFontStyle;
        style.textColorHex = plan.labelTextColorHex;
        style.fontSizePt = labelFontSizePt(plan, h);
        style.tracking = plan.labelTracking;
        style.horizontalScale = plan.labelHorizontalScale;
        for (ASTTextRun run : TextRunSegmenter.fromSyntheticText(plan.labelText, style, false)) {
            paragraph.addItem(run);
        }
        obj.addParagraph(paragraph);
        byte[] raster = renderSimpleLabelPng(plan, shell, fillHex, strokeHex, w, h, style.fontSizePt);
        if (raster != null && raster.length > 0) {
            obj.imageFormat("png");
            obj.imageData(raster);
        }
        return obj;
    }

    private static double labelFontSizePt(SimpleButtonLabelPlan plan, double shellHeightPt) {
        double size = plan.labelFontSizePt != null && plan.labelFontSizePt > 0
                ? plan.labelFontSizePt
                : 0.0;
        if (shellHeightPt > 0) {
            double max = shellHeightPt * 0.8;
            if (size <= 0 || size > max) size = max;
        }
        return size;
    }

    private static byte[] renderSimpleLabelPng(
            SimpleButtonLabelPlan plan,
            ResolvedPageItem shell,
            String fillHex,
            String strokeHex,
            double widthPt,
            double heightPt,
            double fontSizePt) {
        if (plan == null || plan.labelText == null || plan.labelText.isEmpty()
                || widthPt <= 0 || heightPt <= 0) {
            return null;
        }
        try {
            double scale = 6.0;
            int widthPx = Math.max(4, (int) Math.ceil(widthPt * scale));
            int heightPx = Math.max(4, (int) Math.ceil(heightPt * scale));
            BufferedImage image = new BufferedImage(widthPx, heightPx, BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = image.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);

            Color fill = parseColor(fillHex, new Color(0, 0, 0, 0));
            Color stroke = parseColor(strokeHex, null);
            Shape shape;
            if (shell != null && "Oval".equals(shell.type())) {
                shape = new java.awt.geom.Ellipse2D.Double(0, 0, widthPx - 1, heightPx - 1);
            } else {
                double arc = Math.max(0, shell != null ? shell.cornerRadius() * scale * 2.0 : 0);
                if (arc <= 0 && Math.abs(widthPt - heightPt) < 0.2) {
                    arc = Math.min(widthPx, heightPx);
                }
                shape = new RoundRectangle2D.Double(0, 0, widthPx - 1, heightPx - 1, arc, arc);
            }
            g.setColor(fill);
            g.fill(shape);
            if (stroke != null && shell != null && shell.strokeWeight() > 0) {
                g.setColor(stroke);
                g.setStroke(new BasicStroke(Math.max(1f, (float) (shell.strokeWeight() * scale))));
                g.draw(shape);
            }

            Color textColor = parseColor(plan.labelTextColorHex, Color.WHITE);
            g.setColor(textColor);
            int fontPx = Math.max(4, (int) Math.round(fontSizePt * scale));
            Font font = new Font(
                    plan.labelFontFamily != null && !plan.labelFontFamily.isEmpty()
                            ? plan.labelFontFamily
                            : Font.SANS_SERIF,
                    Font.PLAIN,
                    fontPx);
            g.setFont(font);
            FontMetrics fm = g.getFontMetrics();
            String text = plan.labelText;
            int textWidth = fm.stringWidth(text);
            float x = (float) ((widthPx - textWidth) / 2.0);
            float y = (float) ((heightPx - fm.getHeight()) / 2.0 + fm.getAscent());
            g.drawString(text, x, y);
            g.dispose();

            ByteArrayOutputStream out = new ByteArrayOutputStream();
            ImageIO.write(image, "png", out);
            image.flush();
            return out.toByteArray();
        } catch (Exception ignored) {
            return null;
        }
    }

    private static Color parseColor(String hex, Color fallback) {
        if (hex == null || !hex.startsWith("#") || hex.length() != 7) return fallback;
        try {
            int r = Integer.parseInt(hex.substring(1, 3), 16);
            int g = Integer.parseInt(hex.substring(3, 5), 16);
            int b = Integer.parseInt(hex.substring(5, 7), 16);
            return new Color(r, g, b);
        } catch (NumberFormatException e) {
            return fallback;
        }
    }
}
