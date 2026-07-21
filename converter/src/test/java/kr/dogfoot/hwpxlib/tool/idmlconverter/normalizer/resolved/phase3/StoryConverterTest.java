package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.phase3;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTBlock;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTInlineObject;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTInlineItem;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTParagraph;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTSection;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTextFrameBlock;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTextRun;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLCharacterRun;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLParagraph;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLStory;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLStoryParser;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTable;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTableCell;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTableRow;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ResolvedBuildContext;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.AnchoredTablePlan;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.ObjectPlan;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.Placement;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.SimpleButtonLabelPlan;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.TextAction;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.VisualAction;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.ownership.VisualLayer;
import kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer.resolved.shared.ParagraphTextHelpers;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.RenderedGroup;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedData;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedPageItem;
import kr.dogfoot.hwpxlib.tool.idmlconverter.resolved.ResolvedTextFrame;
import org.junit.Assert;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.w3c.dom.Document;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Collections;
import java.util.HashMap;

public class StoryConverterTest {
    @Rule
    public TemporaryFolder temp = new TemporaryFolder();

    @Test
    public void inlinePngPlanWithSeparateHwpxTextDoesNotBecomeInlineTextFrame() {
        ResolvedData data = new ResolvedData();

        ResolvedPageItem group = new ResolvedPageItem();
        group.id("96421");
        group.type("Group");
        data.addPageItem(group);

        ResolvedPageItem textItem = new ResolvedPageItem();
        textItem.id("96423");
        textItem.type("TextFrame");
        textItem.parentId("96421");
        data.addPageItem(textItem);

        ResolvedPageItem shellItem = new ResolvedPageItem();
        shellItem.id("96422");
        shellItem.type("Polygon");
        shellItem.parentId("96421");
        shellItem.geometricBounds(new double[] { 112.0, 283.3, 115.3, 296.6 });
        data.addPageItem(shellItem);

        ResolvedBuildContext ctx = new ResolvedBuildContext();
        ctx.resolvedData = data;
        ctx.ownershipPlans.add(ObjectPlan.legacyDefaulted(
                96421,
                "rendered_floating_item:inline_object:inline_object",
                1,
                TextAction.DROP_TEXT,
                VisualAction.PLACE_INLINE_PNG,
                VisualLayer.CONTENT_VISUAL,
                Placement.INLINE,
                96421,
                new int[] { 96421, 96422 },
                0,
                "inline_graphic_only",
                "rendered_frames/inline_96421.png",
                null));
        ctx.ownershipPlans.add(ObjectPlan.legacyDefaulted(
                96423,
                "text_frame",
                1,
                TextAction.OWNED_BY_HWPX_TEXT,
                VisualAction.DROP_VISUAL,
                VisualLayer.CONTENT_VISUAL,
                Placement.FLOATING,
                null,
                new int[] { 96423 },
                18,
                "editable_text_frame",
                "",
                null));

        Assert.assertTrue(InlineFrameHandler.shouldUsePlannedInlinePngWithSeparateHwpxText(ctx, 96421));
        Assert.assertTrue(InlineFrameHandler.buildChildEditableBoxes(ctx, 96421).isEmpty());

        ResolvedBuildContext renderedCtx = new ResolvedBuildContext();
        renderedCtx.resolvedData = data;
        renderedCtx.ownershipPlans.addAll(ctx.ownershipPlans);
        renderedCtx.resolvedData.addRenderedFloatingItem(renderedGroup(
                96421,
                "inline_object",
                null,
                "inline_graphic_only",
                "indesign_png",
                "none",
                null,
                false));
        renderedCtx.resolvedData.addRenderedFloatingItem(renderedGroup(
                96421,
                "page_object",
                null,
                "visual_label_text_hidden_shell",
                "indesign_png",
                "hwpx_tf",
                new String[] { "96423" },
                true));
        renderedCtx.addSimpleButtonLabelPlan(new SimpleButtonLabelPlan(
                96421,
                96423,
                96422,
                1,
                "예시 답안",
                null,
                null,
                9.0,
                "#0037b8",
                null,
                null,
                SimpleButtonLabelPlan.Mode.TEXT_SHELL,
                new int[] { 96421, 96422, 96423 },
                "simple_button_label_text_shell"));
        Assert.assertTrue(InlineFrameHandler.shouldUsePlannedInlinePngWithSeparateHwpxText(renderedCtx, 96421));
        Assert.assertNull(SimpleButtonLabelInlineFactory.create(renderedCtx, 96421));
    }

