package kr.dogfoot.hwpxlib.tool.idmlconverter.semanticblock;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTDocument;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTFigure;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTInlineObject;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTPageLayout;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTParagraph;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTSection;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTStyleDef;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTextFrameBlock;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTextRun;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.RenderedGroup;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedData;
import org.junit.Assert;
import org.junit.Test;

public class SemanticBlockDetectorTest {
    @Test
    public void groupsAnchorBodyAndNearbyFigure() {
        ASTDocument doc = new ASTDocument();
        doc.sourceFile("sample.indd");
        doc.addParagraphStyle(style("개념제목", "개념 제목 스타일", 2200));
        doc.addParagraphStyle(style("본문", "본문 스타일", 1000));

        ASTSection section = new ASTSection();
        section.pageNumber(6);
        section.addBlock(textFrame("tf-title", 1000, 1000, 20000, 3000,
                "개념제목", "학습 목표"));
        section.addBlock(textFrame("tf-body", 1000, 5000, 20000, 6000,
                "본문", "작품을 읽고 인물의 생각을 파악해 봅시다."));
        section.addBlock(figure("fig-1", 23000, 5000, 8000, 8000, null));
        doc.addSection(section);

        SemanticBlockDocument result = SemanticBlockDetector.detect(doc, doc.sourceFile());

        Assert.assertEquals(1, result.blocks.size());
        SemanticBlock block = result.blocks.get(0);
        Assert.assertEquals("tf-title", block.anchor_id);
        Assert.assertTrue(block.member_ids.contains("tf-title"));
        Assert.assertTrue(block.member_ids.contains("tf-body"));
        Assert.assertTrue(block.member_ids.contains("fig-1"));
        Assert.assertEquals(3, block.member_ids.size());
        Assert.assertEquals(3, block.member_boxes.size());
        Assert.assertEquals("tf-title", block.member_boxes.get(0).id);
        Assert.assertEquals(6, block.member_boxes.get(0).page);
        Assert.assertEquals("text", block.member_boxes.get(0).kind);
        Assert.assertEquals("text_frame", block.member_boxes.get(0).role);
        Assert.assertArrayEquals(new long[] {1000, 1000, 21000, 4000}, block.member_boxes.get(0).bbox);
        Assert.assertEquals(1, result.summary.anchors);
        Assert.assertEquals("개념 제목 스타일", block.display_name);
    }

    @Test
    public void skipsPageBackgroundFigures() {
        ASTDocument doc = new ASTDocument();
        ASTSection section = new ASTSection();
        section.pageNumber(1);
        section.addBlock(figure("bg-1", 0, 0, 100000, 100000, "PAGE_BACKGROUND"));
        section.addBlock(textFrame("tf-1", 1000, 1000, 10000, 3000,
                "제목", "단원 열기"));
        doc.addSection(section);

        SemanticBlockDocument result = SemanticBlockDetector.detect(doc, "sample.indd");

        Assert.assertEquals(1, result.blocks.size());
        Assert.assertFalse(result.blocks.get(0).member_ids.contains("bg-1"));
    }

    @Test
    public void graphicOnlyBlockUsesGraphicDisplayName() {
        ASTDocument doc = new ASTDocument();
        ASTSection section = new ASTSection();
        section.pageNumber(1);
        section.addBlock(figure("fig-1", 1000, 1000, 10000, 10000, null));
        doc.addSection(section);

        SemanticBlockDocument result = SemanticBlockDetector.detect(doc, "sample.indd");

        Assert.assertEquals(1, result.blocks.size());
        Assert.assertEquals("그래픽", result.blocks.get(0).display_name);
    }

