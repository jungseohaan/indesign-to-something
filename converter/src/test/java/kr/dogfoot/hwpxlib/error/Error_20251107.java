package kr.dogfoot.hwpxlib.error;

import kr.dogfoot.hwpxlib.object.HWPXFile;
import kr.dogfoot.hwpxlib.reader.HWPXReader;
import kr.dogfoot.hwpxlib.writer.HWPXWriter;
import kr.dogfoot.hwpxlib.writer.TestUtil;
import org.junit.Assert;
import org.junit.Test;

import java.nio.charset.StandardCharsets;

public class Error_20251107 {
    @Test
    public void test() throws Exception {
        HWPXFile hwpxFile = HWPXReader.fromFilepath("testFile/error/20251107/test.hwpx");
        HWPXWriter.toFilepath(hwpxFile, "testFile/error/20251107/test_re.hwpx");

        String originXML = TestUtil.zipFileString("testFile/error/20251107/test.hwpx", "Contents/section0.xml", StandardCharsets.UTF_8);
        String madeXML = TestUtil.zipFileString("testFile/error/20251107/test_re.hwpx", "Contents/section0.xml", StandardCharsets.UTF_8);
        Assert.assertEquals(originXML, madeXML);
    }
}
