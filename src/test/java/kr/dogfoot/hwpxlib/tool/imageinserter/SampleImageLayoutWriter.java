package kr.dogfoot.hwpxlib.tool.imageinserter;

import kr.dogfoot.hwpxlib.object.HWPXFile;
import kr.dogfoot.hwpxlib.object.content.header_xml.enumtype.ImageEffect;
import kr.dogfoot.hwpxlib.object.content.section_xml.SectionXMLFile;
import kr.dogfoot.hwpxlib.object.content.section_xml.enumtype.*;
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
import java.io.IOException;

/**
 * HWPX 문서에서 가능한 다양한 이미지 배치 모드를 보여주는 7페이지 샘플 파일을 생성하는 테스트.
 *
 * <ul>
 *   <li>Page 1: 인라인 이미지 (treatAsChar=true)</li>
 *   <li>Page 2: 어울림 배치 (Square Wrap)</li>
 *   <li>Page 3: 자리차지 + 빽빽하게 (Top-Bottom + Tight)</li>
 *   <li>Page 4: 글 뒤로 / 글 앞으로 (Behind / In Front)</li>
 *   <li>Page 5: 위치 기준과 정렬 (Position & Alignment)</li>
 *   <li>Page 6: 이미지 효과 (Effects)</li>
 *   <li>Page 7: 다중 이미지 구성 (Composition)</li>
 * </ul>
 */
public class SampleImageLayoutWriter {

    private int idCounter = 0;

    private static final int PIXEL_W = 300;
    private static final int PIXEL_H = 200;

    // 표시 크기 (hwpunit)
    private static final long SMALL_W = 5000L;
    private static final long SMALL_H = 3333L;
    private static final long MEDIUM_W = 14000L;
    private static final long MEDIUM_H = 9333L;
    private static final long LARGE_W = 28000L;
    private static final long LARGE_H = 18667L;
    private static final long FULL_W = 42520L;
    private static final long FULL_H = 28347L;

    private static final String LOREM = "Lorem ipsum dolor sit amet, consectetur adipiscing elit. "
            + "Sed do eiusmod tempor incididunt ut labore et dolore magna aliqua. "
            + "Ut enim ad minim veniam, quis nostrud exercitation ullamco laboris. "
            + "Duis aute irure dolor in reprehenderit in voluptate velit esse cillum dolore. ";

    private static final String LONG_TEXT = LOREM + LOREM + LOREM;

    // ── PicConfig 내부 클래스 ──

    private static class PicConfig {
        boolean treatAsChar = false;
        TextWrapMethod textWrap = TextWrapMethod.SQUARE;
        TextFlowSide textFlow = TextFlowSide.BOTH_SIDES;
        VertRelTo vertRelTo = VertRelTo.PARA;
        HorzRelTo horzRelTo = HorzRelTo.COLUMN;
        VertAlign vertAlign = VertAlign.TOP;
        HorzAlign horzAlign = HorzAlign.LEFT;
        long vertOffset = 0L;
        long horzOffset = 0L;
        boolean allowOverlap = false;
        boolean flowWithText = true;

        long displayW;
        long displayH;
        WidthRelTo widthRelTo = WidthRelTo.ABSOLUTE;
        HeightRelTo heightRelTo = HeightRelTo.ABSOLUTE;

        long marginLeft = 283L;
        long marginRight = 283L;
        long marginTop = 283L;
        long marginBottom = 283L;

        boolean flipH = false;
        boolean flipV = false;
        short angle = 0;

        int bright = 0;
        int contrast = 0;
        float alpha = 0f;
        ImageEffect effect = ImageEffect.REAL_PIC;

        int zOrder = 0;

        String itemId;
        int pixelW = PIXEL_W;
        int pixelH = PIXEL_H;
    }

    // ── 메인 테스트 ──

