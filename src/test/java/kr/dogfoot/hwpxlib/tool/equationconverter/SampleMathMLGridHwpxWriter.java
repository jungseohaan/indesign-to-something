package kr.dogfoot.hwpxlib.tool.equationconverter;

import kr.dogfoot.hwpxlib.object.HWPXFile;
import kr.dogfoot.hwpxlib.object.content.header_xml.enumtype.LineType2;
import kr.dogfoot.hwpxlib.object.content.section_xml.SectionXMLFile;
import kr.dogfoot.hwpxlib.object.content.section_xml.SubList;
import kr.dogfoot.hwpxlib.object.content.section_xml.enumtype.*;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.Para;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.Run;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.object.Equation;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.object.Rectangle;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.object.drawingobject.DrawText;
import kr.dogfoot.hwpxlib.object.content.section_xml.paragraph.object.picture.LineShape;
import kr.dogfoot.hwpxlib.reader.HWPXReader;
import kr.dogfoot.hwpxlib.tool.blankfilemaker.BlankFileMaker;
import kr.dogfoot.hwpxlib.writer.HWPXWriter;
import org.junit.Assert;
import org.junit.Test;

/**
 * 300개 유명 수학 공식을 2컬럼 5행 격자의 글상자(floating textbox)로 배치한 HWPX 파일을 생성하는 테스트.
 * 각 글상자는 투명 배경에 검정 실선(solid) 아웃라인을 가진다.
 */
public class SampleMathMLGridHwpxWriter {

    private static final int COLS = 2;
    private static final int ROWS = 5;
    private static final int CELLS_PER_PAGE = COLS * ROWS;

    // 페이지 크기 (A4 세로)
    private static final long PAGE_WIDTH = 59528;
    private static final long PAGE_HEIGHT = 84188;

    // 페이지 여백
    private static final long MARGIN_LEFT = 8504;
    private static final long MARGIN_RIGHT = 8504;
    private static final long MARGIN_TOP = 5668;
    private static final long MARGIN_BOTTOM = 4252;

    // 본문 영역
    private static final long BODY_WIDTH = PAGE_WIDTH - MARGIN_LEFT - MARGIN_RIGHT;   // 42520
    private static final long BODY_HEIGHT = PAGE_HEIGHT - MARGIN_TOP - MARGIN_BOTTOM;  // 74268

    // 글상자 간 여백 (gap) - 약 4mm
    private static final long GAP_H = 2200;  // 수평 간격
    private static final long GAP_V = 2200;  // 수직 간격

    // 글상자 크기 (간격 고려)
    private static final long BOX_WIDTH = (BODY_WIDTH - GAP_H * (COLS - 1)) / COLS;     // (42520 - 2200) / 2 = 20160
    private static final long BOX_HEIGHT = (BODY_HEIGHT - GAP_V * (ROWS - 1)) / ROWS;   // (74268 - 8800) / 5 = 13093

    // 글상자 내부 텍스트 여백
    private static final long TEXT_MARGIN_LR = 283;
    private static final long TEXT_MARGIN_TB = 141;

    // 아웃라인 두께 (hwpunit)
    private static final int OUTLINE_WIDTH = 7;

