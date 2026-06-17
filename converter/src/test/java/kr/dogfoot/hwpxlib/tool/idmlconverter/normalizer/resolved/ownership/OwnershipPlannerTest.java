package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership;

import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ResolvedBuildContext;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.RenderedGroup;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedData;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedPage;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedPageItem;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedParagraph;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedStory;
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
        data.addTextFrame(textFrame(101, "가"));
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

        ResolvedBuildContext ctx = plan(data);
        ObjectPlan plan = findRenderedPlan(ctx, 100, "inline_graphic_only");

        Assert.assertNotNull(plan);
        Assert.assertEquals(TextAction.OWNED_BY_PNG, plan.textAction);
        Assert.assertEquals(VisualAction.PLACE_INLINE_PNG, plan.visualAction);
        Assert.assertEquals(Placement.INLINE, plan.placement);
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
        Assert.assertNotNull(textPlan);
        Assert.assertEquals(TextAction.OWNED_BY_HWPX_TEXT, textPlan.textAction);
        Assert.assertEquals(Placement.FLOATING, textPlan.placement);
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

    private static ResolvedBuildContext plan(ResolvedData data) {
        ResolvedBuildContext ctx = new ResolvedBuildContext();
        ctx.resolvedData = data;
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
}