    @Test
    public void createSampleImageLayoutDocument() throws Exception {
        HWPXFile hwpxFile = BlankFileMaker.make();
        SectionXMLFile section = hwpxFile.sectionXMLFileList().get(0);

        String[] imageIds = registerDummyImages(hwpxFile, 10);

        buildPage1_InlineImages(section, imageIds);
        buildPage2_SquareWrap(section, imageIds);
        buildPage3_TopBottomAndTight(section, imageIds);
        buildPage4_BehindAndInFront(section, imageIds);
        buildPage5_PositionAndAlignment(section, imageIds);
        buildPage6_ImageEffects(section, imageIds);
        buildPage7_MultipleComposition(section, imageIds);

        String filepath = "testFile/tool/sample_image_layout.hwpx";
        HWPXWriter.toFilepath(hwpxFile, filepath);

        System.out.println("=== Image Layout Sample Result ===");
        System.out.println("File: " + filepath);

        HWPXFile readBack = HWPXReader.fromFilepath(filepath);
        SectionXMLFile readSection = readBack.sectionXMLFileList().get(0);
        Assert.assertTrue("Should have paragraphs", readSection.countOfPara() > 30);

        int pictureCount = countPictures(readSection);
        Assert.assertTrue("Should have many pictures", pictureCount >= 25);

        System.out.println("Round-trip verification passed!");
        System.out.println("Paragraphs: " + readSection.countOfPara() + ", Pictures: " + pictureCount);
    }

    // ── Page 1: 인라인 이미지 ──

    private void buildPage1_InlineImages(SectionXMLFile section, String[] ids) {
        // 제목
        Para title = addPara(section, false);
        Run titleRun = addRun(title);
        titleRun.addNewT().addText("Page 1 - Inline Images (treatAsChar=true)");

        // 1) 작은 인라인 이미지 + 텍스트
        Para p1 = addPara(section, false);
        Run r1 = addRun(p1);
        r1.addNewT().addText("Small inline image in text flow: ");
        buildPicture(r1, inlineConfig(ids[0], SMALL_W, SMALL_H));
        r1.addNewT().addText(" — the image appears like a large character within the paragraph.");

        // 2) 중간 인라인 이미지 + 텍스트
        Para p2 = addPara(section, false);
        Run r2 = addRun(p2);
        r2.addNewT().addText("Medium inline image: ");
        buildPicture(r2, inlineConfig(ids[1], MEDIUM_W, MEDIUM_H));
        r2.addNewT().addText(" embedded in the text flow.");

        // 3) 한 줄에 3개 인라인 이미지
        Para p3 = addPara(section, false);
        Run r3 = addRun(p3);
        r3.addNewT().addText("Three images in a row: ");
        buildPicture(r3, inlineConfig(ids[0], SMALL_W, SMALL_H));
        r3.addNewT().addText(" ");
        buildPicture(r3, inlineConfig(ids[1], SMALL_W, SMALL_H));
        r3.addNewT().addText(" ");
        buildPicture(r3, inlineConfig(ids[2], SMALL_W, SMALL_H));
        r3.addNewT().addText(" all inline.");

        // 4) 같은 소스를 3가지 크기로
        Para p4 = addPara(section, false);
        Run r4 = addRun(p4);
        r4.addNewT().addText("Same image at 3 sizes: ");
        buildPicture(r4, inlineConfig(ids[3], 3000L, 2000L));
        r4.addNewT().addText(" ");
        buildPicture(r4, inlineConfig(ids[3], 8000L, 5333L));
        r4.addNewT().addText(" ");
        buildPicture(r4, inlineConfig(ids[3], 16000L, 10667L));
        r4.addNewT().addText(" — small, medium, large.");
    }

    // ── Page 2: 어울림 배치 (Square Wrap) ──

