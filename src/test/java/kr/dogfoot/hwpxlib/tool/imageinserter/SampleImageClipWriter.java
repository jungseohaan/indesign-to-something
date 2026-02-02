package kr.dogfoot.hwpxlib.tool.imageinserter;

import kr.dogfoot.hwpxlib.object.HWPXFile;
import kr.dogfoot.hwpxlib.object.content.header_xml.enumtype.ImageBrushMode;
import kr.dogfoot.hwpxlib.object.content.header_xml.enumtype.ImageEffect;
import kr.dogfoot.hwpxlib.object.content.section_xml.SectionXMLFile;
import kr.dogfoot.hwpxlib.object.content.section_xml.enumtype.*;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.Para;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.Run;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.object.Polygon;
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
import java.util.concurrent.atomic.AtomicLong;

/**
 * 이미지가 숫자(0~9) 모양으로 클리핑되어 표시되는 샘플 HWPX 생성.
 *
 * <p>Polygon 도형의 꼭짓점을 숫자 외곽선으로 정의하고,
 * FillBrush &gt; ImgBrush (TOTAL 모드)로 이미지를 채워서 클리핑 효과 구현.</p>
 */
public class SampleImageClipWriter {

    private static final AtomicLong ID_COUNTER = new AtomicLong(7000000);
    private int paraIdCounter = 0;

    // 숫자 폴리곤 크기 (hwpunit)
    private static final long DIGIT_W = 8000L;
    private static final long DIGIT_H = 12000L;

    @Test
    public void createImageClipDocument() throws Exception {
        HWPXFile hwpxFile = BlankFileMaker.make();
        SectionXMLFile section = hwpxFile.sectionXMLFileList().get(0);

        // 그라데이션 테스트 이미지 생성 및 등록
        byte[] pngData = createGradientPng(400, 600);
        String imageItemId = ImageInserter.registerImage(hwpxFile, pngData, "png");

        // 제목
        addTextPara(section, "이미지 숫자 클리핑 샘플 (Image Digit Clipping)", false);
        addTextPara(section, "", false);

        int polygonCount = 0;

        // 숫자 0~9 각각 Polygon + ImgBrush로 생성
        for (int digit = 0; digit <= 9; digit++) {
            addTextPara(section, "▶ 숫자 " + digit, false);

            Para imgPara = addEmptyPara(section, false);
            Run imgRun = imgPara.addNewRun();
            imgRun.charPrIDRef("0");

            long[][] points = getDigitPoints(digit);
            buildImagePolygon(imgRun, imageItemId, points, DIGIT_W, DIGIT_H);
            polygonCount++;

            addTextPara(section, "", false);
        }

        // 저장
        String filepath = "testFile/tool/sample_image_clip.hwpx";
        HWPXWriter.toFilepath(hwpxFile, filepath);

        System.out.println("=== Image Clip Sample Result ===");
        System.out.println("File: " + filepath);
        System.out.println("Polygons: " + polygonCount);

        // 라운드트립 검증
        HWPXFile readBack = HWPXReader.fromFilepath(filepath);
        SectionXMLFile readSection = readBack.sectionXMLFileList().get(0);
        Assert.assertTrue("Should have paragraphs",
                readSection.countOfPara() > 20);

        // Polygon 개수 확인
        int readPolygonCount = 0;
        for (int i = 0; i < readSection.countOfPara(); i++) {
            Para para = readSection.getPara(i);
            for (int r = 0; r < para.countOfRun(); r++) {
                Run run = para.getRun(r);
                for (int ri = 0; ri < run.countOfRunItem(); ri++) {
                    if (run.getRunItem(ri) instanceof Polygon) {
                        readPolygonCount++;
                    }
                }
            }
        }
        Assert.assertEquals("Should have 10 digit polygons", 10, readPolygonCount);

        System.out.println("Round-trip verification passed!");
        System.out.println("Read-back polygons: " + readPolygonCount);
    }

