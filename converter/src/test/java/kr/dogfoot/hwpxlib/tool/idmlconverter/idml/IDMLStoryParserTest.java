package kr.dogfoot.hwpxlib.tool.idmlconverter.idml;

import org.junit.Assert;
import org.junit.Test;

import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.Queue;
import java.util.Set;

public class IDMLStoryParserTest {

    @Test
    public void tableCellTextFrameStoryRefsAreLoadedAsInlineStoryReferences() {
        IDMLStory story = new IDMLStory();
        IDMLTable table = new IDMLTable();
        IDMLTableRow row = new IDMLTableRow();
        IDMLTableCell cell = new IDMLTableCell();
        cell.addTextFrameStoryRef("u6c6c");
        row.addCell(cell);
        table.addRow(row);
        story.addTable(table);

        Set<String> loaded = new LinkedHashSet<>();
        loaded.add("u36aa");
        Queue<String> queue = new LinkedList<>();

        IDMLStoryParser.collectInlineStoryIds(story, loaded, queue);

        Assert.assertEquals("u6c6c", queue.poll());
        Assert.assertTrue(queue.isEmpty());
    }

    @Test
    public void idmlGrepAnchoredObjectMarkerMatchesObjectReplacementCharacter() {
        java.util.regex.Pattern pattern = IDMLStoryParser.convertIdGrepToJavaPattern(
                "~a무엇을 알아볼까|어떻게 할까");

        Assert.assertNotNull(pattern);
        java.util.regex.Matcher matcher = pattern.matcher(
                "\uFFFC\uFFFC무엇을 알아볼까\u2007화학 변화에서 새로운 물질이 생성됨을 관찰할 수 있다.");

        Assert.assertTrue(matcher.find());
        Assert.assertEquals("\uFFFC무엇을 알아볼까", matcher.group());
    }

    @Test
    public void genericGrepStyleAppliesAfterLeadingAnchoredObjects() {
        IDMLDocument doc = new IDMLDocument();
        doc.putCharacterStyle("CharacterStyle/green-title", charStyle(
                "CharacterStyle/green-title", "Color/C=79 M=0 Y=77 K=49"));
        doc.putParagraphStyle("ParagraphStyle/probe", paraStyle(
                "ParagraphStyle/probe", "CharacterStyle/green-title", "~a무엇을 알아볼까|어떻게 할까"));

        IDMLStory story = new IDMLStory();
        story.addParagraph(paragraph("ParagraphStyle/probe",
                "\uFFFC\uFFFC무엇을 알아볼까\u2007화학 변화에서 새로운 물질이 생성됨을 관찰할 수 있다."));
        doc.putStory("story", story);

        IDMLStoryParser.resolveGrepGenericStyles(doc);

        IDMLParagraph para = story.paragraphs().get(0);
        Assert.assertEquals(3, para.characterRuns().size());
        Assert.assertEquals("\uFFFC", para.characterRuns().get(0).content());
        Assert.assertNull(para.characterRuns().get(0).grepAppliedCharStyle());
        Assert.assertEquals("\uFFFC무엇을 알아볼까", para.characterRuns().get(1).content());
        Assert.assertEquals("CharacterStyle/green-title",
                para.characterRuns().get(1).grepAppliedCharStyle());
        Assert.assertEquals("\u2007화학 변화에서 새로운 물질이 생성됨을 관찰할 수 있다.",
                para.characterRuns().get(2).content());
        Assert.assertNull(para.characterRuns().get(2).grepAppliedCharStyle());
    }

    private static IDMLStyleDef charStyle(String selfRef, String fillColor) {
        IDMLStyleDef style = new IDMLStyleDef();
        style.selfRef(selfRef);
        style.fillColor(fillColor);
        return style;
    }

    private static IDMLStyleDef paraStyle(String selfRef, String charStyleRef, String grep) {
        IDMLStyleDef style = new IDMLStyleDef();
        style.selfRef(selfRef);
        style.addGrepStyle(new IDMLStyleDef.GrepStyleRule(grep, charStyleRef));
        return style;
    }

    private static IDMLParagraph paragraph(String paraStyleRef, String text) {
        IDMLParagraph para = new IDMLParagraph();
        para.appliedParagraphStyle(paraStyleRef);
        IDMLCharacterRun run = new IDMLCharacterRun();
        run.content(text);
        para.addCharacterRun(run);
        return para;
    }
}
