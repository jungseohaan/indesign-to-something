package kr.dogfoot.hwpxlib.tool.table;

import kr.dogfoot.hwpxlib.object.HWPXFile;
import kr.dogfoot.hwpxlib.object.common.ObjectList;
import kr.dogfoot.hwpxlib.object.content.context_hpf.ManifestItem;
import kr.dogfoot.hwpxlib.object.content.header_xml.enumtype.ImageEffect;
import kr.dogfoot.hwpxlib.object.content.header_xml.references.CharPr;
import kr.dogfoot.hwpxlib.object.content.header_xml.references.borderfill.Image;
import kr.dogfoot.hwpxlib.object.content.section_xml.SectionXMLFile;
import kr.dogfoot.hwpxlib.object.content.section_xml.enumtype.*;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.Ctrl;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.Para;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.Run;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.ctrl.ColPr;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.object.Equation;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.object.Picture;
import kr.dogfoot.hwpxlib.reader.HWPXReader;
import kr.dogfoot.hwpxlib.tool.blankfilemaker.BlankFileMaker;
import kr.dogfoot.hwpxlib.tool.equationconverter.EquationBuilder;
import kr.dogfoot.hwpxlib.writer.HWPXWriter;
import org.junit.Assert;
import org.junit.Test;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;

/**
 * 10페이지 분량의 2단 컬럼 레이아웃에 다양한 이미지(JPG, PNG)와 수식이
 * 텍스트 문장 속에 인라인으로 배치되는 샘플 HWPX 파일을 생성하는 테스트.
 *
 * 각 페이지마다 여러 문단이 있고, 문단 사이에 인라인 이미지와 수식이 텍스트와 함께 흐른다.
 */
public class SampleRichFlowPageWriter {

    private int idCounter = 0;
    private int imageCounter = 0;

    // 인라인 이미지 크기 (hwpunit). 텍스트 흐름에 맞는 작은 크기
    private static final long SMALL_IMG_W = 8000L;   // ~28mm
    private static final long SMALL_IMG_H = 5000L;   // ~17mm
    private static final long MEDIUM_IMG_W = 14000L;  // ~49mm
    private static final long MEDIUM_IMG_H = 9000L;   // ~31mm
    private static final long LARGE_IMG_W = 18000L;   // ~63mm (column width ~21260)
    private static final long LARGE_IMG_H = 12000L;   // ~42mm

    // 더미 이미지 픽셀 크기
    private static final int PIXEL_W = 200;
    private static final int PIXEL_H = 130;

    // 색상 팔레트 (이미지 배경용)
    private static final java.awt.Color[] IMG_COLORS = {
            new java.awt.Color(0x4472C4), // 파랑
            new java.awt.Color(0xED7D31), // 주황
            new java.awt.Color(0xA5A5A5), // 회색
            new java.awt.Color(0xFFC000), // 노랑
            new java.awt.Color(0x5B9BD5), // 연파랑
            new java.awt.Color(0x70AD47), // 녹색
            new java.awt.Color(0xBF8F00), // 금색
            new java.awt.Color(0x7030A0), // 보라
            new java.awt.Color(0xC00000), // 빨강
            new java.awt.Color(0x44546A), // 회남색
    };

    // 샘플 수식 (MathML) - HTML 엔티티 대신 유니코드 문자 직접 사용
    private static final String PLUS_MINUS = "\u00B1";
    private static final String SIGMA = "\u03A3";
    private static final String PI = "\u03C0";
    private static final String INTEGRAL = "\u222B";
    private static final String ALPHA = "\u03B1";
    private static final String BETA = "\u03B2";