    private void buildPage2_SquareWrap(SectionXMLFile section, String[] ids) {
        // 제목
        Para title = addPara(section, true);
        Run titleRun = addRun(title);
        titleRun.addNewT().addText("Page 2 - Square Text Wrap");

        // 1) 왼쪽 정렬 + 오른쪽 텍스트
        Para p1 = addPara(section, false);
        Run r1 = addRun(p1);
        PicConfig cfg1 = anchoredConfig(ids[4], MEDIUM_W, MEDIUM_H,
                TextWrapMethod.SQUARE, HorzAlign.LEFT, VertAlign.TOP);
        cfg1.textFlow = TextFlowSide.RIGHT_ONLY;
        buildPicture(r1, cfg1);
        r1.addNewT().addText("[Square Wrap - Left Aligned] " + LONG_TEXT);

        // 2) 오른쪽 정렬 + 왼쪽 텍스트
        Para p2 = addPara(section, false);
        Run r2 = addRun(p2);
        PicConfig cfg2 = anchoredConfig(ids[5], MEDIUM_W, MEDIUM_H,
                TextWrapMethod.SQUARE, HorzAlign.RIGHT, VertAlign.TOP);
        cfg2.textFlow = TextFlowSide.LEFT_ONLY;
        buildPicture(r2, cfg2);
        r2.addNewT().addText("[Square Wrap - Right Aligned] " + LONG_TEXT);

        // 3) 가운데 + 양쪽 텍스트
        Para p3 = addPara(section, false);
        Run r3 = addRun(p3);
        PicConfig cfg3 = anchoredConfig(ids[6], SMALL_W, SMALL_H,
                TextWrapMethod.SQUARE, HorzAlign.CENTER, VertAlign.TOP);
        cfg3.textFlow = TextFlowSide.BOTH_SIDES;
        buildPicture(r3, cfg3);
        r3.addNewT().addText("[Square Wrap - Center, Both Sides] " + LONG_TEXT);

        // 4) 사용자 지정 바깥 여백
        Para p4 = addPara(section, false);
        Run r4 = addRun(p4);
        PicConfig cfg4 = anchoredConfig(ids[7], MEDIUM_W, MEDIUM_H,
                TextWrapMethod.SQUARE, HorzAlign.LEFT, VertAlign.TOP);
        cfg4.textFlow = TextFlowSide.BOTH_SIDES;
        cfg4.marginLeft = 850L;
        cfg4.marginRight = 850L;
        cfg4.marginTop = 567L;
        cfg4.marginBottom = 567L;
        buildPicture(r4, cfg4);
        r4.addNewT().addText("[Square Wrap - Custom Margin 3mm] " + LONG_TEXT);
    }

    // ── Page 3: 자리차지 + 빽빽하게 ──

    private void buildPage3_TopBottomAndTight(SectionXMLFile section, String[] ids) {
        // 제목
        Para title = addPara(section, true);
        Run titleRun = addRun(title);
        titleRun.addNewT().addText("Page 3 - Top-and-Bottom + Tight Wrap");

        // 설명 텍스트
        Para desc = addPara(section, false);
        Run descRun = addRun(desc);
        descRun.addNewT().addText("The image below uses TOP_AND_BOTTOM wrap — text only appears above and below, never beside the image.");

        // 1) Top-and-Bottom: 전체 폭 이미지
        Para p1 = addPara(section, false);
        Run r1 = addRun(p1);
        PicConfig cfg1 = anchoredConfig(ids[0], FULL_W, 14000L,
                TextWrapMethod.TOP_AND_BOTTOM, HorzAlign.CENTER, VertAlign.TOP);
        cfg1.horzRelTo = HorzRelTo.COLUMN;
        buildPicture(r1, cfg1);
        r1.addNewT().addText(LOREM);

        // 설명 텍스트
        Para desc2 = addPara(section, false);
        Run descRun2 = addRun(desc2);
        descRun2.addNewT().addText("Below: TIGHT wrap — text wraps tightly around the image contour.");

        // 2) Tight 왼쪽
        Para p2 = addPara(section, false);
        Run r2 = addRun(p2);
        PicConfig cfg2 = anchoredConfig(ids[1], MEDIUM_W, MEDIUM_H,
                TextWrapMethod.TIGHT, HorzAlign.LEFT, VertAlign.TOP);
        cfg2.textFlow = TextFlowSide.RIGHT_ONLY;
        buildPicture(r2, cfg2);
        r2.addNewT().addText("[Tight Wrap - Left] " + LONG_TEXT);

        // 3) Tight 오른쪽
        Para p3 = addPara(section, false);
        Run r3 = addRun(p3);
        PicConfig cfg3 = anchoredConfig(ids[2], MEDIUM_W, MEDIUM_H,
                TextWrapMethod.TIGHT, HorzAlign.RIGHT, VertAlign.TOP);
        cfg3.textFlow = TextFlowSide.LEFT_ONLY;
        buildPicture(r3, cfg3);
        r3.addNewT().addText("[Tight Wrap - Right] " + LONG_TEXT);
    }

