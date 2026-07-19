package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership;

import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLCharacterRun;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLParagraph;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLStory;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTable;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTableCell;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTableRow;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ResolvedBuildContext;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.RenderedGroup;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedData;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedPage;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedPageItem;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedParagraph;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedRun;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedStory;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedTable;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedTextFrame;
import org.junit.Assert;
import org.junit.Test;

import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;

public class OwnershipPlannerTest {

    @Test
    public void completeSimpleInlineLabelOwnsTextAndVisibleInlinePng() {
        ResolvedData data = new ResolvedData();
        ResolvedTextFrame label = textFrame(101, "가");
        label.isInline(true);
        data.addTextFrame(label);
        ResolvedPageItem anchor = pageItem(
                100,
                "Oval",
                new double[] { 10.0, 10.0, 19.0, 19.0 },
                "C=60 M=38 Y=3 K=0",
                null,
                0.0);
        anchor.isInline(true);
        data.addPageItem(anchor);
        ResolvedPageItem labelItem = pageItem(
                101,
                "TextFrame",
                new double[] { 10.0, 10.0, 19.0, 19.0 },
                null,
                null,
                0.0);
        labelItem.parentId("100");
        labelItem.isInline(true);
        data.addPageItem(labelItem);
        RenderedGroup inline = rendered(
                100,
                "inline_object",
                "inline_object",
                "inline_graphic_only",
                "indesign_png",
                "indesign_png",
                new String[] { "101" },
                new int[] { 100, 101 });
        data.addRenderedFloatingItem(inline);
        addInlineAnchor(data, 100);

        ResolvedBuildContext ctx = plan(data);
        ObjectPlan plan = findRenderedPlan(ctx, 100, "inline_graphic_only");

        Assert.assertNotNull(plan);
        Assert.assertEquals(TextAction.OWNED_BY_PNG, plan.textAction);
        Assert.assertEquals(VisualAction.PLACE_INLINE_PNG, plan.visualAction);
        Assert.assertEquals(Placement.INLINE, plan.placement);
    }

    @Test
    public void tableCarrierContainingAtomicLabelDoesNotBecomeCompleteMarkerPng() {
        ResolvedData data = new ResolvedData();
        ResolvedTextFrame label = textFrame(101, "가");
        label.isInline(true);
        data.addTextFrame(label);

        ResolvedPageItem marker = pageItem(
                100,
                "Oval",
                new double[] { 10.0, 10.0, 19.0, 19.0 },
                "C=60 M=38 Y=3 K=0",
                null,
                0.0);
        marker.isInline(true);
        data.addPageItem(marker);
        ResolvedPageItem labelItem = pageItem(
                101,
                "TextFrame",
                new double[] { 10.0, 10.0, 19.0, 19.0 },
                null,
                null,
                0.0);
        labelItem.parentId("100");
        labelItem.isInline(true);
        data.addPageItem(labelItem);

        ResolvedPageItem tableCarrier = pageItem(
                200,
                "Rectangle",
                new double[] { 0.0, 0.0, 125.0, 396.0 },
                null,
                "Black",
                0.25);
        tableCarrier.isInline(true);
        data.addPageItem(tableCarrier);

        RenderedGroup carrierRender = rendered(
                200,
                "inline_object",
                "inline_object",
                "visual_marker_label_indesign_png",
                "indesign_png",
                "indesign_png",
                new String[] { "101" },
                new int[] { 200, 101, 27000 });
        carrierRender.bounds(new double[] { 0.0, 0.0, 125.0, 396.0 });
        data.addRenderedFloatingItem(carrierRender);
        addInlineAnchor(data, 200);

        ResolvedBuildContext ctx = plan(data);
        ObjectPlan plan = findRenderedPlan(ctx, 200, "visual_marker_label_indesign_png");

        Assert.assertNotNull(plan);
        Assert.assertEquals(TextAction.DROP_TEXT, plan.textAction);
        Assert.assertEquals(VisualAction.DROP_VISUAL, plan.visualAction);
        Assert.assertFalse(data.shouldUseCompletePngForSimpleButtonLabel(carrierRender));
    }

    @Test
    public void extractorAtomicMetadataCanDefineCompleteMarkerWithoutPageItemParentChain() {
        ResolvedData data = new ResolvedData();
        ResolvedTextFrame label = textFrame(101, "가");
        label.isInline(true);
        data.addTextFrame(label);

        RenderedGroup inline = rendered(
                100,
                "inline_object",
                "inline_object",
                "inline_badge_baked",
                "indesign_png",
                "indesign_png",
                new String[] { "101" },
                new int[] { 100, 101 });
        inline.atomicObjectKind("COMPLETE_PNG");
        inline.atomicSourceObjectIds(new int[] { 100, 101 });
        inline.atomicOwnedTextFrameIds(new int[] { 101 });
        inline.atomicVisualSourceObjectIds(new int[] { 100 });
        data.addRenderedFloatingItem(inline);
        addInlineAnchor(data, 100);

        ResolvedBuildContext ctx = plan(data);
        ObjectPlan plan = findRenderedPlan(ctx, 100, "inline_badge_baked");

        Assert.assertNotNull(plan);
        Assert.assertEquals(TextAction.OWNED_BY_PNG, plan.textAction);
        Assert.assertEquals(VisualAction.PLACE_INLINE_PNG, plan.visualAction);
        Assert.assertTrue(data.shouldUseCompletePngForSimpleButtonLabel(inline));
    }

    @Test
    public void extractorAtomicMetadataRejectsCarrierOutsideClosedBundle() {
        ResolvedData data = new ResolvedData();
        ResolvedTextFrame label = textFrame(101, "가");
        label.isInline(true);
        data.addTextFrame(label);

        RenderedGroup carrierRender = rendered(
                200,
                "inline_object",
                "inline_object",
                "visual_marker_label_indesign_png",
                "indesign_png",
                "indesign_png",
                new String[] { "101" },
                new int[] { 200, 101 });
        carrierRender.atomicObjectKind("COMPLETE_PNG");
        carrierRender.atomicSourceObjectIds(new int[] { 100, 101 });
        carrierRender.atomicOwnedTextFrameIds(new int[] { 101 });
        carrierRender.atomicVisualSourceObjectIds(new int[] { 100 });
        data.addRenderedFloatingItem(carrierRender);
        addInlineAnchor(data, 200);

        ResolvedBuildContext ctx = plan(data);
        ObjectPlan plan = findRenderedPlan(ctx, 200, "visual_marker_label_indesign_png");

        Assert.assertNotNull(plan);
        Assert.assertEquals(TextAction.DROP_TEXT, plan.textAction);
        Assert.assertEquals(VisualAction.DROP_VISUAL, plan.visualAction);
        Assert.assertFalse(data.shouldUseCompletePngForSimpleButtonLabel(carrierRender));
    }

    @Test
    public void canonicalAtomicLabelCanUseTextlessShellWithHwpxText() {
        ResolvedData data = new ResolvedData();
        ResolvedTextFrame label = textFrame(101, "가");
        label.isInline(true);
        data.addTextFrame(label);
        ResolvedPageItem marker = pageItem(
                100,
                "Oval",
                new double[] { 10.0, 10.0, 19.0, 19.0 },
                "C=60 M=38 Y=3 K=0",
                null,
                0.0);
        marker.isInline(true);
        data.addPageItem(marker);
        ResolvedPageItem labelItem = pageItem(
                101,
                "TextFrame",
                new double[] { 10.0, 10.0, 19.0, 19.0 },
                null,
                null,
                0.0);
        labelItem.parentId("100");
        labelItem.isInline(true);
        data.addPageItem(labelItem);
        RenderedGroup shell = rendered(
                100,
                "inline_object",
                "inline_object",
                "visual_label_text_hidden_shell",
                "indesign_png",
                "hwpx_tf",
                new String[] { "101" },
                new int[] { 100, 101 });
        shell.containsText(Boolean.FALSE);
        shell.containsEditableText(Boolean.FALSE);
        shell.textHiddenBeforeExport(true);
        data.addRenderedFloatingItem(shell);
        addInlineAnchor(data, 100);

        ResolvedBuildContext ctx = plan(data);
        ObjectPlan plan = findRenderedPlan(ctx, 100, "visual_label_text_hidden_shell");

        Assert.assertNotNull(plan);
        Assert.assertEquals(TextAction.OWNED_BY_HWPX_TEXT, plan.textAction);
        Assert.assertEquals(VisualAction.PLACE_TEXT_SHELL, plan.visualAction);
        Assert.assertTrue(data.shouldUseTextlessShellForAtomicMarkerLabel(shell));
        Assert.assertFalse(data.shouldUseCompletePngForSimpleButtonLabel(shell));
    }

    @Test
    public void extractorAtomicMetadataCanDefineTextlessShellWithoutPageItemParentChain() {
        ResolvedData data = new ResolvedData();
        ResolvedTextFrame label = textFrame(101, "가");
        label.isInline(true);
        data.addTextFrame(label);

        RenderedGroup shell = rendered(
                100,
                "inline_object",
                "inline_object",
                "inline_badge",
                "indesign_png",
                "hwpx_tf",
                new String[] { "101" },
                new int[] { 100, 101 });
        shell.containsText(Boolean.FALSE);
        shell.containsEditableText(Boolean.FALSE);
        shell.textHiddenBeforeExport(true);
        shell.atomicObjectKind("TEXTLESS_SHELL_WITH_TF");
        shell.atomicSourceObjectIds(new int[] { 100, 101 });
        shell.atomicOwnedTextFrameIds(new int[] { 101 });
        shell.atomicVisualSourceObjectIds(new int[] { 100 });
        data.addRenderedFloatingItem(shell);
        addInlineAnchor(data, 100);

        ResolvedBuildContext ctx = plan(data);
        ObjectPlan plan = findRenderedPlan(ctx, 100, "inline_badge");

        Assert.assertNotNull(plan);
        Assert.assertEquals(TextAction.OWNED_BY_HWPX_TEXT, plan.textAction);
        Assert.assertEquals(VisualAction.PLACE_TEXT_SHELL, plan.visualAction);
        Assert.assertTrue(data.shouldUseTextlessShellForAtomicMarkerLabel(shell));
        Assert.assertFalse(data.shouldUseCompletePngForSimpleButtonLabel(shell));
    }