    private static final String[] SAMPLE_EQUATIONS = {
            "<math><mrow><mi>E</mi><mo>=</mo><mi>m</mi><msup><mi>c</mi><mn>2</mn></msup></mrow></math>",
            "<math><mrow><mi>a</mi><mo>+</mo><mi>b</mi><mo>=</mo><mi>c</mi></mrow></math>",
            "<math><mfrac><mrow><mo>-</mo><mi>b</mi><mo>" + PLUS_MINUS + "</mo><msqrt><mrow><msup><mi>b</mi><mn>2</mn></msup><mo>-</mo><mn>4</mn><mi>a</mi><mi>c</mi></mrow></msqrt></mrow><mrow><mn>2</mn><mi>a</mi></mrow></mfrac></math>",
            "<math><mrow><munderover><mo>" + SIGMA + "</mo><mrow><mi>i</mi><mo>=</mo><mn>1</mn></mrow><mi>n</mi></munderover><mi>i</mi><mo>=</mo><mfrac><mrow><mi>n</mi><mo>(</mo><mi>n</mi><mo>+</mo><mn>1</mn><mo>)</mo></mrow><mn>2</mn></mfrac></mrow></math>",
            "<math><mrow><msup><mi>e</mi><mrow><mi>i</mi><mi>" + PI + "</mi></mrow></msup><mo>+</mo><mn>1</mn><mo>=</mo><mn>0</mn></mrow></math>",
            "<math><mrow><mfrac><mi>d</mi><mrow><mi>d</mi><mi>x</mi></mrow></mfrac><msup><mi>x</mi><mi>n</mi></msup><mo>=</mo><mi>n</mi><msup><mi>x</mi><mrow><mi>n</mi><mo>-</mo><mn>1</mn></mrow></msup></mrow></math>",
            "<math><mrow><msubsup><mo>" + INTEGRAL + "</mo><mi>a</mi><mi>b</mi></msubsup><mi>f</mi><mo>(</mo><mi>x</mi><mo>)</mo><mi>d</mi><mi>x</mi></mrow></math>",
            "<math><mrow><mi>sin</mi><mo>(</mo><mi>" + ALPHA + "</mi><mo>+</mo><mi>" + BETA + "</mi><mo>)</mo></mrow></math>",
            "<math><mrow><msqrt><mrow><msup><mi>a</mi><mn>2</mn></msup><mo>+</mo><msup><mi>b</mi><mn>2</mn></msup></mrow></msqrt></mrow></math>",
            "<math><mrow><mi>F</mi><mo>=</mo><mi>G</mi><mfrac><mrow><msub><mi>m</mi><mn>1</mn></msub><msub><mi>m</mi><mn>2</mn></msub></mrow><msup><mi>r</mi><mn>2</mn></msup></mfrac></mrow></math>",
    };

    // 수식 색상
    private static final String[] EQ_COLORS = {
            "#000000", "#1F4E79", "#C00000", "#375623", "#7030A0",
            "#BF8F00", "#2E74B5", "#843C0C", "#44546A", "#FF6600",
    };

    // 샘플 텍스트 (lorem ipsum 스타일)
    private static final String[] SAMPLE_TEXTS = {
            "Mathematics is the queen of sciences and number theory is the queen of mathematics. ",
            "The beauty of a living thing is not the atoms that go into it, but the way those atoms are put together. ",
            "In mathematics, you don't understand things. You just get used to them. ",
            "Pure mathematics is, in its way, the poetry of logical ideas. ",
            "The essence of mathematics lies in its freedom. ",
            "Mathematics is not about numbers, equations, computations, or algorithms: it is about understanding. ",
            "The important thing is not to stop questioning. Curiosity has its own reason for existing. ",
            "Science is a way of thinking much more than it is a body of knowledge. ",
            "The only way to learn mathematics is to do mathematics. ",
            "Mathematics knows no races or geographic boundaries; it is universal. ",
            "If people do not believe that mathematics is simple, it is only because they do not realize how complicated life is. ",
            "A mathematician is a device for turning coffee into theorems. ",
            "God used beautiful mathematics in creating the world. ",
            "Mathematics is the music of reason. ",
            "Without mathematics, there's nothing you can do. Everything around you is mathematics. ",
    };

