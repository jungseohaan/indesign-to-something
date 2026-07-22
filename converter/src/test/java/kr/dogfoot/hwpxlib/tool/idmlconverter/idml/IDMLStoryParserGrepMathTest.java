package kr.dogfoot.hwpxlib.tool.idmlconverter.idml;

import org.junit.Assert;
import org.junit.Test;

public class IDMLStoryParserGrepMathTest {
    @Test
    public void resolvesEhMathGrepFromAppliedParagraphStyleOnly() {
        IDMLDocument doc = new IDMLDocument();
        doc.putCharacterStyle("CharacterStyle/math-italic", charStyle("CharacterStyle/math-italic", "EH상부자"));
        doc.putParagraphStyle("ParagraphStyle/math", paraStyle("ParagraphStyle/math",
                "CharacterStyle/math-italic", "[\\l\\u]"));
        doc.putParagraphStyle("ParagraphStyle/plain", paraStyle("ParagraphStyle/plain",
                null, null));

        IDMLStory story = new IDMLStory();
        story.addParagraph(paragraph("ParagraphStyle/math", "x라고 할 때 y의 값"));
        story.addParagraph(paragraph("ParagraphStyle/plain", "x라고 할 때 y의 값"));
        doc.putStory("story", story);

        IDMLStoryParser.resolveGrepMathStyles(doc);

        IDMLParagraph mathPara = story.paragraphs().get(0);
        Assert.assertEquals(4, mathPara.characterRuns().size());
        Assert.assertEquals("x", mathPara.characterRuns().get(0).content());
        Assert.assertTrue(mathPara.characterRuns().get(0).grepMathFont());
        Assert.assertEquals("라고 할 때 ", mathPara.characterRuns().get(1).content());
        Assert.assertFalse(mathPara.characterRuns().get(1).grepMathFont());
        Assert.assertEquals("y", mathPara.characterRuns().get(2).content());
        Assert.assertTrue(mathPara.characterRuns().get(2).grepMathFont());

        IDMLParagraph plainPara = story.paragraphs().get(1);
        Assert.assertEquals(1, plainPara.characterRuns().size());
        Assert.assertEquals("x라고 할 때 y의 값", plainPara.characterRuns().get(0).content());
        Assert.assertFalse(plainPara.characterRuns().get(0).grepMathFont());
    }

    @Test
    public void ignoresGrepWhoseCharacterStyleIsNotMathFont() {
        IDMLDocument doc = new IDMLDocument();
        doc.putCharacterStyle("CharacterStyle/accent", charStyle("CharacterStyle/accent", "Yoon가변 윤고딕100Std_OTF"));
        doc.putParagraphStyle("ParagraphStyle/accent", paraStyle("ParagraphStyle/accent",
                "CharacterStyle/accent", "[\\l\\u]"));

        IDMLStory story = new IDMLStory();
        story.addParagraph(paragraph("ParagraphStyle/accent", "x라고"));
        doc.putStory("story", story);

        IDMLStoryParser.resolveGrepMathStyles(doc);

        IDMLParagraph para = story.paragraphs().get(0);
        Assert.assertEquals(1, para.characterRuns().size());
        Assert.assertFalse(para.characterRuns().get(0).grepMathFont());
    }

    private static IDMLStyleDef charStyle(String selfRef, String fontFamily) {
        IDMLStyleDef style = new IDMLStyleDef();
        style.selfRef(selfRef);
        style.fontFamily(fontFamily);
        return style;
    }

    private static IDMLStyleDef paraStyle(String selfRef, String charStyleRef, String grep) {
        IDMLStyleDef style = new IDMLStyleDef();
        style.selfRef(selfRef);
        if (charStyleRef != null && grep != null) {
            style.addGrepStyle(new IDMLStyleDef.GrepStyleRule(grep, charStyleRef));
        }
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
