package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTParagraph;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTable;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTextRun;
import kr.dogfoot.hwpxlib.tool.idmlconverter.idml.IDMLTextFrame;
import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class ASTPageProcessorTest {
    @Test
    public void trailingInlineTableParagraphIsNotRemovedAsEmpty() {
        List<ASTParagraph> paragraphs = new ArrayList<>();

        ASTParagraph text = new ASTParagraph();
        ASTTextRun run = new ASTTextRun();
        run.text("본문");
        text.addItem(run);
        paragraphs.add(text);

        ASTParagraph tableCarrier = new ASTParagraph();
        ASTTable table = new ASTTable();
        table.sourceId("u-table");
        tableCarrier.inlineTable(table);
        paragraphs.add(tableCarrier);

        ASTPageProcessor.removeTrailingEmptyParagraphs(paragraphs);

        Assert.assertEquals(2, paragraphs.size());
        Assert.assertSame(table, paragraphs.get(1).inlineTable());
    }

    @Test
    public void teacherExplanationFrameIsDeferredAfterBody() {
        IDMLTextFrame frame = new IDMLTextFrame();
        frame.appliedObjectStyle("ObjectStyle/@교사용해설프레임");

        Assert.assertTrue(ASTPageProcessor.shouldDeferInlineFrame(frame));
    }

    @Test
    public void ordinaryInlineFrameKeepsSourceAnchorOrder() {
        IDMLTextFrame frame = new IDMLTextFrame();
        frame.appliedObjectStyle("ObjectStyle/@답박스");

        Assert.assertFalse(ASTPageProcessor.shouldDeferInlineFrame(frame));
    }
}