    @Test
    public void createRichFlowPages() throws Exception {
        HWPXFile hwpxFile = BlankFileMaker.make();
        SectionXMLFile section = hwpxFile.sectionXMLFileList().get(0);

        // 볼드 제목용 CharPr 추가 (ID "7")
        addBoldCharPr(hwpxFile);

        // 이미지 리소스 생성 및 등록 (30개: 10 PNG + 10 JPEG + 10 PNG patterns)
        ImageInfo[] images = registerImages(hwpxFile, 30);

        // 기존 첫 번째 Para의 ColPr을 2단으로 변경 (BlankFileMaker가 만든 완전한 SecPr 유지)
        Para firstPara = section.getPara(0);
        Run firstRun = firstPara.getRun(0);
        // Ctrl 안의 ColPr을 찾아서 colCount를 2로 변경
        Ctrl firstCtrl = (Ctrl) firstRun.getRunItem(0);
        ColPr colPr = (ColPr) firstCtrl.getCtrlItem(0);
        colPr.colCountAnd(2).sameSzAnd(true).sameGap(1134);

        int eqIdx = 0;
        int imgIdx = 0;
        int txtIdx = 0;

        for (int page = 0; page < 10; page++) {
            // 페이지 제목 (페이지 구분용)
            Para titlePara = section.addNewPara();
            titlePara.idAnd(nextId()).paraPrIDRefAnd("0").styleIDRefAnd("0")
                    .pageBreakAnd(page > 0).columnBreakAnd(false).merged(false);
            Run titleRun = titlePara.addNewRun();
            titleRun.charPrIDRef("7"); // 볼드
            titleRun.addNewT().addText("Page " + (page + 1) + " - Rich Content Flow Sample");

            // 각 페이지에 5~6개 문단 배치 (이미지 + 수식 + 텍스트 혼합)
            int parasPerPage = 5 + (page % 2); // 5 or 6

            for (int pi = 0; pi < parasPerPage; pi++) {
                Para para = section.addNewPara();
                para.idAnd(nextId()).paraPrIDRefAnd("0").styleIDRefAnd("0")
                        .pageBreakAnd(false).columnBreakAnd(false).merged(false);

                // 패턴에 따라 다른 컨텐츠 조합
                int pattern = (page * parasPerPage + pi) % 6;

                switch (pattern) {
                    case 0: // 텍스트 + 인라인 이미지 + 텍스트
                        addTextImageTextPara(para, images, imgIdx, txtIdx);
                        imgIdx = (imgIdx + 1) % images.length;
                        txtIdx = (txtIdx + 2) % SAMPLE_TEXTS.length;
                        break;
                    case 1: // 텍스트 + 인라인 수식 + 텍스트
                        addTextEquationTextPara(para, eqIdx, txtIdx);
                        eqIdx = (eqIdx + 1) % SAMPLE_EQUATIONS.length;
                        txtIdx = (txtIdx + 1) % SAMPLE_TEXTS.length;
                        break;
                    case 2: // 큰 이미지 단독 문단
                        addLargeImagePara(para, images, imgIdx);
                        imgIdx = (imgIdx + 1) % images.length;
                        break;
                    case 3: // 텍스트 + 수식 + 이미지 + 텍스트
                        addMixedPara(para, images, imgIdx, eqIdx, txtIdx);
                        imgIdx = (imgIdx + 1) % images.length;
                        eqIdx = (eqIdx + 1) % SAMPLE_EQUATIONS.length;
                        txtIdx = (txtIdx + 2) % SAMPLE_TEXTS.length;
                        break;
                    case 4: // 순수 텍스트 (긴 문장)
                        addLongTextPara(para, txtIdx);
                        txtIdx = (txtIdx + 3) % SAMPLE_TEXTS.length;
                        break;
                    case 5: // 이미지 + 수식 연속
                        addImageAndEquationPara(para, images, imgIdx, eqIdx);
                        imgIdx = (imgIdx + 1) % images.length;
                        eqIdx = (eqIdx + 1) % SAMPLE_EQUATIONS.length;
                        break;
                }
            }
        }

        String filepath = "testFile/tool/sample_rich_flow_pages.hwpx";
        HWPXWriter.toFilepath(hwpxFile, filepath);

        System.out.println("=== Rich Flow Pages Result ===");
        System.out.println("Pages: 10 (2-column layout)");
        System.out.println("Images registered: " + images.length);
        System.out.println("File: " + filepath);

        // Round-trip 검증
        HWPXFile readBack = HWPXReader.fromFilepath(filepath);
        SectionXMLFile readSection = readBack.sectionXMLFileList().get(0);
        Assert.assertTrue("Should have paragraphs", readSection.countOfPara() > 0);
        System.out.println("Round-trip verification passed!");
        System.out.println("Paragraphs: " + readSection.countOfPara());
    }

