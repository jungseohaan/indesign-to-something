package kr.dogfoot.hwpxlib.tool.imageinserter;

import kr.dogfoot.hwpxlib.object.HWPXFile;
import kr.dogfoot.hwpxlib.object.content.context_hpf.ManifestItem;
import kr.dogfoot.hwpxlib.object.content.header_xml.enumtype.ImageEffect;
import kr.dogfoot.hwpxlib.object.content.section_xml.SectionXMLFile;
import kr.dogfoot.hwpxlib.object.content.section_xml.enumtype.*;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.Para;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.Run;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.object.Picture;
import kr.dogfoot.hwpxlib.tool.blankfilemaker.BlankFileMaker;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;

public class TestImageInserter {

    private HWPXFile hwpxFile;
    private SectionXMLFile section;

    @Before
    public void setup() {
        hwpxFile = BlankFileMaker.make();
        section = hwpxFile.sectionXMLFileList().get(0);
    }

    private byte[] createDummyPng(int w, int h) throws IOException {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.BLUE);
        g.fillRect(0, 0, w, h);
        g.dispose();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", baos);
        return baos.toByteArray();
    }

    private byte[] createDummyJpeg(int w, int h) throws IOException {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.RED);
        g.fillRect(0, 0, w, h);
        g.dispose();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "jpeg", baos);
        return baos.toByteArray();
    }

    private Run createRun() {
        Para para = section.addNewPara();
        para.idAnd("9000001").paraPrIDRefAnd("0").styleIDRefAnd("0")
                .pageBreakAnd(false).columnBreakAnd(false).merged(false);
        Run run = para.addNewRun();
        run.charPrIDRef("0");
        return run;
    }

    @Test
    public void testRegisterImage() throws IOException {
        byte[] pngData = createDummyPng(100, 80);
        String itemId = ImageInserter.registerImage(hwpxFile, pngData, "png");

        Assert.assertNotNull(itemId);
        Assert.assertTrue(itemId.startsWith("image"));

        // ManifestItem 확인
        boolean found = false;
        for (ManifestItem item : hwpxFile.contentHPFFile().manifest().items()) {
            if (itemId.equals(item.id())) {
                Assert.assertEquals("image/png", item.mediaType());
                Assert.assertTrue(item.href().endsWith(".png"));
                Assert.assertTrue(item.href().startsWith("BinData/"));
                Assert.assertNotNull(item.attachedFile());
                Assert.assertNotNull(item.attachedFile().data());
                Assert.assertEquals(pngData.length, item.attachedFile().data().length);
                found = true;
                break;
            }
        }
        Assert.assertTrue("ManifestItem should be registered", found);
    }

    @Test
    public void testRegisterImageFromFile() throws IOException {
        // 임시 파일 생성
        File tempFile = File.createTempFile("test_image", ".png");
        tempFile.deleteOnExit();
        BufferedImage img = new BufferedImage(120, 90, BufferedImage.TYPE_INT_RGB);
        ImageIO.write(img, "png", tempFile);

        String itemId = ImageInserter.registerImageFromFile(hwpxFile, tempFile);
        Assert.assertNotNull(itemId);

        boolean found = false;
        for (ManifestItem item : hwpxFile.contentHPFFile().manifest().items()) {
            if (itemId.equals(item.id())) {
                Assert.assertEquals("image/png", item.mediaType());
                Assert.assertNotNull(item.attachedFile());
                found = true;
                break;
            }
        }
        Assert.assertTrue("File-based ManifestItem should be registered", found);
    }

    @Test
    public void testInsertInline() throws IOException {
        byte[] pngData = createDummyPng(200, 150);
        String itemId = ImageInserter.registerImage(hwpxFile, pngData, "png");
        Run run = createRun();

        long displayW = 8000L;
        long displayH = 6000L;
        Picture pic = ImageInserter.insertInline(run, itemId, 200, 150, displayW, displayH);

        Assert.assertNotNull(pic);

        // ShapeObject
        Assert.assertNotNull(pic.id());
        Assert.assertEquals(NumberingType.PICTURE, pic.numberingType());
        Assert.assertEquals(TextWrapMethod.SQUARE, pic.textWrap());

        // Position (인라인)
        Assert.assertNotNull(pic.pos());
        Assert.assertTrue(pic.pos().treatAsChar());
        Assert.assertTrue(pic.pos().flowWithText());

        // Size
        Assert.assertNotNull(pic.sz());
        Assert.assertEquals(displayW, pic.sz().width().longValue());
        Assert.assertEquals(displayH, pic.sz().height().longValue());
        Assert.assertEquals(WidthRelTo.ABSOLUTE, pic.sz().widthRelTo());
        Assert.assertEquals(HeightRelTo.ABSOLUTE, pic.sz().heightRelTo());

        // OrgSz / CurSz
        Assert.assertNotNull(pic.orgSz());
        Assert.assertEquals(displayW, pic.orgSz().width().longValue());
        Assert.assertEquals(displayH, pic.orgSz().height().longValue());
        Assert.assertNotNull(pic.curSz());
        Assert.assertEquals(displayW, pic.curSz().width().longValue());
        Assert.assertEquals(displayH, pic.curSz().height().longValue());

        // ImageDim (pixel * 75)
        Assert.assertNotNull(pic.imgDim());
        Assert.assertEquals(200 * 75, pic.imgDim().dimwidth().longValue());
        Assert.assertEquals(150 * 75, pic.imgDim().dimheight().longValue());

        // ImageClip
        Assert.assertNotNull(pic.imgClip());
        Assert.assertEquals(0L, pic.imgClip().left().longValue());
        Assert.assertEquals(200 * 75L, pic.imgClip().right().longValue());
        Assert.assertEquals(0L, pic.imgClip().top().longValue());
        Assert.assertEquals(150 * 75L, pic.imgClip().bottom().longValue());

        // ImageRect
        Assert.assertNotNull(pic.imgRect());
        Assert.assertEquals(0L, pic.imgRect().pt0().x().longValue());
        Assert.assertEquals(0L, pic.imgRect().pt0().y().longValue());
        Assert.assertEquals(displayW, pic.imgRect().pt1().x().longValue());
        Assert.assertEquals(displayH, pic.imgRect().pt2().y().longValue());

        // Image reference
        Assert.assertNotNull(pic.img());
        Assert.assertEquals(itemId, pic.img().binaryItemIDRef());
        Assert.assertEquals(0, pic.img().bright().intValue());
        Assert.assertEquals(0, pic.img().contrast().intValue());
        Assert.assertEquals(ImageEffect.REAL_PIC, pic.img().effect());

        // RenderingInfo (3 identity matrices)
        Assert.assertNotNull(pic.renderingInfo());
        Assert.assertEquals(3, pic.renderingInfo().countOfMatrix());

        // RotationInfo
        Assert.assertNotNull(pic.rotationInfo());
        Assert.assertEquals(0, pic.rotationInfo().angle().shortValue());

        // Flip
        Assert.assertNotNull(pic.flip());
        Assert.assertFalse(pic.flip().horizontal());
        Assert.assertFalse(pic.flip().vertical());
    }

    @Test
    public void testInsertInlineFromBytes() throws IOException {
        byte[] jpegData = createDummyJpeg(300, 200);
        Run run = createRun();

        Picture pic = ImageInserter.insertInlineFromBytes(
                hwpxFile, run, jpegData, "jpeg", 10000L, 6667L);

        Assert.assertNotNull(pic);
        Assert.assertNotNull(pic.img());
        Assert.assertTrue(pic.pos().treatAsChar());
        Assert.assertEquals(10000L, pic.sz().width().longValue());
        Assert.assertEquals(6667L, pic.sz().height().longValue());

        // 픽셀 크기 자동 감지 확인
        Assert.assertEquals(300 * 75L, pic.imgDim().dimwidth().longValue());
        Assert.assertEquals(200 * 75L, pic.imgDim().dimheight().longValue());

        // ManifestItem도 등록되었는지 확인
        boolean found = false;
        for (ManifestItem item : hwpxFile.contentHPFFile().manifest().items()) {
            if (item.mediaType() != null && "image/jpeg".equals(item.mediaType())) {
                found = true;
                break;
            }
        }
        Assert.assertTrue("JPEG ManifestItem should be registered", found);
    }

    @Test
    public void testAutoPixelDetection() throws IOException {
        byte[] pngData = createDummyPng(400, 250);
        int[] size = ImageInserter.detectPixelSize(pngData);
        Assert.assertEquals(400, size[0]);
        Assert.assertEquals(250, size[1]);

        byte[] jpegData = createDummyJpeg(640, 480);
        int[] size2 = ImageInserter.detectPixelSize(jpegData);
        Assert.assertEquals(640, size2[0]);
        Assert.assertEquals(480, size2[1]);
    }

    @Test
    public void testMultipleImages() throws IOException {
        byte[] png1 = createDummyPng(100, 100);
        byte[] png2 = createDummyPng(200, 200);
        byte[] jpeg1 = createDummyJpeg(300, 300);

        String id1 = ImageInserter.registerImage(hwpxFile, png1, "png");
        String id2 = ImageInserter.registerImage(hwpxFile, png2, "png");
        String id3 = ImageInserter.registerImage(hwpxFile, jpeg1, "jpeg");

        // 모두 다른 ID
        Assert.assertNotEquals(id1, id2);
        Assert.assertNotEquals(id2, id3);
        Assert.assertNotEquals(id1, id3);

        // 같은 이미지 재사용 (같은 itemId로 2개의 Picture 생성)
        Run run1 = createRun();
        Run run2 = createRun();
        Picture pic1 = ImageInserter.insertInline(run1, id1, 100, 100, 5000L, 5000L);
        Picture pic2 = ImageInserter.insertInline(run2, id1, 100, 100, 3000L, 3000L);

        // 같은 itemId 참조, 다른 표시 크기
        Assert.assertEquals(id1, pic1.img().binaryItemIDRef());
        Assert.assertEquals(id1, pic2.img().binaryItemIDRef());
        Assert.assertEquals(5000L, pic1.sz().width().longValue());
        Assert.assertEquals(3000L, pic2.sz().width().longValue());
    }

    @Test
    public void testInsertInlineFromFile() throws IOException {
        File tempFile = File.createTempFile("test_img_insert", ".jpg");
        tempFile.deleteOnExit();
        BufferedImage img = new BufferedImage(160, 120, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(Color.GREEN);
        g.fillRect(0, 0, 160, 120);
        g.dispose();
        ImageIO.write(img, "jpeg", tempFile);

        Run run = createRun();
        Picture pic = ImageInserter.insertInlineFromFile(hwpxFile, run, tempFile, 7000L, 5250L);

        Assert.assertNotNull(pic);
        Assert.assertEquals(7000L, pic.sz().width().longValue());
        Assert.assertEquals(5250L, pic.sz().height().longValue());
        Assert.assertEquals(160 * 75L, pic.imgDim().dimwidth().longValue());
        Assert.assertEquals(120 * 75L, pic.imgDim().dimheight().longValue());
        Assert.assertTrue(pic.pos().treatAsChar());
    }
}