    // ── Page 4: 글 뒤로 / 글 앞으로 ──

    private void buildPage4_BehindAndInFront(SectionXMLFile section, String[] ids) {
        // 제목
        Para title = addPara(section, true);
        Run titleRun = addRun(title);
        titleRun.addNewT().addText("Page 4 - Behind Text / In Front of Text");

        // 1) Behind Text (워터마크 효과)
        Para p1 = addPara(section, false);
        Run r1 = addRun(p1);
        PicConfig cfg1 = new PicConfig();
        cfg1.itemId = ids[3];
        cfg1.displayW = LARGE_W;
        cfg1.displayH = LARGE_H;
        cfg1.treatAsChar = false;
        cfg1.textWrap = TextWrapMethod.BEHIND_TEXT;
        cfg1.vertRelTo = VertRelTo.PAGE;
        cfg1.horzRelTo = HorzRelTo.PAGE;
        cfg1.vertAlign = VertAlign.CENTER;
        cfg1.horzAlign = HorzAlign.CENTER;
        cfg1.flowWithText = false;
        cfg1.allowOverlap = true;
        buildPicture(r1, cfg1);
        r1.addNewT().addText("[BEHIND_TEXT - Watermark Effect] " + LONG_TEXT);

        // 추가 텍스트 (뒤에 이미지가 보임)
        Para p2 = addPara(section, false);
        Run r2 = addRun(p2);
        r2.addNewT().addText("This text appears over the background image. " + LOREM);

        // 2) In Front of Text (오버레이)
        Para p3 = addPara(section, false);
        Run r3 = addRun(p3);
        PicConfig cfg2 = new PicConfig();
        cfg2.itemId = ids[4];
        cfg2.displayW = SMALL_W;
        cfg2.displayH = SMALL_H;
        cfg2.treatAsChar = false;
        cfg2.textWrap = TextWrapMethod.IN_FRONT_OF_TEXT;
        cfg2.vertRelTo = VertRelTo.PAGE;
        cfg2.horzRelTo = HorzRelTo.PAGE;
        cfg2.vertAlign = VertAlign.BOTTOM;
        cfg2.horzAlign = HorzAlign.RIGHT;
        cfg2.flowWithText = false;
        cfg2.allowOverlap = true;
        cfg2.zOrder = 10;
        buildPicture(r3, cfg2);
        r3.addNewT().addText("[IN_FRONT_OF_TEXT - Overlay at Bottom-Right] " + LOREM);

        // 3) 반투명 오버레이
        Para p4 = addPara(section, false);
        Run r4 = addRun(p4);
        PicConfig cfg3 = new PicConfig();
        cfg3.itemId = ids[5];
        cfg3.displayW = MEDIUM_W;
        cfg3.displayH = MEDIUM_H;
        cfg3.treatAsChar = false;
        cfg3.textWrap = TextWrapMethod.IN_FRONT_OF_TEXT;
        cfg3.vertRelTo = VertRelTo.PARA;
        cfg3.horzRelTo = HorzRelTo.COLUMN;
        cfg3.vertAlign = VertAlign.TOP;
        cfg3.horzAlign = HorzAlign.LEFT;
        cfg3.flowWithText = true;
        cfg3.allowOverlap = true;
        cfg3.alpha = 0.5f;
        buildPicture(r4, cfg3);
        r4.addNewT().addText("[IN_FRONT_OF_TEXT - Semi-transparent alpha=0.5] " + LONG_TEXT);
    }