    @Test
    public void simpleButtonLabelPlanUsesCanonicalTextlessShellRender() {
        ResolvedData data = new ResolvedData();
        ResolvedTextFrame label = textFrame(101, "가");
        label.isInline(true);
        data.addTextFrame(label);
        ResolvedPageItem marker = pageItem(
                100,
                "Oval",
                new double[] { 10.0, 10.0, 19.0, 19.0 },
                "C=60 M=38 Y=3 K=0",
                null,
                0.0);
        marker.isInline(true);
        data.addPageItem(marker);
        ResolvedPageItem labelItem = pageItem(
                101,
                "TextFrame",
                new double[] { 10.0, 10.0, 19.0, 19.0 },
                null,
                null,
                0.0);
        labelItem.parentId("100");
        labelItem.isInline(true);
        data.addPageItem(labelItem);
        RenderedGroup shell = rendered(
                100,
                "inline_object",
                "inline_object",
                "visual_label_text_hidden_shell",
                "indesign_png",
                "hwpx_tf",
                new String[] { "101" },
                new int[] { 100, 101 });
        shell.containsText(Boolean.FALSE);
        shell.containsEditableText(Boolean.FALSE);
        shell.textHiddenBeforeExport(true);
        shell.file("rendered_frames/atomic_shell.png");
        shell.bounds(new double[] { 11.0, 12.0, 18.0, 20.0 });
        data.addRenderedFloatingItem(shell);
        addInlineAnchor(data, 100);

        ResolvedBuildContext ctx = plan(data);
        SimpleButtonLabelPlanner.plan(ctx);
        ObjectPlan objectPlan = findPlanByKind(ctx, 100, "simple_button_label:");

        Assert.assertNotNull(objectPlan);
        Assert.assertEquals(TextAction.OWNED_BY_HWPX_TEXT, objectPlan.textAction);
        Assert.assertEquals(VisualAction.PLACE_TEXT_SHELL, objectPlan.visualAction);
        Assert.assertEquals(Integer.valueOf(100), objectPlan.renderId);
        Assert.assertEquals("rendered_frames/atomic_shell.png", objectPlan.file);
        Assert.assertArrayEquals(new int[] { 101 }, objectPlan.ownedTextFrameIds);
    }

    @Test
    public void childMarkerRenderWithoutAnchorSourceDoesNotBecomeCompleteSimpleLabel() {
        ResolvedData data = new ResolvedData();
        ResolvedTextFrame label = textFrame(101, "1");
        label.isInline(true);
        data.addTextFrame(label);

        ResolvedPageItem anchor = pageItem(
                100,
                "Rectangle",
                new double[] { 10.0, 10.0, 20.0, 20.0 },
                "C=50 M=100 Y=0 K=6",
                null,
                0.0);
        anchor.isInline(true);
        data.addPageItem(anchor);

        ResolvedPageItem labelItem = pageItem(
                101,
                "TextFrame",
                new double[] { 11.0, 12.0, 19.0, 18.0 },
                null,
                null,
                0.0);
        labelItem.parentId("100");
        labelItem.isInline(true);
        data.addPageItem(labelItem);

        RenderedGroup childMarker = rendered(
                102,
                "page_object",
                "page_object",
                "visual_marker_label_indesign_png",
                "indesign_png",
                "indesign_png",
                new String[] { "101" },
                new int[] { 102, 101 });
        data.addRenderedFloatingItem(childMarker);

        ResolvedBuildContext ctx = plan(data);
        SimpleButtonLabelPlanner.plan(ctx);
        SimpleButtonLabelPlan labelPlan = ctx.simpleButtonLabelPlan(100);
        ObjectPlan objectPlan = findPlanByKind(ctx, 100, "simple_button_label:");

        Assert.assertNotNull(labelPlan);
        Assert.assertSame(labelPlan, ctx.simpleButtonLabelPlan(101));
        Assert.assertEquals(SimpleButtonLabelPlan.Mode.TEXT_SHELL, labelPlan.mode);
        Assert.assertNotNull(objectPlan);
        Assert.assertEquals(TextAction.OWNED_BY_HWPX_TEXT, objectPlan.textAction);
        Assert.assertEquals(VisualAction.PLACE_TEXT_SHELL, objectPlan.visualAction);
    }

    @Test
    public void companionShellOfCompleteSimpleLabelIsDropped() {
        ResolvedData data = new ResolvedData();
        data.addTextFrame(textFrame(101, "가"));
        RenderedGroup shell = rendered(
                102,
                "page_object",
                "page_object",
                "visual_label_text_hidden_shell",
                "indesign_png",
                "hwpx_tf",
                new String[] { "101" },
                new int[] { 102, 101 });
        data.addRenderedFloatingItem(shell);

        ResolvedBuildContext ctx = plan(data);
        ObjectPlan plan = findRenderedPlan(ctx, 102, "visual_label_text_hidden_shell");

        Assert.assertNotNull(plan);
        Assert.assertEquals(TextAction.DROP_TEXT, plan.textAction);
        Assert.assertEquals(VisualAction.DROP_VISUAL, plan.visualAction);
        Assert.assertTrue(ctx.shouldDropVisualByOwnershipPlan(shell));
    }

    @Test
    public void semanticEditableLabelKeepsHwpxTextAndVisualShell() {
        ResolvedData data = new ResolvedData();
        data.addTextFrame(textFrame(201, "최근 사회·문화적 맥락"));
        RenderedGroup shell = rendered(
                202,
                "page_object",
                "page_object",
                "visual_label_text_hidden_shell",
                "indesign_png",
                "hwpx_tf",
                new String[] { "201" },
                new int[] { 202, 201 });
        data.addRenderedFloatingItem(shell);

        ResolvedBuildContext ctx = plan(data);
        ObjectPlan plan = findRenderedPlan(ctx, 202, "visual_label_text_hidden_shell");

        Assert.assertNotNull(plan);
        Assert.assertEquals(TextAction.OWNED_BY_HWPX_TEXT, plan.textAction);
        Assert.assertEquals(VisualAction.PLACE_TEXT_SHELL, plan.visualAction);
        Assert.assertEquals(VisualLayer.LABEL_BACKDROP, plan.visualLayer);
        Assert.assertEquals(Boolean.FALSE, ctx.inFrontLayerByOwnershipPlan(shell));
        Assert.assertEquals(19, plan.zOrder);
        Assert.assertFalse(ctx.shouldDropVisualByOwnershipPlan(shell));
        Assert.assertEquals(Integer.valueOf(19), ctx.zOrderByOwnershipPlan(shell));
    }

    @Test
    public void placementDisallowedEditableCompositeKeepsHwpxTextAndVisualShell() {
        ResolvedData data = new ResolvedData();
        data.addTextFrame(textFrame(501, "재구성"));
        RenderedGroup shell = rendered(
                502,
                "page_object",
                "page_object",
                "decoration_group",
                "indesign_png",
                "hwpx_tf",
                new String[] { "501" },
                new int[] { 502, 501 });
        shell.placementAllowed(Boolean.FALSE);
        data.addRenderedFloatingItem(shell);

        ResolvedBuildContext ctx = plan(data);
        ObjectPlan textPlan = findPlanByKind(ctx, 501, "text_frame");
        ObjectPlan plan = findRenderedPlan(ctx, 502, "decoration_group");

        Assert.assertNotNull(textPlan);
        Assert.assertEquals(TextAction.OWNED_BY_HWPX_TEXT, textPlan.textAction);
        Assert.assertNotNull(plan);
        Assert.assertEquals(TextAction.OWNED_BY_HWPX_TEXT, plan.textAction);
        Assert.assertEquals(VisualAction.PLACE_TEXT_SHELL, plan.visualAction);
        Assert.assertFalse(ctx.shouldDropVisualByOwnershipPlan(shell));
        Assert.assertFalse(data.isTextOwnedByIndesignPng("501"));
    }

    @Test
    public void semanticTitleLabelIsHwpxTextAndDropsCompletePng() {
        ResolvedData data = new ResolvedData();
        ResolvedTextFrame title = textFrame(251, "나를 깨우는, 문학");
        title.storyId("story-title");
        data.addTextFrame(title);
        data.addStory(story("story-title", "02 단원제목"));

        RenderedGroup complete = rendered(
                250,
                "page_object",
                "page_object",
                "visual_label_indesign_png",
                "indesign_png",
                "indesign_png",
                new String[] { "251" },
                new int[] { 250, 251 });
        data.addRenderedFloatingItem(complete);

        ResolvedBuildContext ctx = plan(data);
        ObjectPlan textPlan = findPlanByKind(ctx, 251, "text_frame");
        ObjectPlan pngPlan = findRenderedPlan(ctx, 250, "visual_label_indesign_png");

        Assert.assertNotNull(textPlan);
        Assert.assertEquals(TextAction.OWNED_BY_HWPX_TEXT, textPlan.textAction);
        Assert.assertNotNull(pngPlan);
        Assert.assertEquals(TextAction.OWNED_BY_HWPX_TEXT, pngPlan.textAction);
        Assert.assertEquals(VisualAction.DROP_VISUAL, pngPlan.visualAction);
        Assert.assertFalse(data.isTextOwnedByIndesignPng("251"));
    }