    @Test
    public void storyWithStandaloneTextAndTableKeepsTextParagraphs() throws Exception {
        File idmlDir = temp.newFolder("idml");
        File storiesDir = new File(idmlDir, "Stories");
        Assert.assertTrue(storiesDir.mkdirs());
        File storyFile = new File(storiesDir, "Story_u365c.xml");
        Files.write(storyFile.toPath(), mixedTextAndTableStoryXml().getBytes(StandardCharsets.UTF_8));

        ResolvedData data = new ResolvedData();
        data.addTextFrame(textFrameWithVisibleBodyText());
        data.editableTextFrameIds(Collections.singleton("13913"));

        ResolvedBuildContext ctx = new ResolvedBuildContext();
        ctx.resolvedData = data;
        ctx.idmlDir = idmlDir;
        ctx.idmlStoryCache = new HashMap<String, IDMLStory>();
        ctx.spec016Counts = new int[3];
        ctx.lastMatchResult = new int[] { -1 };
        ctx.loadIDMLStory = storyId -> loadStory(storyFile);

        ASTTextFrameBlock block = new ASTTextFrameBlock();
        block.sourceId("u3659");
        block.storyId("13916");
        block.frameVisibleText("우리나라 초·중등학교 교육과정은 사회 변화와 시대적 요구를 반영하여 지속적으로 개정되고 발전해 왔다.");
        block.frameVisibleTextLength(block.frameVisibleText().length());

        ASTSection section = new ASTSection();
        section.addBlock(block);

        StoryConverter.convertStories(ctx, Collections.singletonList(section));

        Assert.assertFalse(block.paragraphs().isEmpty());
        String text = blockText(block);
        Assert.assertTrue(text.contains("우리나라 초·중등학교 교육과정"));
        Assert.assertTrue(text.contains("교육과정 구성의 중점"));
    }

    @Test
    public void inlineTextFrameWithTableBecomesInlineTableObject() {
        ResolvedData data = new ResolvedData();
        ResolvedTextFrame tf = new ResolvedTextFrame();
        tf.id("26964");
        tf.storyId("26967");
        tf.isInline(true);
        tf.geometricBounds(new double[] { 210.4, 57.0, 254.7, 197.0 });
        data.addTextFrame(tf);
        data.editableTextFrameIds(Collections.singleton("26964"));

        ResolvedBuildContext ctx = new ResolvedBuildContext();
        ctx.resolvedData = data;
        ctx.spec016Counts = new int[3];
        addInlineTableSourcePlan(ctx, 26964);
        ctx.loadIDMLStory = storyId -> inlineTableStory();

        ASTInlineObject obj = InlineFrameHandler.tryInlineTextFrameWithTables(ctx, 26964);

        Assert.assertNotNull(obj);
        Assert.assertNotNull(obj.inlineTables());
        Assert.assertEquals(1, obj.inlineTables().size());
        Assert.assertEquals(3, obj.inlineTables().get(0).rowCount());
        String text = obj.inlineTables().get(0).rows().get(0).cells().get(0)
                .paragraphs().get(0).items().stream()
                .filter(item -> item instanceof ASTTextRun)
                .map(item -> ((ASTTextRun) item).text())
                .reduce("", String::concat);
        Assert.assertTrue(text.contains("디지털 전환"));
    }

    @Test
    public void inlineGroupWithTableTextFrameDescendantBecomesInlineTableObject() {
        ResolvedData data = new ResolvedData();
        ResolvedPageItem group = new ResolvedPageItem();
        group.id("26963");
        group.type("Group");
        group.isInline(true);
        group.geometricBounds(new double[] { 210.4, 57.0, 254.7, 197.0 });
        data.addPageItem(group);

        ResolvedPageItem childItem = new ResolvedPageItem();
        childItem.id("26964");
        childItem.type("TextFrame");
        childItem.parentId("26963");
        childItem.isInline(true);
        childItem.geometricBounds(new double[] { 210.4, 57.0, 254.7, 197.0 });
        data.addPageItem(childItem);

        ResolvedTextFrame childTf = new ResolvedTextFrame();
        childTf.id("26964");
        childTf.storyId("26967");
        childTf.isInline(true);
        childTf.frameVisibleText("\u0016");
        childTf.geometricBounds(new double[] { 210.4, 57.0, 254.7, 197.0 });
        data.addTextFrame(childTf);
        data.editableTextFrameIds(Collections.singleton("26964"));

        ResolvedBuildContext ctx = new ResolvedBuildContext();
        ctx.resolvedData = data;
        ctx.spec016Counts = new int[3];
        addInlineTableSourcePlan(ctx, 26964);
        ctx.loadIDMLStory = storyId -> inlineTableStory();

        ASTInlineObject obj = InlineFrameHandler.tryInlineTextFrameWithTables(ctx, 26963);

        Assert.assertNotNull(obj);
        Assert.assertNotNull(obj.inlineTables());
        Assert.assertEquals(1, obj.inlineTables().size());
        Assert.assertEquals(3, obj.inlineTables().get(0).rowCount());
    }

