package kr.dogfoot.hwpxlib.tool.imageinserter;

import kr.dogfoot.hwpxlib.object.HWPXFile;
import kr.dogfoot.hwpxlib.object.content.section_xml.SectionXMLFile;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.Para;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.Run;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.object.Picture;
import kr.dogfoot.hwpxlib.tool.blankfilemaker.BlankFileMaker;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.io.IOException;

/**
 * DesignFileConverter 유틸리티 테스트.
 *
 * <p>PSD/AI 변환 테스트는 외부 도구(ImageMagick, Ghostscript)가 설치되어 있을 때만 실행되며,
 * 미설치 시 {@code Assume}에 의해 스킵된다.</p>
 */
public class TestDesignFileConverter {

    private HWPXFile hwpxFile;
    private SectionXMLFile section;

    @Before
    public void setup() {
        hwpxFile = BlankFileMaker.make();
        section = hwpxFile.sectionXMLFileList().get(0);
        DesignFileConverter.resetDetectionCache();
    }

    private Run createRun() {
        Para para = section.addNewPara();
        para.idAnd("9900001").paraPrIDRefAnd("0").styleIDRefAnd("0")
                .pageBreakAnd(false).columnBreakAnd(false).merged(false);
        Run run = para.addNewRun();
        run.charPrIDRef("0");
        return run;
    }

    // ── 도구 감지 테스트 ──

    @Test
    public void testImageMagickDetection() {
        boolean available = DesignFileConverter.isImageMagickAvailable();
        boolean available2 = DesignFileConverter.isImageMagickAvailable();
        Assert.assertEquals(available, available2);
        System.out.println("ImageMagick available: " + available);
    }

    @Test
    public void testGhostscriptDetection() {
        boolean available = DesignFileConverter.isGhostscriptAvailable();
        boolean available2 = DesignFileConverter.isGhostscriptAvailable();
        Assert.assertEquals(available, available2);
        System.out.println("Ghostscript available: " + available);
    }

    // ── 에러 처리 테스트 ──

    @Test(expected = IOException.class)
    public void testConvertNonexistentFile() throws IOException {
        DesignFileConverter.convertToPng(new File("/nonexistent/file.psd"));
    }