    @Test
    public void textShellCanMoveToZeroWhenTextIsAtLowestVisibleLayer() {
        ResolvedData data = new ResolvedData();
        ResolvedTextFrame tf = textFrame(301, "쟁점 분석");
        tf.zOrder(1);
        data.addTextFrame(tf);
        RenderedGroup shell = rendered(
                302,
                "page_object",
                "page_object",
                "visual_label_text_hidden_shell",
                "indesign_png",
                "hwpx_tf",
                new String[] { "301" },
                new int[] { 302, 301 });
        data.addRenderedFloatingItem(shell);

        ResolvedBuildContext ctx = plan(data);
        ObjectPlan plan = findRenderedPlan(ctx, 302, "visual_label_text_hidden_shell");

        Assert.assertNotNull(plan);
        Assert.assertEquals(VisualAction.PLACE_TEXT_SHELL, plan.visualAction);
        Assert.assertEquals(0, plan.zOrder);
    }

    @Test
    public void imageBackedPaperDominantTextShellIsContainerBackdrop() throws Exception {
        ResolvedData data = new ResolvedData();
        data.addTextFrame(textFrame(351, "수민"));
        File image = File.createTempFile("img_351_", ".png");
        image.deleteOnExit();
        BufferedImage bi = new BufferedImage(160, 80, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < bi.getHeight(); y++) {
            for (int x = 0; x < bi.getWidth(); x++) {
                bi.setRGB(x, y, 0xffffffff);
            }
        }
        for (int y = 10; y < 60; y++) {
            for (int x = 115; x < 150; x++) {
                bi.setRGB(x, y, 0xffd89aa8);
            }
        }
        ImageIO.write(bi, "png", image);
        RenderedGroup shell = rendered(
                350,
                "page_object",
                "page_object",
                "image_group_text_hidden",
                "indesign_png",
                "hwpx_tf",
                new String[] { "351" },
                new int[] { 350, 351, 352 });
        shell.file(image.getAbsolutePath());
        shell.bounds(new double[] { 10, 10, 80, 160 });
        data.addRenderedFloatingItem(shell);

        ResolvedBuildContext ctx = plan(data);
        ObjectPlan plan = findRenderedPlan(ctx, 350, "image_group_text_hidden");

        Assert.assertNotNull(plan);
        Assert.assertEquals(VisualAction.PLACE_TEXT_SHELL, plan.visualAction);
        Assert.assertEquals(VisualLayer.CONTAINER_BACKDROP, plan.visualLayer);
        Assert.assertEquals(Boolean.FALSE, ctx.inFrontLayerByOwnershipPlan(shell));
    }

    @Test
    public void floatingFallbackIsDroppedWhenSameDomInlinePngIsVisible() {
        ResolvedData data = new ResolvedData();
        RenderedGroup inline = rendered(
                401,
                "inline_object",
                "inline_object",
                "inline_graphic_only",
                "indesign_png",
                "indesign_png",
                null,
                new int[] { 401, 402 });
        RenderedGroup floating = rendered(
                401,
                "page_object",
                "page_object",
                "pure_decoration_group",
                "indesign_png",
                "",
                null,
                new int[] { 401, 402 });
        floating.file("rendered_frames/deco_401.png");
        data.addRenderedFloatingItem(inline);
        data.addRenderedFloatingItem(floating);
        addInlineAnchor(data, 401);

        ResolvedBuildContext ctx = plan(data);
        ObjectPlan inlinePlan = findRenderedPlan(ctx, 401, "inline_graphic_only");
        ObjectPlan floatingPlan = findRenderedPlan(ctx, 401, "pure_decoration_group");

        Assert.assertNotNull(inlinePlan);
        Assert.assertEquals(VisualAction.PLACE_INLINE_PNG, inlinePlan.visualAction);
        Assert.assertNotNull(floatingPlan);
        Assert.assertEquals(VisualAction.DROP_VISUAL, floatingPlan.visualAction);
        Assert.assertTrue(ctx.shouldDropVisualByOwnershipPlan(floating));
    }

    @Test
    public void graphicOnlyAtomicObjectUsesClosedVisualBundleSources() {
        ResolvedData data = new ResolvedData();
        RenderedGroup graphic = rendered(
                501,
                "inline_object",
                "inline_object",
                "inline_graphic_only",
                "indesign_png",
                "none",
                null,
                new int[] { 500, 501, 502 });
        graphic.containsText(Boolean.FALSE);
        graphic.containsEditableText(Boolean.FALSE);
        graphic.atomicObjectKind("GRAPHIC_ONLY");
        graphic.atomicSourceObjectIds(new int[] { 501, 502 });
        graphic.atomicOwnedTextFrameIds(new int[0]);
        graphic.atomicVisualSourceObjectIds(new int[] { 501, 502 });
        data.addRenderedFloatingItem(graphic);
        addInlineAnchor(data, 501);

        ResolvedBuildContext ctx = plan(data);
        ObjectPlan plan = findRenderedPlan(ctx, 501, "inline_graphic_only");

        Assert.assertNotNull(plan);
        Assert.assertEquals(TextAction.DROP_TEXT, plan.textAction);
        Assert.assertEquals(VisualAction.PLACE_INLINE_PNG, plan.visualAction);
        Assert.assertArrayEquals(new int[] { 501, 502 }, plan.sourceObjectIds);
        Assert.assertArrayEquals(new int[] { 501, 502 }, plan.visualSourceObjectIds);
        Assert.assertArrayEquals(new int[0], plan.ownedTextFrameIds);
    }

    @Test
    public void inlineTextlessLabelShellStaysInlineWhenGraphicOnlyCompanionOwnsClosedVisualBundle() {
        ResolvedData data = new ResolvedData();
        ResolvedTextFrame label = textFrame(96423, "예시 답안");
        label.isInline(true);
        data.addTextFrame(label);

        RenderedGroup inlineGraphic = rendered(
                96421,
                "inline_object",
                "inline_object",
                "inline_graphic_only",
                "indesign_png",
                "none",
                null,
                new int[] { 96421, 96422 });
        inlineGraphic.containsText(Boolean.FALSE);
        inlineGraphic.containsEditableText(Boolean.FALSE);
        inlineGraphic.atomicObjectKind("GRAPHIC_ONLY");
        inlineGraphic.atomicSourceObjectIds(new int[] { 96421, 96422 });
        inlineGraphic.atomicOwnedTextFrameIds(new int[0]);
        inlineGraphic.atomicVisualSourceObjectIds(new int[] { 96421, 96422 });

        RenderedGroup shell = rendered(
                96421,
                "page_object",
                "page_object",
                "visual_label_text_hidden_shell",
                "indesign_png",
                "hwpx_tf",
                new String[] { "96423" },
                new int[] { 96421, 96422, 96423 });
        shell.containsText(Boolean.FALSE);
        shell.containsEditableText(Boolean.FALSE);
        shell.textHiddenBeforeExport(true);
        data.addRenderedFloatingItem(inlineGraphic);
        data.addRenderedFloatingItem(shell);
        addInlineAnchor(data, 96421);

        ResolvedBuildContext ctx = plan(data);
        ObjectPlan shellPlan = findRenderedPlan(ctx, 96421, "visual_label_text_hidden_shell");
        ObjectPlan textPlan = findPlanByKind(ctx, 96423, "text_frame");

        Assert.assertNotNull(shellPlan);
        Assert.assertEquals(Placement.INLINE, shellPlan.placement);
        Assert.assertEquals(VisualAction.PLACE_TEXT_SHELL, shellPlan.visualAction);
        Assert.assertNotNull(textPlan);
        Assert.assertEquals(Placement.INLINE, textPlan.placement);
        Assert.assertEquals(TextAction.OWNED_BY_HWPX_TEXT, textPlan.textAction);
    }

    @Test
    public void floatingTextShellIsDroppedWhenSameDomInlinePngIsVisible() {
        ResolvedData data = new ResolvedData();
        data.addTextFrame(textFrame(423, "예시 답안"));
        RenderedGroup inline = rendered(
                421,
                "inline_object",
                "inline_object",
                "inline_graphic_only",
                "indesign_png",
                "none",
                null,
                new int[] { 421, 422 });
        inline.containsText(Boolean.FALSE);
        inline.containsEditableText(Boolean.FALSE);
        RenderedGroup floatingShell = rendered(
                421,
                "page_object",
                "page_object",
                "visual_label_text_hidden_shell",
                "indesign_png",
                "hwpx_tf",
                new String[] { "423" },
                new int[] { 421, 422, 423 });
        data.addRenderedFloatingItem(inline);
        data.addRenderedFloatingItem(floatingShell);
        addInlineAnchor(data, 421);

        ResolvedBuildContext ctx = plan(data);
        ObjectPlan inlinePlan = findRenderedPlan(ctx, 421, "inline_graphic_only");
        ObjectPlan shellPlan = findRenderedPlan(ctx, 421, "visual_label_text_hidden_shell");

        Assert.assertNotNull(inlinePlan);
        Assert.assertEquals(VisualAction.PLACE_INLINE_PNG, inlinePlan.visualAction);
        Assert.assertNotNull(shellPlan);
        Assert.assertEquals(VisualAction.DROP_VISUAL, shellPlan.visualAction);
        Assert.assertTrue(ctx.shouldDropVisualByOwnershipPlan(floatingShell));
    }