    @Test
    public void linkedFrameRangeMatchingIgnoresNonStoryControlSentinels() {
        ResolvedData data = new ResolvedData();
        ResolvedTextFrame first = linkedTextFrame("15275", "15278", 12,
                null, "29630", 0, 6, "\t성취기준\u0016\u0016");
        ResolvedTextFrame second = linkedTextFrame("29630", "15278", 13,
                "15275", null, 6, 20, "(가) 성취기준 해설");
        data.addTextFrame(first);
        data.addTextFrame(second);

        ResolvedBuildContext ctx = new ResolvedBuildContext();
        ctx.resolvedData = data;

        ASTParagraph title = paragraphWithText("성취기준");
        ASTParagraph heading = paragraphWithText("(가) 성취기준 해설");
        java.util.List<ASTParagraph> paragraphs = new java.util.ArrayList<>();
        paragraphs.add(title);
        paragraphs.add(heading);

        ASTTextFrameBlock firstBlock = linkedBlock("15275", "15278", "\t성취기준\u0016\u0016");
        ASTTextFrameBlock secondBlock = linkedBlock("29630", "15278", "(가) 성취기준 해설");
        java.util.List<ASTTextFrameBlock> blocks = new java.util.ArrayList<>();
        blocks.add(firstBlock);
        blocks.add(secondBlock);

        ParagraphDistributor.distributeParagraphs(ctx, paragraphs, blocks, "15278");

        Assert.assertEquals("성취기준", blockText(firstBlock));
        Assert.assertEquals("(가) 성취기준 해설", blockText(secondBlock));
    }

    @Test
    public void linkedFrameRangeMatchingShortensBoundaryPrefixBeforeOffsetFallback() {
        ResolvedData data = new ResolvedData();
        ResolvedTextFrame first = linkedTextFrame("15275", "15278", 12,
                null, "29630", 0, 7, "\uFFFC\t성취기준\n\u0016\n");
        ResolvedTextFrame second = linkedTextFrame("29630", "15278", 13,
                "15275", null, 9, 40, "(가) 성취기준 해설\u2003\n•\u0007[12문학01-02] 본문");
        data.addTextFrame(first);
        data.addTextFrame(second);

        ResolvedBuildContext ctx = new ResolvedBuildContext();
        ctx.resolvedData = data;

        ASTParagraph title = paragraphWithText("성취기준");
        ASTParagraph heading = paragraphWithText("(가) 성취기준 해설");
        ASTParagraph body = paragraphWithText("•[12문학01-02] 본문");
        java.util.List<ASTParagraph> paragraphs = new java.util.ArrayList<>();
        paragraphs.add(title);
        paragraphs.add(heading);
        paragraphs.add(body);

        ASTTextFrameBlock firstBlock = linkedBlock("15275", "15278", "\uFFFC\t성취기준\n\u0016\n");
        ASTTextFrameBlock secondBlock = linkedBlock("29630", "15278",
                "(가) 성취기준 해설\u2003\n•\u0007[12문학01-02] 본문");
        java.util.List<ASTTextFrameBlock> blocks = new java.util.ArrayList<>();
        blocks.add(firstBlock);
        blocks.add(secondBlock);

        ParagraphDistributor.distributeParagraphs(ctx, paragraphs, blocks, "15278");

        Assert.assertEquals("성취기준", blockText(firstBlock));
        Assert.assertEquals("(가) 성취기준 해설•[12문학01-02] 본문", blockText(secondBlock));
    }

