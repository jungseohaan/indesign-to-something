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
}