    @Test
    public void textFrameOwnedByInlinePageObjectShellIsPlannedInline() {
        ResolvedData data = new ResolvedData();
        ResolvedTextFrame tf = textFrame(453, "예시 답안");
        tf.isInline(true);
        data.addTextFrame(tf);
        RenderedGroup inlineComplete = rendered(
                451,
                "inline_object",
                "inline_object",
                "inline_graphic_only",
                "indesign_png",
                "none",
                null,
                new int[] { 451, 452, 453 });
        inlineComplete.containsText(Boolean.FALSE);
        inlineComplete.containsEditableText(Boolean.FALSE);
        RenderedGroup inlineShell = rendered(
                451,
                "page_object",
                "page_object",
                "visual_label_text_hidden_shell",
                "indesign_png",
                "hwpx_tf",
                new String[] { "453" },
                new int[] { 451, 452, 453 });
        inlineShell.containsText(Boolean.FALSE);
        inlineShell.containsEditableText(Boolean.FALSE);
        data.addRenderedFloatingItem(inlineComplete);
        data.addRenderedFloatingItem(inlineShell);
        addInlineAnchor(data, 451);

        ResolvedBuildContext ctx = plan(data);
        ObjectPlan textPlan = findPlanByKind(ctx, 453, "text_frame");
        ObjectPlan shellPlan = findRenderedPlan(ctx, 451, "visual_label_text_hidden_shell");

        Assert.assertNotNull(shellPlan);
        Assert.assertEquals(VisualAction.PLACE_TEXT_SHELL, shellPlan.visualAction);
        Assert.assertEquals(Placement.INLINE, shellPlan.placement);
        Assert.assertNotNull(textPlan);
        Assert.assertEquals(TextAction.OWNED_BY_HWPX_TEXT, textPlan.textAction);
        Assert.assertEquals(Placement.INLINE, textPlan.placement);
    }

    @Test
    public void unanchoredInlineRenderedShellIsPlannedFloating() {
        ResolvedData data = new ResolvedData();
        ResolvedTextFrame tf = textFrame(473, "구절\n풀이");
        tf.isInline(true);
        data.addTextFrame(tf);
        RenderedGroup inlineComplete = rendered(
                471,
                "inline_object",
                "inline_object",
                "inline_graphic_only",
                "indesign_png",
                "none",
                null,
                new int[] { 471, 472, 473 });
        inlineComplete.containsText(Boolean.FALSE);
        inlineComplete.containsEditableText(Boolean.FALSE);
        RenderedGroup floatingShell = rendered(
                471,
                "page_object",
                "page_object",
                "visual_label_text_hidden_shell",
                "indesign_png",
                "hwpx_tf",
                new String[] { "473" },
                new int[] { 471, 472, 473 });
        floatingShell.containsText(Boolean.FALSE);
        floatingShell.containsEditableText(Boolean.FALSE);
        data.addRenderedFloatingItem(inlineComplete);
        data.addRenderedFloatingItem(floatingShell);

        ResolvedBuildContext ctx = plan(data);
        ObjectPlan textPlan = findPlanByKind(ctx, 473, "text_frame");
        ObjectPlan shellPlan = findRenderedPlan(ctx, 471, "visual_label_text_hidden_shell");

        Assert.assertNotNull(shellPlan);
        Assert.assertEquals(VisualAction.PLACE_TEXT_SHELL, shellPlan.visualAction);
        Assert.assertEquals(Placement.FLOATING, shellPlan.placement);
        Assert.assertNotNull(textPlan);
        Assert.assertEquals(TextAction.OWNED_BY_HWPX_TEXT, textPlan.textAction);
        Assert.assertEquals(Placement.FLOATING, textPlan.placement);
    }

    @Test
    public void inlineTextFrameWithFloatingBackdropShellIsPlannedFloating() {
        ResolvedData data = new ResolvedData();
        ResolvedTextFrame tf = textFrame(463, "구절\n풀이");
        tf.isInline(true);
        data.addTextFrame(tf);
        RenderedGroup backdrop = rendered(
                462,
                "page_object",
                "page_object",
                "label_backdrop_group",
                "indesign_png",
                "hwpx_tf",
                new String[] { "463" },
                new int[] { 461 });
        backdrop.containsText(Boolean.FALSE);
        backdrop.containsEditableText(Boolean.FALSE);
        data.addRenderedFloatingItem(backdrop);

        ResolvedBuildContext ctx = plan(data);
        ObjectPlan textPlan = findPlanByKind(ctx, 463, "text_frame");
        ObjectPlan backdropPlan = findRenderedPlan(ctx, 462, "label_backdrop_group");

        Assert.assertNotNull(backdropPlan);
        Assert.assertEquals(Placement.FLOATING, backdropPlan.placement);
        Assert.assertEquals(VisualAction.PLACE_TEXT_SHELL, backdropPlan.visualAction);
        Assert.assertEquals(VisualLayer.LABEL_BACKDROP, backdropPlan.visualLayer);
        Assert.assertEquals(Boolean.FALSE, ctx.inFrontLayerByOwnershipPlan(backdrop));
        Assert.assertNotNull(textPlan);
        Assert.assertEquals(TextAction.OWNED_BY_HWPX_TEXT, textPlan.textAction);
        Assert.assertEquals(Placement.FLOATING, textPlan.placement);
    }

    @Test
    public void mixedTextHiddenGroupWithIndependentDecorationStaysBehindOwnedText() {
        ResolvedData data = new ResolvedData();
        ResolvedTextFrame tf = textFrame(473, "1연");
        tf.zOrder(80);
        tf.geometricBounds(new double[] { 43.0, 32.9, 51.0, 68.0 });
        tf.pageRelativeBounds(new double[] { 43.0, 32.9, 51.0, 68.0 });
        data.addTextFrame(tf);
        data.addPageItem(pageItem(
                473,
                "TextFrame",
                new double[] { 43.0, 32.9, 51.0, 68.0 },
                null,
                null,
                0.0));
        data.addPageItem(pageItem(
                474,
                "Rectangle",
                new double[] { 43.0, 32.9, 51.0, 68.0 },
                "C=75 M=45 Y=0 K=0",
                null,
                0.0));
        data.addPageItem(pageItem(
                475,
                "Rectangle",
                new double[] { 43.0, 92.0, 61.0, 203.0 },
                "C=0 M=0 Y=0 K=10",
                null,
                0.0));
        RenderedGroup shell = rendered(
                472,
                "page_object",
                "page_object",
                "mixed_group_text_hidden",
                "indesign_png",
                "hwpx_tf",
                new String[] { "473" },
                new int[] { 472, 473, 474, 475 });
        shell.containsText(Boolean.FALSE);
        shell.containsEditableText(Boolean.FALSE);
        shell.bounds(new double[] { 43.0, 32.9, 61.0, 203.0 });
        data.addRenderedFloatingItem(shell);

        ResolvedBuildContext ctx = plan(data);
        ObjectPlan shellPlan = findRenderedPlan(ctx, 472, "mixed_group_text_hidden");

        Assert.assertNotNull(shellPlan);
        Assert.assertEquals(TextAction.OWNED_BY_HWPX_TEXT, shellPlan.textAction);
        Assert.assertEquals(VisualAction.PLACE_TEXT_SHELL, shellPlan.visualAction);
        Assert.assertEquals(VisualLayer.CONTAINER_BACKDROP, shellPlan.visualLayer);
        Assert.assertEquals(Boolean.FALSE, ctx.inFrontLayerByOwnershipPlan(shell));
        Assert.assertEquals(79, shellPlan.zOrder);
    }

    @Test
    public void duplicateRenderedImageChannelDropsLowerPriorityPlan() {
        ResolvedData data = new ResolvedData();
        RenderedGroup floating = rendered(
                501,
                "page_object",
                "page_object",
                "image_export",
                "indesign_png",
                "",
                null,
                new int[] { 501, 502 });
        RenderedGroup imageFrame = rendered(
                501,
                "page_object",
                "page_object",
                "image_export",
                "indesign_png",
                "",
                null,
                new int[] { 501, 502 });
        floating.file("rendered_frames/img_501.png");
        imageFrame.file("rendered_frames/img_501.png");
        floating.containsEditableText(Boolean.FALSE);
        floating.containsText(Boolean.FALSE);
        imageFrame.containsEditableText(Boolean.FALSE);
        imageFrame.containsText(Boolean.FALSE);
        data.addRenderedFloatingItem(floating);
        data.addRenderedImageFrame(imageFrame);

        ResolvedBuildContext ctx = plan(data);
        ObjectPlan floatingPlan = findRenderedPlanByKind(ctx, 501, "rendered_floating_item");
        ObjectPlan imagePlan = findRenderedPlanByKind(ctx, 501, "rendered_image_frame");

        Assert.assertNotNull(floatingPlan);
        Assert.assertEquals(VisualAction.PLACE_FLOATING_PNG, floatingPlan.visualAction);
        Assert.assertNotNull(imagePlan);
        Assert.assertEquals(VisualAction.DROP_VISUAL, imagePlan.visualAction);
    }

    @Test
    public void textShellKeepsVisualButReleasesInlineOwnedSources() {
        ResolvedData data = new ResolvedData();
        data.addTextFrame(textFrame(601, "최근 사회·문화적 맥락"));
        RenderedGroup inline = rendered(
                602,
                "inline_object",
                "inline_object",
                "inline_graphic_only",
                "indesign_png",
                "indesign_png",
                null,
                new int[] { 602, 603 });
        RenderedGroup shell = rendered(
                604,
                "page_object",
                "page_object",
                "visual_label_text_hidden_shell",
                "indesign_png",
                "hwpx_tf",
                new String[] { "601" },
                new int[] { 601, 602, 603, 604 });
        data.addRenderedFloatingItem(inline);
        data.addRenderedFloatingItem(shell);

        ResolvedBuildContext ctx = plan(data);
        ObjectPlan shellPlan = findRenderedPlan(ctx, 604, "visual_label_text_hidden_shell");

        Assert.assertNotNull(shellPlan);
        Assert.assertEquals(VisualAction.PLACE_TEXT_SHELL, shellPlan.visualAction);
        Assert.assertArrayEquals(new int[] { 601, 604 }, shellPlan.sourceObjectIds);
    }

