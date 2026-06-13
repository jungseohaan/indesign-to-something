package kr.dogfoot.hwpxlib.tool.idmlconverter.resolved;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTDocument;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTInlineItem;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTParagraph;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTSection;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTStory;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTextFrameBlock;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTextRun;
import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;
import java.util.Collections;

public class ResolvedFrameDistributorTest {

    @Test
    public void characterOffsetFrameRangesKeepNextHeadingPrefixWithNextFrame() {
        ASTDocument doc = new ASTDocument();
        ASTStory story = new ASTStory();
        story.storyId("u3bae");
        story.linkedFrameIds(Arrays.asList("u3bab", "u73be"));
        doc.addStory(story);

        ASTSection page13 = section(13);
        ASTSection page14 = section(14);
        doc.addSection(page13);
        doc.addSection(page14);

        ASTTextFrameBlock first = block("u3bab", "u3bae");
        first.addParagraph(paragraph("\t성취기준"));
        first.addParagraph(paragraph(""));
        first.addParagraph(paragraph("(가) 성취기준 해설"));
        first.addParagraph(paragraph("•[12문학01-02] 본문"));
        ASTTextFrameBlock second = block("u73be", "u3bae");
        page13.addBlock(first);
        page14.addBlock(second);

        ResolvedData data = new ResolvedData();
        ResolvedStory resolvedStory = new ResolvedStory();
        resolvedStory.id("15278");
        resolvedStory.addParagraph(resolvedParagraph("\t성취기준\r"));
        resolvedStory.addParagraph(resolvedParagraph("\r"));
        resolvedStory.addParagraph(resolvedParagraph("(가) 성취기준 해설\r"));
        resolvedStory.addParagraph(resolvedParagraph("•[12문학01-02] 본문\r"));
        data.addStory(resolvedStory);

        data.addTextFrame(textFrame("15275", "15278", null, "29630",
                0, 7, "\uFFFC\t성취기준\n\u0016\n"));
        data.addTextFrame(textFrame("29630", "15278", "15275", null,
                9, 40, "(가) 성취기준 해설\n•[12문학01-02] 본문"));

        ResolvedFrameDistributor.distribute(doc, data);

        Assert.assertEquals("\t성취기준", blockText(first));
        Assert.assertEquals("(가) 성취기준 해설|•[12문학01-02] 본문", blockText(second));
    }

    private static ASTSection section(int pageNumber) {
        ASTSection section = new ASTSection();
        section.pageNumber(pageNumber);
        return section;
    }

    private static ASTTextFrameBlock block(String sourceId, String storyId) {
        ASTTextFrameBlock block = new ASTTextFrameBlock();
        block.sourceId(sourceId);
        block.storyId(storyId);
        return block;
    }

    private static ASTParagraph paragraph(String text) {
        ASTParagraph paragraph = new ASTParagraph();
        ASTTextRun run = new ASTTextRun();
        run.text(text);
        paragraph.addItem(run);
        return paragraph;
    }

    private static ResolvedParagraph resolvedParagraph(String text) {
        ResolvedParagraph paragraph = new ResolvedParagraph();
        ResolvedRun run = new ResolvedRun();
        run.text(text);
        paragraph.addRun(run);
        return paragraph;
    }

    private static ResolvedTextFrame textFrame(String id, String storyId,
                                               String previousFrameId, String nextFrameId,
                                               int start, int end, String visibleText) {
        ResolvedTextFrame frame = new ResolvedTextFrame();
        frame.id(id);
        frame.storyId(storyId);
        frame.previousFrameId(previousFrameId);
        frame.nextFrameId(nextFrameId);
        frame.paragraphStart(start);
        frame.paragraphEnd(end);
        frame.lineCount(1);
        frame.frameVisibleText(visibleText);
        frame.frameParaTexts(Collections.singletonList(visibleText));
        return frame;
    }

    private static String blockText(ASTTextFrameBlock block) {
        StringBuilder sb = new StringBuilder();
        for (ASTParagraph paragraph : block.paragraphs()) {
            if (sb.length() > 0) sb.append('|');
            for (ASTInlineItem item : paragraph.items()) {
                if (item instanceof ASTTextRun) {
                    String text = ((ASTTextRun) item).text();
                    if (text != null) sb.append(text);
                }
            }
        }
        return sb.toString();
    }
}