    @Test
    public void singleExtractedFrameStopsAtFrameBreakAfterInlineShell() {
        ResolvedData data = new ResolvedData();
        ResolvedTextFrame first = linkedTextFrame("15275", "15278", 12,
                null, null, 0, 7, "\uFFFC\t성취기준\n\u0016\n");
        data.addTextFrame(first);

        ResolvedBuildContext ctx = new ResolvedBuildContext();
        ctx.resolvedData = data;

        ASTParagraph title = paragraphWithText("\uFFFC\t성취기준");
        ASTParagraph shell = paragraphWithInlineObject("inline-shell child text that must not shift story range");
        ASTParagraph heading = paragraphWithText("(가) 성취기준 해설");
        ASTParagraph body = paragraphWithText("•[12문학01-02] 본문");
        java.util.List<ASTParagraph> paragraphs = new java.util.ArrayList<>();
        paragraphs.add(title);
        paragraphs.add(shell);
        paragraphs.add(heading);
        paragraphs.add(body);

        ASTTextFrameBlock firstBlock = linkedBlock("15275", "15278", "\uFFFC\t성취기준\n\u0016\n");
        java.util.List<ASTTextFrameBlock> blocks = new java.util.ArrayList<>();
        blocks.add(firstBlock);

        ParagraphDistributor.distributeParagraphs(ctx, paragraphs, blocks, "15278");

        Assert.assertEquals(2, firstBlock.paragraphs().size());
        Assert.assertEquals("\uFFFC\t성취기준", blockText(firstBlock));
    }

    private static ResolvedTextFrame textFrameWithVisibleBodyText() {
        ResolvedTextFrame tf = new ResolvedTextFrame();
        tf.id("13913");
        tf.storyId("13916");
        tf.pageIndex(7);
        tf.paragraphStart(0);
        tf.paragraphEnd(8);
        tf.lineCount(18);
        tf.geometricBounds(new double[] { 77, 57, 254.736776063707, 197 });
        tf.pageRelativeBounds(new double[] { 77, 57, 254.736776063707, 197 });
        tf.frameVisibleText("우리나라 초·중등학교 교육과정은 사회 변화와 시대적 요구를 반영하여 지속적으로 개정되고 발전해 왔다.\n"
                + "이를 위한 교육과정 구성의 중점은 다음과 같다.\u0016");
        return tf;
    }

    private static RenderedGroup renderedGroup(
            int id,
            String type,
            String itemType,
            String reason,
            String visualOwner,
            String textOwner,
            String[] editableTextFrameIds,
            boolean textHiddenBeforeExport) {
        RenderedGroup rg = new RenderedGroup();
        rg.id(id);
        rg.type(type);
        rg.itemType(itemType);
        rg.reason(reason);
        rg.visualOwner(visualOwner);
        rg.textOwner(textOwner);
        rg.editableTextFrameIds(editableTextFrameIds);
        rg.textHiddenBeforeExport(textHiddenBeforeExport);
        rg.containsText(Boolean.FALSE);
        rg.file("rendered_frames/test_" + id + ".png");
        return rg;
    }