    // ── 이미지 생성/등록 ──

    private static class ImageInfo {
        String itemId;
        String format; // "png" or "jpeg"
        long imgW;  // hwpunit
        long imgH;  // hwpunit
        int pixelW;
        int pixelH;
    }

    private ImageInfo[] registerImages(HWPXFile hwpxFile, int count) throws IOException {
        ImageInfo[] infos = new ImageInfo[count];
        ObjectList<ManifestItem> manifest = hwpxFile.contentHPFFile().manifest();

        for (int i = 0; i < count; i++) {
            String itemId = "image" + (i + 1);
            boolean isPng = (i % 3 != 1); // 2/3 PNG, 1/3 JPEG
            String format = isPng ? "png" : "jpeg";
            String ext = isPng ? ".png" : ".jpg";
            String mediaType = isPng ? "image/png" : "image/jpeg";

            // 크기 결정 (3가지 순환)
            long imgW, imgH;
            int sizeType = i % 3;
            if (sizeType == 0) { imgW = SMALL_IMG_W; imgH = SMALL_IMG_H; }
            else if (sizeType == 1) { imgW = MEDIUM_IMG_W; imgH = MEDIUM_IMG_H; }
            else { imgW = LARGE_IMG_W; imgH = LARGE_IMG_H; }

            // 더미 이미지 생성
            byte[] imageData = createDummyImage(format, i, PIXEL_W, PIXEL_H);

            // ManifestItem 등록
            ManifestItem item = manifest.addNew();
            item.idAnd(itemId)
                    .hrefAnd("Contents/" + itemId + ext)
                    .mediaTypeAnd(mediaType);
            item.createAttachedFile();
            item.attachedFile().data(imageData);

            infos[i] = new ImageInfo();
            infos[i].itemId = itemId;
            infos[i].format = format;
            infos[i].imgW = imgW;
            infos[i].imgH = imgH;
            infos[i].pixelW = PIXEL_W;
            infos[i].pixelH = PIXEL_H;
        }
        return infos;
    }

    private byte[] createDummyImage(String format, int colorIdx, int w, int h) throws IOException {
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();

        // 배경색
        java.awt.Color bgColor = IMG_COLORS[colorIdx % IMG_COLORS.length];
        g.setColor(bgColor);
        g.fillRect(0, 0, w, h);

        // 대각선 패턴
        g.setColor(new java.awt.Color(255, 255, 255, 80));
        g.setStroke(new BasicStroke(2));
        for (int d = -h; d < w + h; d += 20) {
            g.drawLine(d, 0, d + h, h);
        }

        // 텍스트 라벨
        g.setColor(java.awt.Color.WHITE);
        g.setFont(new Font("SansSerif", Font.BOLD, 18));
        String label = format.toUpperCase() + " #" + (colorIdx + 1);
        FontMetrics fm = g.getFontMetrics();
        int tx = (w - fm.stringWidth(label)) / 2;
        int ty = (h + fm.getAscent()) / 2;
        g.drawString(label, tx, ty);

        // 테두리
        g.setColor(java.awt.Color.DARK_GRAY);
        g.setStroke(new BasicStroke(3));
        g.drawRect(1, 1, w - 3, h - 3);

        g.dispose();

        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        ImageIO.write(img, format, baos);
        return baos.toByteArray();
    }