    @Test
    public void nonTextInlineVisualReleasesEditableHwpxTextSource() {
        ResolvedData data = new ResolvedData();
        data.addTextFrame(textFrame(651, "보류"));
        RenderedGroup inline = rendered(
                650,
                "inline_object",
                "inline_object",
                "inline_graphic_only",
                "indesign_png",
                "",
                null,
                new int[] { 650, 651, 652 });
        inline.containsEditableText(Boolean.FALSE);
        inline.containsText(Boolean.FALSE);
        RenderedGroup shell = rendered(
                651,
                "page_object",
                "page_object",
                "editable_textframe_visual_shell",
                "indesign_png",
                "hwpx_tf",
                new String[] { "651" },
                new int[] { 651 });
        data.addRenderedFloatingItem(inline);
        data.addRenderedFloatingItem(shell);
        addInlineAnchor(data, 650);

        ResolvedBuildContext ctx = plan(data);
        ObjectPlan inlinePlan = findRenderedPlan(ctx, 650, "inline_graphic_only");

        Assert.assertNotNull(inlinePlan);
        Assert.assertEquals(VisualAction.PLACE_INLINE_PNG, inlinePlan.visualAction);
        Assert.assertArrayEquals(new int[] { 650, 652 }, inlinePlan.sourceObjectIds);
    }

    @Test
    public void droppedRenderedPngDoesNotKeepTextOwnership() {
        ResolvedData data = new ResolvedData();
        data.addTextFrame(textFrame(681, "보류"));
        RenderedGroup inline = rendered(
                680,
                "inline_object",
                "inline_object",
                "inline_graphic_only",
                "indesign_png",
                "",
                null,
                new int[] { 680, 681 });
        inline.containsEditableText(Boolean.FALSE);
        inline.containsText(Boolean.FALSE);
        RenderedGroup floatingFallback = rendered(
                680,
                "page_object",
                "page_object",
                "text_composite_indesign_png",
                "indesign_png",
                "indesign_png",
                new String[] { "681" },
                new int[] { 680, 681 });
        data.addRenderedFloatingItem(inline);
        data.addRenderedFloatingItem(floatingFallback);

        ResolvedBuildContext ctx = plan(data);
        ObjectPlan fallbackPlan = findRenderedPlan(ctx, 680, "text_composite_indesign_png");

        Assert.assertNotNull(fallbackPlan);
        Assert.assertEquals(VisualAction.DROP_VISUAL, fallbackPlan.visualAction);
        Assert.assertEquals(TextAction.DROP_TEXT, fallbackPlan.textAction);
    }

    @Test
    public void parentGroupDropsWhenChildImagesCoverItsSources() {
        ResolvedData data = new ResolvedData();
        RenderedGroup parent = rendered(
                701,
                "page_object",
                "page_object",
                "complex_graphic_text_hidden",
                "indesign_png",
                "",
                null,
                new int[] { 701, 702, 703, 704 });
        RenderedGroup childA = rendered(
                702,
                "page_object",
                "page_object",
                "decoration_group",
                "indesign_png",
                "",
                null,
                new int[] { 702, 703 });
        RenderedGroup childB = rendered(
                704,
                "page_object",
                "page_object",
                "pure_decoration_group",
                "indesign_png",
                "",
                null,
                new int[] { 704 });
        parent.containsEditableText(Boolean.FALSE);
        parent.containsText(Boolean.FALSE);
        childA.containsEditableText(Boolean.FALSE);
        childA.containsText(Boolean.FALSE);
        childB.containsEditableText(Boolean.FALSE);
        childB.containsText(Boolean.FALSE);
        data.addRenderedFloatingItem(parent);
        data.addRenderedFloatingItem(childA);
        data.addRenderedFloatingItem(childB);

        ResolvedBuildContext ctx = plan(data);
        ObjectPlan parentPlan = findRenderedPlan(ctx, 701, "complex_graphic_text_hidden");
        ObjectPlan childAPlan = findRenderedPlan(ctx, 702, "decoration_group");
        ObjectPlan childBPlan = findRenderedPlan(ctx, 704, "pure_decoration_group");

        Assert.assertNotNull(parentPlan);
        Assert.assertEquals(VisualAction.DROP_VISUAL, parentPlan.visualAction);
        Assert.assertNotNull(childAPlan);
        Assert.assertEquals(VisualAction.PLACE_FLOATING_PNG, childAPlan.visualAction);
        Assert.assertNotNull(childBPlan);
        Assert.assertEquals(VisualAction.PLACE_FLOATING_PNG, childBPlan.visualAction);
    }

    @Test
    public void nestedCompositeKeepsBothVisualsButSplitsSourceSlots() {
        ResolvedData data = new ResolvedData();
        RenderedGroup parent = rendered(
                751,
                "page_object",
                "page_object",
                "complex_graphic_text_hidden",
                "indesign_png",
                "",
                null,
                new int[] { 751, 752, 753, 754 });
        RenderedGroup child = rendered(
                752,
                "page_object",
                "page_object",
                "mixed_group_text_hidden",
                "indesign_png",
                "",
                null,
                new int[] { 752, 753 });
        parent.containsEditableText(Boolean.FALSE);
        parent.containsText(Boolean.FALSE);
        parent.bounds(new double[] { 0.0, 0.0, 100.0, 100.0 });
        child.containsEditableText(Boolean.FALSE);
        child.containsText(Boolean.FALSE);
        child.bounds(new double[] { 10.0, 10.0, 30.0, 30.0 });
        data.addRenderedFloatingItem(parent);
        data.addRenderedFloatingItem(child);

        ResolvedBuildContext ctx = plan(data);
        ObjectPlan parentPlan = findRenderedPlan(ctx, 751, "complex_graphic_text_hidden");
        ObjectPlan childPlan = findRenderedPlan(ctx, 752, "mixed_group_text_hidden");

        Assert.assertNotNull(parentPlan);
        Assert.assertEquals(VisualAction.PLACE_FLOATING_PNG, parentPlan.visualAction);
        Assert.assertFalse(containsSource(parentPlan, 752));
        Assert.assertFalse(containsSource(parentPlan, 753));
        Assert.assertTrue(containsSource(parentPlan, 754));
        Assert.assertNotNull(childPlan);
        Assert.assertEquals(VisualAction.PLACE_FLOATING_PNG, childPlan.visualAction);
        Assert.assertTrue(containsSource(childPlan, 752));
        Assert.assertTrue(containsSource(childPlan, 753));
    }

    @Test
    public void vectorWithoutLabelSourceSignalStaysContentVisual() {
        ResolvedData data = new ResolvedData();
        RenderedGroup label = rendered(
                801,
                "page_object",
                "page_object",
                "vector_shape",
                "indesign_png",
                "",
                null,
                new int[] { 801 });
        label.containsEditableText(Boolean.FALSE);
        label.containsText(Boolean.FALSE);
        label.bounds(new double[] { 94.3, 170.2, 101.0, 193.6 });
        data.addRenderedFloatingItem(label);

        ResolvedBuildContext ctx = plan(data);
        ObjectPlan plan = findRenderedPlan(ctx, 801, "vector_shape");

        Assert.assertNotNull(plan);
        Assert.assertEquals(VisualAction.PLACE_FLOATING_PNG, plan.visualAction);
        Assert.assertEquals(VisualLayer.CONTENT_VISUAL, plan.visualLayer);
        Assert.assertEquals(Boolean.TRUE, ctx.inFrontLayerByOwnershipPlan(label));
    }

    @Test
    public void thinVectorLineIsContainerOutlineInFrontPlane() {
        ResolvedData data = new ResolvedData();
        RenderedGroup line = rendered(
                802,
                "page_object",
                "page_object",
                "vector_shape",
                "indesign_png",
                "",
                null,
                new int[] { 802 });
        line.containsEditableText(Boolean.FALSE);
        line.containsText(Boolean.FALSE);
        line.bounds(new double[] { 213.8, 61.8, 245.9, 64.2 });
        data.addRenderedFloatingItem(line);

        ResolvedBuildContext ctx = plan(data);
        ObjectPlan plan = findRenderedPlan(ctx, 802, "vector_shape");

        Assert.assertNotNull(plan);
        Assert.assertEquals(VisualAction.PLACE_FLOATING_PNG, plan.visualAction);
        Assert.assertEquals(VisualLayer.CONTAINER_OUTLINE, plan.visualLayer);
        Assert.assertEquals(Boolean.TRUE, ctx.inFrontLayerByOwnershipPlan(line));
    }

    @Test
    public void paperStrokeBoxIsContainerBackdropBehindContentPlane() {
        ResolvedData data = new ResolvedData();
        data.addPageItem(pageItem(
                901,
                "Rectangle",
                new double[] { 66.0, 37.0, 97.0, 110.0 },
                "Paper",
                "라인_B",
                1.0));
        RenderedGroup box = rendered(
                901,
                "page_object",
                "page_object",
                "vector_shape",
                "indesign_png",
                "",
                null,
                new int[] { 901 });
        box.containsEditableText(Boolean.FALSE);
        box.containsText(Boolean.FALSE);
        box.bounds(new double[] { 66.0, 37.0, 97.0, 110.0 });
        data.addRenderedFloatingItem(box);

        ResolvedBuildContext ctx = plan(data);
        ObjectPlan plan = findRenderedPlan(ctx, 901, "vector_shape");

        Assert.assertNotNull(plan);
        Assert.assertEquals(VisualAction.PLACE_FLOATING_PNG, plan.visualAction);
        Assert.assertEquals(VisualLayer.CONTAINER_BACKDROP, plan.visualLayer);
        Assert.assertEquals(Boolean.FALSE, ctx.inFrontLayerByOwnershipPlan(box));
    }