    // ── Page 5: 위치 기준과 정렬 ──

    private void buildPage5_PositionAndAlignment(SectionXMLFile section, String[] ids) {
        // 제목
        Para title = addPara(section, true);
        Run titleRun = addRun(title);
        titleRun.addNewT().addText("Page 5 - Position and Alignment");

        // 1) PAPER 기준 Top-Left
        Para p1 = addPara(section, false);
        Run r1 = addRun(p1);
        PicConfig cfg1 = anchoredConfig(ids[6], SMALL_W, SMALL_H,
                TextWrapMethod.SQUARE, HorzAlign.LEFT, VertAlign.TOP);
        cfg1.vertRelTo = VertRelTo.PAPER;
        cfg1.horzRelTo = HorzRelTo.PAPER;
        cfg1.flowWithText = false;
        buildPicture(r1, cfg1);
        r1.addNewT().addText("[PAPER reference - Top Left corner of paper] " + LOREM);

        // 2) PAGE 기준 Center-Center
        Para p2 = addPara(section, false);
        Run r2 = addRun(p2);
        PicConfig cfg2 = anchoredConfig(ids[7], SMALL_W, SMALL_H,
                TextWrapMethod.SQUARE, HorzAlign.CENTER, VertAlign.CENTER);
        cfg2.vertRelTo = VertRelTo.PAGE;
        cfg2.horzRelTo = HorzRelTo.PAGE;
        cfg2.flowWithText = false;
        buildPicture(r2, cfg2);
        r2.addNewT().addText("[PAGE reference - Center of page] " + LOREM);

        // 3) PARA 기준 Bottom-Right
        Para p3 = addPara(section, false);
        Run r3 = addRun(p3);
        PicConfig cfg3 = anchoredConfig(ids[8], SMALL_W, SMALL_H,
                TextWrapMethod.SQUARE, HorzAlign.RIGHT, VertAlign.BOTTOM);
        cfg3.vertRelTo = VertRelTo.PARA;
        cfg3.horzRelTo = HorzRelTo.COLUMN;
        buildPicture(r3, cfg3);
        r3.addNewT().addText("[PARA reference - Bottom Right of column] " + LOREM);

        // 4) 절대 오프셋 배치 1
        Para p4 = addPara(section, false);
        Run r4 = addRun(p4);
        PicConfig cfg4 = anchoredConfig(ids[9], SMALL_W, SMALL_H,
                TextWrapMethod.SQUARE, HorzAlign.LEFT, VertAlign.TOP);
        cfg4.vertRelTo = VertRelTo.PAPER;
        cfg4.horzRelTo = HorzRelTo.PAPER;
        cfg4.vertOffset = 5000L;
        cfg4.horzOffset = 10000L;
        cfg4.flowWithText = false;
        buildPicture(r4, cfg4);
        r4.addNewT().addText("[Absolute offset: vertOffset=5000, horzOffset=10000] " + LOREM);

        // 5) 절대 오프셋 배치 2
        Para p5 = addPara(section, false);
        Run r5 = addRun(p5);
        PicConfig cfg5 = anchoredConfig(ids[0], SMALL_W, SMALL_H,
                TextWrapMethod.SQUARE, HorzAlign.LEFT, VertAlign.TOP);
        cfg5.vertRelTo = VertRelTo.PAPER;
        cfg5.horzRelTo = HorzRelTo.PAPER;
        cfg5.vertOffset = 30000L;
        cfg5.horzOffset = 25000L;
        cfg5.flowWithText = false;
        buildPicture(r5, cfg5);
        r5.addNewT().addText("[Absolute offset: vertOffset=30000, horzOffset=25000] " + LOREM);

        // 채움 텍스트
        Para fill = addPara(section, false);
        Run fillRun = addRun(fill);
        fillRun.addNewT().addText(LONG_TEXT);
    }

    // ── Page 6: 이미지 효과 ──

