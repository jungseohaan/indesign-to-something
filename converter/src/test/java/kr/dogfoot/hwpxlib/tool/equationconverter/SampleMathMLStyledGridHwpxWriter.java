package kr.dogfoot.hwpxlib.tool.equationconverter;

import kr.dogfoot.hwpxlib.object.HWPXFile;
import kr.dogfoot.hwpxlib.object.common.ObjectList;
import kr.dogfoot.hwpxlib.object.content.header_xml.enumtype.*;
import kr.dogfoot.hwpxlib.object.content.header_xml.references.CharPr;
import kr.dogfoot.hwpxlib.object.content.header_xml.references.borderfill.FillBrush;
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
 * 300개 유명 수학 공식을 2컬럼 5행 격자의 글상자(floating textbox)로 배치하면서
 * 글상자, 제목 텍스트, 수식에 다양한 스타일을 적용한 HWPX 파일을 생성하는 테스트.
 *
 * 글상자 스타일: 다양한 테두리(선 종류, 색상, 두께), 배경색, 그림자, 둥근 모서리
 * 제목 텍스트 스타일: 다양한 글꼴 크기, 색상, 볼드/이탤릭
 * 수식 스타일: 다양한 textColor, baseUnit
 */
public class SampleMathMLStyledGridHwpxWriter {

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
    private static final long BODY_WIDTH = PAGE_WIDTH - MARGIN_LEFT - MARGIN_RIGHT;
    private static final long BODY_HEIGHT = PAGE_HEIGHT - MARGIN_TOP - MARGIN_BOTTOM;

    // 글상자 간 여백 (gap)
    private static final long GAP_H = 2200;
    private static final long GAP_V = 2200;

    // 글상자 크기
    private static final long BOX_WIDTH = (BODY_WIDTH - GAP_H * (COLS - 1)) / COLS;
    private static final long BOX_HEIGHT = (BODY_HEIGHT - GAP_V * (ROWS - 1)) / ROWS;

    // 글상자 내부 텍스트 여백
    private static final long TEXT_MARGIN_LR = 283;
    private static final long TEXT_MARGIN_TB = 141;

    // ── 스타일 팔레트 ──

    // 글상자 테두리 색상 (10가지 순환)
    private static final String[] BORDER_COLORS = {
            "#000000",  // 검정
            "#2E74B5",  // 파랑
            "#C00000",  // 빨강
            "#548235",  // 녹색
            "#7030A0",  // 보라
            "#BF8F00",  // 금색
            "#2F5496",  // 남색
            "#843C0C",  // 갈색
            "#44546A",  // 회남색
            "#FF6600",  // 주황
    };

    // 글상자 테두리 선 종류 (순환)
    private static final LineType2[] BORDER_STYLES = {
            LineType2.SOLID,
            LineType2.DASH,
            LineType2.DOT,
            LineType2.DASH_DOT,
            LineType2.DOUBLE_SLIM,
            LineType2.LONG_DASH,
            LineType2.SOLID,
            LineType2.SLIM_THICK,
            LineType2.DASH_DOT_DOT,
            LineType2.THICK_SLIM,
    };

    // 글상자 테두리 두께 (hwpunit 값)
    private static final int[] BORDER_WIDTHS = {
            7, 14, 7, 10, 14, 7, 10, 14, 7, 10,
    };

    // 글상자 배경색 (null = 투명)
    private static final String[] BG_COLORS = {
            null,               // 투명
            "#DEEAF6",          // 연파랑
            null,               // 투명
            "#E2EFDA",          // 연녹색
            null,               // 투명
            "#FFF2CC",          // 연노랑
            null,               // 투명
            "#FCE4EC",          // 연분홍
            null,               // 투명
            "#F2E6FF",          // 연보라
    };

    // 글상자 그림자 타입 (10가지 순환)
    private static final DrawingShadowType[] SHADOW_TYPES = {
            DrawingShadowType.NONE,
            DrawingShadowType.NONE,
            DrawingShadowType.PARELLEL_RIGHTBOTTOM,
            DrawingShadowType.NONE,
            DrawingShadowType.NONE,
            DrawingShadowType.PARELLEL_RIGHTBOTTOM,
            DrawingShadowType.NONE,
            DrawingShadowType.NONE,
            DrawingShadowType.PARELLEL_LEFTBOTTOM,
            DrawingShadowType.NONE,
    };

    // 글상자 둥근 모서리 비율 (0=직각, 20=둥글게)
    private static final short[] CORNER_RATIOS = {
            0, 0, 20, 0, 20, 0, 0, 20, 0, 0,
    };

    // 제목 CharPr ID (새로 추가할 ID: "7"~"16")
    // 제목 텍스트 색상 (10가지 순환)
    private static final String[] TITLE_COLORS = {
            "#000000",  // 검정
            "#1F4E79",  // 진파랑
            "#C00000",  // 빨강
            "#375623",  // 진녹색
            "#7030A0",  // 보라
            "#806000",  // 진금색
            "#2F5496",  // 남색
            "#843C0C",  // 갈색
            "#44546A",  // 회남색
            "#FF6600",  // 주황
    };