    @Test
    public void paperPanelInsideLargeBackdropIsForegroundMask() throws Exception {
        ResolvedData data = new ResolvedData();
        data.addPage(page(0, new double[] { 0, 0, 280, 220 }));
        RenderedGroup board = rendered(
                910,
                "page_object",
                "page_object",
                "image_group_text_hidden",
                "indesign_png",
                "",
                null,
                new int[] { 910 });
        board.containsEditableText(Boolean.FALSE);
        board.containsText(Boolean.FALSE);
        board.bounds(new double[] { 180.0, 30.0, 256.0, 176.0 });
        data.addRenderedFloatingItem(board);

        data.addPageItem(pageItem(
                911,
                "Rectangle",
                new double[] { 184.0, 34.0, 252.0, 172.0 },
                "Paper",
                null,
                0.0));
        RenderedGroup paper = rendered(
                911,
                "page_object",
                "page_object",
                "vector_shape",
                "indesign_png",
                "",
                null,
                new int[] { 911 });
        paper.containsEditableText(Boolean.FALSE);
        paper.containsText(Boolean.FALSE);
        paper.bounds(new double[] { 184.0, 34.0, 252.0, 172.0 });
        File image = File.createTempFile("paper_panel_", ".png");
        image.deleteOnExit();
        BufferedImage bi = new BufferedImage(160, 80, BufferedImage.TYPE_INT_ARGB);
        for (int y = 0; y < bi.getHeight(); y++) {
            for (int x = 0; x < bi.getWidth(); x++) {
                bi.setRGB(x, y, 0xffffffff);
            }
        }
        ImageIO.write(bi, "png", image);
        paper.file(image.getAbsolutePath());
        data.addRenderedFloatingItem(paper);

        ResolvedBuildContext ctx = plan(data);
        ObjectPlan plan = findRenderedPlan(ctx, 911, "vector_shape");

        Assert.assertNotNull(plan);
        Assert.assertEquals(VisualAction.PLACE_FLOATING_PNG, plan.visualAction);
        Assert.assertEquals(VisualLayer.FOREGROUND_MASK, plan.visualLayer);
        Assert.assertEquals(Boolean.TRUE, ctx.inFrontLayerByOwnershipPlan(paper));
    }

    @Test
    public void koreanBatangLayerPageSizedImageIsPageBackground() {
        ResolvedData data = new ResolvedData();
        data.addPage(page(0, new double[] { 0, 0, 280, 220 }));
        ResolvedPageItem source = pageItem(
                136984,
                "Rectangle",
                new double[] { -3.0, -3.0, 283.0, 443.0 },
                null,
                null,
                0.0);
        source.layerName("바탕");
        source.zOrder(0);
        data.addPageItem(source);

        RenderedGroup background = rendered(
                136984,
                "page_object",
                "page_object",
                "pdf_export",
                "indesign_png",
                "none",
                null,
                new int[] { 136984 });
        background.containsEditableText(Boolean.FALSE);
        background.containsText(Boolean.FALSE);
        background.bounds(new double[] { 0.0, 0.0, 280.0, 220.0 });
        background.zOrder(0);
        background.zOrderKnown(true);
        data.addRenderedFloatingItem(background);

        ResolvedBuildContext ctx = plan(data);
        ObjectPlan plan = findRenderedPlan(ctx, 136984, "pdf_export");

        Assert.assertNotNull(plan);
        Assert.assertEquals(VisualAction.PLACE_FLOATING_PNG, plan.visualAction);
        Assert.assertEquals(VisualLayer.PAGE_BACKGROUND, plan.visualLayer);
        Assert.assertEquals(0, plan.zOrder);
        Assert.assertEquals(Boolean.FALSE, ctx.inFrontLayerByOwnershipPlan(background));
    }

    @Test
    public void sourceZBeforeTextPlacesContentVisualBehindTextPlane() {
        ResolvedData data = new ResolvedData();
        data.addPage(page(0, new double[] { 0, 0, 280, 220 }));

        ResolvedTextFrame title = textFrame(2001, "대단원 개관");
        title.zOrder(5);
        data.addTextFrame(title);

        ResolvedPageItem source = pageItem(
                2000,
                "Image",
                new double[] { 0.0, 0.0, 280.0, 220.0 },
                null,
                null,
                0.0);
        source.layerName("레이어 1");
        source.zOrder(0);
        data.addPageItem(source);

        RenderedGroup visual = rendered(
                2000,
                "page_object",
                "page_object",
                "pdf_export",
                "indesign_png",
                "none",
                null,
                new int[] { 2000 });
        visual.containsEditableText(Boolean.FALSE);
        visual.containsText(Boolean.FALSE);
        visual.bounds(new double[] { 0.0, 0.0, 280.0, 220.0 });
        visual.zOrder(29);
        visual.zOrderKnown(true);
        data.addRenderedFloatingItem(visual);

        ResolvedBuildContext ctx = plan(data);
        ObjectPlan plan = findRenderedPlan(ctx, 2000, "pdf_export");

        Assert.assertNotNull(plan);
        Assert.assertEquals(0, plan.zOrder);
        Assert.assertEquals(VisualLayer.CONTAINER_BACKDROP, plan.visualLayer);
        Assert.assertEquals(PolicyLayer.BACKGROUND, plan.visualPolicyLayer());
        Assert.assertEquals(Boolean.FALSE, ctx.inFrontLayerByOwnershipPlan(visual));
    }

    @Test
    public void pageWashPlacedPdfWithFilledRootFrameIsPageBackground() {
        ResolvedData data = new ResolvedData();
        data.addPage(page(0, new double[] { 0, 0, 280, 220 }));

        ResolvedPageItem root = pageItem(
                4637,
                "Rectangle",
                new double[] { -3.0, -3.0, 283.0, 220.0 },
                "활동_박스 Y02",
                null,
                0.0);
        root.fillTint(40.0);
        root.zOrder(635);
        data.addPageItem(root);

        ResolvedPageItem child = pageItem(
                27166,
                "PDF",
                new double[] { -15.2, 0.0, 295.2, 220.0 },
                null,
                null,
                0.0);
        child.parentId("4637");
        child.opacity(10.0);
        child.zOrder(636);
        data.addPageItem(child);

        RenderedGroup visual = rendered(
                4637,
                "page_object",
                "page_object",
                "pdf_export",
                "indesign_png",
                "none",
                null,
                new int[] { 4637, 27166 });
        visual.containsEditableText(Boolean.FALSE);
        visual.containsText(Boolean.FALSE);
        visual.bounds(new double[] { 0.0, 0.0, 280.0, 220.0 });
        visual.zOrder(0);
        visual.zOrderKnown(true);
        data.addRenderedFloatingItem(visual);

        ResolvedBuildContext ctx = plan(data);
        ObjectPlan plan = findRenderedPlan(ctx, 4637, "pdf_export");

        Assert.assertNotNull(plan);
        Assert.assertEquals(VisualLayer.PAGE_BACKGROUND, plan.visualLayer);
        Assert.assertEquals(PolicyLayer.BACKGROUND, plan.visualPolicyLayer());
        Assert.assertEquals(0, plan.zOrder);
        Assert.assertEquals(Boolean.FALSE, ctx.inFrontLayerByOwnershipPlan(visual));
    }

    @Test
    public void largeFilledVectorBoxIsContainerBackdropBehindContentPlane() {
        ResolvedData data = new ResolvedData();
        data.addPageItem(pageItem(
                902,
                "Rectangle",
                new double[] { 27.9, 46.6, 106.3, 115.6 },
                "활동_박스 B03",
                "활동_박스 B01",
                1.0));
        RenderedGroup box = rendered(
                902,
                "page_object",
                "page_object",
                "vector_shape",
                "indesign_png",
                "",
                null,
                new int[] { 902 });
        box.containsEditableText(Boolean.FALSE);
        box.containsText(Boolean.FALSE);
        box.bounds(new double[] { 27.9, 46.6, 106.3, 115.6 });
        data.addRenderedFloatingItem(box);

        ResolvedBuildContext ctx = plan(data);
        ObjectPlan plan = findRenderedPlan(ctx, 902, "vector_shape");

        Assert.assertNotNull(plan);
        Assert.assertEquals(VisualAction.PLACE_FLOATING_PNG, plan.visualAction);
        Assert.assertEquals(VisualLayer.CONTAINER_BACKDROP, plan.visualLayer);
        Assert.assertEquals(Boolean.FALSE, ctx.inFrontLayerByOwnershipPlan(box));
    }

    @Test
    public void paperFillOnlyPatchIsContainerBackdropEvenWhenSourceZIsHigh() {
        ResolvedData data = new ResolvedData();
        data.addPageItem(pageItem(
                903,
                "Rectangle",
                new double[] { 115.4, 115.6, 139.5, 121.6 },
                "Paper",
                null,
                0.0));
        RenderedGroup patch = rendered(
                903,
                "page_object",
                "page_object",
                "vector_shape",
                "indesign_png",
                "",
                null,
                new int[] { 903 });
        patch.containsEditableText(Boolean.FALSE);
        patch.containsText(Boolean.FALSE);
        patch.zOrder(420);
        patch.bounds(new double[] { 115.4, 115.6, 139.5, 121.6 });
        data.addRenderedFloatingItem(patch);

        ResolvedBuildContext ctx = plan(data);
        ObjectPlan plan = findRenderedPlan(ctx, 903, "vector_shape");

        Assert.assertNotNull(plan);
        Assert.assertEquals(VisualAction.PLACE_FLOATING_PNG, plan.visualAction);
        Assert.assertEquals(VisualLayer.CONTAINER_BACKDROP, plan.visualLayer);
        Assert.assertEquals(Boolean.FALSE, ctx.inFrontLayerByOwnershipPlan(patch));
    }