    private void buildPage6_ImageEffects(SectionXMLFile section, String[] ids) {
        // 제목
        Para title = addPara(section, true);
        Run titleRun = addRun(title);
        titleRun.addNewT().addText("Page 6 - Image Effects");

        // 1) 좌우 반전
        Para p1 = addPara(section, false);
        Run r1 = addRun(p1);
        r1.addNewT().addText("Flip Horizontal: ");
        PicConfig cfg1 = inlineConfig(ids[0], MEDIUM_W, MEDIUM_H);
        cfg1.flipH = true;
        buildPicture(r1, cfg1);

        // 2) 상하 반전
        Para p2 = addPara(section, false);
        Run r2 = addRun(p2);
        r2.addNewT().addText("Flip Vertical: ");
        PicConfig cfg2 = inlineConfig(ids[0], MEDIUM_W, MEDIUM_H);
        cfg2.flipV = true;
        buildPicture(r2, cfg2);

        // 3) 양방향 반전
        Para p3 = addPara(section, false);
        Run r3 = addRun(p3);
        r3.addNewT().addText("Flip Both: ");
        PicConfig cfg3 = inlineConfig(ids[0], MEDIUM_W, MEDIUM_H);
        cfg3.flipH = true;
        cfg3.flipV = true;
        buildPicture(r3, cfg3);

        // 4) 밝기 조정
        Para p4 = addPara(section, false);
        Run r4 = addRun(p4);
        r4.addNewT().addText("Brightness +50: ");
        PicConfig cfg4 = inlineConfig(ids[1], MEDIUM_W, MEDIUM_H);
        cfg4.bright = 50;
        buildPicture(r4, cfg4);

        // 5) 대비 조정
        Para p5 = addPara(section, false);
        Run r5 = addRun(p5);
        r5.addNewT().addText("Contrast +50: ");
        PicConfig cfg5 = inlineConfig(ids[1], MEDIUM_W, MEDIUM_H);
        cfg5.contrast = 50;
        buildPicture(r5, cfg5);

        // 6) 흑백 효과
        Para p6 = addPara(section, false);
        Run r6 = addRun(p6);
        r6.addNewT().addText("Grayscale Effect: ");
        PicConfig cfg6 = inlineConfig(ids[2], MEDIUM_W, MEDIUM_H);
        cfg6.effect = ImageEffect.GRAY_SCALE;
        buildPicture(r6, cfg6);

        // 7) 투명도 3단계
        Para p7 = addPara(section, false);
        Run r7 = addRun(p7);
        r7.addNewT().addText("Alpha transparency — 0.0: ");
        PicConfig cfgA0 = inlineConfig(ids[3], SMALL_W, SMALL_H);
        cfgA0.alpha = 0.0f;
        buildPicture(r7, cfgA0);
        r7.addNewT().addText(" | 0.3: ");
        PicConfig cfgA3 = inlineConfig(ids[3], SMALL_W, SMALL_H);
        cfgA3.alpha = 0.3f;
        buildPicture(r7, cfgA3);
        r7.addNewT().addText(" | 0.7: ");
        PicConfig cfgA7 = inlineConfig(ids[3], SMALL_W, SMALL_H);
        cfgA7.alpha = 0.7f;
        buildPicture(r7, cfgA7);
    }

    // ── Page 7: 다중 이미지 구성 ──