    @Test
    public void createGridHwpx() throws Exception {
        String[][] equations = SampleMathMLEquationData.getEquations();

        HWPXFile hwpxFile = BlankFileMaker.make();
        SectionXMLFile section = hwpxFile.sectionXMLFileList().get(0);

        int totalEquations = equations.length;
        int totalPages = (totalEquations + CELLS_PER_PAGE - 1) / CELLS_PER_PAGE;

        int successCount = 0;
        int failCount = 0;
        int eqIndex = 0;

        for (int page = 0; page < totalPages; page++) {
            // 페이지에 해당하는 Para (글상자들의 앵커)
            Para anchorPara = section.addNewPara();
            anchorPara.idAnd(String.valueOf(System.nanoTime()))
                    .paraPrIDRefAnd("3")
                    .styleIDRefAnd("0")
                    .pageBreakAnd(page > 0)
                    .columnBreakAnd(false)
                    .merged(false);

            Run anchorRun = anchorPara.addNewRun();
            anchorRun.charPrIDRef("0");

            // 이 페이지에 배치할 수식 수
            int remaining = totalEquations - eqIndex;
            int cellsThisPage = Math.min(CELLS_PER_PAGE, remaining);

            for (int cellIdx = 0; cellIdx < cellsThisPage; cellIdx++) {
                int row = cellIdx / COLS;
                int col = cellIdx % COLS;

                String label = equations[eqIndex][0];
                String mathml = equations[eqIndex][1];

                // 글상자 위치 계산 (페이지 기준 절대 좌표)
                long xOffset = MARGIN_LEFT + col * (BOX_WIDTH + GAP_H);
                long yOffset = MARGIN_TOP + row * (BOX_HEIGHT + GAP_V);

                // 수식 변환
                Equation eq = null;
                boolean converted = false;
                try {
                    eq = EquationBuilder.fromMathML(mathml);
                    converted = true;
                    successCount++;
                } catch (Exception e) {
                    failCount++;
                    System.err.println("FAIL #" + (eqIndex + 1) + " " + label + ": " + e.getMessage());
                }

                // 글상자(Rectangle) 생성
                Rectangle rect = anchorRun.addNewRectangle();
                configureRectangle(rect, xOffset, yOffset, eqIndex);

                // DrawText 안에 제목 + 수식 배치
                SubList subList = rect.drawText().subList();

                // 제목 Para
                Para titlePara = subList.addNewPara();
                titlePara.idAnd(String.valueOf(System.nanoTime()))
                        .paraPrIDRefAnd("3")
                        .styleIDRefAnd("0")
                        .pageBreakAnd(false)
                        .columnBreakAnd(false)
                        .merged(false);
                Run titleRun = titlePara.addNewRun();
                titleRun.charPrIDRef("0");
                titleRun.addNewT().addText((eqIndex + 1) + ". " + label);

                // 수식 Para
                Para eqPara = subList.addNewPara();
                eqPara.idAnd(String.valueOf(System.nanoTime()))
                        .paraPrIDRefAnd("3")
                        .styleIDRefAnd("0")
                        .pageBreakAnd(false)
                        .columnBreakAnd(false)
                        .merged(false);
                Run eqRun = eqPara.addNewRun();
                eqRun.charPrIDRef("0");

                if (converted && eq != null) {
                    eqRun.addRunItem(eq);
                } else {
                    eqRun.addNewT().addText("[conversion failed]");
                }

                eqIndex++;
            }

            // 빈 텍스트 추가 (앵커 Para에 최소 하나의 텍스트)
            anchorRun.addNewT().addText("");
        }

        String filepath = "testFile/tool/sample_mathml_grid.hwpx";
        HWPXWriter.toFilepath(hwpxFile, filepath);

        System.out.println("=== MathML Floating TextBox Grid HWPX Result ===");
        System.out.println("Total: " + totalEquations);
        System.out.println("Success: " + successCount);
        System.out.println("Failed: " + failCount);
        System.out.println("Pages: " + totalPages);
        System.out.println("File: " + filepath);

        // Round-trip 검증
        HWPXFile readBack = HWPXReader.fromFilepath(filepath);
        SectionXMLFile readSection = readBack.sectionXMLFileList().get(0);
        Assert.assertTrue("Should have paragraphs", readSection.countOfPara() > 0);

        Assert.assertEquals("All equations should convert successfully", 0, failCount);
        System.out.println("\nAll " + successCount + " MathML equations placed in "
                + totalPages + " pages of floating textbox grids!");
    }