    // ── Picture 객체 생성 ──

    private Picture createInlinePicture(Run run, ImageInfo info) {
        Picture pic = run.addNewPicture();
        String picId = nextId();

        // ShapeObject
        pic.idAnd(picId).zOrderAnd(0)
                .numberingTypeAnd(NumberingType.PICTURE)
                .textWrapAnd(TextWrapMethod.SQUARE)
                .textFlowAnd(TextFlowSide.BOTH_SIDES)
                .lockAnd(false).dropcapstyleAnd(DropCapStyle.None);

        // ShapeComponent
        pic.hrefAnd("");
        pic.groupLevelAnd((short) 0);
        pic.instidAnd(nextId());
        pic.reverseAnd(false);

        pic.createOffset();
        pic.offset().set(0L, 0L);

        pic.createOrgSz();
        pic.orgSz().set(info.imgW, info.imgH);

        pic.createCurSz();
        pic.curSz().set(info.imgW, info.imgH);

        pic.createFlip();
        pic.flip().horizontalAnd(false).verticalAnd(false);

        pic.createRotationInfo();
        pic.rotationInfo().angleAnd((short) 0)
                .centerXAnd(info.imgW / 2).centerYAnd(info.imgH / 2)
                .rotateimageAnd(true);

        pic.createRenderingInfo();
        pic.renderingInfo().addNewTransMatrix().set(1f, 0f, 0f, 0f, 1f, 0f);
        pic.renderingInfo().addNewScaMatrix().set(1f, 0f, 0f, 0f, 1f, 0f);
        pic.renderingInfo().addNewRotMatrix().set(1f, 0f, 0f, 0f, 1f, 0f);

        // ShapeSize
        pic.createSZ();
        pic.sz().widthAnd(info.imgW).widthRelToAnd(WidthRelTo.ABSOLUTE)
                .heightAnd(info.imgH).heightRelToAnd(HeightRelTo.ABSOLUTE)
                .protectAnd(false);

        // Position (treatAsChar=true for inline flow)
        pic.createPos();
        pic.pos().treatAsCharAnd(true)
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
        pic.createOutMargin();
        pic.outMargin().leftAnd(283L).rightAnd(283L).topAnd(283L).bottomAnd(283L);

        // ImageRect (4 corners in orgSz coordinate)
        pic.createImgRect();
        pic.imgRect().createPt0();
        pic.imgRect().pt0().set(0L, 0L);
        pic.imgRect().createPt1();
        pic.imgRect().pt1().set(info.imgW, 0L);
        pic.imgRect().createPt2();
        pic.imgRect().pt2().set(info.imgW, info.imgH);
        pic.imgRect().createPt3();
        pic.imgRect().pt3().set(0L, info.imgH);

        // ImageClip
        pic.createImgClip();
        long clipW = (long) info.pixelW * 75;
        long clipH = (long) info.pixelH * 75;
        pic.imgClip().leftAnd(0L).rightAnd(clipW).topAnd(0L).bottomAnd(clipH);

        // InMargin
        pic.createInMargin();
        pic.inMargin().leftAnd(0L).rightAnd(0L).topAnd(0L).bottomAnd(0L);

        // ImageDim (pixel * 75)
        pic.createImgDim();
        pic.imgDim().dimwidthAnd(clipW).dimheightAnd(clipH);

        // Image reference
        pic.createImg();
        pic.img().binaryItemIDRefAnd(info.itemId)
                .brightAnd(0).contrastAnd(0)
                .effectAnd(ImageEffect.REAL_PIC).alphaAnd(0f);

        return pic;
    }