    private void buildPage7_MultipleComposition(SectionXMLFile section, String[] ids) {
        // 제목
        Para title = addPara(section, true);
        Run titleRun = addRun(title);
        titleRun.addNewT().addText("Page 7 - Multiple Image Composition");

        // 1) 갤러리 그리드 — 1행 (4개)
        Para grid1 = addPara(section, false);
        Run gridRun1 = addRun(grid1);
        gridRun1.addNewT().addText("Gallery Row 1: ");
        for (int i = 0; i < 4; i++) {
            buildPicture(gridRun1, inlineConfig(ids[i], SMALL_W, SMALL_H));
            if (i < 3) gridRun1.addNewT().addText(" ");
        }

        // 2) 갤러리 그리드 — 2행 (4개)
        Para grid2 = addPara(section, false);
        Run gridRun2 = addRun(grid2);
        gridRun2.addNewT().addText("Gallery Row 2: ");
        for (int i = 4; i < 8; i++) {
            buildPicture(gridRun2, inlineConfig(ids[i], SMALL_W, SMALL_H));
            if (i < 7) gridRun2.addNewT().addText(" ");
        }

        // 3) 겹치는 이미지 (allowOverlap=true, 계단식)
        Para overlap = addPara(section, false);
        Run overlapRun = addRun(overlap);
        for (int i = 0; i < 3; i++) {
            PicConfig cfg = anchoredConfig(ids[i], MEDIUM_W, MEDIUM_H,
                    TextWrapMethod.SQUARE, HorzAlign.LEFT, VertAlign.TOP);
            cfg.allowOverlap = true;
            cfg.vertRelTo = VertRelTo.PARA;
            cfg.horzRelTo = HorzRelTo.COLUMN;
            cfg.vertOffset = (long) i * 3000L;
            cfg.horzOffset = (long) i * 5000L;
            buildPicture(overlapRun, cfg);
        }
        overlapRun.addNewT().addText("[Overlapping images with cascading offset] " + LOREM);

        // 마무리 텍스트
        Para end = addPara(section, false);
        Run endRun = addRun(end);
        endRun.addNewT().addText("End of Image Layout Sample Document.");
    }

    // ── 범용 Picture 빌더 ──

    private Picture buildPicture(Run run, PicConfig cfg) {
        Picture pic = run.addNewPicture();
        String picId = nextId();
        long dW = cfg.displayW;
        long dH = cfg.displayH;

        // ShapeObject
        pic.idAnd(picId).zOrderAnd(cfg.zOrder)
                .numberingTypeAnd(NumberingType.PICTURE)
                .textWrapAnd(cfg.textWrap)
                .textFlowAnd(cfg.textFlow)
                .lockAnd(false).dropcapstyleAnd(DropCapStyle.None);

        // ShapeComponent
        pic.hrefAnd("");
        pic.groupLevelAnd((short) 0);
        pic.instidAnd(nextId());
        pic.reverseAnd(false);

        pic.createOffset();
        pic.offset().set(0L, 0L);

        pic.createOrgSz();
        pic.orgSz().set(dW, dH);

        pic.createCurSz();
        pic.curSz().set(dW, dH);

        pic.createFlip();
        pic.flip().horizontalAnd(cfg.flipH).verticalAnd(cfg.flipV);

        pic.createRotationInfo();
        pic.rotationInfo().angleAnd(cfg.angle)
                .centerXAnd(dW / 2).centerYAnd(dH / 2)
                .rotateimageAnd(true);

        pic.createRenderingInfo();
        pic.renderingInfo().addNewTransMatrix().set(1f, 0f, 0f, 0f, 1f, 0f);
        pic.renderingInfo().addNewScaMatrix().set(1f, 0f, 0f, 0f, 1f, 0f);
        pic.renderingInfo().addNewRotMatrix().set(1f, 0f, 0f, 0f, 1f, 0f);

        // ShapeSize
        pic.createSZ();
        pic.sz().widthAnd(dW).widthRelToAnd(cfg.widthRelTo)
                .heightAnd(dH).heightRelToAnd(cfg.heightRelTo)
                .protectAnd(false);

        // Position
        pic.createPos();
        pic.pos().treatAsCharAnd(cfg.treatAsChar)
                .affectLSpacingAnd(cfg.treatAsChar)
                .flowWithTextAnd(cfg.flowWithText)
                .allowOverlapAnd(cfg.allowOverlap)
                .holdAnchorAndSOAnd(false)
                .vertRelToAnd(cfg.vertRelTo)
                .horzRelToAnd(cfg.horzRelTo)
                .vertAlignAnd(cfg.vertAlign)
                .horzAlignAnd(cfg.horzAlign)
                .vertOffsetAnd(cfg.vertOffset)
                .horzOffset(cfg.horzOffset);

        // OutMargin
        pic.createOutMargin();
        pic.outMargin().leftAnd(cfg.marginLeft).rightAnd(cfg.marginRight)
                .topAnd(cfg.marginTop).bottomAnd(cfg.marginBottom);

        // ImageRect
        pic.createImgRect();
        pic.imgRect().createPt0();
        pic.imgRect().pt0().set(0L, 0L);
        pic.imgRect().createPt1();
        pic.imgRect().pt1().set(dW, 0L);
        pic.imgRect().createPt2();
        pic.imgRect().pt2().set(dW, dH);
        pic.imgRect().createPt3();
        pic.imgRect().pt3().set(0L, dH);

        // ImageClip
        long clipW = (long) cfg.pixelW * 75;
        long clipH = (long) cfg.pixelH * 75;
        pic.createImgClip();
        pic.imgClip().leftAnd(0L).rightAnd(clipW).topAnd(0L).bottomAnd(clipH);

        // InMargin
        pic.createInMargin();
        pic.inMargin().leftAnd(0L).rightAnd(0L).topAnd(0L).bottomAnd(0L);

        // ImageDim
        pic.createImgDim();
        pic.imgDim().dimwidthAnd(clipW).dimheightAnd(clipH);

        // Image reference
        pic.createImg();
        pic.img().binaryItemIDRefAnd(cfg.itemId)
                .brightAnd(cfg.bright).contrastAnd(cfg.contrast)
                .effectAnd(cfg.effect).alphaAnd(cfg.alpha);

        return pic;
    }

