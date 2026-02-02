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

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.Arrays;
import java.util.Comparator;

/**
 * 변환된 PNG 이미지 파일들을 리스트 형태로 보여주는 샘플 HWPX 생성.
 *
 * <p>원본: 미적분 교과서 디자인 파일 (AI/PSD/EPS/TIF)을 ImageMagick/Ghostscript으로
 * PNG 변환한 결과물을 사용한다.</p>
 *
 * <p>각 이미지마다: 파일명 텍스트 + 인라인 이미지 표시.</p>
 */
public class SampleImageListWriter {

    private int idCounter = 0;

    // 이미지 표시 최대 크기 (hwpunit) — A4 본문 폭에 맞춤
    private static final long MAX_DISPLAY_W = 36000L;  // ~126mm (여백 고려)
    private static final long MAX_DISPLAY_H = 28000L;  // ~98mm

    @Test
    public void createImageListDocument() throws Exception {
        File imageDir = new File("testFile/tool/converted_images");
        Assert.assertTrue(
                "Converted images directory not found: " + imageDir.getAbsolutePath(),
                imageDir.exists() && imageDir.isDirectory());

        File[] pngFiles = imageDir.listFiles((dir, name) ->
                name.toLowerCase().endsWith(".png"));
        Assert.assertNotNull(pngFiles);
        Assert.assertTrue("No PNG files found in " + imageDir.getAbsolutePath(),
                pngFiles.length > 0);

        // 파일명 정렬
        Arrays.sort(pngFiles, Comparator.comparing(File::getName));

        HWPXFile hwpxFile = BlankFileMaker.make();
        SectionXMLFile section = hwpxFile.sectionXMLFileList().get(0);

        // ── 제목 ──
        Para titlePara = addPara(section, false);
        Run titleRun = addRun(titlePara);
        titleRun.addNewT().addText("Image List - 미적분 교과서 디자인 파일 (" + pngFiles.length + " images)");

        // ── 빈 줄 ──
        Para spacer = addPara(section, false);
        addRun(spacer).addNewT().addText("");

        int imageCount = 0;

        for (int i = 0; i < pngFiles.length; i++) {
            File pngFile = pngFiles[i];

            // 파일명 표시
            Para namePara = addPara(section, false);
            Run nameRun = addRun(namePara);
            String label = (i + 1) + ". " + pngFile.getName();
            // 원본 확장자 추론
            String origName = pngFile.getName().replace(".png", "");
            nameRun.addNewT().addText(label);

            // 이미지 삽입
            try {
                int[] pixelSize = ImageInserter.detectPixelSize(
                        Files.readAllBytes(pngFile.toPath()));
                int pixelW = pixelSize[0];
                int pixelH = pixelSize[1];

                // 비율 유지하면서 최대 크기에 맞춤
                long[] displaySize = fitToMaxSize(pixelW, pixelH, MAX_DISPLAY_W, MAX_DISPLAY_H);

                Para imgPara = addPara(section, false);
                Run imgRun = addRun(imgPara);
                ImageInserter.insertInlineFromFile(
                        hwpxFile, imgRun, pngFile, displaySize[0], displaySize[1]);
                imageCount++;
            } catch (IOException e) {
                // 이미지 읽기 실패 시 에러 메시지 표시
                Para errPara = addPara(section, false);
                Run errRun = addRun(errPara);
                errRun.addNewT().addText("  [Error loading image: " + e.getMessage() + "]");
            }

            // 이미지 사이 간격 (빈 줄)
            Para gap = addPara(section, false);
            addRun(gap).addNewT().addText("");
        }

        // ── 저장 및 검증 ──
        String filepath = "testFile/tool/sample_image_list.hwpx";
        HWPXWriter.toFilepath(hwpxFile, filepath);

        System.out.println("=== Image List Sample Result ===");
        System.out.println("File: " + filepath);
        System.out.println("Total images: " + imageCount + " / " + pngFiles.length);

        // 라운드트립 검증
        HWPXFile readBack = HWPXReader.fromFilepath(filepath);
        SectionXMLFile readSection = readBack.sectionXMLFileList().get(0);
        Assert.assertTrue("Should have paragraphs",
                readSection.countOfPara() > pngFiles.length);

        // Picture 존재 확인
        int pictureCount = 0;
        for (int i = 0; i < readSection.countOfPara(); i++) {
            Para para = readSection.getPara(i);
            for (int r = 0; r < para.countOfRun(); r++) {
                Run run = para.getRun(r);
                for (int ri = 0; ri < run.countOfRunItem(); ri++) {
                    if (run.getRunItem(ri) instanceof Picture) {
                        pictureCount++;
                    }
                }
            }
        }
        Assert.assertTrue("Should contain pictures", pictureCount > 0);

        System.out.println("Round-trip verification passed!");
        System.out.println("Paragraphs: " + readSection.countOfPara()
                + ", Pictures: " + pictureCount);
    }

    /**
     * 픽셀 크기를 비율 유지하면서 최대 표시 크기에 맞춘다.
     *
     * @return [displayWidth, displayHeight] (hwpunit)
     */
    private long[] fitToMaxSize(int pixelW, int pixelH, long maxW, long maxH) {
        if (pixelW <= 0 || pixelH <= 0) {
            return new long[]{maxW / 2, maxH / 2};
        }

        double ratio = (double) pixelW / pixelH;

        long w = maxW;
        long h = (long) (w / ratio);

        if (h > maxH) {
            h = maxH;
            w = (long) (h * ratio);
        }

        return new long[]{w, h};
    }

    private Para addPara(SectionXMLFile section, boolean pageBreak) {
        Para para = section.addNewPara();
        para.idAnd(nextId()).paraPrIDRefAnd("0").styleIDRefAnd("0")
                .pageBreakAnd(pageBreak).columnBreakAnd(false).merged(false);
        return para;
    }

    private Run addRun(Para para) {
        Run run = para.addNewRun();
        run.charPrIDRef("0");
        return run;
    }

    private String nextId() {
        return String.valueOf(9500000 + (idCounter++));
    }
}