    // ── 문단 패턴 ──

    /** 텍스트 + 인라인 이미지 + 텍스트 */
    private void addTextImageTextPara(Para para, ImageInfo[] images, int imgIdx, int txtIdx) {
        Run run = para.addNewRun();
        run.charPrIDRef("0");
        run.addNewT().addText(SAMPLE_TEXTS[txtIdx % SAMPLE_TEXTS.length]);

        createInlinePicture(run, images[imgIdx]);

        run.addNewT().addText(" " + SAMPLE_TEXTS[(txtIdx + 1) % SAMPLE_TEXTS.length]);
    }

    /** 텍스트 + 인라인 수식 + 텍스트 */
    private void addTextEquationTextPara(Para para, int eqIdx, int txtIdx) {
        Run run = para.addNewRun();
        run.charPrIDRef("0");
        run.addNewT().addText(SAMPLE_TEXTS[txtIdx % SAMPLE_TEXTS.length] + "The formula is: ");

        try {
            Equation eq = EquationBuilder.fromMathML(SAMPLE_EQUATIONS[eqIdx % SAMPLE_EQUATIONS.length]);
            eq.textColorAnd(EQ_COLORS[eqIdx % EQ_COLORS.length]);
            eq.baseUnitAnd(1000 + (eqIdx % 3) * 100);
            run.addRunItem(eq);
        } catch (Exception e) {
            run.addNewT().addText("[equation]");
        }

        run.addNewT().addText(" which demonstrates this principle elegantly. " +
                SAMPLE_TEXTS[(txtIdx + 1) % SAMPLE_TEXTS.length]);
    }

    /** 큰 이미지 단독 문단 */
    private void addLargeImagePara(Para para, ImageInfo[] images, int imgIdx) {
        Run run = para.addNewRun();
        run.charPrIDRef("0");

        // 큰 이미지 크기로 조정
        ImageInfo info = images[imgIdx];
        ImageInfo largeInfo = new ImageInfo();
        largeInfo.itemId = info.itemId;
        largeInfo.format = info.format;
        largeInfo.imgW = LARGE_IMG_W;
        largeInfo.imgH = LARGE_IMG_H;
        largeInfo.pixelW = info.pixelW;
        largeInfo.pixelH = info.pixelH;
        createInlinePicture(run, largeInfo);

        run.addNewT().addText("");
    }

    /** 텍스트 + 수식 + 이미지 + 텍스트 (혼합) */
    private void addMixedPara(Para para, ImageInfo[] images, int imgIdx, int eqIdx, int txtIdx) {
        Run run = para.addNewRun();
        run.charPrIDRef("0");
        run.addNewT().addText(SAMPLE_TEXTS[txtIdx % SAMPLE_TEXTS.length]);

        // 수식
        try {
            Equation eq = EquationBuilder.fromMathML(SAMPLE_EQUATIONS[eqIdx % SAMPLE_EQUATIONS.length]);
            eq.textColorAnd(EQ_COLORS[eqIdx % EQ_COLORS.length]);
            run.addRunItem(eq);
        } catch (Exception e) {
            run.addNewT().addText("[eq]");
        }

        run.addNewT().addText(" Here is the visual representation: ");

        // 작은 이미지
        ImageInfo smallInfo = new ImageInfo();
        smallInfo.itemId = images[imgIdx].itemId;
        smallInfo.format = images[imgIdx].format;
        smallInfo.imgW = SMALL_IMG_W;
        smallInfo.imgH = SMALL_IMG_H;
        smallInfo.pixelW = images[imgIdx].pixelW;
        smallInfo.pixelH = images[imgIdx].pixelH;
        createInlinePicture(run, smallInfo);

        run.addNewT().addText(" " + SAMPLE_TEXTS[(txtIdx + 1) % SAMPLE_TEXTS.length]);
    }