    @Test(expected = IOException.class)
    public void testUnsupportedFormat() throws IOException {
        File tempFile = File.createTempFile("test", ".xyz");
        tempFile.deleteOnExit();
        try {
            DesignFileConverter.convertToPng(tempFile);
        } finally {
            tempFile.delete();
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void testInvalidDpi() throws IOException {
        File tempFile = File.createTempFile("test", ".ai");
        tempFile.deleteOnExit();
        try {
            DesignFileConverter.convertAiToPng(tempFile, 0);
        } finally {
            tempFile.delete();
        }
    }

    // ── PSD 변환 테스트 (ImageMagick 필요) ──

    @Test
    public void testConvertPsdToPng() throws IOException {
        Assume.assumeTrue(
                "ImageMagick not installed, skipping PSD test",
                DesignFileConverter.isImageMagickAvailable());

        File psdFile = findTestResource("test.psd");
        Assume.assumeTrue(
                "Test PSD file not found, skipping",
                psdFile != null && psdFile.exists());

        byte[] pngData = DesignFileConverter.convertPsdToPng(psdFile);
        Assert.assertNotNull(pngData);
        Assert.assertTrue("PNG data should not be empty", pngData.length > 0);
        assertPngMagicBytes(pngData);
    }

    @Test
    public void testInsertInlineFromPSD() throws IOException {
        Assume.assumeTrue(
                "ImageMagick not installed, skipping",
                DesignFileConverter.isImageMagickAvailable());

        File psdFile = findTestResource("test.psd");
        Assume.assumeTrue(
                "Test PSD file not found, skipping",
                psdFile != null && psdFile.exists());

        Run run = createRun();
        Picture pic = DesignFileConverter.insertInlineFromPSD(
                hwpxFile, run, psdFile, 10000L, 7500L);

        Assert.assertNotNull(pic);
        Assert.assertNotNull(pic.img());
        Assert.assertTrue(pic.pos().treatAsChar());
        Assert.assertEquals(10000L, pic.sz().width().longValue());
        Assert.assertEquals(7500L, pic.sz().height().longValue());
    }

    // ── AI 변환 테스트 (Ghostscript 필요) ──

    @Test
    public void testConvertAiToPng() throws IOException {
        Assume.assumeTrue(
                "Ghostscript not installed, skipping AI test",
                DesignFileConverter.isGhostscriptAvailable());

        File aiFile = findTestResource("test.ai");
        Assume.assumeTrue(
                "Test AI file not found, skipping",
                aiFile != null && aiFile.exists());

        byte[] pngData = DesignFileConverter.convertAiToPng(aiFile);
        Assert.assertNotNull(pngData);
        Assert.assertTrue("PNG data should not be empty", pngData.length > 0);
        assertPngMagicBytes(pngData);
    }

    @Test
    public void testConvertAiToPngCustomDpi() throws IOException {
        Assume.assumeTrue(
                "Ghostscript not installed, skipping",
                DesignFileConverter.isGhostscriptAvailable());

        File aiFile = findTestResource("test.ai");
        Assume.assumeTrue(
                "Test AI file not found, skipping",
                aiFile != null && aiFile.exists());

        byte[] png72 = DesignFileConverter.convertAiToPng(aiFile, 72);
        byte[] png300 = DesignFileConverter.convertAiToPng(aiFile, 300);

        Assert.assertTrue(
                "300 DPI should produce larger output than 72 DPI",
                png300.length > png72.length);
    }

    // ── 통합 테스트 ──

    @Test
    public void testInsertInlineFromDesignFile() throws IOException {
        Assume.assumeTrue(
                "ImageMagick not installed, skipping",
                DesignFileConverter.isImageMagickAvailable());

        File psdFile = findTestResource("test.psd");
        Assume.assumeTrue(
                "Test PSD file not found, skipping",
                psdFile != null && psdFile.exists());

        Run run = createRun();
        Picture pic = DesignFileConverter.insertInlineFromDesignFile(
                hwpxFile, run, psdFile, 15000L, 10000L);

        Assert.assertNotNull(pic);
        Assert.assertTrue(pic.pos().treatAsChar());
    }

    @Test
    public void testInsertInlineFromAI() throws IOException {
        Assume.assumeTrue(
                "Ghostscript not installed, skipping",
                DesignFileConverter.isGhostscriptAvailable());

        File aiFile = findTestResource("test.ai");
        Assume.assumeTrue(
                "Test AI file not found, skipping",
                aiFile != null && aiFile.exists());

        Run run = createRun();
        Picture pic = DesignFileConverter.insertInlineFromAI(
                hwpxFile, run, aiFile, 12000L, 9000L);

        Assert.assertNotNull(pic);
        Assert.assertTrue(pic.pos().treatAsChar());
    }

    // ── 헬퍼 ──

    private void assertPngMagicBytes(byte[] data) {
        Assert.assertTrue("Data too short for PNG", data.length >= 4);
        Assert.assertEquals("PNG signature byte 0", (byte) 0x89, data[0]);
        Assert.assertEquals("PNG signature byte 1 (P)", (byte) 0x50, data[1]);
        Assert.assertEquals("PNG signature byte 2 (N)", (byte) 0x4E, data[2]);
        Assert.assertEquals("PNG signature byte 3 (G)", (byte) 0x47, data[3]);
    }

    private File findTestResource(String name) {
        File f = new File("testFile/tool/" + name);
        if (f.exists()) return f;
        f = new File("src/test/resources/" + name);
        if (f.exists()) return f;
        return null;
    }
}