    @Test
    public void paperFillCardBehindEditableTextIsTextCardBackdrop() {
        ResolvedData data = new ResolvedData();
        ResolvedTextFrame tf = textFrame(914, "Ⅱ\n2022 개정 문학\n교육과정\n11");
        tf.geometricBounds(new double[] { 194.8, 58.6, 229.2, 94.6 });
        tf.pageRelativeBounds(new double[] { 194.8, 58.6, 229.2, 94.6 });
        data.addTextFrame(tf);
        data.addPageItem(pageItem(
                913,
                "Rectangle",
                new double[] { 191.8, 59.5, 225.7, 93.4 },
                "Paper",
                null,
                0.0));
        RenderedGroup card = rendered(
                913,
                "page_object",
                "page_object",
                "vector_shape",
                "indesign_png",
                "",
                null,
                new int[] { 913 });
        card.containsEditableText(Boolean.FALSE);
        card.containsText(Boolean.FALSE);
        card.bounds(new double[] { 191.8, 59.5, 225.7, 93.4 });
        data.addRenderedFloatingItem(card);

        ResolvedBuildContext ctx = plan(data);
        ObjectPlan plan = findRenderedPlan(ctx, 913, "vector_shape");

        Assert.assertNotNull(plan);
        Assert.assertEquals(VisualAction.PLACE_TEXT_SHELL, plan.visualAction);
        Assert.assertEquals(VisualLayer.TEXT_CARD_BACKDROP, plan.visualLayer);
        Assert.assertEquals(Boolean.FALSE, ctx.inFrontLayerByOwnershipPlan(card));
    }

    @Test
    public void nativeParentTextShellKeepsShellRelationButDirectTextFrameOwnsText() {
        ResolvedData data = new ResolvedData();
        ResolvedTextFrame tf = textFrame(921, "사물에 사연이 쌓여 가서 추억이 사물보다 더 거대하게 부풀어");
        tf.isInline(true);
        tf.geometricBounds(new double[] { 50.0, 74.0, 72.0, 197.0 });
        tf.pageRelativeBounds(new double[] { 50.0, 74.0, 72.0, 197.0 });
        data.addTextFrame(tf);

        ResolvedPageItem shell = pageItem(
                920,
                "Rectangle",
                new double[] { 50.0, 74.0, 72.0, 197.0 },
                "Paper",
                null,
                0.0);
        shell.isInline(true);
        data.addPageItem(shell);
        ResolvedPageItem tfItem = pageItem(
                921,
                "TextFrame",
                new double[] { 50.0, 74.0, 72.0, 197.0 },
                null,
                null,
                0.0);
        tfItem.parentId("920");
        tfItem.isInline(true);
        data.addPageItem(tfItem);

        ResolvedBuildContext ctx = plan(data);
        ObjectPlan textPlan = findPlanByKind(ctx, 921, "text_frame");
        ObjectPlan shellPlan = findPlanByKind(ctx, 920, "native_parent_text_shell");

        Assert.assertNotNull(textPlan);
        Assert.assertNotNull(shellPlan);
        Assert.assertEquals(TextAction.OWNED_BY_HWPX_TEXT, textPlan.textAction);
        Assert.assertEquals(VisualAction.DROP_VISUAL, textPlan.visualAction);
        Assert.assertEquals(TextAction.DROP_TEXT, shellPlan.textAction);
        Assert.assertEquals(VisualAction.PLACE_TEXT_SHELL, shellPlan.visualAction);
        Assert.assertArrayEquals(new int[] { 921 }, shellPlan.ownedTextFrameIds);
        Assert.assertArrayEquals(new int[] { 920 }, shellPlan.visualSourceObjectIds);
    }

    @Test
    public void slotOnlyTextlessShellUsesConcreteVisualSourceBoundsInsteadOfBroadGroupBounds() {
        ResolvedData data = new ResolvedData();
        data.addPage(page(0, new double[] { 0.0, 0.0, 280.0, 220.0 }));
        data.addPage(page(1, new double[] { 0.0, 220.0, 280.0, 440.0 }));

        ResolvedTextFrame tf = textFrame(329606, "독자는 작가가 심어 놓은");
        tf.pageIndex(1);
        tf.pageRelativeBounds(new double[] { 233.0, 162.0, 250.0, 196.0 });
        tf.geometricBounds(new double[] { 233.0, 382.0, 250.0, 416.0 });
        data.addTextFrame(tf);

        ResolvedTextFrame otherTf = textFrame(273534, "다른 글상자");
        otherTf.pageIndex(1);
        otherTf.pageRelativeBounds(new double[] { 222.0, 63.0, 226.0, 140.0 });
        otherTf.geometricBounds(new double[] { 222.0, 283.0, 226.0, 360.0 });
        data.addTextFrame(otherTf);

        ResolvedPageItem group = pageItem(
                349341,
                "Group",
                new double[] { 221.0, 283.0, 255.0, 423.0 },
                null,
                null,
                0.0);
        group.pageIndex(1);
        group.childIds(new int[] { 273534, 329605, 329606 });
        data.addPageItem(group);

        ResolvedPageItem shell = pageItem(
                329605,
                "Rectangle",
                new double[] { 228.0, 375.0, 255.0, 423.0 },
                null,
                null,
                0.0);
        shell.pageIndex(1);
        shell.parentId("349341");
        shell.childIds(new int[] { 329606 });
        data.addPageItem(shell);

        ResolvedPageItem tfItem = pageItem(
                329606,
                "TextFrame",
                new double[] { 233.0, 382.0, 250.0, 416.0 },
                null,
                null,
                0.0);
        tfItem.pageIndex(1);
        tfItem.parentId("329605");
        data.addPageItem(tfItem);

        ResolvedPageItem otherTfItem = pageItem(
                273534,
                "TextFrame",
                new double[] { 222.0, 283.0, 226.0, 360.0 },
                null,
                null,
                0.0);
        otherTfItem.pageIndex(1);
        otherTfItem.parentId("349341");
        data.addPageItem(otherTfItem);

        RenderedGroup rendered = rendered(
                349341,
                "page_object",
                "page_object",
                "slot_only_textless_shell",
                "indesign_png",
                "hwpx_tf",
                new String[] { "329606", "273534" },
                new int[] { 273534, 329605, 329606, 349341 });
        rendered.pageIndex(1);
        rendered.bounds(new double[] { 221.0, 63.0, 255.0, 203.0 });
        rendered.containsText(Boolean.FALSE);
        rendered.containsEditableText(Boolean.FALSE);
        data.addRenderedFloatingItem(rendered);

        ResolvedBuildContext ctx = plan(data);
        ObjectPlan shellPlan = findRenderedPlan(ctx, 349341, "slot_only_textless_shell");

        Assert.assertNotNull(shellPlan);
        Assert.assertEquals("reason=" + shellPlan.reason,
                VisualAction.PLACE_TEXT_SHELL, shellPlan.visualAction);
        Assert.assertArrayEquals(java.util.Arrays.toString(shellPlan.bounds),
                new double[] { 228.0, 155.0, 255.0, 203.0 },
                shellPlan.bounds,
                0.0001);
        Assert.assertArrayEquals(new int[] { 329606 },
                shellPlan.ownedTextFrameIds);
    }

    @Test
    public void tableOnlyCarrierSiblingDecorationKeepsShellVisualBesideTableStructure() {
        ResolvedData data = new ResolvedData();
        ResolvedTextFrame tf = textFrame(501, "\u0016");
        tf.storyId("501");
        tf.geometricBounds(new double[] { 155.0, 63.0, 189.0, 203.0 });
        tf.pageRelativeBounds(new double[] { 155.0, 63.0, 189.0, 203.0 });
        data.addTextFrame(tf);

        ResolvedPageItem parent = pageItem(
                500,
                "Group",
                new double[] { 155.0, 63.0, 189.0, 203.0 },
                null,
                null,
                0.0);
        parent.childIds(new int[] { 501, 502 });
        data.addPageItem(parent);

        ResolvedPageItem tfItem = pageItem(
                501,
                "TextFrame",
                new double[] { 155.0, 63.0, 189.0, 203.0 },
                null,
                null,
                0.0);
        tfItem.parentId("500");
        data.addPageItem(tfItem);

        ResolvedPageItem cellDecoration = pageItem(
                502,
                "Rectangle",
                new double[] { 155.0, 63.0, 189.0, 203.0 },
                "#표색_인디핑크미색",
                "Black",
                0.25);
        cellDecoration.parentId("500");
        data.addPageItem(cellDecoration);

        RenderedGroup shell = rendered(
                502,
                "page_object",
                "page_object",
                "slot_only_textless_shell",
                "indesign_png",
                "hwpx_tf",
                new String[] { "501" },
                new int[] { 501, 502 });
        shell.bounds(new double[] { 155.0, 63.0, 189.0, 203.0 });
        shell.containsEditableText(Boolean.FALSE);
        shell.containsText(Boolean.FALSE);
        data.addRenderedFloatingItem(shell);

        ResolvedBuildContext ctx = plan(data, storyWithSingleCellTable("u1f5i1", "cell text"));
        ObjectPlan tablePlan = findPlanByKind(ctx, 501, "text_frame:table_only");
        ObjectPlan shellPlan = findRenderedPlan(ctx, 502, "slot_only_textless_shell");

        Assert.assertNotNull(tablePlan);
        Assert.assertEquals(VisualAction.PLACE_TABLE_STYLE, tablePlan.visualAction);
        Assert.assertArrayEquals(new int[0], tablePlan.styleSourceObjectIds);
        Assert.assertNotNull(shellPlan);
        Assert.assertEquals(VisualAction.PLACE_TEXT_SHELL, shellPlan.visualAction);
    }

    @Test
    public void shiftedResolvedTableBoundsDoNotOverrideTableOnlyOwnerBounds() {
        ResolvedData data = new ResolvedData();
        ResolvedTextFrame tf = textFrame(501, "\u0016");
        tf.storyId("501");
        tf.pageRelativeBounds(new double[] { 155.0, 63.0, 189.0, 203.0 });
        data.addTextFrame(tf);

        ResolvedTable table = new ResolvedTable();
        table.id("u1f5i1");
        table.bounds(new double[] { 149.0, 67.5, 183.0, 207.5 });
        data.addTable(table);

        Assert.assertArrayEquals(
                new double[] { 155.0, 63.0, 189.0, 203.0 },
                data.getTablePlacementBounds("u1f5i1"),
                0.0001);
    }