    private static IDMLStory loadStory(File storyFile) {
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            DocumentBuilder db = dbf.newDocumentBuilder();
            Document doc = db.parse(storyFile);
            return IDMLStoryParser.parseStory(doc, "u365c");
        } catch (Exception e) {
            throw new AssertionError(e);
        }
    }

    private static IDMLStory inlineTableStory() {
        IDMLStory story = new IDMLStory();
        story.selfId("u6957");

        IDMLTable table = new IDMLTable();
        table.selfId("u6957i696d");
        table.columnCount(1);
        table.addColumnWidth(396.85);
        for (int i = 0; i < 3; i++) {
            IDMLTableRow row = new IDMLTableRow();
            row.rowIndex(i);
            row.rowHeight(41.85);

            IDMLTableCell cell = new IDMLTableCell();
            cell.rowIndex(i);
            cell.columnIndex(0);
            cell.rowSpan(1);
            cell.columnSpan(1);
            cell.addParagraph(paragraph(i == 0
                    ? "디지털 전환, 기후·생태환경 변화 등에 따른 미래 사회의 불확실성에 능동적으로 대응한다."
                    : "학생 개개인의 성장을 지원한다."));
            row.addCell(cell);
            table.addRow(row);
        }
        table.rowCount(3);
        story.addTable(table);
        return story;
    }

    private static void addInlineTableSourcePlan(ResolvedBuildContext ctx, int textFrameDomId) {
        ctx.addOwnershipPlan(ObjectPlan.legacyDefaulted(
                textFrameDomId,
                "text_frame",
                0,
                TextAction.OWNED_BY_HWPX_TEXT,
                VisualAction.PLACE_TABLE_STYLE,
                VisualLayer.CONTENT_VISUAL,
                Placement.INLINE,
                null,
                new int[] { textFrameDomId },
                0,
                "test_inline_table_text_frame",
                null,
                null));
        ctx.addAnchoredTablePlan(new AnchoredTablePlan(
                textFrameDomId,
                "26967",
                0,
                "u6957i696d",
                textFrameDomId,
                "26967",
                "u6957i696d",
                0,
                "test_inline_table_source"));
    }

    private static IDMLParagraph paragraph(String text) {
        IDMLParagraph paragraph = new IDMLParagraph();
        IDMLCharacterRun run = new IDMLCharacterRun();
        run.content(text);
        run.fontFamily("[Yoon가변] 윤고딕100_OTF");
        run.fontSize(9.0);
        run.fillColor("Black");
        paragraph.addCharacterRun(run);
        return paragraph;
    }

    private static ResolvedTextFrame linkedTextFrame(String id, String storyId, int pageIndex,
                                                     String previousFrameId, String nextFrameId,
                                                     int start, int end, String visibleText) {
        ResolvedTextFrame tf = new ResolvedTextFrame();
        tf.id(id);
        tf.storyId(storyId);
        tf.pageIndex(pageIndex);
        tf.previousFrameId(previousFrameId);
        tf.nextFrameId(nextFrameId);
        tf.paragraphStart(start);
        tf.paragraphEnd(end);
        tf.lineCount(1);
        tf.frameVisibleText(visibleText);
        tf.frameParaTexts(Collections.singletonList(visibleText));
        return tf;
    }

    private static ASTTextFrameBlock linkedBlock(String domId, String storyId, String visibleText) {
        ASTTextFrameBlock block = new ASTTextFrameBlock();
        block.sourceId(ParagraphTextHelpers.domIdToSourceId(domId));
        block.storyId(storyId);
        block.frameVisibleText(visibleText);
        block.frameVisibleTextLength(visibleText.length());
        return block;
    }

    private static ASTParagraph paragraphWithText(String text) {
        ASTParagraph paragraph = new ASTParagraph();
        ASTTextRun run = new ASTTextRun();
        run.text(text);
        paragraph.addItem(run);
        return paragraph;
    }

    private static ASTParagraph paragraphWithInlineObject(String childText) {
        ASTParagraph paragraph = new ASTParagraph();
        ASTInlineObject obj = new ASTInlineObject();
        obj.kind(ASTInlineObject.ObjectKind.INLINE_TEXT_FRAME);
        obj.addParagraph(paragraphWithText(childText));
        paragraph.addItem(obj);
        return paragraph;
    }

    private static String blockText(ASTTextFrameBlock block) {
        StringBuilder sb = new StringBuilder();
        for (ASTParagraph paragraph : block.paragraphs()) {
            for (ASTInlineItem item : paragraph.items()) {
                if (item instanceof ASTTextRun) {
                    String text = ((ASTTextRun) item).text();
                    if (text != null) sb.append(text);
                }
            }
        }
        return sb.toString();
    }

    private static String mixedTextAndTableStoryXml() {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n"
                + "<idPkg:Story xmlns:idPkg=\"http://ns.adobe.com/AdobeInDesign/idml/1.0/packaging\" DOMVersion=\"18.0\">\n"
                + "  <Story Self=\"u365c\">\n"
                + "    <StoryPreference StoryOrientation=\"Horizontal\"/>\n"
                + "    <ParagraphStyleRange AppliedParagraphStyle=\"ParagraphStyle/총론_내용\">\n"
                + "      <CharacterStyleRange AppliedCharacterStyle=\"CharacterStyle/$ID/[No character style]\">\n"
                + "        <Content>우리나라 초·중등학교 교육과정은 사회 변화와 시대적 요구를 반영하여 지속적으로 개정되고 발전해 왔다.</Content>\n"
                + "        <Br/>\n"
                + "        <Content>이를 위한 교육과정 구성의 중점은 다음과 같다.</Content>\n"
                + "        <Table Self=\"Table_u1\">\n"
                + "          <Column Self=\"Column_u1\" SingleColumnWidth=\"30\"/>\n"
                + "          <Row Self=\"Row_u1\" SingleRowHeight=\"10\"/>\n"
                + "          <Cell Self=\"Cell_u1\" Name=\"0:0\">\n"
                + "            <CellStyleRange>\n"
                + "              <ParagraphStyleRange AppliedParagraphStyle=\"ParagraphStyle/표_내용\">\n"
                + "                <CharacterStyleRange AppliedCharacterStyle=\"CharacterStyle/$ID/[No character style]\">\n"
                + "                  <Content>표 내용</Content>\n"
                + "                </CharacterStyleRange>\n"
                + "              </ParagraphStyleRange>\n"
                + "            </CellStyleRange>\n"
                + "          </Cell>\n"
                + "        </Table>\n"
                + "      </CharacterStyleRange>\n"
                + "    </ParagraphStyleRange>\n"
                + "  </Story>\n"
                + "</idPkg:Story>\n";
    }
}