    /**
     * 이미지가 채워진 Polygon을 Run에 추가한다.
     */
    private void buildImagePolygon(Run run, String imageItemId,
                                   long[][] points, long w, long h) {
        Polygon polygon = run.addNewPolygon();
        String objId = nextId();
        String instId = nextId();

        // ShapeObject
        polygon.idAnd(objId).zOrderAnd(0)
                .numberingTypeAnd(NumberingType.PICTURE)
                .textWrapAnd(TextWrapMethod.TOP_AND_BOTTOM)
                .textFlowAnd(TextFlowSide.BOTH_SIDES)
                .lockAnd(false).dropcapstyleAnd(DropCapStyle.None);

        // ShapeComponent
        polygon.hrefAnd("");
        polygon.groupLevelAnd((short) 0);
        polygon.instidAnd(instId);

        polygon.createOffset();
        polygon.offset().set(0L, 0L);

        polygon.createOrgSz();
        polygon.orgSz().set(w, h);

        polygon.createCurSz();
        polygon.curSz().set(w, h);

        polygon.createFlip();
        polygon.flip().horizontalAnd(false).verticalAnd(false);

        polygon.createRotationInfo();
        polygon.rotationInfo().angleAnd((short) 0)
                .centerXAnd(w / 2).centerYAnd(h / 2)
                .rotateimageAnd(true);

        polygon.createRenderingInfo();
        polygon.renderingInfo().addNewTransMatrix().set(1f, 0f, 0f, 0f, 1f, 0f);
        polygon.renderingInfo().addNewScaMatrix().set(1f, 0f, 0f, 0f, 1f, 0f);
        polygon.renderingInfo().addNewRotMatrix().set(1f, 0f, 0f, 0f, 1f, 0f);

        // FillBrush + ImgBrush
        polygon.createFillBrush();
        polygon.fillBrush().createImgBrush();
        polygon.fillBrush().imgBrush().modeAnd(ImageBrushMode.TOTAL);
        polygon.fillBrush().imgBrush().createImg();
        polygon.fillBrush().imgBrush().img()
                .binaryItemIDRefAnd(imageItemId)
                .brightAnd(0).contrastAnd(0)
                .effectAnd(ImageEffect.REAL_PIC).alphaAnd(0f);

        // 꼭짓점
        for (long[] pt : points) {
            polygon.addNewPt().set(pt[0], pt[1]);
        }

        // ShapeSize
        polygon.createSZ();
        polygon.sz().widthAnd(w).widthRelToAnd(WidthRelTo.ABSOLUTE)
                .heightAnd(h).heightRelToAnd(HeightRelTo.ABSOLUTE)
                .protectAnd(false);

        // Position (인라인)
        polygon.createPos();
        polygon.pos().treatAsCharAnd(true)
                .affectLSpacingAnd(true)
                .flowWithTextAnd(true)
                .allowOverlapAnd(false)
                .holdAnchorAndSOAnd(false)
                .vertRelToAnd(VertRelTo.PARA)
                .horzRelToAnd(HorzRelTo.COLUMN)
                .vertAlignAnd(VertAlign.TOP)
                .horzAlignAnd(HorzAlign.LEFT)
                .vertOffsetAnd(0L)
                .horzOffset(0L);

        // OutMargin
        polygon.createOutMargin();
        polygon.outMargin().set(0L, 0L, 0L, 0L);
    }

    // ── 숫자 꼭짓점 데이터 ──

    /**
     * 숫자(0~9) 외곽선의 다각형 꼭짓점을 반환한다.
     * 좌표계: (0,0) ~ (DIGIT_W, DIGIT_H)
     */
    private long[][] getDigitPoints(int digit) {
        switch (digit) {
            case 0: return digit0();
            case 1: return digit1();
            case 2: return digit2();
            case 3: return digit3();
            case 4: return digit4();
            case 5: return digit5();
            case 6: return digit6();
            case 7: return digit7();
            case 8: return digit8();
            case 9: return digit9();
            default: return digit0();
        }
    }

    // 숫자 "0" — 타원형
    private long[][] digit0() {
        return ellipsePoints(4000, 6000, 3600, 5600, 20);
    }

    // 숫자 "1" — 세로 막대 + 세리프
    private long[][] digit1() {
        return new long[][]{
                {3000, 0}, {5000, 0},
                {5000, 10500}, {6500, 10500},
                {6500, 12000}, {1500, 12000},
                {1500, 10500}, {3000, 10500},
                {3000, 2500}, {1500, 2500},
                {1500, 1000}
        };
    }

    // 숫자 "2" — 곡선 상단 + 대각선 + 바닥
    private long[][] digit2() {
        return new long[][]{
                {1000, 3000}, {1200, 2000}, {1800, 1000},
                {2800, 300}, {4000, 0}, {5200, 300},
                {6200, 1000}, {6800, 2000}, {7000, 3000},
                {6800, 4000}, {6200, 5000},
                {5000, 6500}, {3500, 8000}, {1500, 10000},
                {1000, 10500},
                {7000, 10500}, {7000, 12000},
                {1000, 12000}, {1000, 10000},
                {2500, 8000}, {4000, 6500},
                {5500, 5200}, {6000, 4500},
                {6200, 3800}, {6200, 3000},
                {6000, 2200}, {5500, 1600},
                {4800, 1200}, {4000, 1000},
                {3200, 1200}, {2500, 1600},
                {2000, 2200}, {1800, 3000}
        };
    }

    // 숫자 "3" — 두 곡선 결합
    private long[][] digit3() {
        return new long[][]{
                {1000, 1000}, {2000, 300}, {3500, 0}, {5000, 0},
                {6200, 300}, {7000, 1200}, {7200, 2200},
                {7000, 3200}, {6200, 4200}, {5000, 4800},
                {4000, 5000},
                {5200, 5200}, {6500, 5800}, {7200, 6800},
                {7500, 7800}, {7200, 9000},
                {6500, 10200}, {5500, 11200}, {4000, 12000},
                {2500, 12000}, {1500, 11500}, {800, 10500},
                {800, 9500}, {1500, 9800}, {2500, 10500},
                {3500, 11000}, {4500, 10800},
                {5500, 10200}, {6200, 9200}, {6200, 8200},
                {5800, 7200}, {5000, 6500}, {4000, 6200},
                {3500, 6000},
                {4500, 5800}, {5500, 5000}, {6200, 4000},
                {6200, 3000}, {5800, 2000}, {5000, 1200},
                {4000, 1000}, {3000, 1200}, {2000, 1800}
        };
    }

