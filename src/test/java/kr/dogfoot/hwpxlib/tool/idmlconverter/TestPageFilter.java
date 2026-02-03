package kr.dogfoot.hwpxlib.tool.idmlconverter;

import kr.dogfoot.hwpxlib.tool.idmlconverter.converter.PageFilter;
import org.junit.Assert;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;

public class TestPageFilter {

    @Test
    public void testIncludesAll() {
        PageFilter filter = new PageFilter(0, 0);
        Assert.assertTrue(filter.includesAll());
        Assert.assertTrue(filter.shouldInclude(1));
        Assert.assertTrue(filter.shouldInclude(100));
        Assert.assertTrue(filter.shouldInclude(999));
    }

    @Test
    public void testStartPageOnly() {
        PageFilter filter = new PageFilter(5, 0);
        Assert.assertFalse(filter.includesAll());
        Assert.assertFalse(filter.shouldInclude(1));
        Assert.assertFalse(filter.shouldInclude(4));
        Assert.assertTrue(filter.shouldInclude(5));
        Assert.assertTrue(filter.shouldInclude(6));
        Assert.assertTrue(filter.shouldInclude(100));
    }

    @Test
    public void testEndPageOnly() {
        PageFilter filter = new PageFilter(0, 10);
        Assert.assertFalse(filter.includesAll());
        Assert.assertTrue(filter.shouldInclude(1));
        Assert.assertTrue(filter.shouldInclude(10));
        Assert.assertFalse(filter.shouldInclude(11));
        Assert.assertFalse(filter.shouldInclude(100));
    }

    @Test
    public void testRange() {
        PageFilter filter = new PageFilter(8, 20);
        Assert.assertFalse(filter.includesAll());
        Assert.assertFalse(filter.shouldInclude(7));
        Assert.assertTrue(filter.shouldInclude(8));
        Assert.assertTrue(filter.shouldInclude(14));
        Assert.assertTrue(filter.shouldInclude(20));
        Assert.assertFalse(filter.shouldInclude(21));
    }

    @Test
    public void testSinglePage() {
        PageFilter filter = new PageFilter(5, 5);
        Assert.assertFalse(filter.shouldInclude(4));
        Assert.assertTrue(filter.shouldInclude(5));
        Assert.assertFalse(filter.shouldInclude(6));
    }

    @Test
    public void testFilterList() {
        PageFilter filter = new PageFilter(3, 7);
        List<Integer> pages = Arrays.asList(1, 2, 3, 4, 5, 6, 7, 8, 9, 10);
        List<Integer> filtered = filter.filter(pages);
        Assert.assertEquals(Arrays.asList(3, 4, 5, 6, 7), filtered);
    }

    @Test
    public void testFilterListAll() {
        PageFilter filter = new PageFilter(0, 0);
        List<Integer> pages = Arrays.asList(1, 2, 3);
        List<Integer> filtered = filter.filter(pages);
        Assert.assertEquals(Arrays.asList(1, 2, 3), filtered);
    }

    @Test
    public void testFromConvertOptions() {
        ConvertOptions options = ConvertOptions.defaults().startPage(8).endPage(20);
        PageFilter filter = new PageFilter(options);
        Assert.assertEquals(8, filter.startPage());
        Assert.assertEquals(20, filter.endPage());
        Assert.assertFalse(filter.shouldInclude(7));
        Assert.assertTrue(filter.shouldInclude(8));
        Assert.assertTrue(filter.shouldInclude(20));
        Assert.assertFalse(filter.shouldInclude(21));
    }

    @Test
    public void testFromDefaultOptions() {
        PageFilter filter = new PageFilter(ConvertOptions.defaults());
        Assert.assertTrue(filter.includesAll());
    }
}
