package kr.dogfoot.hwpxlib.tool.imageinserter;

import kr.dogfoot.hwpxlib.object.HWPXFile;
import kr.dogfoot.hwpxlib.object.content.section_xml.SectionXMLFile;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.Para;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.Run;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.object.Picture;
import kr.dogfoot.hwpxlib.reader.HWPXReader;
import kr.dogfoot.hwpxlib.tool.blankfilemaker.BlankFileMaker;
import kr.dogfoot.hwpxlib.writer.HWPXWriter;
import org.junit.Assert;
import org.junit.Test;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;

/**
 * ImageInserter를 활용하여 다양한 이미지가 삽입된 샘플 HWPX 파일을 생성하는 테스트.
 */
public class SampleImageInsertWriter {

    private int idCounter = 0;

    @Test
    public void createSampleImageDocument() throws Exception {
        HWPXFile hwpxFile = BlankFileMaker.make();
        SectionXMLFile section = hwpxFile.sectionXMLFileList().get(0);

        // ── 1. 더미 이미지 생성 ──

        byte[] pngSmall = createDummyImage("png", 120, 80, Color.BLUE, "PNG Small");
        byte[] pngLarge = createDummyImage("png", 400, 300, new Color(0x70AD47), "PNG Large");
        byte[] jpegMedium = createDummyImage("jpeg", 250, 180, new Color(0xED7D31), "JPEG Medium");

        // ── 2. 이미지 등록 (재사용 가능) ──

        String idPngSmall = ImageInserter.registerImage(hwpxFile, pngSmall, "png");
        String idPngLarge = ImageInserter.registerImage(hwpxFile, pngLarge, "png");
        String idJpegMed = ImageInserter.registerImage(hwpxFile, jpegMedium, "jpeg");

        // ── 3. 제목 문단 ──

        Para titlePara = section.addNewPara();
        titlePara.idAnd(nextId()).paraPrIDRefAnd("0").styleIDRefAnd("0")
                .pageBreakAnd(false).columnBreakAnd(false).merged(false);
        Run titleRun = titlePara.addNewRun();
        titleRun.charPrIDRef("0");
        titleRun.addNewT().addText("ImageInserter Sample - Inline Image Insertion");

        // ── 4. 텍스트 + 작은 이미지 인라인 ──

        Para para1 = addPara(section);
        Run run1 = addRun(para1);
        run1.addNewT().addText("Here is a small inline image: ");
        ImageInserter.insertInline(run1, idPngSmall, 120, 80, 5000L, 3333L);
        run1.addNewT().addText(" embedded within the text flow. ");
        run1.addNewT().addText("The image appears like a large character in the text.");

        // ── 5. 텍스트 + 중간 JPEG 이미지 ──

        Para para2 = addPara(section);
        Run run2 = addRun(para2);
        run2.addNewT().addText("This paragraph contains a medium JPEG image: ");
        ImageInserter.insertInline(run2, idJpegMed, 250, 180, 10000L, 7200L);
        run2.addNewT().addText(" followed by more text content.");

        // ── 6. 큰 이미지 단독 문단 ──

        Para para3 = addPara(section);
        Run run3 = addRun(para3);
        ImageInserter.insertInline(run3, idPngLarge, 400, 300, 18000L, 13500L);

        // ── 7. 같은 이미지 재사용 (다른 크기) ──

        Para para4 = addPara(section);
        Run run4 = addRun(para4);
        run4.addNewT().addText("Same small PNG at original size: ");
        ImageInserter.insertInline(run4, idPngSmall, 120, 80, 5000L, 3333L);
        run4.addNewT().addText("  and at double size: ");
        ImageInserter.insertInline(run4, idPngSmall, 120, 80, 10000L, 6666L);
        run4.addNewT().addText("  reusing the same registered image.");

        // ── 8. 편의 메서드 (byte[] → 등록+삽입 한번에) ──

        byte[] extraPng = createDummyImage("png", 180, 130, new Color(0x7030A0), "Auto-detect");
        Para para5 = addPara(section);
        Run run5 = addRun(para5);
        run5.addNewT().addText("This image was inserted using insertInlineFromBytes: ");
        ImageInserter.insertInlineFromBytes(hwpxFile, run5, extraPng, "png", 8000L, 5778L);
        run5.addNewT().addText(" with auto pixel size detection.");

        // ── 9. 파일 경로 방식 ──

        File tempFile = File.createTempFile("sample_img_insert_", ".jpg");
        tempFile.deleteOnExit();
        BufferedImage fileImg = new BufferedImage(200, 140, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = fileImg.createGraphics();
        g.setColor(new Color(0xC00000));
        g.fillRect(0, 0, 200, 140);
        g.setColor(Color.WHITE);
        g.setFont(new Font("SansSerif", Font.BOLD, 16));
        g.drawString("From File", 50, 80);
        g.dispose();
        ImageIO.write(fileImg, "jpeg", tempFile);

        Para para6 = addPara(section);
        Run run6 = addRun(para6);
        run6.addNewT().addText("This image was loaded from a file: ");
        ImageInserter.insertInlineFromFile(hwpxFile, run6, tempFile, 9000L, 6300L);
        run6.addNewT().addText(" using insertInlineFromFile.");

        // ── 10. 여러 이미지 연속 ──

        Para para7 = addPara(section);
        Run run7 = addRun(para7);
        run7.addNewT().addText("Multiple images in a row: ");
        ImageInserter.insertInline(run7, idPngSmall, 120, 80, 4000L, 2667L);
        run7.addNewT().addText(" ");
        ImageInserter.insertInline(run7, idJpegMed, 250, 180, 4000L, 2880L);
        run7.addNewT().addText(" ");
        ImageInserter.insertInline(run7, idPngLarge, 400, 300, 4000L, 3000L);
        run7.addNewT().addText(" all inline.");

        // ── 저장 및 검증 ──

        String filepath = "testFile/tool/sample_image_insert.hwpx";
        HWPXWriter.toFilepath(hwpxFile, filepath);

        System.out.println("=== Image Insert Sample Result ===");
        System.out.println("File: " + filepath);

        // 라운드트립
        HWPXFile readBack = HWPXReader.fromFilepath(filepath);
        SectionXMLFile readSection = readBack.sectionXMLFileList().get(0);
        Assert.assertTrue("Should have paragraphs", readSection.countOfPara() > 5);

        // Picture 존재 확인
        boolean foundPicture = false;
        for (int i = 0; i < readSection.countOfPara() && !foundPicture; i++) {
            Para para = readSection.getPara(i);
            for (int r = 0; r < para.countOfRun() && !foundPicture; r++) {
                Run run = para.getRun(r);
                for (int ri = 0; ri < run.countOfRunItem(); ri++) {
                    if (run.getRunItem(ri) instanceof Picture) {
                        foundPicture = true;
                        break;
                    }
                }
            }
        }
        Assert.assertTrue("Should contain at least one Picture", foundPicture);

        System.out.println("Round-trip verification passed!");
        System.out.println("Paragraphs: " + readSection.countOfPara());
    }

    private Para addPara(SectionXMLFile section) {
        Para para = section.addNewPara();
        para.idAnd(nextId()).paraPrIDRefAnd("3").styleIDRefAnd("0")
                .pageBreakAnd(false).columnBreakAnd(false).merged(false);
        return para;
    }

    private Run addRun(Para para) {
        Run run = para.addNewRun();
        run.charPrIDRef("0");
        return run;
    }

    private String nextId() {
        return String.valueOf(8000000 + (idCounter++));
    }

    private byte[] createDummyImage(String format, int w, int h, Color bgColor, String label)
            throws IOException {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setColor(bgColor);
        g.fillRect(0, 0, w, h);
        g.setColor(new Color(255, 255, 255, 180));
        g.setStroke(new BasicStroke(2));
        for (int d = -h; d < w + h; d += 15) {
            g.drawLine(d, 0, d + h, h);
        }
        g.setColor(Color.WHITE);
        g.setFont(new Font("SansSerif", Font.BOLD, Math.min(w, h) / 5));
        FontMetrics fm = g.getFontMetrics();
        int tx = (w - fm.stringWidth(label)) / 2;
        int ty = (h + fm.getAscent()) / 2;
        g.drawString(label, tx, ty);
        g.setColor(Color.DARK_GRAY);
        g.setStroke(new BasicStroke(2));
        g.drawRect(1, 1, w - 3, h - 3);
        g.dispose();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, format, baos);
        return baos.toByteArray();
    }
}