    @Test
    public void includesInlineObjectSourceIdsWithParentTextFrame() {
        ASTDocument doc = new ASTDocument();
        ASTSection section = new ASTSection();
        section.pageNumber(1);

        ASTTextFrameBlock tf = textFrame("tf-1", 1000, 1000, 10000, 3000,
                "제목", "활동 1");
        ASTInlineObject inline = new ASTInlineObject();
        inline.kind(ASTInlineObject.ObjectKind.IMAGE);
        inline.sourceId("inline-img-1");
        tf.paragraphs().get(0).addItem(inline);
        section.addBlock(tf);
        doc.addSection(section);

        SemanticBlockDocument result = SemanticBlockDetector.detect(doc, "sample.indd");

        Assert.assertEquals(1, result.blocks.size());
        Assert.assertTrue(result.blocks.get(0).member_ids.contains("tf-1"));
        Assert.assertTrue(result.blocks.get(0).member_ids.contains("inline-img-1"));
        SemanticBlock.MemberBox inlineBox = findBox(result.blocks.get(0), "inline-img-1");
        Assert.assertNotNull(inlineBox);
        Assert.assertEquals("inline_object", inlineBox.role);
        Assert.assertEquals("tf-1", inlineBox.parent_id);
        Assert.assertArrayEquals(new long[] {1000, 1000, 11000, 4000}, inlineBox.bbox);
    }

    @Test
    public void skipsPageNumbersAndRepeatedEdgeText() {
        ASTDocument doc = new ASTDocument();

        ASTSection page1 = section(11);
        page1.addBlock(textFrame("footer-1", 1000, 76000, 20000, 1200,
                "꼬리말", "Ⅰ. 단원 반복 푸터"));
        page1.addBlock(textFrame("page-no-1", 56000, 76000, 3000, 1200,
                "쪽번호", "11"));
        page1.addBlock(textFrame("content-1", 1000, 5000, 20000, 3000,
                "본문", "활동 1"));
        doc.addSection(page1);

        ASTSection page2 = section(12);
        page2.addBlock(textFrame("footer-2", 1000, 76000, 20000, 1200,
                "꼬리말", "Ⅰ. 단원 반복 푸터"));
        page2.addBlock(textFrame("page-no-2", 56000, 76000, 3000, 1200,
                "쪽번호", "12"));
        page2.addBlock(textFrame("content-2", 1000, 5000, 20000, 3000,
                "본문", "활동 2"));
        doc.addSection(page2);

        SemanticBlockDocument result = SemanticBlockDetector.detect(doc, "sample.indd");

        Assert.assertEquals(2, result.blocks.size());
        for (SemanticBlock block : result.blocks) {
            Assert.assertFalse(block.member_ids.contains("footer-1"));
            Assert.assertFalse(block.member_ids.contains("footer-2"));
            Assert.assertFalse(block.member_ids.contains("page-no-1"));
            Assert.assertFalse(block.member_ids.contains("page-no-2"));
        }
    }

    @Test
    public void separatesDistantFallbackTextFrames() {
        ASTDocument doc = new ASTDocument();
        ASTSection section = section(1);
        section.addBlock(textFrame("body-1", 1000, 1000, 20000, 3000,
                "본문", "첫 번째 본문입니다."));
        section.addBlock(textFrame("body-2", 1000, 31000, 20000, 3000,
                "본문", "두 번째 본문입니다."));
        doc.addSection(section);

        SemanticBlockDocument result = SemanticBlockDetector.detect(doc, "sample.indd");

        Assert.assertEquals(2, result.blocks.size());
        Assert.assertTrue(result.blocks.get(0).member_ids.contains("body-1"));
        Assert.assertTrue(result.blocks.get(1).member_ids.contains("body-2"));
    }

    @Test
    public void classifiesActivityAnchor() {
        ASTDocument doc = new ASTDocument();
        ASTSection section = new ASTSection();
        section.pageNumber(1);
        section.addBlock(textFrame("activity-1", 1000, 1000, 20000, 3000,
                "본문", "활동 1"));
        doc.addSection(section);

        SemanticBlockDocument result = SemanticBlockDetector.detect(doc, "sample.indd");

        Assert.assertEquals(1, result.blocks.size());
        Assert.assertEquals("activity", result.blocks.get(0).block_type);
        Assert.assertEquals("activity_start", result.blocks.get(0).debug.anchor_type);
    }