    // 제목 글꼴 크기 (hwpunit, 10가지 순환)
    private static final int[] TITLE_HEIGHTS = {
            1000, 1100, 1000, 1050, 1000, 1100, 1000, 1050, 1000, 1100,
    };

    // 제목 볼드 여부 (10가지 순환)
    private static final boolean[] TITLE_BOLD = {
            true, true, false, true, false, true, true, false, true, false,
    };

    // 제목 이탤릭 여부 (10가지 순환)
    private static final boolean[] TITLE_ITALIC = {
            false, false, true, false, true, false, false, true, false, true,
    };

    // 수식 텍스트 색상 (10가지 순환)
    private static final String[] EQ_COLORS = {
            "#000000",  // 검정
            "#002060",  // 진남색
            "#C00000",  // 빨강
            "#1D6B33",  // 녹색
            "#4B0082",  // 인디고
            "#806000",  // 올리브
            "#003366",  // 진파랑
            "#660000",  // 어두운 빨강
            "#333333",  // 진회색
            "#CC6600",  // 갈색주황
    };

    // 수식 baseUnit (10가지 순환)
    private static final int[] EQ_BASE_UNITS = {
            1100, 1200, 1100, 1150, 1100, 1200, 1100, 1150, 1100, 1200,
    };

    @Test
    public void createStyledGridHwpx() throws Exception {
        String[][] equations = SampleMathMLEquationData.getEquations();

        HWPXFile hwpxFile = BlankFileMaker.make();

        // 새 CharPr 추가 (제목용 10가지 스타일: ID "7" ~ "16")
        addTitleCharProperties(hwpxFile);

        SectionXMLFile section = hwpxFile.sectionXMLFileList().get(0);

        int totalEquations = equations.length;
        int totalPages = (totalEquations + CELLS_PER_PAGE - 1) / CELLS_PER_PAGE;

        int successCount = 0;
        int failCount = 0;
        int eqIndex = 0;

        for (int page = 0; page < totalPages; page++) {
            Para anchorPara = section.addNewPara();
            anchorPara.idAnd(String.valueOf(System.nanoTime()))
                    .paraPrIDRefAnd("3")
                    .styleIDRefAnd("0")
                    .pageBreakAnd(page > 0)
                    .columnBreakAnd(false)
                    .merged(false);

            Run anchorRun = anchorPara.addNewRun();
            anchorRun.charPrIDRef("0");

            int remaining = totalEquations - eqIndex;
            int cellsThisPage = Math.min(CELLS_PER_PAGE, remaining);

            for (int cellIdx = 0; cellIdx < cellsThisPage; cellIdx++) {
                int row = cellIdx / COLS;
                int col = cellIdx % COLS;

                String label = equations[eqIndex][0];
                String mathml = equations[eqIndex][1];

                long xOffset = MARGIN_LEFT + col * (BOX_WIDTH + GAP_H);
                long yOffset = MARGIN_TOP + row * (BOX_HEIGHT + GAP_V);

                // 스타일 인덱스 (10가지 순환)
                int styleIdx = eqIndex % 10;

                // 수식 변환
                Equation eq = null;
                boolean converted = false;
                try {
                    eq = EquationBuilder.fromMathML(mathml);
                    // 수식 스타일 적용
                    eq.textColorAnd(EQ_COLORS[styleIdx]);
                    eq.baseUnitAnd(EQ_BASE_UNITS[styleIdx]);
                    converted = true;
                    successCount++;
                } catch (Exception e) {
                    failCount++;
                    System.err.println("FAIL #" + (eqIndex + 1) + " " + label + ": " + e.getMessage());
                }

                // 글상자(Rectangle) 생성 (스타일 적용)
                Rectangle rect = anchorRun.addNewRectangle();
                configureStyledRectangle(rect, xOffset, yOffset, eqIndex, styleIdx);

                // DrawText 안에 제목 + 수식 배치
                SubList subList = rect.drawText().subList();

                // 제목 Para (스타일 적용된 CharPr 사용)
                Para titlePara = subList.addNewPara();
                titlePara.idAnd(String.valueOf(System.nanoTime()))
                        .paraPrIDRefAnd("3")
                        .styleIDRefAnd("0")
                        .pageBreakAnd(false)
                        .columnBreakAnd(false)
                        .merged(false);
                Run titleRun = titlePara.addNewRun();
                titleRun.charPrIDRef(String.valueOf(7 + styleIdx));  // ID "7"~"16"
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

            anchorRun.addNewT().addText("");
        }

        String filepath = "testFile/tool/sample_mathml_styled_grid.hwpx";
        HWPXWriter.toFilepath(hwpxFile, filepath);

        System.out.println("=== MathML Styled Floating TextBox Grid HWPX Result ===");
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
        System.out.println("\nAll " + successCount + " MathML equations placed with diverse styles in "
                + totalPages + " pages of floating textbox grids!");
    }

    /**
     * 제목 텍스트용 CharPr 10가지 스타일을 헤더에 추가한다 (ID "7" ~ "16").
     * 각 스타일은 색상, 크기, 볼드/이탤릭이 다르다.
     */
    private void addTitleCharProperties(HWPXFile hwpxFile) {
        ObjectList<CharPr> charProperties = hwpxFile.headerXMLFile().refList().charProperties();

        for (int i = 0; i < 10; i++) {
            CharPr cp = charProperties.addNew();
            cp.idAnd(String.valueOf(7 + i))
                    .heightAnd(TITLE_HEIGHTS[i])
                    .textColorAnd(TITLE_COLORS[i])
                    .shadeColorAnd("none")
                    .useFontSpaceAnd(false)
                    .useKerningAnd(false)
                    .symMarkAnd(SymMarkSort.NONE)
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

            // 볼드
            if (TITLE_BOLD[i]) {
                cp.createBold();
            }

            // 이탤릭
            if (TITLE_ITALIC[i]) {
                cp.createItalic();
            }

            cp.createUnderline();
            cp.underline()
                    .typeAnd(UnderlineType.NONE)
                    .shapeAnd(LineType3.SOLID)
                    .color("#000000");

            cp.createStrikeout();
            cp.strikeout()
                    .shapeAnd(LineType2.NONE)
                    .color("#000000");

            cp.createOutline();
            cp.outline().type(LineType1.NONE);

            cp.createShadow();
            cp.shadow()
                    .typeAnd(CharShadowType.NONE)
                    .colorAnd("#B2B2B2")
                    .offsetXAnd((short) 10)
                    .offsetY((short) 10);
        }
    }

    /**
     * Rectangle (글상자)를 스타일을 적용하여 설정한다.
     */
    private void configureStyledRectangle(Rectangle rect, long xOffset, long yOffset, int index, int styleIdx) {
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

        rect.createOffset();
        rect.offset().set(0L, 0L);

        rect.createOrgSz();
        rect.orgSz().set(BOX_WIDTH, BOX_HEIGHT);

        rect.createCurSz();
        rect.curSz().set(0L, 0L);

        rect.createFlip();
        rect.flip().horizontalAnd(false).verticalAnd(false);

        rect.createRotationInfo();
        rect.rotationInfo()
                .angleAnd((short) 0)
                .centerXAnd(BOX_WIDTH / 2)
                .centerYAnd(BOX_HEIGHT / 2)
                .rotateimageAnd(true);

        rect.createRenderingInfo();
        rect.renderingInfo().addNewTransMatrix().set(1f, 0f, 0f, 0f, 1f, 0f);
        rect.renderingInfo().addNewScaMatrix().set(1f, 0f, 0f, 0f, 1f, 0f);
        rect.renderingInfo().addNewRotMatrix().set(1f, 0f, 0f, 0f, 1f, 0f);

        // Rectangle 속성 (둥근 모서리)
        rect.ratioAnd(CORNER_RATIOS[styleIdx]);

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

        // 아웃라인 (스타일별 선 종류, 색상, 두께)
        rect.createLineShape();
        LineShape line = rect.lineShape();
        line.color(BORDER_COLORS[styleIdx]);
        line.width(BORDER_WIDTHS[styleIdx]);
        line.style(BORDER_STYLES[styleIdx]);
        line.endCap(LineCap.FLAT);
        line.headStyle(ArrowType.NORMAL);
        line.tailStyle(ArrowType.NORMAL);
        line.headfill(true);
        line.tailfill(true);
        line.headSz(ArrowSize.MEDIUM_MEDIUM);
        line.tailSz(ArrowSize.MEDIUM_MEDIUM);
        line.outlineStyle(OutlineStyle.NORMAL);
        line.alpha(0f);

        // 배경색 (스타일별)
        String bgColor = BG_COLORS[styleIdx];
        if (bgColor != null) {
            rect.createFillBrush();
            rect.fillBrush().createWinBrush();
            rect.fillBrush().winBrush()
                    .faceColorAnd(bgColor)
                    .hatchColorAnd("#FF000000")
                    .alphaAnd(0f);
        }

        // 그림자 (스타일별)
        rect.createShadow();
        DrawingShadowType shadowType = SHADOW_TYPES[styleIdx];
        if (shadowType != DrawingShadowType.NONE) {
            rect.shadow()
                    .typeAnd(shadowType)
                    .colorAnd("#808080")
                    .offsetXAnd(283L)
                    .offsetYAnd(283L)
                    .alphaAnd(0f);
        } else {
            rect.shadow()
                    .typeAnd(DrawingShadowType.NONE)
                    .colorAnd("#B2B2B2")
                    .offsetXAnd(0L)
                    .offsetYAnd(0L)
                    .alphaAnd(0f);
        }

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