    /** 순수 긴 텍스트 */
    private void addLongTextPara(Para para, int txtIdx) {
        Run run = para.addNewRun();
        run.charPrIDRef("0");
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 4; i++) {
            sb.append(SAMPLE_TEXTS[(txtIdx + i) % SAMPLE_TEXTS.length]);
        }
        run.addNewT().addText(sb.toString());
    }

    /** 이미지 + 수식 연속 */
    private void addImageAndEquationPara(Para para, ImageInfo[] images, int imgIdx, int eqIdx) {
        Run run = para.addNewRun();
        run.charPrIDRef("0");
        run.addNewT().addText("Figure and formula: ");

        // 중간 크기 이미지
        ImageInfo medInfo = new ImageInfo();
        medInfo.itemId = images[imgIdx].itemId;
        medInfo.format = images[imgIdx].format;
        medInfo.imgW = MEDIUM_IMG_W;
        medInfo.imgH = MEDIUM_IMG_H;
        medInfo.pixelW = images[imgIdx].pixelW;
        medInfo.pixelH = images[imgIdx].pixelH;
        createInlinePicture(run, medInfo);

        run.addNewT().addText(" The corresponding equation: ");

        try {
            Equation eq = EquationBuilder.fromMathML(SAMPLE_EQUATIONS[eqIdx % SAMPLE_EQUATIONS.length]);
            eq.textColorAnd(EQ_COLORS[eqIdx % EQ_COLORS.length]);
            eq.baseUnitAnd(1200);
            run.addRunItem(eq);
        } catch (Exception e) {
            run.addNewT().addText("[equation]");
        }

        run.addNewT().addText(" as shown above. ");
    }

    // ── 유틸리티 ──

    private String nextId() {
        return String.valueOf(2000000 + (idCounter++));
    }

    private void addBoldCharPr(HWPXFile hwpxFile) {
        ObjectList<CharPr> cps = hwpxFile.headerXMLFile().refList().charProperties();
        CharPr cp = cps.addNew();
        cp.idAnd("7").heightAnd(1200).textColorAnd("#1F4E79")
                .shadeColorAnd("none").useFontSpaceAnd(false).useKerningAnd(false)
                .symMarkAnd(kr.dogfoot.hwpxlib.object.content.header_xml.enumtype.SymMarkSort.NONE)
                .borderFillIDRef("2");
        cp.createFontRef();
        cp.fontRef().set("0", "0", "0", "0", "0", "0", "0");
        cp.createRatio();
        cp.ratio().set((short) 100, (short) 100, (short) 100, (short) 100, (short) 100, (short) 100, (short) 100);
        cp.createSpacing();
        cp.spacing().set((short) 0, (short) 0, (short) 0, (short) 0, (short) 0, (short) 0, (short) 0);
        cp.createRelSz();
        cp.relSz().set((short) 100, (short) 100, (short) 100, (short) 100, (short) 100, (short) 100, (short) 100);
        cp.createOffset();
        cp.offset().set((short) 0, (short) 0, (short) 0, (short) 0, (short) 0, (short) 0, (short) 0);
        cp.createBold();
        cp.createUnderline();
        cp.underline().typeAnd(kr.dogfoot.hwpxlib.object.content.header_xml.enumtype.UnderlineType.NONE)
                .shapeAnd(kr.dogfoot.hwpxlib.object.content.header_xml.enumtype.LineType3.SOLID).color("#000000");
        cp.createStrikeout();
        cp.strikeout().shapeAnd(kr.dogfoot.hwpxlib.object.content.header_xml.enumtype.LineType2.NONE).color("#000000");
        cp.createOutline();
        cp.outline().type(kr.dogfoot.hwpxlib.object.content.header_xml.enumtype.LineType1.NONE);
        cp.createShadow();
        cp.shadow().typeAnd(kr.dogfoot.hwpxlib.object.content.header_xml.enumtype.CharShadowType.NONE)
                .colorAnd("#B2B2B2").offsetXAnd((short) 10).offsetY((short) 10);
    }
}