    @Test
    public void attachesDistantVisualByResolvedEditableTextRelation() {
        ASTDocument doc = new ASTDocument();
        ASTSection section = section(1);
        section.addBlock(textFrame("u64", 1000, 1000, 14000, 3000,
                "제목", "활동 1"));
        section.addBlock(figure("page_obj_900", 52000, 65000, 6000, 6000, null));
        doc.addSection(section);

        ResolvedData resolved = new ResolvedData();
        RenderedGroup group = renderedGroup(900, 0);
        group.editableTextFrameIds(new String[] {"100"});
        group.textOwner("hwpx_tf");
        group.visualOwner("indesign_png");
        resolved.addRenderedFloatingItem(group);

        SemanticBlockDocument result = SemanticBlockDetector.detect(doc, "sample.indd", resolved);

        Assert.assertEquals(1, result.blocks.size());
        SemanticBlock block = result.blocks.get(0);
        Assert.assertTrue(block.member_ids.contains("u64"));
        Assert.assertTrue(block.member_ids.contains("page_obj_900"));
        Assert.assertEquals(0, result.summary.unattached_visuals);
        Assert.assertTrue(block.signals.resolved_relation_score >= 0.98);
    }

    @Test
    public void skipsPlacementDisallowedResolvedVisual() {
        ASTDocument doc = new ASTDocument();
        ASTSection section = section(1);
        section.addBlock(textFrame("u64", 1000, 1000, 14000, 3000,
                "제목", "활동 1"));
        section.addBlock(figure("page_obj_901", 2000, 5000, 6000, 6000, null));
        doc.addSection(section);

        ResolvedData resolved = new ResolvedData();
        RenderedGroup group = renderedGroup(901, 0);
        group.placementAllowed(Boolean.FALSE);
        resolved.addRenderedFloatingItem(group);

        SemanticBlockDocument result = SemanticBlockDetector.detect(doc, "sample.indd", resolved);

        Assert.assertEquals(1, result.blocks.size());
        Assert.assertFalse(result.blocks.get(0).member_ids.contains("page_obj_901"));
        Assert.assertEquals(0, result.summary.unattached_visuals);
    }

    private static SemanticBlock.MemberBox findBox(SemanticBlock block, String id) {
        for (SemanticBlock.MemberBox box : block.member_boxes) {
            if (id.equals(box.id)) return box;
        }
        return null;
    }

    private static ASTTextFrameBlock textFrame(String id, long x, long y, long w, long h,
                                               String paragraphStyle, String text) {
        ASTTextFrameBlock tf = new ASTTextFrameBlock();
        tf.sourceId(id);
        tf.x(x);
        tf.y(y);
        tf.width(w);
        tf.height(h);
        ASTParagraph paragraph = new ASTParagraph();
        paragraph.paragraphStyleRef(paragraphStyle);
        ASTTextRun run = new ASTTextRun();
        run.text(text);
        paragraph.addItem(run);
        tf.addParagraph(paragraph);
        return tf;
    }

    private static ASTSection section(int pageNumber) {
        ASTSection section = new ASTSection();
        section.pageNumber(pageNumber);
        ASTPageLayout layout = new ASTPageLayout();
        layout.pageWidth(60000);
        layout.pageHeight(80000);
        section.layout(layout);
        return section;
    }

    private static RenderedGroup renderedGroup(int id, int pageIndex) {
        RenderedGroup group = new RenderedGroup();
        group.id(id);
        group.pageIndex(pageIndex);
        group.type("page_object");
        group.itemType("group");
        group.reason("editable_textframe_visual_shell");
        group.placementAllowed(Boolean.TRUE);
        group.bounds(new double[] {0, 0, 10, 10});
        return group;
    }

    private static ASTFigure figure(String id, long x, long y, long w, long h, String visualLayer) {
        ASTFigure figure = new ASTFigure();
        figure.sourceId(id);
        figure.kind(ASTFigure.FigureKind.IMAGE);
        figure.x(x);
        figure.y(y);
        figure.width(w);
        figure.height(h);
        figure.visualLayer(visualLayer);
        return figure;
    }

    private static ASTStyleDef style(String id, String name, int fontSizeHwpunits) {
        ASTStyleDef style = new ASTStyleDef();
        style.styleId(id);
        style.styleName(name);
        style.fontSizeHwpunits(fontSizeHwpunits);
        return style;
    }
}
