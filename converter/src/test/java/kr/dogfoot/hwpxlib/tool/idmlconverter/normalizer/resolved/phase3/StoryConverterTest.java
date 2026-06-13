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
        ctx.loadIDMLStory = storyId -> inlineTableStory();

        ASTInlineObject obj = InlineFrameHandler.tryInlineTextFrameWithTables(ctx, 26963);

        Assert.assertNotNull(obj);
        Assert.assertNotNull(obj.inlineTables());
        Assert.assertEquals(1, obj.inlineTables().size());
        Assert.assertEquals(3, obj.inlineTables().get(0).rowCount());
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
