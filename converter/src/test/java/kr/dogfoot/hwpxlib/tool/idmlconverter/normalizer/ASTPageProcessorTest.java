package kr.dogfoot.hwpxlib.tool.idmlconverter.normalizer;

import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTParagraph;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTable;
import kr.dogfoot.hwpxlib.tool.idmlconverter.ast.ASTTextRun;
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
}