    // 숫자 "4" — L자 역삼각
    private long[][] digit4() {
        return new long[][]{
                {5000, 0}, {7000, 0},
                {7000, 8000}, {8000, 8000},
                {8000, 9500}, {7000, 9500},
                {7000, 12000}, {5000, 12000},
                {5000, 9500}, {0, 9500},
                {0, 8000},
                {5000, 0}
        };
    }

    // 숫자 "5" — 상단 수평선 + 곡선
    private long[][] digit5() {
        return new long[][]{
                {1000, 0}, {7000, 0},
                {7000, 1500}, {2500, 1500},
                {2000, 3000}, {1800, 4500},
                {2500, 4000}, {3500, 3800},
                {5000, 4000}, {6200, 4800},
                {7000, 6000}, {7200, 7500},
                {7000, 9000}, {6200, 10500},
                {5000, 11500}, {3500, 12000},
                {2000, 11500}, {1000, 10500},
                {800, 9500},
                {1500, 9500}, {2200, 10500},
                {3500, 11000}, {4800, 10800},
                {5800, 10000}, {6200, 8800},
                {6200, 7500}, {5800, 6300},
                {5000, 5500}, {3800, 5200},
                {2800, 5200}, {2000, 5500},
                {1500, 5000}
        };
    }

    // 숫자 "6" — 곡선
    private long[][] digit6() {
        return ellipsePoints(4000, 8000, 3200, 3500, 16);
    }

    // 숫자 "7" — 단순 삼각형
    private long[][] digit7() {
        return new long[][]{
                {1000, 0}, {7000, 0},
                {7000, 1500},
                {4000, 12000}, {2000, 12000},
                {5000, 1500}, {1000, 1500}
        };
    }

    // 숫자 "8" — 두 원 결합 (상하)
    private long[][] digit8() {
        // 상단 작은 원 + 하단 큰 원
        long[][] top = ellipsePoints(4000, 3200, 2800, 2800, 12);
        long[][] bottom = ellipsePoints(4000, 8500, 3200, 3200, 12);
        long[][] combined = new long[top.length + bottom.length][];
        System.arraycopy(top, 0, combined, 0, top.length);
        System.arraycopy(bottom, 0, combined, top.length, bottom.length);
        return combined;
    }

    // 숫자 "9" — 6을 뒤집은 형태
    private long[][] digit9() {
        return ellipsePoints(4000, 4000, 3200, 3500, 16);
    }

    /**
     * 타원 외곽선 꼭짓점을 생성한다.
     */
    private long[][] ellipsePoints(long cx, long cy, long rx, long ry, int segments) {
        long[][] pts = new long[segments][];
        for (int i = 0; i < segments; i++) {
            double angle = 2.0 * Math.PI * i / segments;
            long x = cx + (long) (rx * Math.cos(angle));
            long y = cy + (long) (ry * Math.sin(angle));
            // 좌표 범위 클램핑
            x = Math.max(0, Math.min(DIGIT_W, x));
            y = Math.max(0, Math.min(DIGIT_H, y));
            pts[i] = new long[]{x, y};
        }
        return pts;
    }

    // ── 이미지 생성 ──

    /**
     * 그라데이션 테스트 이미지를 생성한다.
     */
    private byte[] createGradientPng(int w, int h) throws IOException {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        GradientPaint gp = new GradientPaint(
                0, 0, new Color(255, 100, 50),
                w, h, new Color(50, 100, 255));
        g.setPaint(gp);
        g.fillRect(0, 0, w, h);
        // 격자 패턴 추가
        g.setColor(new Color(255, 255, 255, 80));
        for (int x = 0; x < w; x += 40) {
            g.drawLine(x, 0, x, h);
        }
        for (int y = 0; y < h; y += 40) {
            g.drawLine(0, y, w, y);
        }
        g.dispose();
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, "png", baos);
        return baos.toByteArray();
    }

    // ── 헬퍼 ──

    private Para addTextPara(SectionXMLFile section, String text, boolean pageBreak) {
        Para p = section.addNewPara();
        p.idAnd(nextParaId()).paraPrIDRefAnd("0").styleIDRefAnd("0")
                .pageBreakAnd(pageBreak).columnBreakAnd(false).merged(false);
        Run r = p.addNewRun();
        r.charPrIDRef("0");
        r.addNewT().addText(text);
        return p;
    }

    private Para addEmptyPara(SectionXMLFile section, boolean pageBreak) {
        Para p = section.addNewPara();
        p.idAnd(nextParaId()).paraPrIDRefAnd("0").styleIDRefAnd("0")
                .pageBreakAnd(pageBreak).columnBreakAnd(false).merged(false);
        return p;
    }

    private String nextId() {
        return String.valueOf(ID_COUNTER.incrementAndGet());
    }

    private String nextParaId() {
        return String.valueOf(8000000 + (paraIdCounter++));
    }
}