    /**
     * Rectangle (글상자)를 설정한다.
     * - 절대 위치 (페이지 기준)
     * - 검정 실선 아웃라인
     * - 투명 배경
     * - DrawText 포함
     */
    private void configureRectangle(Rectangle rect, long xOffset, long yOffset, int index) {
        String id = String.valueOf(System.nanoTime() + index);

        // ShapeObject 속성
        rect.idAnd(id)
                .zOrderAnd(index)
                .numberingTypeAnd(NumberingType.PICTURE)
                .textWrapAnd(TextWrapMethod.IN_FRONT_OF_TEXT)
                .textFlowAnd(TextFlowSide.BOTH_SIDES)
                .lockAnd(false)
                .dropcapstyleAnd(DropCapStyle.None);

        // ShapeComponent 속성
        rect.hrefAnd("");
        rect.groupLevelAnd((short) 0);
        rect.instidAnd(String.valueOf(System.nanoTime() + index + 1000));

        // offset
        rect.createOffset();
        rect.offset().set(0L, 0L);

        // original size
        rect.createOrgSz();
        rect.orgSz().set(BOX_WIDTH, BOX_HEIGHT);

        // current size
        rect.createCurSz();
        rect.curSz().set(0L, 0L);

        // flip
        rect.createFlip();
        rect.flip().horizontalAnd(false).verticalAnd(false);

        // rotation
        rect.createRotationInfo();
        rect.rotationInfo()
                .angleAnd((short) 0)
                .centerXAnd(BOX_WIDTH / 2)
                .centerYAnd(BOX_HEIGHT / 2)
                .rotateimageAnd(true);

        // rendering info (identity matrix)
        rect.createRenderingInfo();
        rect.renderingInfo().addNewTransMatrix().set(1f, 0f, 0f, 0f, 1f, 0f);
        rect.renderingInfo().addNewScaMatrix().set(1f, 0f, 0f, 0f, 1f, 0f);
        rect.renderingInfo().addNewRotMatrix().set(1f, 0f, 0f, 0f, 1f, 0f);

        // Rectangle 속성 (직각)
        rect.ratioAnd((short) 0);

        // 4개 꼭짓점
        rect.createPt0();
        rect.pt0().set(0L, 0L);
        rect.createPt1();
        rect.pt1().set(BOX_WIDTH, 0L);
        rect.createPt2();
        rect.pt2().set(BOX_WIDTH, BOX_HEIGHT);
        rect.createPt3();
        rect.pt3().set(0L, BOX_HEIGHT);

        // 크기
        rect.createSZ();
        rect.sz()
                .widthAnd(BOX_WIDTH)
                .widthRelToAnd(WidthRelTo.ABSOLUTE)
                .heightAnd(BOX_HEIGHT)
                .heightRelToAnd(HeightRelTo.ABSOLUTE)
                .protectAnd(false);

        // 위치 (페이지 기준 절대 좌표)
        rect.createPos();
        rect.pos()
                .treatAsCharAnd(false)
                .affectLSpacingAnd(false)
                .flowWithTextAnd(false)
                .allowOverlapAnd(true)
                .holdAnchorAndSOAnd(false)
                .vertRelToAnd(VertRelTo.PAPER)
                .horzRelToAnd(HorzRelTo.PAPER)
                .vertAlignAnd(VertAlign.TOP)
                .horzAlignAnd(HorzAlign.LEFT)
                .vertOffsetAnd(yOffset)
                .horzOffset(xOffset);

        // 외부 여백
        rect.createOutMargin();
        rect.outMargin()
                .leftAnd(0L)
                .rightAnd(0L)
                .topAnd(0L)
                .bottomAnd(0L);

        // 아웃라인 (검정 실선)
        rect.createLineShape();
        LineShape line = rect.lineShape();
        line.color("#000000");
        line.width(OUTLINE_WIDTH);
        line.style(LineType2.SOLID);
        line.endCap(LineCap.FLAT);
        line.headStyle(ArrowType.NORMAL);
        line.tailStyle(ArrowType.NORMAL);
        line.headfill(true);
        line.tailfill(true);
        line.headSz(ArrowSize.MEDIUM_MEDIUM);
        line.tailSz(ArrowSize.MEDIUM_MEDIUM);
        line.outlineStyle(OutlineStyle.NORMAL);
        line.alpha(0f);

        // 배경 없음 (투명)
        // fillBrush를 설정하지 않으면 투명

        // 그림자 없음
        rect.createShadow();
        rect.shadow()
                .typeAnd(DrawingShadowType.NONE)
                .colorAnd("#B2B2B2")
                .offsetXAnd(0L)
                .offsetYAnd(0L)
                .alphaAnd(0f);

        // DrawText (글상자 텍스트 영역)
        rect.createDrawText();
        DrawText drawText = rect.drawText();
        drawText.lastWidthAnd(BOX_WIDTH);
        drawText.nameAnd("");
        drawText.editableAnd(false);

        drawText.createTextMargin();
        drawText.textMargin()
                .leftAnd(TEXT_MARGIN_LR)
                .rightAnd(TEXT_MARGIN_LR)
                .topAnd(TEXT_MARGIN_TB)
                .bottomAnd(TEXT_MARGIN_TB);

        drawText.createSubList();
        drawText.subList()
                .idAnd("")
                .textDirectionAnd(TextDirection.HORIZONTAL)
                .lineWrapAnd(LineWrapMethod.BREAK)
                .vertAlignAnd(VerticalAlign2.TOP)
                .linkListIDRefAnd("0")
                .linkListNextIDRefAnd("0")
                .textWidthAnd(0)
                .textHeightAnd(0)
                .hasTextRefAnd(false)
                .hasNumRefAnd(false);
    }
}