    @Test
    public void renderedPlanLookupPrefersExactArtifactBeforeSharedCandidate() {
        ResolvedData data = new ResolvedData();
        RenderedGroup composite = rendered(
                700,
                "page_object",
                "page_object",
                "master_graphic_textless",
                "indesign_png",
                "hwpx_tf",
                new String[0],
                new int[] { 10, 11, 12 });
        composite.candidateId("shared-candidate");
        composite.file("rendered_frames/composite.png");
        data.addRenderedFloatingItem(composite);

        RenderedGroup direct = rendered(
                701,
                "page_object",
                "page_object",
                "master_graphic",
                "indesign_png",
                "none",
                new String[0],
                new int[] { 12 });
        direct.candidateId("shared-candidate");
        direct.file("rendered_frames/direct.png");
        data.addRenderedFloatingItem(direct);

        ResolvedBuildContext ctx = new ResolvedBuildContext();
        ctx.resolvedData = data;
        ObjectPlan compositePlan = renderedPlan(
                700,
                "planner_declared_rendered:pass.master_page_graphics:MASTER_ITEM",
                "rendered_frames/composite.png",
                VisualAction.PLACE_TEXT_SHELL,
                VisualLayer.LABEL_BACKDROP,
                Materialization.EXTRACTED_PNG_VECTOR,
                0)
                .withExtractionCandidate("shared-candidate", "pass.master_page_graphics", "TEXTLESS_SHELL_SLOT");
        ObjectPlan directPlan = renderedPlan(
                701,
                "rendered_floating_item:page_object:page_object",
                "rendered_frames/direct.png",
                VisualAction.PLACE_FLOATING_PNG,
                VisualLayer.CONTENT_VISUAL,
                Materialization.EXTRACTED_PNG_VECTOR,
                5)
                .withExtractionCandidate("shared-candidate", "pass.master_page_graphics", "CONTENT_VISUAL_SLOT");
        ctx.addOwnershipPlan(compositePlan);
        ctx.addOwnershipPlan(directPlan);

        Assert.assertSame(directPlan, ctx.findOwnershipPlanForRendered(direct));
        OwnershipPlanValidator.validate(ctx);
        Assert.assertFalse(hasWarning(ctx, "STAGE4_RENDERED_PLAN_ARTIFACT_MISMATCH"));
    }

    @Test
    public void validatorWarnsWhenPageBackgroundKeepsSourceZAboveBottomBand() {
        ResolvedBuildContext ctx = new ResolvedBuildContext();
        ctx.resolvedData = new ResolvedData();
        ctx.addOwnershipPlan(renderedPlan(
                720,
                "rendered_floating_item:page_object:page_object",
                "rendered_frames/background.png",
                VisualAction.PLACE_FLOATING_PNG,
                VisualLayer.PAGE_BACKGROUND,
                Materialization.TEXTLESS_VISUAL_FRAGMENT,
                12));

        OwnershipPlanValidator.validate(ctx);

        Assert.assertTrue(hasWarning(ctx, "STAGE4_PAGE_BACKGROUND_NOT_BOTTOM_Z"));
    }

    private static ResolvedBuildContext plan(ResolvedData data) {
        ResolvedBuildContext ctx = new ResolvedBuildContext();
        ctx.resolvedData = data;
        OwnershipPlanner.runObservation(ctx);
        return ctx;
    }

    private static ResolvedBuildContext plan(ResolvedData data, IDMLStory story) {
        ResolvedBuildContext ctx = new ResolvedBuildContext();
        ctx.resolvedData = data;
        ctx.loadIDMLStory = storyId -> story;
        OwnershipPlanner.runObservation(ctx);
        return ctx;
    }

    private static ObjectPlan findRenderedPlan(ResolvedBuildContext ctx, int renderId, String reason) {
        for (ObjectPlan plan : ctx.ownershipPlans) {
            if (plan.renderId == null) continue;
            if (plan.renderId.intValue() == renderId && reason.equals(plan.reason)) {
                return plan;
            }
        }
        return null;
    }

    private static ObjectPlan findRenderedPlanByKind(ResolvedBuildContext ctx, int renderId, String kindPrefix) {
        for (ObjectPlan plan : ctx.ownershipPlans) {
            if (plan.renderId == null) continue;
            if (plan.renderId.intValue() == renderId && plan.kind.startsWith(kindPrefix)) {
                return plan;
            }
        }
        return null;
    }

    private static ObjectPlan findPlanByKind(ResolvedBuildContext ctx, int domId, String kindPrefix) {
        for (ObjectPlan plan : ctx.ownershipPlans) {
            if (plan.domId == domId && plan.kind.startsWith(kindPrefix)) {
                return plan;
            }
        }
        return null;
    }

    private static boolean containsSource(ObjectPlan plan, int sourceId) {
        if (plan == null || plan.sourceObjectIds == null) return false;
        for (int id : plan.sourceObjectIds) {
            if (id == sourceId) return true;
        }
        return false;
    }

    private static boolean hasWarning(ResolvedBuildContext ctx, String code) {
        if (ctx == null || ctx.ownershipWarningLines == null) return false;
        String needle = "\"code\":\"" + code + "\"";
        for (String line : ctx.ownershipWarningLines) {
            if (line != null && line.contains(needle)) return true;
        }
        return false;
    }

    private static ObjectPlan renderedPlan(
            int renderId,
            String kind,
            String file,
            VisualAction visualAction,
            VisualLayer visualLayer,
            Materialization materialization,
            int zOrder) {
        return new ObjectPlan(
                renderId,
                kind,
                0,
                TextAction.DROP_TEXT,
                visualAction,
                visualLayer,
                Placement.FLOATING,
                renderId,
                new int[] { renderId },
                new int[] { renderId },
                new int[0],
                new int[0],
                new int[0],
                "test-bundle-" + renderId,
                materialization,
                CoordinateSpace.PAGE,
                null,
                zOrder,
                "test_plan",
                file,
                new double[] { 10, 10, 20, 40 },
                null,
                null,
                -1);
    }

    private static ResolvedTextFrame textFrame(int id, String text) {
        ResolvedTextFrame tf = new ResolvedTextFrame();
        tf.id(String.valueOf(id));
        tf.pageIndex(0);
        tf.zOrder(20);
        tf.frameVisibleText(text);
        tf.geometricBounds(new double[] { 10, 10, 20, 40 });
        tf.pageRelativeBounds(new double[] { 10, 10, 20, 40 });
        return tf;
    }

    private static ResolvedPage page(int index, double[] bounds) {
        ResolvedPage page = new ResolvedPage();
        page.index(index);
        page.name(String.valueOf(index + 1));
        page.bounds(bounds);
        return page;
    }

    private static ResolvedPageItem pageItem(
            int id,
            String type,
            double[] bounds,
            String fillColorName,
            String strokeColorName,
            double strokeWeight) {
        ResolvedPageItem item = new ResolvedPageItem();
        item.id(String.valueOf(id));
        item.type(type);
        item.pageIndex(0);
        item.geometricBounds(bounds);
        item.fillColorName(fillColorName);
        item.strokeColorName(strokeColorName);
        item.strokeWeight(strokeWeight);
        return item;
    }

    private static ResolvedStory story(String id, String firstParagraphStyleName) {
        ResolvedStory story = new ResolvedStory();
        story.id(id);
        ResolvedParagraph paragraph = new ResolvedParagraph();
        paragraph.styleName(firstParagraphStyleName);
        story.addParagraph(paragraph);
        return story;
    }

    private static void addInlineAnchor(ResolvedData data, int anchoredObjectId) {
        ResolvedStory story = new ResolvedStory();
        story.id("story-" + anchoredObjectId);
        ResolvedParagraph paragraph = new ResolvedParagraph();
        ResolvedRun run = new ResolvedRun();
        run.type("inline_anchor");
        run.anchoredObjectId(anchoredObjectId);
        paragraph.runs().add(run);
        story.addParagraph(paragraph);
        data.addStory(story);
    }

    private static RenderedGroup rendered(
            int id,
            String type,
            String itemType,
            String reason,
            String visualOwner,
            String textOwner,
            String[] editableTextFrameIds,
            int[] sourceObjectIds) {
        RenderedGroup rg = new RenderedGroup();
        rg.id(id);
        rg.type(type);
        rg.itemType(itemType);
        rg.reason(reason);
        rg.visualOwner(visualOwner);
        rg.textOwner(textOwner);
        rg.editableTextFrameIds(editableTextFrameIds);
        rg.sourceObjectIds(sourceObjectIds);
        rg.containsEditableText(Boolean.TRUE);
        rg.containsText(Boolean.TRUE);
        rg.placementAllowed(Boolean.TRUE);
        rg.pageIndex(0);
        rg.zOrder(10);
        rg.file("rendered_frames/test_" + id + ".png");
        rg.bounds(new double[] { 10, 10, 20, 40 });
        return rg;
    }

    private static IDMLStory storyWithSingleCellTable(String tableId, String text) {
        IDMLStory story = new IDMLStory();
        IDMLTable table = new IDMLTable();
        table.selfId(tableId);
        table.rowCount(1);
        table.columnCount(1);
        table.addColumnWidth(100.0);

        IDMLTableRow row = new IDMLTableRow();
        row.rowIndex(0);
        row.rowHeight(20.0);

        IDMLTableCell cell = new IDMLTableCell();
        cell.rowIndex(0);
        cell.columnIndex(0);
        IDMLParagraph paragraph = new IDMLParagraph();
        IDMLCharacterRun run = new IDMLCharacterRun();
        run.content(text);
        paragraph.addCharacterRun(run);
        cell.addParagraph(paragraph);
        row.addCell(cell);
        table.addRow(row);
        story.addTable(table);
        return story;
    }
}