    // ── 유틸리티 ──

    private PicConfig inlineConfig(String itemId, long w, long h) {
        PicConfig cfg = new PicConfig();
        cfg.itemId = itemId;
        cfg.displayW = w;
        cfg.displayH = h;
        cfg.treatAsChar = true;
        cfg.textWrap = TextWrapMethod.SQUARE;
        cfg.textFlow = TextFlowSide.BOTH_SIDES;
        cfg.vertRelTo = VertRelTo.PARA;
        cfg.horzRelTo = HorzRelTo.COLUMN;
        cfg.vertAlign = VertAlign.TOP;
        cfg.horzAlign = HorzAlign.LEFT;
        cfg.marginLeft = 0L;
        cfg.marginRight = 0L;
        cfg.marginTop = 0L;
        cfg.marginBottom = 0L;
        return cfg;
    }

    private PicConfig anchoredConfig(String itemId, long w, long h,
                                     TextWrapMethod wrap, HorzAlign hAlign, VertAlign vAlign) {
        PicConfig cfg = new PicConfig();
        cfg.itemId = itemId;
        cfg.displayW = w;
        cfg.displayH = h;
        cfg.treatAsChar = false;
        cfg.textWrap = wrap;
        cfg.horzAlign = hAlign;
        cfg.vertAlign = vAlign;
        return cfg;
    }

    private String[] registerDummyImages(HWPXFile hwpxFile, int count) throws IOException {
        Color[] colors = {
                new Color(0x4472C4), new Color(0xED7D31), new Color(0x70AD47),
                new Color(0xFFC000), new Color(0x7030A0), new Color(0xC00000),
                new Color(0x5B9BD5), new Color(0x44546A), new Color(0xBF8F00),
                new Color(0xA5A5A5)
        };
        String[] ids = new String[count];
        for (int i = 0; i < count; i++) {
            byte[] data = createDummyImage("png", PIXEL_W, PIXEL_H,
                    colors[i % colors.length], "IMG-" + (i + 1));
            ids[i] = ImageInserter.registerImage(hwpxFile, data, "png");
        }
        return ids;
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
        return String.valueOf(9000000 + (idCounter++));
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

    private int countPictures(SectionXMLFile section) {
        int count = 0;
        for (int i = 0; i < section.countOfPara(); i++) {
            Para para = section.getPara(i);
            for (int r = 0; r < para.countOfRun(); r++) {
                Run run = para.getRun(r);
                for (int ri = 0; ri < run.countOfRunItem(); ri++) {
                    if (run.getRunItem(ri) instanceof Picture) {
                        count++;
                    }
                }
            }
        }
        return count;
    }
}
